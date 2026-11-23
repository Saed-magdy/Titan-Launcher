/*
 * Titan Cockpit Launcher
 * Created by Dr. Saed
 */
package com.dongfeng.shinecockpit;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Process;
import android.util.Log;

import java.util.List;

/**
 * Protects the head unit against boot-loop crashes: restarts up to 2 times within
 * 60 seconds, then falls back cleanly to the factory car launcher.
 */
public final class LauncherWatchdog {
    private static final String TAG = "TitanWatchdog";
    private static final String PREF_WATCHDOG = "ShineLauncherWatchdog";
    private static final String KEY_CRASH_COUNT = "consecutive_crash_count";
    private static final String KEY_LAST_CRASH_TIME = "last_crash_timestamp";
    private static final String KEY_WATCHDOG_ENABLED = "watchdog_enabled";
    private static final int MAX_CONSECUTIVE_RESTARTS = 2;
    private static final long CRASH_LOOP_WINDOW_MS = 60000L;

    private LauncherWatchdog() {}

    public static void install(final Context context, Handler handler) {
        final Context appCtx = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                Log.e(TAG, "FATAL CRASH DETECTED in thread " + (thread != null ? thread.getName() : "unknown"), throwable);
                handleFatalCrashAndRecovery(appCtx, "UncaughtException: " + (throwable != null ? throwable.getMessage() : "null"));
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable);
                } else {
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            }
        });

        // Reset consecutive crash counter after 40s of stable operation
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences sp = appCtx.getSharedPreferences(PREF_WATCHDOG, Context.MODE_PRIVATE);
                    int count = sp.getInt(KEY_CRASH_COUNT, 0);
                    if (count > 0) {
                        Log.i(TAG, "Launcher stable for 40s. Resetting crash count from " + count + " to 0.");
                        sp.edit().putInt(KEY_CRASH_COUNT, 0).apply();
                    }
                } catch (Exception ignored) {}
            }
        }, 40000L);
    }

    public static synchronized void handleFatalCrashAndRecovery(Context context, String reason) {
        try {
            Context appCtx = context.getApplicationContext();
            SharedPreferences sp = appCtx.getSharedPreferences(PREF_WATCHDOG, Context.MODE_PRIVATE);
            boolean enabled = sp.getBoolean(KEY_WATCHDOG_ENABLED, true);
            if (!enabled) {
                Log.w(TAG, "Watchdog auto-restart is disabled by user setting.");
                return;
            }

            long now = System.currentTimeMillis();
            long lastCrash = sp.getLong(KEY_LAST_CRASH_TIME, 0);
            int count = sp.getInt(KEY_CRASH_COUNT, 0);
            count = ((now - lastCrash) < CRASH_LOOP_WINDOW_MS) ? (count + 1) : 1;

            // Synchronous commit required here because process may exit immediately afterwards
            sp.edit()
              .putInt(KEY_CRASH_COUNT, count)
              .putLong(KEY_LAST_CRASH_TIME, now)
              .commit();

            Log.e(TAG, "Watchdog Crash: count=" + count + "/" + MAX_CONSECUTIVE_RESTARTS + ", reason=" + reason);

            if (count <= MAX_CONSECUTIVE_RESTARTS) {
                Log.w(TAG, "Watchdog: Scheduling auto-restart #" + count + "...");
                scheduleLauncherRestart(appCtx);
            } else {
                Log.e(TAG, "Watchdog: CRASH LOOP DETECTED (" + count + " crashes)! Falling back to factory launcher!");
                sp.edit().putInt(KEY_CRASH_COUNT, 0).commit();
                launchFallbackFactoryLauncher(appCtx);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in handleFatalCrashAndRecovery", e);
        }
    }

    private static void scheduleLauncherRestart(Context appCtx) {
        try {
            Intent intent = new Intent(appCtx, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                appCtx,
                9999,
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT
            );
            AlarmManager am = (AlarmManager) appCtx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.RTC, System.currentTimeMillis() + 1000L, pendingIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule restart", e);
        }
    }

    public static void launchFallbackFactoryLauncher(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            String myPkg = context.getPackageName();

            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            if (list == null || list.isEmpty()) {
                list = pm.queryIntentActivities(homeIntent, 0);
            }

            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo != null && !myPkg.equals(ri.activityInfo.packageName)) {
                        Intent target = new Intent(Intent.ACTION_MAIN);
                        target.addCategory(Intent.CATEGORY_HOME);
                        target.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        context.startActivity(target);
                        Log.i(TAG, "Watchdog: Fallback launched factory home: " + ri.activityInfo.packageName + "/" + ri.activityInfo.name);
                        return;
                    }
                }
            }

            String[] candidates = new String[] {
                "com.lecoauto",
                "com.qinggan.app.launcher",
                "com.dongfeng.launcher",
                "com.qinggan.launcher",
                "com.android.launcher3",
                "com.android.launcher"
            };
            for (String pkg : candidates) {
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    context.startActivity(launch);
                    Log.i(TAG, "Watchdog: Fallback launched candidate: " + pkg);
                    return;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Watchdog: Failed to launch fallback launcher", e);
        }
    }

    public static boolean isEnabled(Context context) {
        try {
            return context.getSharedPreferences(PREF_WATCHDOG, Context.MODE_PRIVATE)
                    .getBoolean(KEY_WATCHDOG_ENABLED, true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void setEnabled(Context context, boolean enabled) {
        try {
            context.getSharedPreferences(PREF_WATCHDOG, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_WATCHDOG_ENABLED, enabled)
                    .apply();
            Log.i(TAG, "Saved watchdog_enabled: " + enabled);
        } catch (Exception e) {
            Log.e(TAG, "Error saving watchdog_enabled", e);
        }
    }
}
