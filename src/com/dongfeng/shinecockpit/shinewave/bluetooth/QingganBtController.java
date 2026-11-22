package com.dongfeng.shinecockpit.shinewave.bluetooth;

import android.content.Context;
import android.util.Log;

import dalvik.system.DexClassLoader;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Reflection bridge to Qinggan / WL8 vendor A2DP stack (/system/framework/WL8BluetoothAdapter.jar).
 * Drives play, pause, next, and previous directly through the factory A2dp profile.
 */
public final class QingganBtController {
    private static final String TAG = "SWQingganBt";
    private static final String WL8_JAR = "/system/framework/WL8BluetoothAdapter.jar";
    private static final String BT_MANAGER = "com.qinggan.bluetooth.BTManager";
    private static final String BT_CALLBACK = "com.qinggan.bluetooth.profile.IBTCallback";
    private static final String BT_ADAPTER = "com.qinggan.bluetooth.profile.BTAdapter";
    private static final String A2DP_CALLBACK = "com.qinggan.bluetooth.profile.IA2dpCallback";

    public static final class BtState {
        public final boolean available;
        public final boolean connected;
        public final boolean playing;
        public final String lastAction;
        public final String error;

        public BtState(boolean available, boolean connected, boolean playing, String lastAction, String error) {
            this.available = available;
            this.connected = connected;
            this.playing = playing;
            this.lastAction = lastAction;
            this.error = error;
        }
    }

    public interface Listener {
        void onVendorBtStateChanged(BtState state);
    }

    private static volatile QingganBtController instance;

