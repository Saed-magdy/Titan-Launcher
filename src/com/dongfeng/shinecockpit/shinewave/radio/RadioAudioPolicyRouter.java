package com.dongfeng.shinecockpit.shinewave.radio;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Coordinates Qinggan AudioPolicyManager radio stream routing (STREAM_RADIO = 21 / type = 3)
 * and releases audio policy when switching away from car radio.
 */
public final class RadioAudioPolicyRouter {
    private static final String MANAGER_CLASS = "com.qinggan.audiopolicy.AudioPolicyManager";
    private static final String INIT_LISTENER_CLASS = "com.qinggan.audiopolicy.AudioPolicyManager$OnInitListener";
    private static final String POLICY_LISTENER_CLASS = "com.qinggan.audiopolicy.AudioPolicyManager$OnAudioPolicyListener";
    private static final String FACTORY_RADIO_PACKAGE = "com.qinggan.app.radio";
    private static final int STREAM_RADIO = 21;
    private static final int POLICY_ALREADY_ACTIVE = 3;

    public interface StatusCallback {
        void onStatus(String message);
    }

    private final Context appContext;
    private final StatusCallback statusCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Class<?> managerClass;
    private Class<?> initListenerClass;
    private Class<?> policyListenerClass;
    private Object manager;
    private Object initListener;
    private Object policyListener;
    private Boolean initialized;
    private boolean requestPending = false;
    private boolean policyRequested = false;
    private int generation = 0;

    public RadioAudioPolicyRouter(Context context, StatusCallback statusCallback) {
        this.appContext = context.getApplicationContext();
        this.statusCallback = statusCallback;
    }

    public synchronized boolean requestRadioPolicy() {
        if (policyRequested) return true;
        if (requestPending) return true;

        requestPending = true;
        generation += 1;
        final int currentGen = generation;

        try {
            resolveClassesLocked();
            createPolicyListenerLocked(currentGen);

            if (manager != null && Boolean.TRUE.equals(initialized)) {
                return sendRequestLocked();
            }

            initialized = null;
            createInitListenerLocked(currentGen);
            Method getInstance = managerClass.getMethod("getInstance", Context.class, initListenerClass);
            manager = getInstance.invoke(null, appContext, initListener);
            if (manager == null) {
                requestPending = false;
                return false;
            }
            if (Boolean.TRUE.equals(initialized)) {
                return sendRequestLocked();
            } else if (Boolean.FALSE.equals(initialized)) {
                requestPending = false;
                return false;
            }
            return true;
        } catch (Throwable t) {
            requestPending = false;
            policyRequested = false;
            notifyStatus("AudioPolicy غير متاح: " + t.getClass().getSimpleName());
            return false;
        }
    }

    public synchronized void abandonRadioPolicy() {
        generation += 1;
        boolean hadPolicy = policyRequested;
        requestPending = false;
        policyRequested = false;
        if (!hadPolicy || manager == null) return;
        try {
            manager.getClass().getMethod("abandonAudioPolicy").invoke(manager);
            notifyStatus("تم تحرير مسار صوت الراديو");
        } catch (Throwable ignored) {}
    }

    private void resolveClassesLocked() throws Exception {
        if (managerClass != null && initListenerClass != null && policyListenerClass != null) return;
        Class<?> mCls = null;
        try {
            mCls = Class.forName(MANAGER_CLASS);
        } catch (Throwable ignored) {}

        if (mCls == null) {
            Context factoryCtx = appContext.createPackageContext(
                    FACTORY_RADIO_PACKAGE, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            mCls = Class.forName(MANAGER_CLASS, true, factoryCtx.getClassLoader());
        }
        ClassLoader loader = mCls.getClassLoader();
        managerClass = mCls;
        initListenerClass = Class.forName(INIT_LISTENER_CLASS, true, loader);
        policyListenerClass = Class.forName(POLICY_LISTENER_CLASS, true, loader);
    }

    private void createInitListenerLocked(final int gen) {
        initListener = Proxy.newProxyInstance(initListenerClass.getClassLoader(), new Class<?>[]{initListenerClass}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("onResult".equals(method.getName())) {
                    boolean success = args != null && args.length > 0 && Boolean.TRUE.equals(args[0]);
                    synchronized (RadioAudioPolicyRouter.this) {
                        if (gen == generation && requestPending) {
                            initialized = success;
                            if (success && manager != null) {
                                sendRequestLocked();
                            } else {
                                requestPending = false;
                                policyRequested = false;
                            }
                        }
                    }
                }
                return defaultValue(method.getReturnType());
            }
        });
    }

    private void createPolicyListenerLocked(final int gen) {
        policyListener = Proxy.newProxyInstance(policyListenerClass.getClassLoader(), new Class<?>[]{policyListenerClass}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String n = method.getName();
                if ("onPause".equals(n) || "onStop".equals(n)) {
                    synchronized (RadioAudioPolicyRouter.this) {
                        if (gen == generation) notifyStatus("AudioPolicy: " + n);
                    }
                }
                return defaultValue(method.getReturnType());
            }
        });
    }

    private boolean sendRequestLocked() {
        try {
            int policyType = choosePolicyType(manager);
            Method m = managerClass.getMethod("requestAudioPolicy", policyListenerClass, int.class);
            Object res = m.invoke(manager, policyListener, policyType);
            boolean accepted = (res instanceof Boolean && (Boolean) res) || (res instanceof Number && ((Number) res).intValue() != 0);
            requestPending = false;
            policyRequested = accepted;
            if (accepted) notifyStatus("تم توجيه صوت الراديو بنجاح");
            return accepted;
        } catch (Throwable t) {
            requestPending = false;
            policyRequested = false;
            return false;
        }
    }

    private int choosePolicyType(Object mgr) {
        try {
            Object info = mgr.getClass().getMethod("getCurrentAudioPolicyInfo").invoke(mgr);
            if (info == null) return STREAM_RADIO;
            Object stream = info.getClass().getMethod("getStreamType").invoke(info);
            if (stream instanceof Number && ((Number) stream).intValue() == STREAM_RADIO) {
                return POLICY_ALREADY_ACTIVE;
            }
        } catch (Throwable ignored) {}
        return STREAM_RADIO;
    }

    private void notifyStatus(final String msg) {
        if (statusCallback != null) {
            mainHandler.post(new Runnable() {
                @Override public void run() { statusCallback.onStatus(msg); }
            });
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        return null;
    }
}
