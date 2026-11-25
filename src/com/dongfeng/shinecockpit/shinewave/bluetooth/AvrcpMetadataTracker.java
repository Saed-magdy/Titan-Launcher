package com.dongfeng.shinecockpit.shinewave.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.dongfeng.shinecockpit.CockpitMediaListenerService;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Monitors Android hidden A2DP_SINK (11) and AVRCP_CONTROLLER (12) profiles to read
 * Bluetooth track metadata, playback status, and dispatch AVRCP fast-forward/rewind pass-through keys.
 */
public final class AvrcpMetadataTracker {
    private static final int A2DP_SINK_PROFILE = 11;
    private static final int AVRCP_CONTROLLER_PROFILE = 12;

    private static final String ACTION_A2DP_SINK_CONN = "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED";
    private static final String ACTION_AVRCP_CONN = "android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED";
    private static final String ACTION_AVRCP_TRACK_EVENT = "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT";
    private static final String EXTRA_AVRCP_METADATA = "android.bluetooth.avrcp-controller.profile.extra.METADATA";
    private static final String EXTRA_AVRCP_PLAYBACK = "android.bluetooth.avrcp-controller.profile.extra.PLAYBACK";

    private static final int KEY_PLAY = 0x44;
    private static final int KEY_PAUSE = 0x46;
    private static final int KEY_FORWARD = 0x4B;
    private static final int KEY_BACKWARD = 0x4C;
    private static final int KEY_STATE_PRESSED = 0;
    private static final int KEY_STATE_RELEASED = 1;
    private static final long POLL_INTERVAL_MS = 1000L;

    public static final class AvrcpSnapshot {
        public final boolean enabled;
        public final boolean connected;
        public final String deviceName;
        public final String title;
        public final String artist;
        public final String album;
        public final Bitmap artwork;
        public final boolean playing;
        public final long positionMs;
        public final long durationMs;

        public AvrcpSnapshot(boolean enabled, boolean connected, String deviceName, String title, String artist,
                             String album, Bitmap artwork, boolean playing, long positionMs, long durationMs) {
            this.enabled = enabled;
            this.connected = connected;
            this.deviceName = deviceName;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.artwork = artwork;
            this.playing = playing;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
    }

    public interface Callback {
        void onAvrcpStateChanged(AvrcpSnapshot snapshot);
    }

