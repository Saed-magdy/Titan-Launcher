package com.dongfeng.shinecockpit;

import android.app.AlarmManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Locks local timezone and maintains a monotonic offline clock anchor so Qinggan OS / T-Box
 * does not revert the car clock to China time (Asia/Shanghai, UTC+8) when offline.
 */
public final class OfflineTimeKeeper {
    private static final String TAG = "OfflineTimeKeeper";
    private static final String PREF_HUB = "ShineHubPrefs";

    private static final String KEY_ENABLED = "offline_time_guard_enabled";
    private static final String KEY_TZ_MODE = "offline_tz_mode";
    private static final String KEY_LOCKED_TZ_ID = "offline_locked_tz_id";
    private static final String KEY_ANCHOR_UTC_MS = "offline_anchor_utc_ms";
    private static final String KEY_ANCHOR_ELAPSED_MS = "offline_anchor_elapsed_ms";
    private static final String KEY_ANCHOR_BOOT_ID = "offline_anchor_boot_id";
    private static final String KEY_ANCHOR_VERIFIED_NET = "offline_anchor_verified_net";
    private static final String KEY_LAST_KNOWN_UTC_MS = "offline_last_known_utc_ms";

    // 2024-01-01T00:00:00Z sanity lower bound for wall clock
    private static final long MIN_VALID_UTC_MS = 1704067200000L;
    private static final long NET_RECHECK_INTERVAL_MS = 300_000L;

    private static final ExecutorService sWorker = Executors.newSingleThreadExecutor();
    private static final Set<String> sAvailableTzIds = new HashSet<>(Arrays.asList(TimeZone.getAvailableIDs()));

    private static volatile long sSelfChangeSuppressUntilElapsed = 0L;
    private static volatile long sLastNetCheckElapsed = 0L;

    private OfflineTimeKeeper() {}