    public static QingganBtController getInstance(Context context) {
        if (instance == null) {
            synchronized (QingganBtController.class) {
                if (instance == null) {
                    instance = new QingganBtController(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private final Context appContext;
    private final Object lock = new Object();
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();

    private Object btManager;
    private Object btAdapter;
    private Object a2dp;
    private Object btCallbackProxy;
    private Object a2dpCallbackProxy;
    private BtState currentState = new BtState(false, false, false, "", null);
    private boolean released = false;

    private QingganBtController(Context appContext) {
        this.appContext = appContext;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onVendorBtStateChanged(snapshot());
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public BtState snapshot() {
        synchronized (lock) {
            return currentState;
        }
    }

    public BtState refresh() {
        ensureInitialized();
        synchronized (lock) {
            Object profile = a2dp;
            if (profile == null) {
                publishLocked(new BtState(false, false, false, currentState.lastAction,
                        currentState.error != null ? currentState.error : "Qinggan Bluetooth API unavailable"));
                return currentState;
            }
            boolean connected = Boolean.TRUE.equals(invokeBoolean(profile, "isConnected"));
            boolean playing = Boolean.TRUE.equals(invokeBoolean(profile, "isPlaying"));
            publishLocked(new BtState(true, connected, playing, currentState.lastAction, null));
            return currentState;
        }
    }

    public boolean play() {
        return performAction("play", new ActionCallable() {
            @Override
            public boolean call(Object profile) {
                if (!Boolean.TRUE.equals(invokeBoolean(profile, "isConnected"))) return false;
                if (Boolean.TRUE.equals(invokeBoolean(profile, "isPlaying"))) return true;
                return Boolean.TRUE.equals(invokeBoolean(profile, "play"));
            }
        });
    }

    public boolean pause() {
        return performAction("pause", new ActionCallable() {
            @Override
            public boolean call(Object profile) {
                if (!Boolean.TRUE.equals(invokeBoolean(profile, "isConnected"))) return false;
                if (!Boolean.TRUE.equals(invokeBoolean(profile, "isPlaying"))) return true;
                return Boolean.TRUE.equals(invokeBoolean(profile, "pause"));
            }
        });
    }

    public boolean togglePlayPause() {
        return performAction("toggle", new ActionCallable() {
            @Override
            public boolean call(Object profile) {
                if (!Boolean.TRUE.equals(invokeBoolean(profile, "isConnected"))) return false;
                boolean playing = Boolean.TRUE.equals(invokeBoolean(profile, "isPlaying"));
                return Boolean.TRUE.equals(invokeBoolean(profile, playing ? "pause" : "play"));
            }
        });
    }

    public boolean next() {
        return performAction("next", new ActionCallable() {
            @Override
            public boolean call(Object profile) {
                if (!Boolean.TRUE.equals(invokeBoolean(profile, "isConnected"))) return false;
                return Boolean.TRUE.equals(invokeBoolean(profile, "forward"));
            }
        });
    }

    public boolean previous() {
        return performAction("previous", new ActionCallable() {
            @Override
            public boolean call(Object profile) {
                if (!Boolean.TRUE.equals(invokeBoolean(profile, "isConnected"))) return false;
                return Boolean.TRUE.equals(invokeBoolean(profile, "backward"));
            }
        });
    }

    private interface ActionCallable {
        boolean call(Object profile);
    }

    private boolean performAction(String actionName, ActionCallable callable) {
        synchronized (lock) {
            ensureInitialized();
            Object profile = a2dp;
            if (profile == null) return false;
            try {
                boolean result = callable.call(profile);
                boolean connected = Boolean.TRUE.equals(invokeBoolean(a2dp, "isConnected"));
                boolean playing = Boolean.TRUE.equals(invokeBoolean(a2dp, "isPlaying"));
                publishLocked(new BtState(true, connected, playing, result ? actionName : actionName + "(failed)", null));
                return result;
            } catch (Throwable t) {
                Log.w(TAG, "action=" + actionName + " failed: " + t.getMessage());
                return false;
            }
        }
    }

    private void ensureInitialized() {
        synchronized (lock) {
            if (released) released = false;
            if (a2dp != null) return;

            try {
                ClassLoader loader = findVendorClassLoader();
                if (loader == null) {
                    throw new ClassNotFoundException(WL8_JAR + " unavailable");
                }
                Class<?> btManagerClass = Class.forName(BT_MANAGER, true, loader);
                Class<?> btCallbackClass = Class.forName(BT_CALLBACK, true, loader);
                Class<?> a2dpCallbackClass = Class.forName(A2DP_CALLBACK, true, loader);

                btCallbackProxy = createCallbackProxy(btCallbackClass);
                a2dpCallbackProxy = createCallbackProxy(a2dpCallbackClass);

                Method getInstance = btManagerClass.getMethod("getInstance", Context.class);
                btManager = getInstance.invoke(null, appContext);
                if (btManager == null) throw new IllegalStateException("BTManager.getInstance null");

                Method getAdapter = findSingleArgMethod(btManager, "getAdapter");
                btAdapter = getAdapter.invoke(btManager, btCallbackProxy);
                if (btAdapter == null) throw new IllegalStateException("BTManager.getAdapter null");

                Method getA2dp = findSingleArgMethod(btAdapter, "getA2dp");
                a2dp = getA2dp.invoke(btAdapter, a2dpCallbackProxy);
                if (a2dp == null) throw new IllegalStateException("BTAdapter.getA2dp null");

                invokeVoid(a2dp, "registerListener", a2dpCallbackProxy);

                boolean connected = Boolean.TRUE.equals(invokeBoolean(a2dp, "isConnected"));
                boolean playing = Boolean.TRUE.equals(invokeBoolean(a2dp, "isPlaying"));
                publishLocked(new BtState(true, connected, playing, "init", null));
            } catch (Throwable t) {
                Log.w(TAG, "Qinggan BT stack init note: " + t.getMessage());
                a2dp = null;
                btAdapter = null;
                btManager = null;
                publishLocked(new BtState(false, false, false, "init_error", t.getMessage()));
            }
        }
    }

    private ClassLoader findVendorClassLoader() {
        ClassLoader parent = appContext.getClassLoader();
        try {
            Class.forName(BT_MANAGER, false, parent);
            return parent;
        } catch (Throwable ignored) {}

        File jar = new File(WL8_JAR);
        if (!jar.isFile()) return null;

        File optDir = new File(appContext.getCodeCacheDir(), "qg_wl8_dex");
        if (!optDir.exists()) {
            boolean ignored = optDir.mkdirs();
        }
        try {
            return new DexClassLoader(jar.getAbsolutePath(), optDir.getAbsolutePath(), "/system/framework", parent);
        } catch (Throwable t) {
            Log.w(TAG, "DexClassLoader failed: " + t.getMessage());
            return null;
        }
    }

    private Object createCallbackProxy(Class<?> interfaceClass) {
        return Proxy.newProxyInstance(interfaceClass.getClassLoader(), new Class<?>[]{interfaceClass}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                handleVendorCallback(method.getName(), args);
                return defaultValue(method.getReturnType());
            }
        });
    }

    private void handleVendorCallback(String name, Object[] args) {
        synchronized (lock) {
            if ("onPlayStateChanged".equals(name) && args != null && args.length > 0 && args[0] instanceof Boolean) {
                boolean playing = (Boolean) args[0];
                publishLocked(new BtState(true, currentState.connected, playing, "callback:onPlayStateChanged", null));
            } else if ("onStateChanged".equals(name) || "onReady".equals(name)) {
                boolean connected = Boolean.TRUE.equals(invokeBoolean(a2dp, "isConnected"));
                boolean playing = Boolean.TRUE.equals(invokeBoolean(a2dp, "isPlaying"));
                publishLocked(new BtState(true, connected, playing, "callback:" + name, null));
            }
        }
    }

    private void publishLocked(BtState state) {
        currentState = state;
        for (Listener listener : listeners) {
            try {
                listener.onVendorBtStateChanged(state);
            } catch (Throwable ignored) {}
        }
    }

    private static Method findSingleArgMethod(Object target, String name) throws NoSuchMethodException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (name.equals(m.getName()) && m.getParameterTypes().length == 1) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name + "(1 arg)");
    }

    private static Boolean invokeBoolean(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            Object val = m.invoke(target);
            return (val instanceof Boolean) ? (Boolean) val : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void invokeVoid(Object target, String methodName, Object arg) {
        try {
            Method m = findSingleArgMethod(target, methodName);
            m.invoke(target, arg);
        } catch (Throwable ignored) {}
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0.0;
        if (type == char.class) return '\0';
        return null;
    }

    public void release() {
        synchronized (lock) {
            if (released) return;
            released = true;
            if (a2dp != null && a2dpCallbackProxy != null) invokeVoid(a2dp, "unregisterListener", a2dpCallbackProxy);
            if (btAdapter != null && btCallbackProxy != null) invokeVoid(btAdapter, "unregisterListener", btCallbackProxy);
            a2dp = null;
            btAdapter = null;
            btManager = null;
        }
    }
}
