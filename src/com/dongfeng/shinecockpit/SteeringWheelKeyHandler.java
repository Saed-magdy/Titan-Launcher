/*
 * Titan Cockpit Launcher
 * Created by Dr. Saed
 */
package com.dongfeng.shinecockpit;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import com.dongfeng.shinecockpit.shinewave.core.ShineWaveEngine;

/**
 * Unifies steering wheel control (SWC) handling across both Qinggan OEM
 * KeyManager callbacks and standard Android KeyEvent dispatches.
 */
public final class SteeringWheelKeyHandler {
    private static final String TAG = "TitanSWC";

    private static final long SWC_LONG_PRESS_DELAY_MS = 650L;
    private static final long SWC_SEEK_INTERVAL_MS = 450L;
    private static final long SWC_SEEK_STEP_MS = 10000L;
    private static final long CENTER_KEY_DEBOUNCE_MS = 350L;

    // Dongfeng Shine CAN SWC mapping: Next/Forward = 4 (long: 14), Before/Backward = 3 (long: 13)
    private static final int QG_KEY_NEXT = 4;
    private static final int QG_KEY_PREVIOUS = 3;
    private static final int QG_KEY_PLAY_PAUSE = 6;
    private static final int QG_KEY_MEDIA_OK = 145;
    private static final int QG_LONG_NEXT_KEY = 14;
    private static final int QG_LONG_PREVIOUS_KEY = 13;

    public interface HostCallback {
        ShineWaveEngine getShineWaveEngine();
        void evaluateJs(String script);
    }

    private final Context mContext;
    private final HostCallback mCallback;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private Object mQingganKeyManager = null;
    private boolean mQingganKeyRegistered = false;
    private long mLastCenterKeyTime = 0L;

    private Runnable mLongPressSeekRunnable = null;
    private Runnable mContinuousSeekRunnable = null;
    private int mPressedSwcKey = 0;
    private boolean mIsLongPressSeeking = false;
    private long mSwcKeyDownTime = 0L;

    public SteeringWheelKeyHandler(Context context, HostCallback callback) {
        this.mContext = context;
        this.mCallback = callback;
    }

    public void registerQingganKeys() {
        try {
            final Class<?> managerClass = Class.forName("com.qinggan.system.KeyManager");
            final Class<?> initListenerClass = Class.forName("com.qinggan.system.KeyManager$OnInitListener");
            final Class<?> keyListenerClass = Class.forName("com.qinggan.system.KeyManager$OnKeyEventListener");

            Object initListener = java.lang.reflect.Proxy.newProxyInstance(
                initListenerClass.getClassLoader(),
                new Class<?>[] { initListenerClass },
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                        if ("onResult".equals(method.getName()) && args != null && args.length > 0 && Boolean.TRUE.equals(args[0])) {
                            mHandler.post(new Runnable() {
                                @Override public void run() { registerKeyListener(keyListenerClass); }
                            });
                        }
                        return null;
                    }
                }
            );

