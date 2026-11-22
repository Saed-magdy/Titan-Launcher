package com.dongfeng.shinecockpit;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * CockpitMediaListenerService:
 * 1. Connects to MediaSessionManager to capture live metadata, cover art, and playback state from ANY active media app.
 * 2. Provides precise transport controls (play, pause, next, prev, seekTo, seekBy).
 * 3. Intercepts ongoing notifications from Google Maps (turn-by-turn) and Radarbot (speed camera alerts).
 */
public class CockpitMediaListenerService extends NotificationListenerService {
    private static final String TAG = "CockpitMediaService";
    private static volatile CockpitMediaListenerService sInstance = null;

    private MediaSessionManager mSessionManager = null;
    private MediaController mActiveController = null;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    public interface MediaEventListener {
        void onMediaUpdated(JSONObject mediaJson);
        void onRadarbotAlert(String alertType, int speedLimit, int distanceMeters);
        void onGoogleMapsNav(String instruction, String distance, String turnIcon);
    }

    private static MediaEventListener sListener = null;

    public static void setListener(MediaEventListener listener) {
        sListener = listener;
        if (sInstance != null) {
            sInstance.updateActiveSessions();
        }
    }

    public static String stripExtension(String title) {
        if (title == null || title.length() == 0) return "";
        return title.replaceAll("(?i)\\.(mp3|m4a|flac|wav|aac|ogg|wma|ape|alac|opus|mp4)$", "").trim();
    }

    public static CockpitMediaListenerService getInstance() {
        return sInstance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        Log.i(TAG, "CockpitMediaListenerService onCreate");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (sInstance == this) {
            sInstance = null;
        }
        if (mActiveController != null) {
            try {
                mActiveController.unregisterCallback(mControllerCallback);
            } catch (Exception ignored) {}
            mActiveController = null;
        }
        Log.i(TAG, "CockpitMediaListenerService onDestroy");
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sInstance = this;
        Log.i(TAG, "Notification listener connected successfully!");
        initMediaSessionManager();
        updateActiveSessions();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.i(TAG, "Notification listener disconnected");
    }

    private void initMediaSessionManager() {
        try {
            mSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (mSessionManager != null) {
                mSessionManager.addOnActiveSessionsChangedListener(
                    new MediaSessionManager.OnActiveSessionsChangedListener() {
                        @Override
                        public void onActiveSessionsChanged(List<MediaController> controllers) {
                            Log.i(TAG, "Active media sessions changed: " + (controllers != null ? controllers.size() : 0));
                            updateActiveSessions();
                        }
                    },
                    new ComponentName(this, CockpitMediaListenerService.class)
                );
            }
        } catch (SecurityException se) {
            Log.w(TAG, "SecurityException: BIND_NOTIFICATION_LISTENER_SERVICE permission required: " + se.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error initializing MediaSessionManager: " + e.getMessage(), e);
        }
    }

    public synchronized void updateActiveSessions() {
        if (mSessionManager == null) {
            try {
                mSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            } catch (Exception ignored) {}
        }
        if (mSessionManager == null) return;

        try {
            List<MediaController> controllers = null;
            try {
                controllers = mSessionManager.getActiveSessions(
                    new ComponentName(this, CockpitMediaListenerService.class)
                );
            } catch (Throwable t) {
                try {
                    controllers = mSessionManager.getActiveSessions(null);
                } catch (Throwable ignored) {}
            }

            if (controllers != null && !controllers.isEmpty()) {
                MediaController bestPlaying = null;
                for (MediaController c : controllers) {
                    PlaybackState st = c.getPlaybackState();
                    if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                        bestPlaying = c;
                        break;
                    }
                }
                // If one is actively playing, pick it; otherwise pick the first active session
                MediaController target = (bestPlaying != null) ? bestPlaying : controllers.get(0);
                setActiveController(target);
            } else {
                Log.d(TAG, "No active media sessions currently available.");
            }
        } catch (SecurityException se) {
            Log.w(TAG, "Cannot get active sessions - listener permission not yet granted: " + se.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error querying active media sessions: " + e.getMessage(), e);
        }
    }

    private synchronized void setActiveController(MediaController controller) {
        if (mActiveController != null && mActiveController.equals(controller)) {
            // Already tracking this controller; just update latest state
            notifyMediaChange();
            return;
        }

        if (mActiveController != null) {
            try {
                mActiveController.unregisterCallback(mControllerCallback);
            } catch (Exception ignored) {}
        }

        mActiveController = controller;

        if (mActiveController != null) {
            try {
                mActiveController.registerCallback(mControllerCallback, mMainHandler);
                Log.i(TAG, "Bound to MediaController package: " + mActiveController.getPackageName());
            } catch (Exception e) {
                Log.e(TAG, "Failed to register controller callback: " + e.getMessage());
            }
            notifyMediaChange();
        }
    }

    private final MediaController.Callback mControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            notifyMediaChange();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            notifyMediaChange();
        }