    private final Context appContext;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothProfile a2dpSink;
    private BluetoothProfile avrcpController;
    private BluetoothDevice activeDevice;
    private boolean pollingActive = false;
    private boolean receiverRegistered = false;
    private Runnable pendingReleaseRunnable = null;
    private long lastRawPosMs = -1L;
    private long lastRawPosAtElapsedMs = 0L;
    private String lastTrackKey = "";
    private AvrcpSnapshot currentSnapshot = new AvrcpSnapshot(false, false, "", "", "", "", null, false, 0L, 0L);

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!pollingActive) return;
            refresh();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private final BluetoothProfile.ServiceListener profileListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == A2DP_SINK_PROFILE) a2dpSink = proxy;
            else if (profile == AVRCP_CONTROLLER_PROFILE) avrcpController = proxy;
            refresh();
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == A2DP_SINK_PROFILE) a2dpSink = null;
            else if (profile == AVRCP_CONTROLLER_PROFILE) {
                avrcpController = null;
                activeDevice = null;
            }
            refresh();
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (ACTION_AVRCP_TRACK_EVENT.equals(action)) {
                handleTrackEventIntent(intent);
                handler.postDelayed(new Runnable() {
                    @Override public void run() { refresh(); }
                }, 220L);
            } else {
                refresh();
            }
        }
    };

    public AvrcpMetadataTracker(Context context, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
        registerReceiver();
        refresh();
    }

    public void setActivePolling(boolean active) {
        if (this.pollingActive == active) return;
        this.pollingActive = active;
        handler.removeCallbacks(pollRunnable);
        if (active) {
            handler.post(pollRunnable);
        }
    }

    public void refresh() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            activeDevice = null;
            publish(new AvrcpSnapshot(false, false, "", "", "", "", null, false, 0L, 0L));
            return;
        }

        requestProfilesIfMissing(adapter);

        BluetoothDevice sinkDev = firstConnectedDevice(a2dpSink);
        BluetoothDevice avrcpDev = firstConnectedDevice(avrcpController);
        BluetoothDevice remote = (sinkDev != null) ? sinkDev : avrcpDev;
        activeDevice = remote;

        if (remote == null) {
            publish(new AvrcpSnapshot(true, false, "", "", "", "", null, false, 0L, 0L));
            return;
        }

        MediaMetadata meta = queryMetadata(remote);
        PlaybackState ps = queryPlaybackState(remote);
        MediaController btMc = getBluetoothMediaController();
        if (btMc != null) {
            try {
                MediaMetadata mcMeta = btMc.getMetadata();
                if (mcMeta != null) {
                    if (meta == null) {
                        meta = mcMeta;
                    } else {
                        String mTitle = trimOrEmpty(meta.getString(MediaMetadata.METADATA_KEY_TITLE));
                        long mDur = Math.max(0L, meta.getLong(MediaMetadata.METADATA_KEY_DURATION));
                        if (mTitle.isEmpty() || mDur <= 0L) {
                            meta = mcMeta;
                        }
                    }
                }
                PlaybackState mcPs = btMc.getPlaybackState();
                if (mcPs != null && (ps == null || mcPs.getLastPositionUpdateTime() > ps.getLastPositionUpdateTime())) {
                    ps = mcPs;
                }
            } catch (Throwable ignored) {}
        }
        String devName = safeName(remote);

        AvrcpSnapshot prev = currentSnapshot;
        boolean sameDevice = prev.connected && devName.equals(prev.deviceName);

        String title = meta != null ? trimOrEmpty(meta.getString(MediaMetadata.METADATA_KEY_TITLE)) : "";
        String artist = meta != null ? trimOrEmpty(meta.getString(MediaMetadata.METADATA_KEY_ARTIST)) : "";
        String album = meta != null ? trimOrEmpty(meta.getString(MediaMetadata.METADATA_KEY_ALBUM)) : "";
        long durMs = meta != null ? Math.max(0L, meta.getLong(MediaMetadata.METADATA_KEY_DURATION)) : 0L;
        Bitmap art = extractArtwork(meta);

        String finalTitle = !title.isEmpty() ? title : (sameDevice ? prev.title : "");
        String finalArtist = !artist.isEmpty() ? artist : (sameDevice ? prev.artist : "");
        String finalAlbum = !album.isEmpty() ? album : (sameDevice ? prev.album : "");
        long finalDur = durMs > 0L ? durMs : (sameDevice ? prev.durationMs : 0L);
        boolean sameTrack = sameDevice && (title.isEmpty() || title.equals(prev.title));
        Bitmap finalArt = (art != null) ? art : (sameTrack ? prev.artwork : null);
        boolean playing = ps != null ? isPlaybackActive(ps) : (sameDevice && prev.playing);

        long posMs;
        long nowElapsed = SystemClock.elapsedRealtime();
        String trackKey = finalTitle + "|" + finalArtist + "|" + finalDur;
        if (ps != null) {
            long rawPos = Math.max(0L, ps.getPosition());
            if (!trackKey.equals(lastTrackKey)) {
                lastTrackKey = trackKey;
                lastRawPosMs = rawPos;
                lastRawPosAtElapsedMs = nowElapsed;
            }
            if (isPlaybackActive(ps) && ps.getLastPositionUpdateTime() > 0L) {
                float spd = ps.getPlaybackSpeed() > 0f ? ps.getPlaybackSpeed() : 1.0f;
                long elapsed = nowElapsed - ps.getLastPositionUpdateTime();
                posMs = (elapsed > 0L && elapsed < 3_600_000L) ? (rawPos + (long) (elapsed * spd)) : rawPos;
            } else if (playing) {
                if (rawPos != lastRawPosMs) {
                    lastRawPosMs = rawPos;
                    lastRawPosAtElapsedMs = nowElapsed;
                    posMs = rawPos;
                } else {
                    posMs = lastRawPosMs + Math.max(0L, nowElapsed - lastRawPosAtElapsedMs);
                }
            } else {
                lastRawPosMs = rawPos;
                lastRawPosAtElapsedMs = nowElapsed;
                posMs = rawPos;
            }
        } else {
            posMs = sameTrack ? prev.positionMs : 0L;
            if (!trackKey.equals(lastTrackKey)) {
                lastTrackKey = trackKey;
                lastRawPosMs = posMs;
                lastRawPosAtElapsedMs = nowElapsed;
            }
        }
        if (finalDur > 0L && posMs > finalDur) {
            posMs = finalDur;
        }

        publish(new AvrcpSnapshot(true, true, devName, finalTitle, finalArtist, finalAlbum,
                finalArt, playing, posMs, finalDur));
    }

    public AvrcpSnapshot getSnapshot() {
        return currentSnapshot;
    }

    public MediaController getBluetoothMediaController() {
        try {
            MediaSessionManager mgr = (MediaSessionManager) appContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (mgr == null) return null;
            List<MediaController> list = mgr.getActiveSessions(new ComponentName(appContext, CockpitMediaListenerService.class));
            if (list != null) {
                for (MediaController c : list) {
                    if ("com.android.bluetooth".equals(c.getPackageName())) {
                        return c;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public void sendPassThroughFallback(String cmd) {
        // 1. Try dispatching directly to the Bluetooth MediaSession first
        MediaController mc = getBluetoothMediaController();
        if (mc != null) {
            try {
                MediaController.TransportControls tc = mc.getTransportControls();
                switch (cmd) {
                    case "play": tc.play(); break;
                    case "pause": tc.pause(); break;
                    case "toggle":
                        if (currentSnapshot.playing) tc.pause(); else tc.play();
                        break;
                    case "next": tc.skipToNext(); break;
                    case "previous": tc.skipToPrevious(); break;
                }
            } catch (Throwable t) {
                Log.w("AvrcpMetadataTracker", "Bluetooth MediaController transport error: " + t.getMessage());
            }
        }

        // 2. Also send AVRCP Pass-Through command (0x44 PLAY / 0x46 PAUSE / etc.)
        BluetoothDevice dev = activeDevice != null ? activeDevice : firstConnectedDevice(avrcpController);
        if (dev != null && avrcpController != null) {
            int code;
            switch (cmd) {
                case "next": code = KEY_FORWARD; break;
                case "previous": code = KEY_BACKWARD; break;
                case "play": code = KEY_PLAY; break;
                case "pause": code = KEY_PAUSE; break;
                case "toggle":
                    code = currentSnapshot.playing ? KEY_PAUSE : KEY_PLAY;
                    break;
                default: return;
            }
            sendPassThrough(dev, code, KEY_STATE_PRESSED, 45L);
        }
    }

    private void handleTrackEventIntent(Intent intent) {
        try {
            MediaMetadata meta = intent.getParcelableExtra(EXTRA_AVRCP_METADATA);
            PlaybackState ps = intent.getParcelableExtra(EXTRA_AVRCP_PLAYBACK);
            if (meta == null && ps == null) return;
            AvrcpSnapshot prev = currentSnapshot;
            String title = meta != null ? trimOrEmpty(meta.getString(MediaMetadata.METADATA_KEY_TITLE)) : "";
            String artist = meta != null ? trimOrEmpty(meta.getString(MediaMetadata.METADATA_KEY_ARTIST)) : "";
            String album = meta != null ? trimOrEmpty(meta.getString(MediaMetadata.METADATA_KEY_ALBUM)) : "";
            long durMs = meta != null ? Math.max(0L, meta.getLong(MediaMetadata.METADATA_KEY_DURATION)) : 0L;
            Bitmap art = extractArtwork(meta);
            boolean playing = ps != null ? isPlaybackActive(ps) : prev.playing;
            long posMs = ps != null ? Math.max(0L, ps.getPosition()) : prev.positionMs;
            if (ps != null) {
                lastRawPosMs = posMs;
                lastRawPosAtElapsedMs = SystemClock.elapsedRealtime();
            }

            publish(new AvrcpSnapshot(
                    prev.enabled, true, prev.deviceName,
                    !title.isEmpty() ? title : prev.title,
                    !artist.isEmpty() ? artist : prev.artist,
                    !album.isEmpty() ? album : prev.album,
                    art != null ? art : prev.artwork,
                    playing, posMs,
                    durMs > 0L ? durMs : prev.durationMs
            ));
        } catch (Throwable ignored) {}
    }

    private void sendPassThrough(final BluetoothDevice dev, final int keyCode, int state, Long releaseDelayMs) {
        final Object ctrl = avrcpController;
        if (ctrl == null) return;
        try {
            final Method m = findPassThroughMethod(ctrl);
            if (m == null) return;
            m.setAccessible(true);
            m.invoke(ctrl, dev, keyCode, state);
            if (releaseDelayMs != null) {
                if (pendingReleaseRunnable != null) {
                    handler.removeCallbacks(pendingReleaseRunnable);
                }
                pendingReleaseRunnable = new Runnable() {
                    @Override
                    public void run() {
                        pendingReleaseRunnable = null;
                        try {
                            m.invoke(ctrl, dev, keyCode, KEY_STATE_RELEASED);
                        } catch (Throwable ignored) {}
                    }
                };
                handler.postDelayed(pendingReleaseRunnable, releaseDelayMs);
            }
        } catch (Throwable ignored) {}
    }

    private Method findPassThroughMethod(Object ctrl) {
        String[] names = new String[]{"sendPassThroughCmd", "sendPassThroughCommand"};
        for (Class<?> c = ctrl.getClass(); c != null; c = c.getSuperclass()) {
            for (String n : names) {
                try {
                    return c.getDeclaredMethod(n, BluetoothDevice.class, int.class, int.class);
                } catch (NoSuchMethodException ignored) {}
            }
        }
        return null;
    }

    private MediaMetadata queryMetadata(BluetoothDevice dev) {
        if (avrcpController == null) return null;
        for (String name : new String[]{"getMetadata", "getMetaData"}) {
            try {
                Method m = avrcpController.getClass().getMethod(name, BluetoothDevice.class);
                m.setAccessible(true);
                Object res = m.invoke(avrcpController, dev);
                if (res instanceof MediaMetadata) return (MediaMetadata) res;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private PlaybackState queryPlaybackState(BluetoothDevice dev) {
        if (avrcpController == null) return null;
        try {
            Method m = avrcpController.getClass().getMethod("getPlaybackState", BluetoothDevice.class);
            m.setAccessible(true);
            Object res = m.invoke(avrcpController, dev);
            if (res instanceof PlaybackState) return (PlaybackState) res;
        } catch (Throwable ignored) {}
        return null;
    }

    private void requestProfilesIfMissing(BluetoothAdapter adapter) {
        try {
            if (a2dpSink == null) adapter.getProfileProxy(appContext, profileListener, A2DP_SINK_PROFILE);
        } catch (Throwable ignored) {}
        try {
            if (avrcpController == null) adapter.getProfileProxy(appContext, profileListener, AVRCP_CONTROLLER_PROFILE);
        } catch (Throwable ignored) {}
    }

    private BluetoothDevice firstConnectedDevice(BluetoothProfile profile) {
        if (profile == null) return null;
        try {
            List<BluetoothDevice> list = profile.getConnectedDevices();
            return (list != null && !list.isEmpty()) ? list.get(0) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap extractArtwork(MediaMetadata meta) {
        if (meta == null) return null;
        try {
            Bitmap b = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (b != null && !b.isRecycled()) return b;
            b = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (b != null && !b.isRecycled()) return b;
            b = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (b != null && !b.isRecycled()) return b;
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isPlaybackActive(PlaybackState ps) {
        int s = ps.getState();
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING;
    }

    private static String safeName(BluetoothDevice dev) {
        try {
            String n = dev.getName();
            return n != null ? n : "Bluetooth Device";
        } catch (Throwable ignored) {
            return "Bluetooth Device";
        }
    }

    private static String trimOrEmpty(String s) {
        return s != null ? s.trim() : "";
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction(ACTION_A2DP_SINK_CONN);
            f.addAction(ACTION_AVRCP_CONN);
            f.addAction(ACTION_AVRCP_TRACK_EVENT);
            appContext.registerReceiver(receiver, f);
            receiverRegistered = true;
        } catch (Throwable ignored) {}
    }

    private void publish(AvrcpSnapshot snapshot) {
        currentSnapshot = snapshot;
        if (callback != null) callback.onAvrcpStateChanged(snapshot);
    }

    public void release() {
        pollingActive = false;
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) {
            try { appContext.unregisterReceiver(receiver); } catch (Throwable ignored) {}
            receiverRegistered = false;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            try { if (a2dpSink != null) adapter.closeProfileProxy(A2DP_SINK_PROFILE, a2dpSink); } catch (Throwable ignored) {}
            try { if (avrcpController != null) adapter.closeProfileProxy(AVRCP_CONTROLLER_PROFILE, avrcpController); } catch (Throwable ignored) {}
        }
        a2dpSink = null;
        avrcpController = null;
    }
}