            java.lang.reflect.Method getInstance = managerClass.getMethod("getInstance", Context.class, initListenerClass);
            mQingganKeyManager = getInstance.invoke(null, mContext, initListener);
            Log.i(TAG, "Requested Qinggan KeyManager initialization for SWC");
        } catch (Throwable t) {
            Log.w(TAG, "Qinggan KeyManager unavailable: " + t.getMessage());
        }
    }

    private void registerKeyListener(Class<?> listenerClass) {
        if (mQingganKeyManager == null || mQingganKeyRegistered) return;
        try {
            Object listenerProxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[] { listenerClass },
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                        final String name = method.getName();
                        if (args != null && args.length > 0 && args[0] instanceof Number) {
                            final int keyCode = ((Number) args[0]).intValue();
                            if ("onKeyShortClick".equals(name)) {
                                mHandler.post(new Runnable() {
                                    @Override public void run() { handleQingganShortClick(keyCode); }
                                });
                            } else if ("onKeyLongPressStart".equals(name) || "onKeyHoldPressStart".equals(name)) {
                                if (keyCode == QG_LONG_NEXT_KEY || keyCode == QG_KEY_NEXT) {
                                    startQingganSeek(1);
                                } else if (keyCode == QG_LONG_PREVIOUS_KEY || keyCode == QG_KEY_PREVIOUS) {
                                    startQingganSeek(-1);
                                }
                            } else if ("onKeyLongPressStop".equals(name) || "onKeyHoldPressStop".equals(name)
                                    || "onKeyRelease".equals(name) || "onKeyUp".equals(name)) {
                                if (keyCode == QG_LONG_NEXT_KEY || keyCode == QG_KEY_NEXT
                                        || keyCode == QG_LONG_PREVIOUS_KEY || keyCode == QG_KEY_PREVIOUS) {
                                    mHandler.post(new Runnable() {
                                        @Override public void run() {
                                            ShineWaveEngine sw = mCallback.getShineWaveEngine();
                                            if (sw != null) sw.stopLongSeek();
                                            mCallback.evaluateJs("if (typeof window.onSwcSeekEnd === 'function') { window.onSwcSeekEnd(); }");
                                        }
                                    });
                                }
                            }
                        }
                        return false;
                    }
                }
            );

            java.lang.reflect.Method reg = mQingganKeyManager.getClass().getMethod("registerKeyEventListener", listenerClass);
            int res = ((Number) reg.invoke(mQingganKeyManager, listenerProxy)).intValue();
            if (res >= 0) {
                mQingganKeyRegistered = true;
                Log.i(TAG, "Qinggan KeyManager listener registered! Code=" + res);
            }
        } catch (Throwable t) {
            Log.e(TAG, "registerKeyEventListener failed: " + t.getMessage());
        }
    }

    private void startQingganSeek(final int dirSign) {
        final String dir = (dirSign > 0) ? "forward" : "backward";
        mHandler.post(new Runnable() {
            @Override public void run() {
                ShineWaveEngine sw = mCallback.getShineWaveEngine();
                if (sw != null) sw.startLongSeek(dirSign);
                mCallback.evaluateJs("if (typeof window.onSwcSeek === 'function') { window.onSwcSeek('" + dir + "', " + SWC_SEEK_STEP_MS + "); }");
            }
        });
    }

    private void handleQingganShortClick(int keyCode) {
        ShineWaveEngine sw = mCallback.getShineWaveEngine();
        long now = System.currentTimeMillis();
        if (keyCode == QG_KEY_PLAY_PAUSE || keyCode == QG_KEY_MEDIA_OK) {
            if (now - mLastCenterKeyTime < CENTER_KEY_DEBOUNCE_MS) return;
            mLastCenterKeyTime = now;
            mCallback.evaluateJs("if (typeof window.onSwcMediaKey === 'function') { window.onSwcMediaKey('play_pause'); }");
            if (sw != null) sw.handleCommand("toggle");
        } else if (keyCode == QG_KEY_NEXT) {
            mCallback.evaluateJs("if (typeof window.onSwcMediaKey === 'function') { window.onSwcMediaKey('next'); }");
            if (sw != null) sw.handleCommand("next");
        } else if (keyCode == QG_KEY_PREVIOUS) {
            mCallback.evaluateJs("if (typeof window.onSwcMediaKey === 'function') { window.onSwcMediaKey('prev'); }");
            if (sw != null) sw.handleCommand("previous");
        }
    }

    /** Handles standard Android KeyEvents; returns true if consumed. */
    public boolean handleDispatchKeyEvent(KeyEvent event) {
        if (event == null) return false;
        final int code = event.getKeyCode();
        final int action = event.getAction();
        final ShineWaveEngine sw = mCallback.getShineWaveEngine();

        if (code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS || code == KeyEvent.KEYCODE_MEDIA_REWIND) {

            final boolean isForward = (code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
            final long stepMs = isForward ? SWC_SEEK_STEP_MS : -SWC_SEEK_STEP_MS;
            final String dir = isForward ? "forward" : "backward";

            if (action == KeyEvent.ACTION_DOWN) {
                if (mPressedSwcKey == 0) {
                    mPressedSwcKey = code;
                    mSwcKeyDownTime = System.currentTimeMillis();
                    mIsLongPressSeeking = false;

                    mLongPressSeekRunnable = new Runnable() {
                        @Override
                        public void run() {
                            mIsLongPressSeeking = true;
                            ShineWaveEngine curSw = mCallback.getShineWaveEngine();
                            if (curSw != null) {
                                curSw.startLongSeek(isForward ? 1 : -1);
                            } else {
                                CockpitMediaListenerService.seekBy(mContext, stepMs);
                            }
                            mCallback.evaluateJs("if (typeof window.onSwcSeek === 'function') { window.onSwcSeek('" + dir + "', " + SWC_SEEK_STEP_MS + "); }");

                            mContinuousSeekRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    if (mIsLongPressSeeking && mPressedSwcKey != 0) {
                                        if (mCallback.getShineWaveEngine() == null) {
                                            CockpitMediaListenerService.seekBy(mContext, stepMs);
                                        }
                                        mCallback.evaluateJs("if (typeof window.onSwcSeek === 'function') { window.onSwcSeek('" + dir + "', " + SWC_SEEK_STEP_MS + "); }");
                                        mHandler.postDelayed(this, SWC_SEEK_INTERVAL_MS);
                                    }
                                }
                            };
                            mHandler.postDelayed(mContinuousSeekRunnable, SWC_SEEK_INTERVAL_MS);
                        }
                    };
                    mHandler.postDelayed(mLongPressSeekRunnable, SWC_LONG_PRESS_DELAY_MS);
                }
                return true;
            } else if (action == KeyEvent.ACTION_UP) {
                if (mPressedSwcKey == code) {
                    cancelSeekTimers();
                    long heldDuration = System.currentTimeMillis() - mSwcKeyDownTime;

                    if (mIsLongPressSeeking || heldDuration >= SWC_LONG_PRESS_DELAY_MS) {
                        mIsLongPressSeeking = false;
                        mPressedSwcKey = 0;
                        if (sw != null) sw.stopLongSeek();
                        mCallback.evaluateJs("if (typeof window.onSwcSeekEnd === 'function') { window.onSwcSeekEnd(); }");
                    } else {
                        mPressedSwcKey = 0;
                        final String cmd = isForward ? "next" : "prev";
                        if (sw != null) {
                            sw.handleCommand(isForward ? "next" : "previous");
                        } else if (isForward) {
                            CockpitMediaListenerService.skipNext(mContext);
                        } else {
                            CockpitMediaListenerService.skipPrev(mContext);
                        }
                        mCallback.evaluateJs("if (typeof window.onSwcMediaKey === 'function') { window.onSwcMediaKey('" + cmd + "'); }");
                    }
                    return true;
                }
            }
        } else if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_STOP) {
            if (action == KeyEvent.ACTION_UP) {
                if (sw != null) sw.handleCommand("toggle");
                else CockpitMediaListenerService.togglePlayPause(mContext);
                mCallback.evaluateJs("if (typeof window.onSwcMediaKey === 'function') { window.onSwcMediaKey('play_pause'); }");
            }
            return true;
        } else if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                final String vCmd = (code == KeyEvent.KEYCODE_VOLUME_UP) ? "vol_up" : "vol_down";
                mCallback.evaluateJs("if (typeof window.onSwcMediaKey === 'function') { window.onSwcMediaKey('" + vCmd + "'); }");
            }
        }
        return false;
    }

    private void cancelSeekTimers() {
        if (mLongPressSeekRunnable != null) {
            mHandler.removeCallbacks(mLongPressSeekRunnable);
            mLongPressSeekRunnable = null;
        }
        if (mContinuousSeekRunnable != null) {
            mHandler.removeCallbacks(mContinuousSeekRunnable);
            mContinuousSeekRunnable = null;
        }
    }

    public void release() {
        cancelSeekTimers();
        mHandler.removeCallbacksAndMessages(null);
    }
}
