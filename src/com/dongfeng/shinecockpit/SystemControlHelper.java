/*
 * Titan Cockpit Launcher
 * Created by Dr. Saed
 */
package com.dongfeng.shinecockpit;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles system connectivity status/toggles (Wi-Fi, Bluetooth, Cellular),
 * RAM cleanup, default home selection, and notification-listener rebinding.
 */
public final class SystemControlHelper {
    private static final String TAG = "TitanSystemCtrl";

    private SystemControlHelper() {}

    public static String getConnectivityStatusJson(Context context) {
        JSONObject res = new JSONObject();
        try {
            boolean bt = isBluetoothConnected(context);
            res.put("bluetooth", bt);

            boolean wifi = false;
            boolean cell = false;
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    NetworkInfo active = cm.getActiveNetworkInfo();
                    if (active != null && active.isConnected()) {
                        if (active.getType() == ConnectivityManager.TYPE_WIFI) {
                            wifi = true;
                        } else {
                            cell = true;
                        }
                    }
                }
            } catch (Throwable ignored) {}
            res.put("wifi", wifi);
            res.put("cellular", cell);
        } catch (Exception e) {
            Log.e(TAG, "Error in getConnectivityStatusJson", e);
        }
        return res.toString();
    }

    private static boolean isBluetoothConnected(Context context) {
        try {
            BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
            if (bta == null || !bta.isEnabled()) return false;

            try {
                java.lang.reflect.Method mConn = BluetoothAdapter.class.getMethod("getConnectionState");
                int state = (Integer) mConn.invoke(bta);
                if (state == 2) return true;
            } catch (Throwable ignored) {}

            int[] profiles = new int[] { 2, 1, 11, 16, 4, 17 };
            for (int p : profiles) {
                try {
                    if (bta.getProfileConnectionState(p) == BluetoothProfile.STATE_CONNECTED) {
                        return true;
                    }
                } catch (Throwable ignored) {}
            }

            try {
                Set<BluetoothDevice> paired = bta.getBondedDevices();
                if (paired != null && !paired.isEmpty()) {
                    java.lang.reflect.Method isConnMethod = BluetoothDevice.class.getMethod("isConnected");
                    for (BluetoothDevice dev : paired) {
                        if (Boolean.TRUE.equals(isConnMethod.invoke(dev))) {
                            return true;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            try {
                SharedPreferences sp = context.getSharedPreferences("shine_wave_integrated_prefs", Context.MODE_PRIVATE);
                String src = sp.getString("selected_source", "");
                if ("BLUETOOTH".equalsIgnoreCase(src) || "BT".equalsIgnoreCase(src)) {
                    return true;
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return false;
    }

    public static void openConnectivitySettings(Context context, String type) {
        try {
            Intent intent;
            if ("bluetooth".equalsIgnoreCase(type)) {
                intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
            } else if ("wifi".equalsIgnoreCase(type)) {
                try {
                    Intent wifiIntent = new Intent();
                    wifiIntent.setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings$WifiSettingsActivity"));
                    wifiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(wifiIntent);
                    return;
                } catch (Exception ignored) {}
                intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                intent.setPackage("com.android.settings");
            } else {
                try {
                    Intent mobIntent = new Intent();
                    mobIntent.setComponent(new ComponentName("com.android.phone", "com.android.phone.MobileNetworkSettings"));
                    mobIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(mobIntent);
                    return;
                } catch (Exception ignored) {}
                intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open connectivity settings for: " + type, e);
        }
    }

    public static void setWifiEnabled(Context context, boolean enabled) {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) wm.setWifiEnabled(enabled);
        } catch (Exception e) {
            Log.e(TAG, "setWifiEnabled error", e);
        }
    }

    public static void setBluetoothEnabled(boolean enabled) {
        try {
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            if (ba != null) {
                if (enabled) ba.enable();
                else ba.disable();
            }
        } catch (Exception e) {
            Log.e(TAG, "setBluetoothEnabled error", e);
        }
    }

    public static String cleanMemory(Context context) {
        JSONObject res = new JSONObject();
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo miBefore = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(miBefore);
            long availBefore = miBefore.availMem;

            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(0);
            // Apps the driver may be relying on right now: never close these.
            Set<String> keep = findAppsToKeep(context, pm);
            int killedCount = 0;
            if (packages != null) {
                for (ApplicationInfo appInfo : packages) {
                    String pkg = appInfo.packageName;
                    if (pkg == null || keep.contains(pkg)) continue;
                    // Built-in system and car apps (Bluetooth phone, radio, CAN service, keyboard...)
                    // are left alone; only apps the owner installed are closed.
                    if ((appInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                    if (pkg.startsWith("com.qinggan.")) continue;
                    try {
                        am.killBackgroundProcesses(pkg);
                        killedCount++;
                    } catch (Exception ignored) {}
                }
            }

            System.gc();

            ActivityManager.MemoryInfo miAfter = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(miAfter);
            // The REAL amount freed. It can be 0 when nothing was running; the screen then
            // says "apps closed" without a number instead of showing a made-up one.
            long diffMb = Math.max(0L, (miAfter.availMem - availBefore) / (1024L * 1024L));
            res.put("ok", true);
            res.put("freedMb", diffMb);
            res.put("availMb", miAfter.availMem / (1024L * 1024L));
            res.put("totalMb", miAfter.totalMem / (1024L * 1024L));
            res.put("killedCount", killedCount);
        } catch (Exception e) {
            Log.e(TAG, "Error in cleanMemory", e);
            try {
                res.put("ok", false);
            } catch (Exception ignored) {}
        }
        return res.toString();
    }

    /**
     * Lists the apps the memory cleanup must not close, because the driver may be using them:
     * the launcher itself, the on-screen keyboard, every music / radio / podcast app,
     * and every navigation (maps) app.
     */
    private static Set<String> findAppsToKeep(Context context, PackageManager pm) {
        Set<String> keep = new HashSet<>();
        keep.add(context.getPackageName());
        try {
            String ime = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null && ime.contains("/")) keep.add(ime.substring(0, ime.indexOf('/')));
        } catch (Exception ignored) {}
        try {
            // Music apps that offer their library to car screens (Spotify, Anghami, YouTube Music...).
            for (ResolveInfo ri : pm.queryIntentServices(new Intent("android.media.browse.MediaBrowserService"), 0)) {
                if (ri.serviceInfo != null) keep.add(ri.serviceInfo.packageName);
            }
            // Apps that can play an audio file.
            Intent audio = new Intent(Intent.ACTION_VIEW);
            audio.setDataAndType(Uri.parse("file:///sdcard/a.mp3"), "audio/*");
            for (ResolveInfo ri : pm.queryIntentActivities(audio, 0)) {
                if (ri.activityInfo != null) keep.add(ri.activityInfo.packageName);
            }
            // Navigation apps (Google Maps, Waze...): they open "geo:" map links.
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")), 0)) {
                if (ri.activityInfo != null) keep.add(ri.activityInfo.packageName);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not list media/navigation apps to keep", e);
        }
        return keep;
    }

    public static void openDefaultLauncherSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(homeIntent);
            } catch (Exception e2) {
                try {
                    Intent s = new Intent(Settings.ACTION_SETTINGS);
                    s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(s);
                } catch (Exception ignored) {}
            }
        }
    }

    public static void ensureNotificationListenerAccess(Context context) {
        try {
            String pkgName = context.getPackageName();
            String serviceName = pkgName + "/" + CockpitMediaListenerService.class.getName();
            String listeners = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
            if (listeners == null || !listeners.contains(serviceName)) {
                String newListeners = (listeners == null || listeners.trim().isEmpty()) ? serviceName : (listeners + ":" + serviceName);
                Settings.Secure.putString(context.getContentResolver(), "enabled_notification_listeners", newListeners);
                Log.i(TAG, "Auto-granted notification listener permission to " + serviceName);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    NotificationListenerService.requestRebind(new ComponentName(context, CockpitMediaListenerService.class));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not auto-grant notification listener: " + t.getMessage());
        }
    }
}
