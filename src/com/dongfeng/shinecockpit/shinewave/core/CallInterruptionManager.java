package com.dongfeng.shinecockpit.shinewave.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * Handles automatic playback pause during incoming/active phone calls and auto-resume once idle.
 */
public final class CallInterruptionManager {
    private static final String TAG = "SWCallInterrupt";

    public interface Callback {
        void onCallStarted();
        void onCallEnded();
    }

    private final Context appContext;
    private final Callback callback;
    private boolean registered = false;
    private volatile boolean callInProgress = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
            if (state == null) return;

            if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)
                    || TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
                if (!callInProgress) {
                    callInProgress = true;
                    Log.i(TAG, "Phone call started; requesting playback pause");
                    callback.onCallStarted();
                }
            } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
                if (callInProgress) {
                    callInProgress = false;
                    Log.i(TAG, "Phone call ended; checking auto-resume");
                    callback.onCallEnded();
                }
            }
        }
    };

    public CallInterruptionManager(Context context, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.callback = callback;
    }

    public void start() {
        if (registered) return;
        try {
            IntentFilter filter = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
            appContext.registerReceiver(receiver, filter);
            registered = true;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register call receiver: " + t.getMessage());
        }
    }

    public void stop() {
        if (!registered) return;
        try {
            appContext.unregisterReceiver(receiver);
        } catch (Throwable ignored) {}
        registered = false;
        callInProgress = false;
    }

    public boolean isCallInProgress() {
        return callInProgress;
    }
}