        @Override
        public void onSessionDestroyed() {
            Log.i(TAG, "Active media session destroyed, searching next session...");
            mActiveController = null;
            mMainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updateActiveSessions();
                }
            }, 300);
        }
    };

    private void notifyMediaChange() {
        if (sListener == null || mActiveController == null) return;

        try {
            final MediaMetadata meta = mActiveController.getMetadata();
            final PlaybackState state = mActiveController.getPlaybackState();
            final String pkg = mActiveController.getPackageName();

            String title = "ShineWave Audio";
            String artist = "Lossless Hi-Fi Cockpit Sound";
            String album = "";
            long durationMs = 225000L;
            long positionMs = 0L;
            boolean isPlaying = false;
            String albumArtBase64 = null;

            if (meta != null) {
                CharSequence t = meta.getText(MediaMetadata.METADATA_KEY_TITLE);
                if (t != null && t.length() > 0) title = stripExtension(t.toString());

                CharSequence a = meta.getText(MediaMetadata.METADATA_KEY_ARTIST);
                if (a != null && a.length() > 0) artist = a.toString();

                CharSequence al = meta.getText(MediaMetadata.METADATA_KEY_ALBUM);
                if (al != null && al.length() > 0) album = al.toString();

                long dur = meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
                if (dur > 0) durationMs = dur;

                // Extract Album Art Bitmap
                Bitmap artBmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (artBmp == null) artBmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (artBmp == null) artBmp = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);

                if (artBmp != null) {
                    try {
                        // Scale to 180x180 for low memory & fast IPC transfer
                        int w = artBmp.getWidth();
                        int h = artBmp.getHeight();
                        int targetDim = 180;
                        Bitmap scaled;
                        if (w > targetDim || h > targetDim) {
                            scaled = Bitmap.createScaledBitmap(artBmp, targetDim, targetDim, true);
                        } else {
                            scaled = artBmp;
                        }
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        scaled.compress(Bitmap.CompressFormat.JPEG, 82, baos);
                        byte[] b = baos.toByteArray();
                        albumArtBase64 = "data:image/jpeg;base64," + Base64.encodeToString(b, Base64.NO_WRAP);
                    } catch (Throwable th) {
                        Log.w(TAG, "Error encoding album art bitmap: " + th.getMessage());
                    }
                }
            }

            if (state != null) {
                isPlaying = (state.getState() == PlaybackState.STATE_PLAYING);
                positionMs = state.getPosition();
            }

            final JSONObject json = new JSONObject();
            json.put("title", title);
            json.put("artist", artist);
            json.put("album", album);
            json.put("duration", Math.max(1, durationMs / 1000));
            json.put("position", Math.max(0, positionMs / 1000));
            json.put("playing", isPlaying);
            json.put("package", pkg != null ? pkg : "");
            if (albumArtBase64 != null) {
                json.put("albumArt", albumArtBase64);
            }

            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (sListener != null) {
                        sListener.onMediaUpdated(json);
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error building media JSON: " + e.getMessage(), e);
        }
    }

    // ==========================================
    // Media Controls (Steering Wheel & UI Actions)
    // ==========================================

    public static boolean togglePlayPause(Context ctx) {
        if (sInstance != null && sInstance.mActiveController != null) {
            try {
                PlaybackState st = sInstance.mActiveController.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                    sInstance.mActiveController.getTransportControls().pause();
                } else {
                    sInstance.mActiveController.getTransportControls().play();
                }
                return true;
            } catch (Exception e) {
                Log.w(TAG, "togglePlayPause failed on active controller: " + e.getMessage());
            }
        }
        dispatchSystemMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        return false;
    }

    public static boolean skipNext(Context ctx) {
        if (sInstance != null && sInstance.mActiveController != null) {
            try {
                sInstance.mActiveController.getTransportControls().skipToNext();
                return true;
            } catch (Exception e) {
                Log.w(TAG, "skipNext failed on active controller: " + e.getMessage());
            }
        }
        dispatchSystemMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT);
        return false;
    }

    public static boolean skipPrev(Context ctx) {
        if (sInstance != null && sInstance.mActiveController != null) {
            try {
                sInstance.mActiveController.getTransportControls().skipToPrevious();
                return true;
            } catch (Exception e) {
                Log.w(TAG, "skipPrev failed on active controller: " + e.getMessage());
            }
        }
        dispatchSystemMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        return false;
    }

    public static boolean seekBy(Context ctx, long deltaMs) {
        if (sInstance != null && sInstance.mActiveController != null) {
            try {
                PlaybackState st = sInstance.mActiveController.getPlaybackState();
                long currentPos = (st != null) ? st.getPosition() : 0L;
                long newPos = Math.max(0L, currentPos + deltaMs);
                sInstance.mActiveController.getTransportControls().seekTo(newPos);
                sInstance.notifyMediaChange();
                return true;
            } catch (Exception e) {
                Log.w(TAG, "seekBy failed on active controller: " + e.getMessage());
            }
        }
        return false;
    }

    public static boolean seekTo(Context ctx, long positionMs) {
        if (sInstance != null && sInstance.mActiveController != null) {
            try {
                sInstance.mActiveController.getTransportControls().seekTo(Math.max(0L, positionMs));
                sInstance.notifyMediaChange();
                return true;
            } catch (Exception e) {
                Log.w(TAG, "seekTo failed on active controller: " + e.getMessage());
            }
        }
        return false;
    }

    private static void dispatchSystemMediaKey(Context ctx, int keyCode) {
        if (ctx == null) return;
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                long now = System.currentTimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error dispatching system media key: " + keyCode, e);
        }
    }

    // ==========================================
    // Live Notification Parsing (Maps & Radarbot)
    // ==========================================

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sListener == null) return;
        try {
            String pkg = sbn.getPackageName();
            if (pkg == null) return;

            Notification notification = sbn.getNotification();
            if (notification == null) return;

            Bundle extras = notification.extras;
            if (extras == null) return;

            // 1. Google Maps Navigation Notification
            if (pkg.contains("com.google.android.apps.maps")) {
                CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
                CharSequence subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);

                String instruction = title != null ? title.toString() : "";
                String dist = text != null ? text.toString() : (subText != null ? subText.toString() : "");

                if (instruction.length() > 0 || dist.length() > 0) {
                    sListener.onGoogleMapsNav(instruction, dist, "turn_right");
                }
            }
            // 2. Radarbot Alert Notification
            else if (pkg.contains("radarbot")) {
                CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);

                String t = title != null ? title.toString() : "";
                String b = text != null ? text.toString() : "";
                int speed = 90;
                int dist = 100;

                // Simple regex to extract numbers if available
                try {
                    String combined = t + " " + b;
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2,3})\\s*(km|كم)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(combined);
                    if (m.find()) {
                        speed = Integer.parseInt(m.group(1));
                    }
                } catch (Exception ignored) {}

                sListener.onRadarbotAlert(t.length() > 0 ? t : "رادار مراقبة سرعة", speed, dist);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing notification: " + e.getMessage());
        }
    }
}
