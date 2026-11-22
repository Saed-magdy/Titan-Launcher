package com.dongfeng.shinecockpit.shinewave.external;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Method;

import com.dongfeng.shinecockpit.CockpitMediaListenerService;
import com.dongfeng.shinecockpit.shinewave.local.ArtworkCacheManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Routes playback controls and live metadata reading for external media applications
 * via CockpitMediaListenerService. Filters selectable apps strictly to downloaded
 * media/audio apps plus the stock Music app (excluding general system apps).
 */
public final class ExternalPlayerRouter {
    private static final String TAG = "SWExternalRouter";
    private static final String PREFS = "shine_wave_integrated_prefs";
    private static final String KEY_PKG = "external_selected_pkg";
    private static final String KEY_LABEL = "external_selected_label";

    private static final Set<String> ALLOWED_SYSTEM_MUSIC_PACKAGES = new HashSet<>(Arrays.asList(
            "com.qinggan.app.music",
            "com.android.music",
            "com.google.android.music",
            "com.google.android.apps.youtube.music"
    ));

    private static final String[] MEDIA_KEYWORDS = new String[]{
            "music", "audio", "radio", "spotify", "anghami", "youtube", "ytmusic", "vanced", "revanced",
            "newpipe", "soundcloud", "deezer", "tidal", "audible", "podcast", "vlc", "poweramp",
            "aimp", "foobar", "musicolet", "jetaudio", "blackplayer", "pulsar", "retro", "auxio",
            "shahid", "tunein", "quran", "mp3", "flac", "plex", "kodi", "stream", "fm", "player"
    };

    private static final String[] EXCLUDED_NON_MEDIA_KEYWORDS = new String[]{
            "chrome", "browser", "webview", "whatsapp", "telegram", "viber", "messenger", "facebook",
            "instagram", "tiktok.lite", "maps", "waze", "radarbot", "torque", "carscanner", "obd",
            "launcher", "settings", "gallery", "photos", "camera", "filemanager", "files",
            "phone", "contacts", "dialer", "messages", "clock", "calendar", "calculator",
            "playstore", "vending", "installer", "systemui", "updater", "keyboard"
    };

    public static final class SessionSnapshot {
        public final String packageName;
        public final String label;
        public final String title;
        public final String artist;
        public final String album;
        public final boolean playing;
        public final long positionSec;
        public final long durationSec;
        public final String artSig;
        public final String artDataUrl;

        public SessionSnapshot(String packageName, String label, String title, String artist, String album,
                               boolean playing, long positionSec, long durationSec, String artSig, String artDataUrl) {
            this.packageName = packageName;
            this.label = label;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.playing = playing;
            this.positionSec = positionSec;
            this.durationSec = durationSec;
            this.artSig = artSig;
            this.artDataUrl = artDataUrl;
        }
    }

    private final Context appContext;
    private final SharedPreferences prefs;
    private final ArtworkCacheManager artworkCache;
    private final Map<String, String> appIconCache = new HashMap<>();

