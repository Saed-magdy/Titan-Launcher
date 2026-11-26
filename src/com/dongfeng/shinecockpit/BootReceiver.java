package com.dongfeng.shinecockpit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "DongfengBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "Boot/Time event received: " + action);
        OfflineTimeKeeper.onSystemBroadcast(context.getApplicationContext(), action, null);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            SharedPreferences prefs = context.getSharedPreferences("ShineHubPrefs", Context.MODE_PRIVATE);
            boolean autoBoot = prefs.getBoolean("auto_boot_enabled", true);
            if (autoBoot) {
                Intent launchIntent = new Intent(context, MainActivity.class);
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(launchIntent);
                Log.i(TAG, "MainActivity successfully started on boot");
            }
        }
    }
}
