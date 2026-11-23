package com.dongfeng.shinecockpit.shinewave.core;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Universal Hardware Audio Policy Bridge for Dongfeng / Qinggan OS.
 * <p>
 * Directly commands com.qinggan.audiopolicy.AudioPolicyManager to switch the car's
 * hardware DSP audio routing channel between:
 * - Bluetooth A2DP Sink (streamType = 4, extraType = 300)
 * - Car Radio Tuner (streamType = 21)
 * - Local / Media playback (streamType = 3)
 * <p>
 * This prevents the silence bug where car hardware DSP unmutes only after launching OEM music.
 */
public final class QingganAudioPolicyBridge {
    private static final String TAG = "QGAudioPolicyBridge";

    private static final String MANAGER_CLASS = "com.qinggan.audiopolicy.AudioPolicyManager";
    private static final String INIT_LISTENER_CLASS = "com.qinggan.audiopolicy.AudioPolicyManager$OnInitListener";
    private static final String POLICY_LISTENER_CLASS = "com.qinggan.audiopolicy.AudioPolicyManager$OnAudioPolicyListener";

    private static final String OEM_MUSIC_PKG = "com.qinggan.app.music";
    private static final String OEM_RADIO_PKG = "com.qinggan.app.radio";

    public static final int STREAM_MUSIC = 3;
    public static final int STREAM_A2DP = 4;
    public static final int STREAM_RADIO = 21;

    private static volatile QingganAudioPolicyBridge sInstance;

    public static QingganAudioPolicyBridge getInstance(Context context) {
        if (sInstance == null) {
            synchronized (QingganAudioPolicyBridge.class) {
                if (sInstance == null) {
                    sInstance = new QingganAudioPolicyBridge(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private final Context appContext;
    private final AudioManager audioManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private Class<?> managerClass;
    private Class<?> initListenerClass;
    private Class<?> policyListenerClass;
    private Object policyManager;
    private Object initListenerProxy;
    private Object policyListenerProxy;

    private volatile boolean initialized = false;
    private volatile boolean initInProgress = false;
    private volatile int currentRoutingStream = -1;

    private QingganAudioPolicyBridge(Context context) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        ensureManagerInitializedAsync();
    }

    public void ensureManagerInitializedAsync() {
        synchronized (lock) {
            if (initialized || initInProgress) return;
            initInProgress = true;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    try {
                        resolveClassesLocked();
                        createInitListenerProxyLocked();
                        createPolicyListenerProxyLocked();

                        Method getInstance = managerClass.getMethod("getInstance", Context.class, initListenerClass);
                        policyManager = getInstance.invoke(null, appContext, initListenerProxy);
                        Log.i(TAG, "AudioPolicyManager.getInstance returned: " + policyManager);
                    } catch (Throwable t) {
                        Log.w(TAG, "AudioPolicyManager init exception: " + t.getMessage());
                        initInProgress = false;
                    }
                }
            }
        }, "QGAudioPolicyInit").start();
    }

    /**
     * Routes hardware DSP audio to Media/Music (streamType = 3).
     */
    public boolean routeToMusic() {
        requestMediaAudioFocus();
        synchronized (lock) {
            ensureClassesAvailable();
            if (policyManager == null) return false;
            try {
                Method m = managerClass.getMethod("requestAudioPolicy", policyListenerClass, int.class);
                Object res = m.invoke(policyManager, policyListenerProxy, STREAM_MUSIC);
                Log.i(TAG, "requestAudioPolicy(3) music returned: " + res);
                currentRoutingStream = STREAM_MUSIC;
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "routeToMusic failed: " + t.getMessage());
                return false;
            }
        }
    }