    public ExternalPlayerRouter(Context context, ArtworkCacheManager artworkCache) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.artworkCache = artworkCache;
    }

    public String getSelectedPackage() {
        return prefs.getString(KEY_PKG, "");
    }

    public String getSelectedLabel() {
        return prefs.getString(KEY_LABEL, "");
    }

    public void selectApp(String pkg, String label, boolean launchNow) {
        String oldPkg = getSelectedPackage();
        if (!oldPkg.isEmpty() && !oldPkg.equals(pkg)) {
            pausePackage(oldPkg);
        }
        prefs.edit().putString(KEY_PKG, pkg != null ? pkg : "").putString(KEY_LABEL, label != null ? label : "").apply();
        if (launchNow && pkg != null && !pkg.isEmpty()) {
            try {
                Intent launch = appContext.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appContext.startActivity(launch);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Unable to launch external app " + pkg + ": " + t.getMessage());
            }
        }
    }

    public void pauseSelected() {
        String pkg = getSelectedPackage();
        if (!pkg.isEmpty()) {
            pausePackage(pkg);
        }
    }

    public static final int REPEAT_MODE_INVALID = -1;
    public static final int REPEAT_MODE_NONE = 0;
    public static final int REPEAT_MODE_ONE = 1;
    public static final int REPEAT_MODE_ALL = 2;
    public static final int REPEAT_MODE_GROUP = 3;

    public static final int SHUFFLE_MODE_INVALID = -1;
    public static final int SHUFFLE_MODE_NONE = 0;
    public static final int SHUFFLE_MODE_ALL = 1;
    public static final int SHUFFLE_MODE_GROUP = 2;

    public boolean cycleRepeatMode() {
        MediaController controller = findSession(getSelectedPackage());
        if (controller == null) return false;
        try {
            int current = getRepeatMode();
            int next;
            // 0: NONE/OFF -> 2: ALL -> 1: ONE -> 0: OFF
            if (current == REPEAT_MODE_NONE) {
                next = REPEAT_MODE_ALL;
            } else if (current == REPEAT_MODE_ALL) {
                next = REPEAT_MODE_ONE;
            } else {
                next = REPEAT_MODE_NONE;
            }
            Method m = controller.getTransportControls().getClass().getMethod("setRepeatMode", int.class);
            m.invoke(controller.getTransportControls(), next);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "cycleRepeatMode failed: " + t.getMessage());
            return false;
        }
    }

    public boolean toggleShuffleMode() {
        MediaController controller = findSession(getSelectedPackage());
        if (controller == null) return false;
        try {
            int current = getShuffleMode();
            int next = (current == SHUFFLE_MODE_NONE) ? SHUFFLE_MODE_ALL : SHUFFLE_MODE_NONE;
            Method m = controller.getTransportControls().getClass().getMethod("setShuffleMode", int.class);
            m.invoke(controller.getTransportControls(), next);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "toggleShuffleMode failed: " + t.getMessage());
            return false;
        }
    }

    public int getRepeatMode() {
        MediaController controller = findSession(getSelectedPackage());
        if (controller == null) return REPEAT_MODE_NONE;
        try {
            Method m = controller.getClass().getMethod("getRepeatMode");
            Object res = m.invoke(controller);
            return (res instanceof Number) ? ((Number) res).intValue() : REPEAT_MODE_NONE;
        } catch (Throwable ignored) {
            return REPEAT_MODE_NONE;
        }
    }

    public int getShuffleMode() {
        MediaController controller = findSession(getSelectedPackage());
        if (controller == null) return SHUFFLE_MODE_NONE;
        try {
            Method m = controller.getClass().getMethod("getShuffleMode");
            Object res = m.invoke(controller);
            return (res instanceof Number) ? ((Number) res).intValue() : SHUFFLE_MODE_NONE;
        } catch (Throwable ignored) {
            return SHUFFLE_MODE_NONE;
        }
    }

    public boolean dispatchCommand(String cmd) {
        String pkg = getSelectedPackage();
        MediaController controller = findSession(pkg);
        if (controller != null) {
            try {
                MediaController.TransportControls tc = controller.getTransportControls();
                switch (cmd) {
                    case "play":
                        tc.play();
                        // Also dispatch targeted media key for players that ignore onPlay() when paused
                        dispatchTargetedMediaKey(pkg, KeyEvent.KEYCODE_MEDIA_PLAY);
                        return true;
                    case "pause":
                        tc.pause();
                        dispatchTargetedMediaKey(pkg, KeyEvent.KEYCODE_MEDIA_PAUSE);
                        return true;
                    case "toggle": {
                        PlaybackState st = controller.getPlaybackState();
                        boolean isPlaying = st != null && (st.getState() == PlaybackState.STATE_PLAYING
                                || st.getState() == PlaybackState.STATE_BUFFERING);
                        if (isPlaying) {
                            tc.pause();
                            dispatchTargetedMediaKey(pkg, KeyEvent.KEYCODE_MEDIA_PAUSE);
                        } else {
                            tc.play();
                            dispatchTargetedMediaKey(pkg, KeyEvent.KEYCODE_MEDIA_PLAY);
                        }
                        return true;
                    }
                    case "next": tc.skipToNext(); return true;
                    case "previous": tc.skipToPrevious(); return true;
                    case "seek_back":
                        seekControllerBy(controller, -10_000L);
                        return true;
                    case "seek_forward":
                        seekControllerBy(controller, 10_000L);
                        return true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "MediaController transport failed: " + t.getMessage());
            }
        }
        return dispatchMediaKey(cmd);
    }

    private void dispatchTargetedMediaKey(String pkg, int keyCode) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            long now = SystemClock.uptimeMillis();
            Intent down = new Intent(Intent.ACTION_MEDIA_BUTTON);
            down.setPackage(pkg);
            down.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            appContext.sendOrderedBroadcast(down, null);

            Intent up = new Intent(Intent.ACTION_MEDIA_BUTTON);
            up.setPackage(pkg);
            up.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
            appContext.sendOrderedBroadcast(up, null);
        } catch (Throwable ignored) {}
    }

    public boolean seekToSec(long sec) {
        MediaController controller = findSession(getSelectedPackage());
        if (controller == null) return false;
        try {
            controller.getTransportControls().seekTo(Math.max(0L, sec * 1000L));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean seekBySec(long deltaSec) {
        MediaController controller = findSession(getSelectedPackage());
        if (controller == null) return false;
        return seekControllerBy(controller, deltaSec * 1000L);
    }

    private boolean seekControllerBy(MediaController controller, long deltaMs) {
        try {
            PlaybackState st = controller.getPlaybackState();
            long pos = st != null ? st.getPosition() : 0L;
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            }
            long target = Math.max(0L, pos + deltaMs);
            MediaMetadata meta = controller.getMetadata();
            long dur = meta != null ? Math.max(0L, meta.getLong(MediaMetadata.METADATA_KEY_DURATION)) : 0L;
            if (dur > 0L) target = Math.min(target, dur);
            controller.getTransportControls().seekTo(target);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public SessionSnapshot readSelectedSession() {
        String pkg = getSelectedPackage();
        String label = getSelectedLabel();
        if (pkg.isEmpty()) return null;

        MediaController controller = findSession(pkg);
        if (controller == null) {
            return new SessionSnapshot(pkg, label, label.isEmpty() ? "مشغل خارجي" : label,
                    "اضغط تشغيل لفتح المشغل", "", false, 0L, 0L, "", null);
        }

        MediaMetadata meta = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        String title = "";
        String artist = "";
        String album = "";
        long durMs = 0L;
        String artDataUrl = null;
        String artSig = "";

        if (meta != null) {
            CharSequence t = meta.getText(MediaMetadata.METADATA_KEY_TITLE);
            if (t != null) title = t.toString().trim();
            CharSequence a = meta.getText(MediaMetadata.METADATA_KEY_ARTIST);
            if (a == null) a = meta.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (a != null) artist = a.toString().trim();
            CharSequence al = meta.getText(MediaMetadata.METADATA_KEY_ALBUM);
            if (al != null) album = al.toString().trim();
            durMs = Math.max(0L, meta.getLong(MediaMetadata.METADATA_KEY_DURATION));

            String uriStr = trimOrEmpty(meta.getString(MediaMetadata.METADATA_KEY_ART_URI));
            if (uriStr.isEmpty()) uriStr = trimOrEmpty(meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI));
            artSig = "ext|" + pkg + "|" + title + "|" + artist + "|" + durMs + "|" + uriStr;
            artDataUrl = artworkCache.getCachedDataUrl(artSig);

            if (artDataUrl == null) {
                Bitmap bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (bmp == null) bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (bmp == null) bmp = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (bmp == null && !uriStr.isEmpty()) {
                    bmp = loadBitmapFromUri(uriStr);
                }
                if (bmp != null) {
                    artDataUrl = artworkCache.cacheBitmap(artSig, bmp);
                }
            }
        }

        boolean playing = state != null && (state.getState() == PlaybackState.STATE_PLAYING
                || state.getState() == PlaybackState.STATE_BUFFERING);
        long posMs = state != null ? Math.max(0L, state.getPosition()) : 0L;

        return new SessionSnapshot(
                pkg,
                label,
                !title.isEmpty() ? title : (!label.isEmpty() ? label : "External Player"),
                !artist.isEmpty() ? artist : (!label.isEmpty() ? label : "مشغل خارجي"),
                album,
                playing,
                posMs / 1000L,
                durMs / 1000L,
                artSig,
                artDataUrl
        );
    }

    /**
     * Returns only downloaded/user-installed media applications plus the stock Music app,
     * excluding general system utilities, navigation, messengers, and Titan Launcher itself.
     */
    public JSONObject getFilteredMediaAppsJson() {
        JSONObject root = new JSONObject();
        JSONArray appsArr = new JSONArray();
        String selectedPkg = getSelectedPackage();

        try {
            PackageManager pm = appContext.getPackageManager();
            Set<String> mediaServicePkgs = collectMediaServicePackages(pm);

            Intent mainIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(mainIntent, 0);
            Map<String, ResolveInfo> distinctByPkg = new HashMap<>();
            for (ResolveInfo ri : resolved) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (pkg == null || pkg.equals(appContext.getPackageName()) || "com.shine.wave".equals(pkg)) {
                    continue;
                }
                if (!distinctByPkg.containsKey(pkg)) {
                    distinctByPkg.put(pkg, ri);
                }
            }

            List<ResolveInfo> filtered = new ArrayList<>();
            for (Map.Entry<String, ResolveInfo> entry : distinctByPkg.entrySet()) {
                ResolveInfo ri = entry.getValue();
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                if (ai == null) continue;

                String pkg = ai.packageName;
                String label = safeLabel(pm, ri);
                if (shouldIncludeExternalApp(pkg, label, ai, mediaServicePkgs)) {
                    filtered.add(ri);
                }
            }

            Collections.sort(filtered, new Comparator<ResolveInfo>() {
                @Override
                public int compare(ResolveInfo a, ResolveInfo b) {
                    return safeLabel(pm, a).compareToIgnoreCase(safeLabel(pm, b));
                }
            });

            for (ResolveInfo ri : filtered) {
                String pkg = ri.activityInfo.packageName;
                String label = safeLabel(pm, ri);
                JSONObject item = new JSONObject();
                item.put("pkg", pkg);
                item.put("label", label);
                item.put("icon", getCachedAppIcon(pm, ri, pkg));
                item.put("selected", pkg.equals(selectedPkg));
                item.put("isSystemMusic", (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                appsArr.put(item);
            }

            root.put("selectedPackage", selectedPkg);
            root.put("selectedLabel", getSelectedLabel());
            root.put("apps", appsArr);
        } catch (Throwable t) {
            Log.e(TAG, "getFilteredMediaAppsJson error: " + t.getMessage());
        }
        return root;
    }

    private boolean shouldIncludeExternalApp(String pkg, String label, ApplicationInfo ai, Set<String> mediaServicePkgs) {
        String lowerPkg = pkg.toLowerCase(Locale.ROOT);
        String lowerLbl = label.toLowerCase(Locale.ROOT);

        // Exclude browsers, navigators, messengers, utilities regardless of flags
        for (String banned : EXCLUDED_NON_MEDIA_KEYWORDS) {
            if (lowerPkg.contains(banned) || lowerLbl.equals(banned)) {
                return false;
            }
        }
        // Exclude stock OEM radio/video/bluetooth apps handled natively inside Shine Wave tabs
        if (lowerPkg.contains("qinggan.app.radio") || lowerPkg.contains("yhxdabw8")
                || lowerPkg.contains("qinggan.app.bt") || lowerPkg.contains("qinggan.app.video")) {
            return false;
        }

        boolean isPureSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;

        if (isPureSystem) {
            // From system apps, ONLY allow the stock Music app
            if (ALLOWED_SYSTEM_MUSIC_PACKAGES.contains(lowerPkg)) return true;
            return "music".equals(lowerLbl) || "الموسيقى".equals(lowerLbl) || "موسيقى".equals(lowerLbl);
        }

        // For downloaded / user-installed (or updated) apps: filter strictly to media/audio apps
        if (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_AUDIO) {
            return true;
        }
        if (mediaServicePkgs.contains(pkg)) {
            return true;
        }
        for (String kw : MEDIA_KEYWORDS) {
            if (lowerPkg.contains(kw) || lowerLbl.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> collectMediaServicePackages(PackageManager pm) {
        Set<String> pkgs = new HashSet<>();
        String[] serviceActions = new String[]{
                "android.media.browse.MediaBrowserService",
                "androidx.media3.session.MediaSessionService",
                "androidx.media3.session.MediaLibraryService"
        };
        for (String action : serviceActions) {
            try {
                List<ResolveInfo> list = pm.queryIntentServices(new Intent(action), 0);
                if (list != null) {
                    for (ResolveInfo ri : list) {
                        if (ri.serviceInfo != null) pkgs.add(ri.serviceInfo.packageName);
                    }
                }
            } catch (Throwable ignored) {}
        }
        try {
            List<ResolveInfo> receivers = pm.queryBroadcastReceivers(new Intent(Intent.ACTION_MEDIA_BUTTON), 0);
            if (receivers != null) {
                for (ResolveInfo ri : receivers) {
                    if (ri.activityInfo != null) pkgs.add(ri.activityInfo.packageName);
                }
            }
        } catch (Throwable ignored) {}
        return pkgs;
    }

    private String getCachedAppIcon(PackageManager pm, ResolveInfo ri, String pkg) {
        String cached = appIconCache.get(pkg);
        if (cached != null) return cached;
        try {
            Drawable d = ri.loadIcon(pm);
            Bitmap bmp = drawableToBitmap(d, 72);
            if (bmp == null) return "";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 90, baos);
            if (!bmp.isRecycled()) bmp.recycle();
            String url = "data:image/png;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            appIconCache.put(pkg, url);
            return url;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Bitmap drawableToBitmap(Drawable d, int sizePx) {
        if (d == null) return null;
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), sizePx, sizePx, true);
        }
        Bitmap b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, sizePx, sizePx);
        d.draw(c);
        return b;
    }

    private void pausePackage(String pkg) {
        MediaController controller = findSession(pkg);
        if (controller != null) {
            try {
                controller.getTransportControls().pause();
            } catch (Throwable ignored) {}
        }
    }

    private MediaController findSession(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        try {
            MediaSessionManager mgr = (MediaSessionManager) appContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (mgr == null) return null;
            List<MediaController> list = mgr.getActiveSessions(new ComponentName(appContext, CockpitMediaListenerService.class));
            if (list != null) {
                for (MediaController c : list) {
                    if (pkg.equals(c.getPackageName())) return c;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean dispatchMediaKey(String cmd) {
        int code;
        switch (cmd) {
            case "toggle": code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE; break;
            case "play": code = KeyEvent.KEYCODE_MEDIA_PLAY; break;
            case "pause": code = KeyEvent.KEYCODE_MEDIA_PAUSE; break;
            case "next": code = KeyEvent.KEYCODE_MEDIA_NEXT; break;
            case "previous": code = KeyEvent.KEYCODE_MEDIA_PREVIOUS; break;
            case "seek_back": code = KeyEvent.KEYCODE_MEDIA_REWIND; break;
            case "seek_forward": code = KeyEvent.KEYCODE_MEDIA_FAST_FORWARD; break;
            default: return false;
        }
        try {
            AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Bitmap loadBitmapFromUri(String uriStr) {
        try {
            Uri uri = Uri.parse(uriStr);
            if ("content".equalsIgnoreCase(uri.getScheme()) || "file".equalsIgnoreCase(uri.getScheme())) {
                try (InputStream is = appContext.getContentResolver().openInputStream(uri)) {
                    return is != null ? android.graphics.BitmapFactory.decodeStream(is) : null;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String safeLabel(PackageManager pm, ResolveInfo ri) {
        try {
            CharSequence cs = ri.loadLabel(pm);
            return cs != null ? cs.toString().trim() : ri.activityInfo.packageName;
        } catch (Throwable ignored) {
            return ri.activityInfo.packageName;
        }
    }

    private static String trimOrEmpty(String s) {
        return s != null ? s.trim() : "";
    }
}
