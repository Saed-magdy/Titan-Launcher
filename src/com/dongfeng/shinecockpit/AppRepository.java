/*
 * Titan Cockpit Launcher
 * Created by Dr. Saed
 */
package com.dongfeng.shinecockpit;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Queries, caches, and launches installed applications and OEM head-unit
 * shortcuts (Camera 360, Navigation, Media, Bluetooth, Settings).
 */
public final class AppRepository {
    private static final String TAG = "TitanAppRepo";
    private volatile String mCachedAppsJson = null;

    public boolean launchApp(Context context, String keyOrPkg, Runnable openCamera360Action) {
        if (keyOrPkg == null || keyOrPkg.isEmpty()) return false;
        try {
            if ("pip".equalsIgnoreCase(keyOrPkg) || "com.titan.pip".equalsIgnoreCase(keyOrPkg)) {
                if (tryStartPackage(context, "com.titan.pip")) return true;
            }
            if ("cam360".equalsIgnoreCase(keyOrPkg) || "org.codeaurora.snapcam".equalsIgnoreCase(keyOrPkg)
                    || "com.dongfeng.auto360".equalsIgnoreCase(keyOrPkg) || "camera".equalsIgnoreCase(keyOrPkg)) {
                if (openCamera360Action != null) openCamera360Action.run();
                if (tryStartPackage(context, "com.dongfeng.auto360")) return true;
                tryStartPackage(context, "com.qinggan.app.avm");
                return true;
            }
            if ("wifi".equalsIgnoreCase(keyOrPkg)) {
                return openWifiSettings(context);
            }
            if ("bluetooth".equalsIgnoreCase(keyOrPkg) || "bt".equalsIgnoreCase(keyOrPkg)) {
                if (tryStartAny(context, new String[] {
                        "com.qinggan.bluetooth", "com.qinggan.app.bluetooth",
                        "com.dongfeng.bluetooth", "com.qinggan.app.btmusic" })) return true;
                if (tryStartPackageByKeyword(context, new String[] { "bluetooth", "bt" })) return true;
                if (tryStartAction(context, Settings.ACTION_BLUETOOTH_SETTINGS)) return true;
                return tryStartAction(context, Settings.ACTION_SETTINGS);
            }
            if ("network".equalsIgnoreCase(keyOrPkg) || "cell".equalsIgnoreCase(keyOrPkg)
                    || "cellular".equalsIgnoreCase(keyOrPkg) || "data".equalsIgnoreCase(keyOrPkg)) {
                if (tryStartComponent(context, "com.android.phone", "com.android.phone.MobileNetworkSettings")) return true;
                if (tryStartAction(context, Settings.ACTION_WIRELESS_SETTINGS)) return true;
                if (tryStartAction(context, Settings.ACTION_DATA_ROAMING_SETTINGS)) return true;
                if (tryStartAction(context, Settings.ACTION_NETWORK_OPERATOR_SETTINGS)) return true;
                return tryStartAction(context, Settings.ACTION_SETTINGS);
            }
            if ("maps".equalsIgnoreCase(keyOrPkg)) {
                if (tryStartAny(context, new String[] {
                        "com.google.android.apps.maps", "com.waze",
                        "com.autonavi.amapauto", "com.baidu.BaiduMap", "com.here.app.maps" })) return true;
                if (tryStartPackageByKeyword(context, new String[] { "map", "navi", "waze", "gps" })) return true;
                Intent geo = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="));
                geo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(geo);
                return true;
            }
            if ("radar".equalsIgnoreCase(keyOrPkg)) {
                if (tryStartAny(context, new String[] {
                        "com.vialsoft.radarbot_free", "com.vialsoft.radarbot", "com.camonroad.radar" })) return true;
                return tryStartPackageByKeyword(context, new String[] { "radar", "speedcam" });
            }
            if ("shinewave".equalsIgnoreCase(keyOrPkg) || "shine_wave".equalsIgnoreCase(keyOrPkg)
                    || "com.shine.wave".equalsIgnoreCase(keyOrPkg)) {
                if (tryStartPackage(context, "com.shine.wave")) return true;
            }
            if ("music".equalsIgnoreCase(keyOrPkg)) {
                if (tryStartAny(context, new String[] {
                        "com.shine.wave", "com.qinggan.app.music", "com.qinggan.music",
                        "com.android.music", "com.maxmpz.audioplayer", "com.spotify.music",
                        "com.google.android.music", "com.sec.android.app.music", "com.miui.player" })) return true;
                if (tryStartPackageByKeyword(context, new String[] { "music", "audio", "player", "radio" })) return true;
                Intent intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            }
            if ("video".equalsIgnoreCase(keyOrPkg)) {
                if (tryStartAny(context, new String[] {
                        "com.qinggan.app.video", "com.qinggan.video", "org.videolan.vlc",
                        "com.mxtech.videoplayer.ad", "com.mxtech.videoplayer.pro",
                        "com.android.gallery3d", "com.google.android.videos" })) return true;
                if (tryStartPackageByKeyword(context, new String[] { "video", "vlc", "gallery", "movie" })) return true;
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_APP_GALLERY);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            }
            if ("radio".equalsIgnoreCase(keyOrPkg)) {
                if (tryStartAny(context, new String[] {
                        "com.qinggan.app.radio", "com.qinggan.radio", "com.android.fmradio" })) return true;
                return tryStartPackageByKeyword(context, new String[] { "radio", "fm" });
            }
            if ("bt_music".equalsIgnoreCase(keyOrPkg)) {
                return tryStartAny(context, new String[] {
                        "com.qinggan.app.btmusic", "com.qinggan.bluetooth", "com.qinggan.app.bluetooth" });
            }
            if ("youtube".equalsIgnoreCase(keyOrPkg)) {
                if (tryStartAny(context, new String[] {
                        "com.google.android.youtube", "com.google.android.youtube.tv", "org.schabi.newpipe" })) return true;
                return tryStartPackageByKeyword(context, new String[] { "youtube", "yt" });
            }
            if ("store".equalsIgnoreCase(keyOrPkg)) {
                if (tryStartAny(context, new String[] { "com.android.vending", "com.google.android.finsky" })) return true;
                return tryStartPackageByKeyword(context, new String[] { "vending", "store", "market" });
            }
            if ("settings".equalsIgnoreCase(keyOrPkg)) {
                if (tryStartAction(context, Settings.ACTION_SETTINGS)) return true;
                return tryStartPackage(context, "com.android.settings");
            }
            if ("voice".equalsIgnoreCase(keyOrPkg)) {
                if (tryStartAny(context, new String[] { "com.qinggan.app.voice", "com.google.android.googlequicksearchbox" })) return true;
                return tryStartAction(context, Intent.ACTION_VOICE_COMMAND);
            }

            return tryStartPackage(context, keyOrPkg);
        } catch (Exception e) {
            Log.e(TAG, "Error in launchApp: " + keyOrPkg, e);
            return false;
        }
    }

    public String getInstalledAppsJson(Context context) {
        if (mCachedAppsJson != null && !mCachedAppsJson.isEmpty()) {
            return mCachedAppsJson;
        }
        JSONArray arr = new JSONArray();
        try {
            final PackageManager pm = context.getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(mainIntent, 0);
            if (list != null) {
                Collections.sort(list, new Comparator<ResolveInfo>() {
                    @Override
                    public int compare(ResolveInfo a, ResolveInfo b) {
                        String la = a.loadLabel(pm) != null ? a.loadLabel(pm).toString() : "";
                        String lb = b.loadLabel(pm) != null ? b.loadLabel(pm).toString() : "";
                        return la.compareToIgnoreCase(lb);
                    }
                });
                String myPkg = context.getPackageName();
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || pkg.equals(myPkg)) continue;
                    if ("org.codeaurora.snapcam".equalsIgnoreCase(pkg)
                            || "com.android.camera".equalsIgnoreCase(pkg)
                            || "com.android.camera2".equalsIgnoreCase(pkg)) {
                        continue;
                    }
                    CharSequence labelCs = ri.loadLabel(pm);
                    String label = labelCs != null ? labelCs.toString() : pkg;
                    JSONObject obj = new JSONObject();
                    obj.put("name", label);
                    obj.put("pkg", pkg);
                    boolean isSystem = false;
                    if (ri.activityInfo.applicationInfo != null) {
                        int flags = ri.activityInfo.applicationInfo.flags;
                        isSystem = ((flags & ApplicationInfo.FLAG_SYSTEM) != 0)
                                || ((flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
                    }
                    obj.put("isSystem", isSystem);
                    try {
                        String b64 = drawableToBase64(ri.loadIcon(pm));
                        if (b64 != null) obj.put("icon", b64);
                    } catch (Exception ignored) {}
                    arr.put(obj);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error querying installed apps", e);
        }
        mCachedAppsJson = arr.toString();
        return mCachedAppsJson;
    }

    private static boolean openWifiSettings(Context context) {
        if (tryStartComponent(context, "com.android.settings", "com.android.settings.Settings$WifiSettingsActivity")) {
            return true;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            intent.setPackage("com.android.settings");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return tryStartAction(context, Settings.ACTION_SETTINGS);
        }
    }

    private static boolean tryStartAny(Context context, String[] pkgs) {
        for (String p : pkgs) {
            if (tryStartPackage(context, p)) return true;
        }
        return false;
    }

    private static boolean tryStartAction(Context context, String action) {
        try {
            Intent intent = new Intent(action);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean tryStartComponent(Context context, String pkg, String cls) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(pkg, cls));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean tryStartPackage(Context context, String pkg) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to start package: " + pkg, e);
        }
        return false;
    }

    private static boolean tryStartPackageByKeyword(Context context, String[] keywords) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
            if (apps != null) {
                String myPkg = context.getPackageName().toLowerCase();
                for (ResolveInfo ri : apps) {
                    if (ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
                    String pkg = ri.activityInfo.packageName.toLowerCase();
                    if (pkg.equals(myPkg)) continue;
                    for (String kw : keywords) {
                        if (pkg.contains(kw)) {
                            return tryStartPackage(context, ri.activityInfo.packageName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Keyword search failed", e);
        }
        return false;
    }

    private static String drawableToBase64(Drawable drawable) {
        if (drawable == null) return null;
        try {
            int w = drawable.getIntrinsicWidth() > 0 ? drawable.getIntrinsicWidth() : 72;
            int h = drawable.getIntrinsicHeight() > 0 ? drawable.getIntrinsicHeight() : 72;
            Bitmap bitmap;
            if (drawable instanceof BitmapDrawable) {
                bitmap = ((BitmapDrawable) drawable).getBitmap();
            } else {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);
            }
            if (bitmap != null) {
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 72, 72, true);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.PNG, 85, baos);
                return "data:image/png;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