    /**
     * Routes hardware DSP audio to Bluetooth A2DP Sink (streamType = 4).
     * Calls requestAudioPolicyForVehicleBtMusicOnly() or requestAudioPolicy(listener, 4).
     */
    public boolean routeToBluetooth() {
        requestMediaAudioFocus();
        synchronized (lock) {
            ensureClassesAvailable();
            if (policyManager == null) {
                Log.w(TAG, "policyManager is null, cannot route to BT");
                return false;
            }

            try {
                // 1. Try dedicated factory method: requestAudioPolicyForVehicleBtMusicOnly(listener)
                try {
                    Method m = managerClass.getMethod("requestAudioPolicyForVehicleBtMusicOnly", policyListenerClass);
                    Object res = m.invoke(policyManager, policyListenerProxy);
                    int code = (res instanceof Number) ? ((Number) res).intValue() : 1;
                    Log.i(TAG, "requestAudioPolicyForVehicleBtMusicOnly returned: " + code);
                    currentRoutingStream = STREAM_A2DP;
                    return code == 1 || code == 3;
                } catch (NoSuchMethodException ignored) {}

                // 2. Try requestAudioPolicyEx(listener, 4, -1, "300", 1) or requestAudioPolicy(listener, 4)
                try {
                    Method m = managerClass.getMethod("requestAudioPolicy", policyListenerClass, int.class);
                    Object res = m.invoke(policyManager, policyListenerProxy, STREAM_A2DP);
                    Log.i(TAG, "requestAudioPolicy(4) returned: " + res);
                    currentRoutingStream = STREAM_A2DP;
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "requestAudioPolicy fallback error: " + t.getMessage());
                }
            } catch (Throwable t) {
                Log.w(TAG, "routeToBluetooth failed: " + t.getMessage());
            }
            return false;
        }
    }

    /**
     * Routes hardware DSP audio to Car Radio Tuner (streamType = 21).
     */
    public boolean routeToRadio() {
        synchronized (lock) {
            ensureClassesAvailable();
            if (policyManager == null) return false;

            try {
                Method m = managerClass.getMethod("requestAudioPolicy", policyListenerClass, int.class);
                Object res = m.invoke(policyManager, policyListenerProxy, STREAM_RADIO);
                Log.i(TAG, "requestAudioPolicy(21) radio returned: " + res);
                currentRoutingStream = STREAM_RADIO;
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "routeToRadio failed: " + t.getMessage());
                return false;
            }
        }
    }

    /**
     * Releases active hardware policy (standby / normal media routing).
     */
    public void abandonCurrentPolicy() {
        synchronized (lock) {
            if (policyManager == null) return;
            try {
                Method m = managerClass.getMethod("abandonAudioPolicy");
                m.invoke(policyManager);
                Log.i(TAG, "abandonAudioPolicy() called");
                currentRoutingStream = -1;
            } catch (Throwable ignored) {
                try {
                    Method m = managerClass.getMethod("abandonAudioPolicy", policyListenerClass);
                    m.invoke(policyManager, policyListenerProxy);
                    currentRoutingStream = -1;
                } catch (Throwable ignored2) {}
            }
        }
    }

    private void requestMediaAudioFocus() {
        try {
            if (audioManager != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    AudioFocusRequest afr = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build())
                            .build();
                    audioManager.requestAudioFocus(afr);
                } else {
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void ensureClassesAvailable() {
        if (managerClass == null || policyManager == null) {
            try {
                resolveClassesLocked();
                createInitListenerProxyLocked();
                createPolicyListenerProxyLocked();
                Method getInstance = managerClass.getMethod("getInstance", Context.class, initListenerClass);
                policyManager = getInstance.invoke(null, appContext, initListenerProxy);
            } catch (Throwable t) {
                Log.w(TAG, "ensureClassesAvailable: " + t.getMessage());
            }
        }
    }

    private void resolveClassesLocked() throws Exception {
        if (managerClass != null && initListenerClass != null && policyListenerClass != null) return;

        Class<?> mCls = null;
        try {
            mCls = Class.forName(MANAGER_CLASS);
        } catch (Throwable ignored) {}

        if (mCls == null) {
            try {
                Context musicCtx = appContext.createPackageContext(OEM_MUSIC_PKG,
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                mCls = Class.forName(MANAGER_CLASS, true, musicCtx.getClassLoader());
            } catch (Throwable ignored) {}
        }

        if (mCls == null) {
            try {
                Context radioCtx = appContext.createPackageContext(OEM_RADIO_PKG,
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
                mCls = Class.forName(MANAGER_CLASS, true, radioCtx.getClassLoader());
            } catch (Throwable ignored) {}
        }

        if (mCls == null) {
            throw new ClassNotFoundException("Could not locate " + MANAGER_CLASS);
        }

        ClassLoader loader = mCls.getClassLoader();
        managerClass = mCls;
        initListenerClass = Class.forName(INIT_LISTENER_CLASS, true, loader);
        policyListenerClass = Class.forName(POLICY_LISTENER_CLASS, true, loader);
    }

    private void createInitListenerProxyLocked() {
        if (initListenerProxy != null) return;
        initListenerProxy = Proxy.newProxyInstance(initListenerClass.getClassLoader(), new Class<?>[]{initListenerClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("onResult".equals(method.getName())) {
                            boolean res = args != null && args.length > 0 && Boolean.TRUE.equals(args[0]);
                            synchronized (lock) {
                                initialized = res;
                                initInProgress = false;
                            }
                            Log.i(TAG, "OnInitListener.onResult: " + res);
                        }
                        return defaultValue(method.getReturnType());
                    }
                });
    }

    private void createPolicyListenerProxyLocked() {
        if (policyListenerProxy != null) return;
        policyListenerProxy = Proxy.newProxyInstance(policyListenerClass.getClassLoader(), new Class<?>[]{policyListenerClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        Log.d(TAG, "OnAudioPolicyListener invoked: " + name);
                        return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        return null;
    }
}
