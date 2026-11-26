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

    private static final long SWC_LONG_PRESS_DELAY_MS = 900L;
    private static final long SWC_SEEK_INTERVAL_MS = 450L;
    private static final long SWC_SEEK_STEP_MS = 10000L;
    private static final long SWC_SEEK_WATCHDOG_MS = 12000L;
    private static final long CENTER_KEY_DEBOUNCE_MS = 350L;
    private static final int QG_CB_FLAG_ALWAYS = 2;
    private static final int QG_INTERCEPT_PRIORITY = 1000;

    // Dongfeng Shine CAN / KeyManager SWC mapping (verified from QGKeyEvent, TranslatorSink, DongfengKeyMaster):
    // Physical Forward (SEEK+)  = 3 (at 1000ms long press, TranslatorSink translates 3 -> 14 = QG_LONG_NEXT_KEY)
    // Physical Backward (SEEK-) = 4 (at 1000ms long press, TranslatorSink translates 4 -> 13 = QG_LONG_PREVIOUS_KEY)
    private static final int QG_KEY_NEXT = 3;
    private static final int QG_KEY_PREVIOUS = 4;
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
    private Object mQingganKeyListener = null;
    private boolean mQingganKeyRegistered = false;
    private long mLastCenterKeyTime = 0L;

    private Runnable mLongPressSeekRunnable = null;
    private Runnable mContinuousSeekRunnable = null;
    private Runnable mSeekWatchdogRunnable = null;
    private int mPressedSwcKey = 0;
    private boolean mIsLongPressSeeking = false;
    private volatile boolean mQingganSeekActive = false;
    private int mQingganSeekDir = 0;
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

            try {
                java.lang.reflect.Method getInstanceFlag = managerClass.getMethod("getInstance", Context.class, initListenerClass, int.class);
                mQingganKeyManager = getInstanceFlag.invoke(null, mContext, initListener, QG_CB_FLAG_ALWAYS);
            } catch (NoSuchMethodException ignored) {
                java.lang.reflect.Method getInstance = managerClass.getMethod("getInstance", Context.class, initListenerClass);
                mQingganKeyManager = getInstance.invoke(null, mContext, initListener);
            }
            Log.i(TAG, "Requested Qinggan KeyManager initialization for SWC (flag=" + QG_CB_FLAG_ALWAYS + ")");
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
                            final boolean isHandledMedia = isQingganMediaKey(keyCode);
                            if (!isHandledMedia) {
                                return false;
                            }
                            if ("onKeyDown".equals(name)) {
                                // Clear any lingering seek if a new press begins
                                stopQingganSeek();
                                return true;
                            } else if ("onKeyShortClick".equals(name)) {
                                stopQingganSeek();
                                mHandler.post(new Runnable() {
                                    @Override public void run() { handleQingganShortClick(keyCode); }
                                });
                                return true;
                            } else if ("onKeyHoldPressStart".equals(name)) {
                                // 500ms hold phase: consume without starting 10s seek; true long-seek starts at 1000ms onKeyLongPressStart.
                                return true;
                            } else if ("onKeyLongPressStart".equals(name)) {
                                if (keyCode == QG_KEY_NEXT || keyCode == QG_LONG_NEXT_KEY) {
                                    startQingganSeek(1);
                                } else if (keyCode == QG_KEY_PREVIOUS || keyCode == QG_LONG_PREVIOUS_KEY) {
                                    startQingganSeek(-1);
                                }
                                return true;
                            } else if ("onKeyHoldPressStop".equals(name)) {
                                // Released between 500ms and 999ms: KeyManagerReader does not emit onKeyShortClick here,
                                // so treat as a normal single-press track skip if long-seek has not started.
                                final boolean wasSeeking = mQingganSeekActive;
                                stopQingganSeek();
                                if (!wasSeeking && (keyCode == QG_KEY_NEXT || keyCode == QG_KEY_PREVIOUS
                                        || keyCode == QG_KEY_PLAY_PAUSE || keyCode == QG_KEY_MEDIA_OK)) {
                                    mHandler.post(new Runnable() {
                                        @Override public void run() { handleQingganShortClick(keyCode); }
                                    });
                                }
                                return true;
                            } else if ("onKeyLongPressStop".equals(name)
                                    || "onKeyRelease".equals(name) || "onKeyUp".equals(name)) {
                                if (keyCode == QG_KEY_NEXT || keyCode == QG_LONG_NEXT_KEY
                                        || keyCode == QG_KEY_PREVIOUS || keyCode == QG_LONG_PREVIOUS_KEY) {
                                    stopQingganSeek();
                                }
                                return true;
                            }
                        }
                        return false;
                    }
                }
            );

            // Clear any stale entries in KeyManager's static mKeyEventListenerMap before registering
            unregisterKeyListenerQuietly(listenerClass, listenerProxy);

            // Register intercept at priority 1000 (> DongfengKeyMaster's 100 and KeyManagerSinkCollector's 99)
            // so translated keys 13/14 LONG & LONG_STOP are never swallowed before reaching us.
            int resIntercept = -1;
            try {
                java.lang.reflect.Method regIntPrio = mQingganKeyManager.getClass().getMethod("registerInterceptListener", listenerClass, int.class);
                resIntercept = ((Number) regIntPrio.invoke(mQingganKeyManager, listenerProxy, QG_INTERCEPT_PRIORITY)).intValue();
            } catch (NoSuchMethodException nsme) {
                try {
                    java.lang.reflect.Method regInt = mQingganKeyManager.getClass().getMethod("registerInterceptListener", listenerClass);
                    resIntercept = ((Number) regInt.invoke(mQingganKeyManager, listenerProxy)).intValue();
                } catch (Throwable t2) {
                    Log.w(TAG, "registerInterceptListener 1-arg note: " + t2.getMessage());
                }
            } catch (Throwable t) {
                Log.w(TAG, "registerInterceptListener note: " + t.getMessage());
            }

            java.lang.reflect.Method reg = mQingganKeyManager.getClass().getMethod("registerKeyEventListener", listenerClass);
            int resNormal = ((Number) reg.invoke(mQingganKeyManager, listenerProxy)).intValue();
            if (resIntercept >= 0 || resNormal >= 0) {
                mQingganKeyListener = listenerProxy;
                mQingganKeyRegistered = true;
                Log.i(TAG, "Qinggan KeyManager registered! intercept(p=" + QG_INTERCEPT_PRIORITY + ")=" + resIntercept + " normal=" + resNormal);
            }
        } catch (Throwable t) {
            Log.e(TAG, "registerKeyEventListener failed: " + t.getMessage());
        }
    }

    private void unregisterKeyListenerQuietly(Class<?> listenerClass, Object listener) {
        if (mQingganKeyManager == null || listener == null) return;
        try {
            java.lang.reflect.Method unregInt = mQingganKeyManager.getClass().getMethod("unregisterInterceptListener", listenerClass);
            unregInt.invoke(mQingganKeyManager, listener);
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method unregNorm = mQingganKeyManager.getClass().getMethod("unregisterKeyEventListener", listenerClass);
            unregNorm.invoke(mQingganKeyManager, listener);
        } catch (Throwable ignored) {}
    }

    private static boolean isQingganMediaKey(int keyCode) {
        return keyCode == QG_KEY_NEXT || keyCode == QG_KEY_PREVIOUS
                || keyCode == QG_LONG_NEXT_KEY || keyCode == QG_LONG_PREVIOUS_KEY
                || keyCode == QG_KEY_PLAY_PAUSE || keyCode == QG_KEY_MEDIA_OK;
    }

    private void resetSeekWatchdogLocked() {
        cancelSeekWatchdogLocked();
        mSeekWatchdogRunnable = new Runnable() {
            @Override public void run() {
                mSeekWatchdogRunnable = null;
                if (mQingganSeekActive) {
                    Log.w(TAG, "SWC seek watchdog expired; forcing seek stop");
                    stopQingganSeek();
                }
            }
        };
        mHandler.postDelayed(mSeekWatchdogRunnable, SWC_SEEK_WATCHDOG_MS);
    }

    private void cancelSeekWatchdogLocked() {
        if (mSeekWatchdogRunnable != null) {
            mHandler.removeCallbacks(mSeekWatchdogRunnable);
            mSeekWatchdogRunnable = null;
        }
    }

    private void startQingganSeek(final int dirSign) {
        mHandler.post(new Runnable() {
            @Override public void run() {
                final ShineWaveEngine sw = mCallback.getShineWaveEngine();
                if (sw != null && !sw.supportsAdvancedControls()) {
                    return;
                }
                resetSeekWatchdogLocked();
                if (mQingganSeekActive && mQingganSeekDir == dirSign) {
                    // Already seeking from 500ms HOLD when 1000ms LONG arrives in the same direction
                    return;
                }
                if (mQingganSeekActive && mQingganSeekDir != dirSign) {
                    if (sw != null) sw.stopLongSeek();
                }
                mQingganSeekActive = true;
                mQingganSeekDir = dirSign;
                if (sw != null) {
                    sw.setSeekUiCallback(new ShineWaveEngine.SeekUiCallback() {
                        @Override public void onSeekTick(int direction) {
                            final String d = (direction > 0) ? "forward" : "backward";
                            mCallback.evaluateJs("if (typeof window.onSwcSeek === 'function') { window.onSwcSeek('" + d + "', " + SWC_SEEK_STEP_MS + "); }");
                        }
                        @Override public void onSeekStopped() {
                            mCallback.evaluateJs("if (typeof window.onSwcSeekEnd === 'function') { window.onSwcSeekEnd(); }");
                        }
                    });
                    sw.startLongSeek(dirSign);
                } else {
                    final String dir = (dirSign > 0) ? "forward" : "backward";
                    mCallback.evaluateJs("if (typeof window.onSwcSeek === 'function') { window.onSwcSeek('" + dir + "', " + SWC_SEEK_STEP_MS + "); }");
                }
            }
        });
    }

    private void stopQingganSeek() {
        mHandler.post(new Runnable() {
            @Override public void run() {
                cancelSeekWatchdogLocked();
                if (!mQingganSeekActive && mQingganSeekDir == 0) return;
                mQingganSeekActive = false;
                mQingganSeekDir = 0;
                ShineWaveEngine sw = mCallback.getShineWaveEngine();
                if (sw != null) {
                    sw.stopLongSeek();
                } else {
                    mCallback.evaluateJs("if (typeof window.onSwcSeekEnd === 'function') { window.onSwcSeekEnd(); }");
                }
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
            // Avoid duplicate timers when Qinggan KeyManager intercept/listener already owns virtual SWC keys
            if (mQingganKeyRegistered && event.getDeviceId() <= 0) {
                return true;
            }

            final boolean isForward = (code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
            final long stepMs = isForward ? SWC_SEEK_STEP_MS : -SWC_SEEK_STEP_MS;
            final String dir = isForward ? "forward" : "backward";

            if (action == KeyEvent.ACTION_DOWN) {
                if (mPressedSwcKey == 0) {
                    mPressedSwcKey = code;
                    mSwcKeyDownTime = System.currentTimeMillis();
                    mIsLongPressSeeking = false;

                    if (sw == null || sw.supportsAdvancedControls()) {
                        mLongPressSeekRunnable = new Runnable() {
                            @Override
                            public void run() {
                                ShineWaveEngine curSw = mCallback.getShineWaveEngine();
                                if (curSw != null && !curSw.supportsAdvancedControls()) return;
                                mIsLongPressSeeking = true;
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
                                            ShineWaveEngine loopSw = mCallback.getShineWaveEngine();
                                            if (loopSw != null && !loopSw.supportsAdvancedControls()) return;
                                            if (loopSw == null) {
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
                }
                return true;
            } else if (action == KeyEvent.ACTION_UP) {
                if (mPressedSwcKey == code) {
                    cancelSeekTimers();

                    if (mIsLongPressSeeking) {
                        mIsLongPressSeeking = false;
                        mPressedSwcKey = 0;
                        if (sw != null) sw.stopLongSeek();
                        mCallback.evaluateJs("if (typeof window.onSwcSeekEnd === 'function') { window.onSwcSeekEnd(); }");
                    } else {
                        mPressedSwcKey = 0;
                        boolean isDedicatedSeekKey = (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_REWIND);
                        if (isDedicatedSeekKey && sw != null && !sw.supportsAdvancedControls()) {
                            return true;
                        }
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
        cancelSeekWatchdogLocked();
        mQingganSeekActive = false;
        mQingganSeekDir = 0;
        if (mQingganKeyManager != null && mQingganKeyListener != null) {
            try {
                Class<?> keyListenerClass = Class.forName("com.qinggan.system.KeyManager$OnKeyEventListener");
                unregisterKeyListenerQuietly(keyListenerClass, mQingganKeyListener);
            } catch (Throwable ignored) {}
            mQingganKeyListener = null;
        }
        mQingganKeyRegistered = false;
        mHandler.removeCallbacksAndMessages(null);
    }
}