    public static boolean isEnabled(Context context) {
        if (context == null) return true;
        return context.getApplicationContext()
                .getSharedPreferences(PREF_HUB, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(final Context context, final boolean enabled, final Runnable onChangedCallback) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();
        appCtx.getSharedPreferences(PREF_HUB, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
        if (!enabled) {
            sWorker.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        sSelfChangeSuppressUntilElapsed = SystemClock.elapsedRealtime() + 2500L;
                        Settings.Global.putInt(appCtx.getContentResolver(), Settings.Global.AUTO_TIME_ZONE, 1);
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to restore AUTO_TIME_ZONE=1: " + t.getMessage());
                    }
                    if (onChangedCallback != null) onChangedCallback.run();
                }
            });
        } else {
            enforceTimeZoneAndClock(appCtx, "enabled", onChangedCallback);
        }
    }

    public static String getTimeZoneMode(Context context) {
        if (context == null) return "auto";
        String mode = context.getApplicationContext()
                .getSharedPreferences(PREF_HUB, Context.MODE_PRIVATE)
                .getString(KEY_TZ_MODE, "auto");
        return normalizeTzMode(mode);
    }

    public static void setTimeZoneMode(final Context context, final String mode, final Runnable onChangedCallback) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();
        final String norm = normalizeTzMode(mode);
        appCtx.getSharedPreferences(PREF_HUB, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_TZ_MODE, norm)
                .apply();
        enforceTimeZoneAndClock(appCtx, "tz_mode_" + norm, onChangedCallback);
    }

    public static void onSystemBroadcast(Context context, String action, Runnable onChangedCallback) {
        if (context == null) return;
        long nowElapsed = SystemClock.elapsedRealtime();
        if (("android.intent.action.TIME_SET".equals(action)
                || "android.intent.action.TIMEZONE_CHANGED".equals(action))
                && nowElapsed < sSelfChangeSuppressUntilElapsed) {
            return;
        }
        enforceTimeZoneAndClock(context, "broadcast_" + action, onChangedCallback);
    }

    public static void enforceTimeZoneAndClock(final Context context, final String reason, final Runnable onChangedCallback) {
        if (context == null) return;
        final Context appCtx = context.getApplicationContext();
        sWorker.execute(new Runnable() {
            @Override
            public void run() {
                enforceSyncInternal(appCtx, reason, onChangedCallback);
            }
        });
    }

    public static String getClockSyncJson(Context context) {
        JSONObject obj = new JSONObject();
        try {
            Context appCtx = context.getApplicationContext();
            SharedPreferences prefs = appCtx.getSharedPreferences(PREF_HUB, Context.MODE_PRIVATE);
            boolean enabled = prefs.getBoolean(KEY_ENABLED, true);
            String tzMode = normalizeTzMode(prefs.getString(KEY_TZ_MODE, "auto"));
            String tzId;
            if (enabled) {
                tzId = resolveTargetTimeZoneId(prefs, tzMode, false);
            } else {
                tzId = TimeZone.getDefault().getID();
            }
            TimeZone tz = TimeZone.getTimeZone(tzId);
            long utcMs = System.currentTimeMillis();
            int offsetMs = tz.getOffset(utcMs);
            boolean netVerified = prefs.getBoolean(KEY_ANCHOR_VERIFIED_NET, false);

            obj.put("guardEnabled", enabled);
            obj.put("tzMode", tzMode);
            obj.put("tzId", tz.getID());
            obj.put("tzOffsetMs", offsetMs);
            obj.put("utcMs", utcMs);
            obj.put("netVerified", netVerified);
        } catch (Throwable t) {
            Log.e(TAG, "getClockSyncJson error", t);
        }
        return obj.toString();
    }

    private static void enforceSyncInternal(Context appCtx, String reason, Runnable onChangedCallback) {
        boolean changed = false;
        try {
            SharedPreferences prefs = appCtx.getSharedPreferences(PREF_HUB, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_ENABLED, true)) {
                return;
            }

            String tzMode = normalizeTzMode(prefs.getString(KEY_TZ_MODE, "auto"));
            // Resolve target timezone BEFORE disabling AUTO_TIME_ZONE so auto mode can capture non-China zone.
            String targetTz = resolveTargetTimeZoneId(prefs, tzMode, true);

            try {
                Settings.Global.putInt(appCtx.getContentResolver(), Settings.Global.AUTO_TIME, 1);
                Settings.Global.putInt(appCtx.getContentResolver(), Settings.Global.AUTO_TIME_ZONE, 0);
            } catch (Throwable t) {
                Log.w(TAG, "Could not update AUTO_TIME/AUTO_TIME_ZONE: " + t.getMessage());
            }

            String currentTz = TimeZone.getDefault().getID();
            if (!targetTz.equals(currentTz)) {
                applySystemTimeZone(appCtx, targetTz);
                changed = true;
                Log.i(TAG, "Locked timezone [" + reason + "]: " + currentTz + " -> " + targetTz);
            }

            String bootId = getBootId(appCtx);
            long nowElapsed = SystemClock.elapsedRealtime();
            long nowUtc = System.currentTimeMillis();

            String anchorBootId = prefs.getString(KEY_ANCHOR_BOOT_ID, "");
            long anchorUtc = prefs.getLong(KEY_ANCHOR_UTC_MS, 0L);
            long anchorElapsed = prefs.getLong(KEY_ANCHOR_ELAPSED_MS, 0L);
            boolean netVerified = prefs.getBoolean(KEY_ANCHOR_VERIFIED_NET, false);
            long lastKnownUtc = prefs.getLong(KEY_LAST_KNOWN_UTC_MS, 0L);

            boolean online = isInternetConnected(appCtx);

            if (bootId.equals(anchorBootId) && anchorUtc > MIN_VALID_UTC_MS && nowElapsed >= anchorElapsed) {
                long expectedUtc = anchorUtc + (nowElapsed - anchorElapsed);
                if (!online && Math.abs(nowUtc - expectedUtc) > 120_000L) {
                    if (applySystemTime(appCtx, expectedUtc)) {
                        Log.i(TAG, "Restored monotonic offline time [" + reason + "]: delta=" + ((nowUtc - expectedUtc) / 1000L) + "s");
                        nowUtc = expectedUtc;
                        changed = true;
                    }
                }
            } else {
                // Cold boot or missing anchor
                if (lastKnownUtc > MIN_VALID_UTC_MS && nowUtc < (lastKnownUtc - 600_000L)) {
                    long restoredUtc = lastKnownUtc + Math.min(nowElapsed, 3_600_000L);
                    if (applySystemTime(appCtx, restoredUtc)) {
                        Log.i(TAG, "Restored cold-boot clock from compile/past reset [" + reason + "] -> " + restoredUtc);
                        nowUtc = restoredUtc;
                        changed = true;
                    }
                }
                if (nowUtc > MIN_VALID_UTC_MS) {
                    prefs.edit()
                            .putString(KEY_ANCHOR_BOOT_ID, bootId)
                            .putLong(KEY_ANCHOR_UTC_MS, nowUtc)
                            .putLong(KEY_ANCHOR_ELAPSED_MS, nowElapsed)
                            .putBoolean(KEY_ANCHOR_VERIFIED_NET, false)
                            .apply();
                    anchorBootId = bootId;
                    anchorUtc = nowUtc;
                    anchorElapsed = nowElapsed;
                    netVerified = false;
                }
            }

            if (nowUtc > MIN_VALID_UTC_MS) {
                long updatedLastKnown = Math.max(lastKnownUtc, nowUtc);
                if (updatedLastKnown != lastKnownUtc) {
                    prefs.edit().putLong(KEY_LAST_KNOWN_UTC_MS, updatedLastKnown).apply();
                    lastKnownUtc = updatedLastKnown;
                }
            }

            boolean needNetCheck = online && (!netVerified
                    || !bootId.equals(anchorBootId)
                    || sLastNetCheckElapsed == 0L
                    || (nowElapsed - sLastNetCheckElapsed) > NET_RECHECK_INTERVAL_MS);
            if (needNetCheck) {
                sLastNetCheckElapsed = nowElapsed;
                long netUtc = fetchNetworkUtcMillis();
                if (netUtc > MIN_VALID_UTC_MS) {
                    long verifiedElapsed = SystemClock.elapsedRealtime();
                    prefs.edit()
                            .putString(KEY_ANCHOR_BOOT_ID, bootId)
                            .putLong(KEY_ANCHOR_UTC_MS, netUtc)
                            .putLong(KEY_ANCHOR_ELAPSED_MS, verifiedElapsed)
                            .putBoolean(KEY_ANCHOR_VERIFIED_NET, true)
                            .putLong(KEY_LAST_KNOWN_UTC_MS, Math.max(lastKnownUtc, netUtc))
                            .apply();
                    if (Math.abs(System.currentTimeMillis() - netUtc) > 45_000L) {
                        if (applySystemTime(appCtx, netUtc)) {
                            Log.i(TAG, "Synchronized wall clock with HTTP network UTC [" + reason + "]");
                        }
                    }
                    changed = true;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "enforceSyncInternal failed [" + reason + "]: " + t.getMessage(), t);
        } finally {
            if (onChangedCallback != null && (changed || reason.startsWith("tz_mode_") || "enabled".equals(reason) || "onCreate".equals(reason))) {
                onChangedCallback.run();
            }
        }
    }

    private static String normalizeTzMode(String mode) {
        if ("utc2".equalsIgnoreCase(mode)) return "utc2";
        if ("utc3".equalsIgnoreCase(mode)) return "utc3";
        if ("utc4".equalsIgnoreCase(mode)) return "utc4";
        return "auto";
    }

    public static boolean isChinaDefaultTimeZone(String tzId) {
        if (tzId == null) return true;
        String clean = tzId.trim();
        if (clean.isEmpty()) return true;
        String lower = clean.toLowerCase(Locale.US);
        return "asia/shanghai".equals(lower)
                || "asia/chongqing".equals(lower)
                || "asia/harbin".equals(lower)
                || "asia/urumqi".equals(lower)
                || "asia/kashgar".equals(lower)
                || "asia/hong_kong".equals(lower)
                || "asia/macau".equals(lower)
                || "asia/taipei".equals(lower)
                || "prc".equals(lower)
                || "ctt".equals(lower)
                || "etc/gmt-8".equals(lower)
                || "gmt+08:00".equals(lower)
                || "gmt+8".equals(lower)
                || "utc+08:00".equals(lower)
                || "utc+8".equals(lower);
    }

    private static String firstAvailableTz(String... candidates) {
        for (String c : candidates) {
            if (c != null && sAvailableTzIds.contains(c)) {
                return c;
            }
        }
        return "UTC";
    }

    private static String resolveTargetTimeZoneId(SharedPreferences prefs, String tzMode, boolean persistCapturedAuto) {
        if ("utc2".equals(tzMode)) {
            return firstAvailableTz("Etc/GMT-2", "Africa/Tripoli", "Africa/Cairo");
        }
        if ("utc3".equals(tzMode)) {
            return firstAvailableTz("Etc/GMT-3", "Asia/Riyadh", "Asia/Baghdad");
        }
        if ("utc4".equals(tzMode)) {
            return firstAvailableTz("Etc/GMT-4", "Asia/Dubai", "Asia/Muscat");
        }

        // "auto": capture any valid non-China system timezone, else reuse saved, else Egypt (Africa/Cairo) / UTC+2
        String sysTz = TimeZone.getDefault().getID();
        if (sysTz != null && sAvailableTzIds.contains(sysTz) && !isChinaDefaultTimeZone(sysTz)) {
            if (persistCapturedAuto) {
                prefs.edit().putString(KEY_LOCKED_TZ_ID, sysTz).apply();
            }
            return sysTz;
        }

        String saved = prefs.getString(KEY_LOCKED_TZ_ID, "");
        if (saved != null && !saved.isEmpty() && sAvailableTzIds.contains(saved) && !isChinaDefaultTimeZone(saved)) {
            return saved;
        }

        String fallback = firstAvailableTz("Africa/Cairo", "Etc/GMT-2");
        if (persistCapturedAuto) {
            prefs.edit().putString(KEY_LOCKED_TZ_ID, fallback).apply();
        }
        return fallback;
    }

    private static void applySystemTimeZone(Context appCtx, String tzId) {
        sSelfChangeSuppressUntilElapsed = SystemClock.elapsedRealtime() + 2500L;
        try {
            AlarmManager am = (AlarmManager) appCtx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.setTimeZone(tzId);
            }
        } catch (Throwable t) {
            Log.w(TAG, "AlarmManager.setTimeZone failed for " + tzId + ": " + t.getMessage());
        }
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(tzId));
        } catch (Throwable ignored) {}
    }

    private static boolean applySystemTime(Context appCtx, long utcMs) {
        if (utcMs <= MIN_VALID_UTC_MS) return false;
        sSelfChangeSuppressUntilElapsed = SystemClock.elapsedRealtime() + 2500L;
        try {
            AlarmManager am = (AlarmManager) appCtx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.setTime(utcMs);
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "AlarmManager.setTime failed: " + t.getMessage());
        }
        try {
            return SystemClock.setCurrentTimeMillis(utcMs);
        } catch (Throwable t) {
            Log.w(TAG, "SystemClock.setCurrentTimeMillis failed: " + t.getMessage());
        }
        return false;
    }

    private static String getBootId(Context appCtx) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"));
            String line = br.readLine();
            if (line != null && !line.trim().isEmpty()) {
                return line.trim();
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable ignored) {}
            }
        }
        try {
            return "bc_" + Settings.Global.getInt(appCtx.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        } catch (Throwable ignored) {}
        return "boot_unknown";
    }

    private static boolean isInternetConnected(Context appCtx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo active = cm.getActiveNetworkInfo();
            return active != null && active.isConnected();
        } catch (Throwable ignored) {}
        return false;
    }

    private static long fetchNetworkUtcMillis() {
        String[] urls = new String[] {
                "http://connectivitycheck.gstatic.com/generate_204",
                "https://www.google.com/generate_204"
        };
        for (String urlStr : urls) {
            HttpURLConnection conn = null;
            try {
                long t0 = SystemClock.elapsedRealtime();
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(3500);
                conn.setReadTimeout(3500);
                conn.setUseCaches(false);
                conn.connect();
                long serverDate = conn.getDate();
                long rtt = Math.max(0L, SystemClock.elapsedRealtime() - t0);
                if (serverDate > MIN_VALID_UTC_MS) {
                    return serverDate + (rtt / 2L);
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        }
        return 0L;
    }
}
