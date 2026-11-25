package com.dongfeng.shinecockpit.shinewave.radio;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.dongfeng.shinecockpit.shinewave.core.QingganAudioPolicyBridge;

/**
 * Coordinates Qinggan AudioPolicyManager radio stream routing (STREAM_RADIO = 21 / type = 3)
 * through the shared QingganAudioPolicyBridge singleton so initialization callbacks are never
 * lost across competing AudioPolicyManager.getInstance() listeners.
 */
public final class RadioAudioPolicyRouter {
    public interface StatusCallback {
        void onStatus(String message);
    }

    private final Context appContext;
    private final StatusCallback statusCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean policyRequested = false;

    public RadioAudioPolicyRouter(Context context, StatusCallback statusCallback) {
        this.appContext = context.getApplicationContext();
        this.statusCallback = statusCallback;
    }

    public synchronized boolean requestRadioPolicy() {
        QingganAudioPolicyBridge bridge = QingganAudioPolicyBridge.getInstance(appContext);
        boolean accepted = bridge.routeToRadio();
        policyRequested = true;
        if (accepted) {
            notifyStatus("تم توجيه صوت الراديو بنجاح");
        }
        return accepted;
    }

    public synchronized void abandonRadioPolicy() {
        if (!policyRequested) return;
        policyRequested = false;
        QingganAudioPolicyBridge.getInstance(appContext).abandonCurrentPolicy();
        notifyStatus("تم تحرير مسار صوت الراديو");
    }

    private void notifyStatus(final String msg) {
        if (statusCallback != null) {
            mainHandler.post(new Runnable() {
                @Override public void run() { statusCallback.onStatus(msg); }
            });
        }
    }
}
