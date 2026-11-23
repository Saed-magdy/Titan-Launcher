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
import android.util.Log;

import com.dongfeng.shinecockpit.CockpitMediaListenerService;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import android.media.session.MediaSession;
import org.json.JSONArray;
import org.json.JSONObject;

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
    private static final int KEY_FAST_FORWARD = 0x49;
    private static final int KEY_REWIND = 0x48;
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
    private int heldSeekKey = 0;
    private Runnable pendingReleaseRunnable = null;
    private AvrcpSnapshot currentSnapshot = new AvrcpSnapshot(false, false, "", "", "", "", null, false, 0L, 0L);

    private static final class BtTrackItem {
        final String title;
        final String artist;
        final long durationSec;
        BtTrackItem(String title, String artist, long durationSec) {
            this.title = title;
            this.artist = artist;
            this.durationSec = durationSec;
        }
    }
    private final List<BtTrackItem> sessionTracks = new ArrayList<>();

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
        long posMs = ps != null ? Math.max(0L, ps.getPosition()) : (sameDevice ? prev.positionMs : 0L);

        publish(new AvrcpSnapshot(true, true, devName, finalTitle, finalArtist, finalAlbum,
                finalArt, playing, posMs, finalDur));
    }

    public AvrcpSnapshot getSnapshot() {
        return currentSnapshot;
    }

    public static final int REPEAT_MODE_INVALID = -1;
    public static final int REPEAT_MODE_NONE = 0;
    public static final int REPEAT_MODE_ONE = 1;
    public static final int REPEAT_MODE_ALL = 2;

    public static final int SHUFFLE_MODE_INVALID = -1;
    public static final int SHUFFLE_MODE_NONE = 0;
    public static final int SHUFFLE_MODE_ALL = 1;

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

    public boolean cycleRepeatMode() {
        MediaController mc = getBluetoothMediaController();
        if (mc != null) {
            try {
                int current = getRepeatMode();
                if (current < 0) current = REPEAT_MODE_NONE;
                int next;
                if (current == REPEAT_MODE_NONE) {
                    next = REPEAT_MODE_ALL;
                } else if (current == REPEAT_MODE_ALL) {
                    next = REPEAT_MODE_ONE;
                } else {
                    next = REPEAT_MODE_NONE;
                }
                Method m = mc.getTransportControls().getClass().getMethod("setRepeatMode", int.class);
                m.invoke(mc.getTransportControls(), next);
                return true;
            } catch (Throwable t) {
                Log.w("AvrcpMetadataTracker", "cycleRepeatMode unsupported on Android 8.1: " + t.getMessage());
            }
        }
        return false;
    }

    public boolean toggleShuffleMode() {
        MediaController mc = getBluetoothMediaController();
        if (mc != null) {
            try {
                int current = getShuffleMode();
                if (current < 0) current = SHUFFLE_MODE_NONE;
                int next = (current == SHUFFLE_MODE_NONE) ? SHUFFLE_MODE_ALL : SHUFFLE_MODE_NONE;
                Method m = mc.getTransportControls().getClass().getMethod("setShuffleMode", int.class);
                m.invoke(mc.getTransportControls(), next);
                return true;
            } catch (Throwable t) {
                Log.w("AvrcpMetadataTracker", "toggleShuffleMode unsupported on Android 8.1: " + t.getMessage());
            }
        }
        return false;
    }

    public int getRepeatMode() {
        MediaController mc = getBluetoothMediaController();
        if (mc != null) {
            try {
                Method m = mc.getClass().getMethod("getRepeatMode");
                Object res = m.invoke(mc);
                return (res instanceof Number) ? ((Number) res).intValue() : REPEAT_MODE_INVALID;
            } catch (Throwable ignored) {}
        }
        return REPEAT_MODE_INVALID;
    }

    public int getShuffleMode() {
        MediaController mc = getBluetoothMediaController();
        if (mc != null) {
            try {
                Method m = mc.getClass().getMethod("getShuffleMode");
                Object res = m.invoke(mc);
                return (res instanceof Number) ? ((Number) res).intValue() : SHUFFLE_MODE_INVALID;
            } catch (Throwable ignored) {}
        }
        return SHUFFLE_MODE_INVALID;
    }

    public synchronized JSONObject getQueueJson() {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        int curIdx = 0;
        try {
            MediaController mc = getBluetoothMediaController();
            if (mc != null) {
                List<MediaSession.QueueItem> q = mc.getQueue();
                if (q != null && !q.isEmpty()) {
                    PlaybackState ps = mc.getPlaybackState();
                    long activeId = (ps != null) ? ps.getActiveQueueItemId() : -1L;
                    for (int i = 0; i < q.size(); i++) {
                        MediaSession.QueueItem qi = q.get(i);
                        String t = (qi.getDescription() != null && qi.getDescription().getTitle() != null)
                                ? qi.getDescription().getTitle().toString() : ("Track " + (i + 1));
                        String sub = (qi.getDescription() != null && qi.getDescription().getSubtitle() != null)
                                ? qi.getDescription().getSubtitle().toString() : "Bluetooth Audio";
                        boolean active = (qi.getQueueId() == activeId) || (activeId == -1L && currentSnapshot != null && t.equals(currentSnapshot.title));
                        if (active) curIdx = i;
                        arr.put(new JSONObject()
                                .put("index", i)
                                .put("id", String.valueOf(qi.getQueueId()))
                                .put("title", t)
                                .put("artist", sub)
                                .put("durationSec", 0)
                                .put("active", active));
                    }
                    root.put("source", "BLUETOOTH");
                    root.put("currentIndex", curIdx);
                    root.put("items", arr);
                    return root;
                }
            }
            String curTitle = currentSnapshot != null ? currentSnapshot.title : "";
            String curArtist = currentSnapshot != null ? currentSnapshot.artist : "";
            if (!sessionTracks.isEmpty()) {
                for (int i = 0; i < sessionTracks.size(); i++) {
                    BtTrackItem it = sessionTracks.get(i);
                    boolean active = it.title.equals(curTitle) && it.artist.equals(curArtist);
                    if (active) curIdx = i;
                    arr.put(new JSONObject()
                            .put("index", i)
                            .put("id", "bt_" + i)
                            .put("title", it.title)
                            .put("artist", it.artist.isEmpty() ? "Bluetooth Audio" : it.artist)
                            .put("durationSec", it.durationSec)
                            .put("active", active));
                }
            } else {
                arr.put(new JSONObject()
                        .put("index", 0)
                        .put("id", "bt_active")
                        .put("title", curTitle.isEmpty() ? "Bluetooth Audio" : curTitle)
                        .put("artist", curArtist)
                        .put("durationSec", currentSnapshot != null ? (currentSnapshot.durationMs / 1000L) : 0)
                        .put("active", true));
            }
            root.put("source", "BLUETOOTH");
            root.put("currentIndex", curIdx);
            root.put("items", arr);
        } catch (Throwable ignored) {}
        return root;
    }

    public synchronized int resolveQueueStepOffset(int targetIndex) {
        MediaController mc = getBluetoothMediaController();
        if (mc != null) {
            try {
                List<MediaSession.QueueItem> q = mc.getQueue();
                if (q != null && targetIndex >= 0 && targetIndex < q.size()) {
                    mc.getTransportControls().skipToQueueItem(q.get(targetIndex).getQueueId());
                    return 0;
                }
            } catch (Throwable ignored) {}
        }
        String curTitle = currentSnapshot != null ? currentSnapshot.title : "";
        String curArtist = currentSnapshot != null ? currentSnapshot.artist : "";
        int curIdx = -1;
        for (int i = 0; i < sessionTracks.size(); i++) {
            BtTrackItem it = sessionTracks.get(i);
            if (it.title.equals(curTitle) && it.artist.equals(curArtist)) {
                curIdx = i;
                break;
            }
        }
        if (curIdx < 0 || targetIndex < 0 || targetIndex >= sessionTracks.size()) return 0;
        return targetIndex - curIdx;
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
                case "seek_forward": code = KEY_FAST_FORWARD; break;
                case "seek_back": code = KEY_REWIND; break;
                default: return;
            }
            sendPassThrough(dev, code, KEY_STATE_PRESSED, (code == KEY_FAST_FORWARD || code == KEY_REWIND) ? 650L : 45L);
        }
    }

    public void beginSeekHold(boolean forward) {
        BluetoothDevice dev = activeDevice != null ? activeDevice : firstConnectedDevice(avrcpController);
        if (dev == null || avrcpController == null) return;
        heldSeekKey = forward ? KEY_FAST_FORWARD : KEY_REWIND;
        sendPassThrough(dev, heldSeekKey, KEY_STATE_PRESSED, null);
    }

    public void seekToMs(final long posMs) {
        final long target = Math.max(0L, posMs);
        boolean handled = false;
        MediaController mc = getBluetoothMediaController();
        if (mc != null) {
            try {
                mc.getTransportControls().seekTo(target);
                handled = true;
            } catch (Throwable t) {
                Log.w("AvrcpMetadataTracker", "seekToMs failed on Bluetooth MediaController: " + t.getMessage());
            }
        }
        if (!handled) {
            handled = CockpitMediaListenerService.seekTo(appContext, target);
        }
        if (currentSnapshot != null) {
            currentSnapshot = new AvrcpSnapshot(
                currentSnapshot.enabled, currentSnapshot.connected, currentSnapshot.deviceName,
                currentSnapshot.title, currentSnapshot.artist, currentSnapshot.album,
                currentSnapshot.artwork, currentSnapshot.playing, target, currentSnapshot.durationMs
            );
            publish(currentSnapshot);
        }
    }

    public void seekByMs(final long deltaMs) {
        long curPos = currentSnapshot != null ? currentSnapshot.positionMs : 0L;
        long target = Math.max(0L, curPos + deltaMs);
        if (currentSnapshot != null && currentSnapshot.durationMs > 0L) {
            target = Math.min(target, currentSnapshot.durationMs);
        }
        boolean handled = false;
        MediaController mc = getBluetoothMediaController();
        if (mc != null) {
            try {
                mc.getTransportControls().seekTo(target);
                handled = true;
            } catch (Throwable t) {
                Log.w("AvrcpMetadataTracker", "seekByMs failed on Bluetooth MediaController: " + t.getMessage());
            }
        }
        if (!handled) {
            handled = CockpitMediaListenerService.seekBy(appContext, deltaMs);
        }
        BluetoothDevice dev = activeDevice != null ? activeDevice : firstConnectedDevice(avrcpController);
        if (!handled && heldSeekKey == 0 && dev != null && avrcpController != null) {
            int code = (deltaMs > 0) ? KEY_FAST_FORWARD : KEY_REWIND;
            sendPassThrough(dev, code, KEY_STATE_PRESSED, 400L);
        }
        if (currentSnapshot != null) {
            currentSnapshot = new AvrcpSnapshot(
                currentSnapshot.enabled, currentSnapshot.connected, currentSnapshot.deviceName,
                currentSnapshot.title, currentSnapshot.artist, currentSnapshot.album,
                currentSnapshot.artwork, currentSnapshot.playing, target, currentSnapshot.durationMs
            );
            publish(currentSnapshot);
        }
    }

    public void endSeekHold() {
        if (pendingReleaseRunnable != null) {
            handler.removeCallbacks(pendingReleaseRunnable);
            pendingReleaseRunnable = null;
        }
        int key = heldSeekKey;
        if (key == 0) return;
        heldSeekKey = 0;
        BluetoothDevice dev = activeDevice != null ? activeDevice : firstConnectedDevice(avrcpController);
        if (dev != null) {
            sendPassThrough(dev, key, KEY_STATE_RELEASED, null);
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
        if (snapshot != null && snapshot.connected && snapshot.title != null && !snapshot.title.isEmpty()
                && !snapshot.title.equals("Bluetooth Audio") && !snapshot.title.contains("لا يوجد جهاز")) {
            synchronized (this) {
                boolean exists = false;
                for (BtTrackItem it : sessionTracks) {
                    if (it.title.equalsIgnoreCase(snapshot.title) && it.artist.equalsIgnoreCase(snapshot.artist)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    sessionTracks.add(new BtTrackItem(snapshot.title, snapshot.artist, snapshot.durationMs / 1000L));
                    if (sessionTracks.size() > 30) sessionTracks.remove(0);
                }
            }
        }
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
