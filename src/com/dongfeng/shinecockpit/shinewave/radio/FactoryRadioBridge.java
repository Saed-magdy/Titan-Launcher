package com.dongfeng.shinecockpit.shinewave.radio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-level reflection bridge to com.qinggan.app.radio's IRadioRemoteService so
 * tuning updates propagate cleanly through the factory LocalRadioEngine and CAN/HUD.
 */
public final class FactoryRadioBridge {
    private static final String OEM_PACKAGE = "com.qinggan.app.radio";
    private static final String OEM_SERVICE = "com.qinggan.radioservice.service.RadioService";
    private static final String OEM_ACTION = "qinggan.Service.Radio.START";
    private static final String REMOTE_INTERFACE = "com.qinggan.radio.IRadioRemoteService";
    private static final String REMOTE_STUB = "com.qinggan.radio.IRadioRemoteService$Stub";
    private static final String LOCAL_RADIO_INFO = "com.qinggan.radio.LocalRadioInfo";
    private static final String RADIO_TYPE = "com.qinggan.radio.RadioConstants$RadioType";
    private static final long BIND_TIMEOUT_MS = 2000L;
    private static final long TIMEOUT_BACKOFF_MS = 5000L;

    public interface StatusCallback {
        void onStatus(String message);
    }

    private static final class PendingTune {
        final int band;
        final int frequencyKhz;
        final long generation;

        PendingTune(int band, int frequencyKhz, long generation) {
            this.band = band;
            this.frequencyKhz = frequencyKhz;
            this.generation = generation;
        }
    }

    private final Context appContext;
    private final StatusCallback statusCallback;
    private final Object lock = new Object();
    private final Object tuneDispatchLock = new Object();
    private final AtomicLong tuneGeneration = new AtomicLong(0L);
    private final AtomicLong bindingGeneration = new AtomicLong(0L);
    private final ExecutorService tuneExecutor = Executors.newSingleThreadExecutor();

    private ServiceConnection serviceConnection;
    private volatile PendingTune pendingTune;
    private volatile IBinder remoteBinder;
    private volatile Object remoteApi;
    private boolean isBound = false;
    private CountDownLatch bindLatch;
    private long lastTimeoutAtMs = 0L;
    private Context factoryCodeContext;

    public FactoryRadioBridge(Context context, StatusCallback statusCallback) {
        this.appContext = context.getApplicationContext();
        this.statusCallback = statusCallback;
    }

    public void prewarm() {
        ensureBindingStarted();
    }

    public void cancelPendingTune() {
        synchronized (tuneDispatchLock) {
            tuneGeneration.incrementAndGet();
            synchronized (lock) {
                pendingTune = null;
            }
        }
    }

    public boolean playLocalRadio(int band, int frequencyKhz) {
        if (band != FmAmTunerController.BAND_FM && band != FmAmTunerController.BAND_AM) return false;
        if (band == FmAmTunerController.BAND_FM && (frequencyKhz < 87500 || frequencyKhz > 108000)) return false;
        if (band == FmAmTunerController.BAND_AM && (frequencyKhz < 531 || frequencyKhz > 1629)) return false;

        PendingTune req;
        synchronized (tuneDispatchLock) {
            req = new PendingTune(band, frequencyKhz, tuneGeneration.incrementAndGet());
            synchronized (lock) {
                if (pendingTune != null && pendingTune.generation < req.generation) {
                    pendingTune = null;
                }
            }
        }

        Object api = getRemoteApi();
        if (api == null) {
            queueTuneWhileBinding(req);
            return false;
        }
        return dispatchTune(req);
    }

    public List<OemStationRepository.Station> getRadioList(int band) {
        if (band != FmAmTunerController.BAND_FM && band != FmAmTunerController.BAND_AM) return null;
        if (Looper.myLooper() == Looper.getMainLooper()) return null;

        Object api = getRemoteApi();
        if (api == null) return null;

        try {
            Class<?> remoteInterface = loadFactoryClass(REMOTE_INTERFACE);
            Class<?> radioTypeClass = loadFactoryClass(RADIO_TYPE);
            String enumName = (band == FmAmTunerController.BAND_FM) ? "FM" : "AM";
            Object radioType = radioTypeClass.getMethod("valueOf", String.class).invoke(null, enumName);
            int typeVal = (radioType instanceof Enum<?>) ? ((Enum<?>) radioType).ordinal() : (band == FmAmTunerController.BAND_FM ? 0 : 1);

            Method listMethod = remoteInterface.getMethod("getRadioList", int.class);
            Object raw = listMethod.invoke(api, typeVal);
            if (!(raw instanceof Iterable<?>)) return null;

            Map<Integer, OemStationRepository.Station> found = new LinkedHashMap<>();
            for (Object item : (Iterable<?>) raw) {
                if (item == null) continue;
                Integer freq = readIntGetter(item, "getFrequency", "getFreq", "getFrequencyKhz");
                if (freq == null) continue;
                if (band == FmAmTunerController.BAND_FM && (freq < 87500 || freq > 108000)) continue;
                if (band == FmAmTunerController.BAND_AM && (freq < 531 || freq > 1629)) continue;

                String name = readStringGetter(item, "getRadioName", "getName", "getStationName", "getTitle");
                boolean fav = readBoolGetter(item, "getIsCollected", "isCollected", "isFavorite", "getFavorite");
                found.put(freq, new OemStationRepository.Station(name, freq, fav));
            }
            return new ArrayList<>(found.values());
        } catch (Throwable t) {
            notifyStatus("تعذرت قراءة قائمة محطات المصنع: " + t.getClass().getSimpleName());
            return null;
        }
    }

    private void queueTuneWhileBinding(PendingTune req) {
        boolean flushNow = false;
        synchronized (tuneDispatchLock) {
            if (req.generation != tuneGeneration.get()) return;
            synchronized (lock) {
                if (!isBound) return;
                pendingTune = req;
                flushNow = remoteBinder != null && isBinderAliveSafe(remoteBinder);
            }
        }
        if (flushNow) flushPendingTune();
    }

    private void flushPendingTune() {
        PendingTune req;
        synchronized (lock) {
            if (pendingTune == null || !isBinderAliveSafe(remoteBinder)) return;
            req = pendingTune;
            pendingTune = null;
        }
        dispatchTune(req);
    }

    private boolean dispatchTune(final PendingTune req) {
        synchronized (tuneDispatchLock) {
            if (req.generation != tuneGeneration.get()) return false;
            synchronized (lock) {
                if (pendingTune != null && pendingTune.generation == req.generation) pendingTune = null;
            }
        }
        try {
            tuneExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (req.generation != tuneGeneration.get()) return;
                    Object api = getRemoteApi();
                    if (api == null || req.generation != tuneGeneration.get()) return;
                    invokeFactoryTune(req, api);
                }
            });
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean invokeFactoryTune(PendingTune req, Object api) {
        try {
            if (req.generation != tuneGeneration.get()) return false;
            IBinder binderNow;
            synchronized (lock) {
                binderNow = remoteBinder;
            }
            if (!isBinderAliveSafe(binderNow)) return false;

            Class<?> remoteInterface = loadFactoryClass(REMOTE_INTERFACE);
            Class<?> radioInfoClass = loadFactoryClass(LOCAL_RADIO_INFO);
            Class<?> radioTypeClass = loadFactoryClass(RADIO_TYPE);

            String enumName = (req.band == FmAmTunerController.BAND_FM) ? "FM" : "AM";
            Object radioType = radioTypeClass.getMethod("valueOf", String.class).invoke(null, enumName);
            Object info = radioInfoClass.getConstructor(radioTypeClass).newInstance(radioType);
            radioInfoClass.getMethod("setFrequency", int.class).invoke(info, req.frequencyKhz);

            String label = (req.band == FmAmTunerController.BAND_FM)
                    ? String.format(Locale.US, "FM %.1f MHz", req.frequencyKhz / 1000.0)
                    : ("AM " + req.frequencyKhz + " kHz");
            tryInvoke(info, "setCurrentProgram", String.class, label);
            tryInvoke(info, "setRadioName", String.class, label);
            tryInvoke(info, "setIsCollected", int.class, 0);

            if (req.generation != tuneGeneration.get()) return false;
            remoteInterface.getMethod("playLocalRadio", radioInfoClass).invoke(api, info);
            return true;
        } catch (Throwable t) {
            notifyStatus("فشل إرسال التردد عبر RadioService: " + t.getClass().getSimpleName());
            return false;
        }
    }

    private Object getRemoteApi() {
        IBinder binderNow;
        synchronized (lock) {
            binderNow = remoteBinder;
            if (isBinderAliveSafe(binderNow) && remoteApi != null) return remoteApi;
        }
        if (isBinderAliveSafe(binderNow)) {
            Object proxy = createRemoteProxy(binderNow);
            synchronized (lock) {
                if (remoteBinder == binderNow) remoteApi = proxy;
            }
            return proxy;
        }

        CountDownLatch latch = ensureBindingStarted();
        if (latch == null || Looper.myLooper() == Looper.getMainLooper()) return null;

        ServiceConnection waitingConn;
        synchronized (lock) {
            waitingConn = serviceConnection;
        }
        boolean signaled = false;
        try {
            signaled = latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!signaled) {
            invalidateBinding(waitingConn, true);
            return null;
        }
        IBinder connected;
        synchronized (lock) {
            connected = remoteBinder;
        }
        if (!isBinderAliveSafe(connected)) return null;
        Object proxy = createRemoteProxy(connected);
        synchronized (lock) {
            if (remoteBinder == connected) remoteApi = proxy;
        }
        return proxy;
    }

    private CountDownLatch ensureBindingStarted() {
        ServiceConnection stale = null;
        CountDownLatch latch;
        synchronized (lock) {
            if (isBinderAliveSafe(remoteBinder)) return null;
            if (remoteBinder != null) {
                stale = serviceConnection;
                resetBindingLocked(false);
            }
            if (lastTimeoutAtMs != 0L && (SystemClock.uptimeMillis() - lastTimeoutAtMs) < TIMEOUT_BACKOFF_MS) {
                return null;
            }
            if (!isBound) {
                bindLatch = new CountDownLatch(1);
                final long gen = bindingGeneration.incrementAndGet();
                ServiceConnection conn = createConnection(gen);
                serviceConnection = conn;
                Intent intent = new Intent(OEM_ACTION).setComponent(new ComponentName(OEM_PACKAGE, OEM_SERVICE));
                boolean accepted = false;
                try {
                    isBound = true;
                    accepted = appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE);
                } catch (Throwable ignored) {}
                if (!accepted) {
                    resetBindingLocked(true);
                    return null;
                }
            }
            latch = bindLatch;
        }
        if (stale != null) {
            try { appContext.unbindService(stale); } catch (Throwable ignored) {}
        }
        return latch;
    }

    private ServiceConnection createConnection(final long gen) {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                boolean unbindEmpty = false;
                synchronized (lock) {
                    if (!isBound || serviceConnection != this || bindingGeneration.get() != gen) return;
                    if (service == null) {
                        resetBindingLocked(true);
                        unbindEmpty = true;
                    } else {
                        remoteBinder = service;
                        remoteApi = null;
                        lastTimeoutAtMs = 0L;
                        if (bindLatch != null) bindLatch.countDown();
                    }
                }
                if (unbindEmpty) {
                    try { appContext.unbindService(this); } catch (Throwable ignored) {}
                    return;
                }
                notifyStatus("متصل بخدمة راديو المصنع");
                if (pendingTune != null) {
                    tuneExecutor.execute(new Runnable() {
                        @Override public void run() { flushPendingTune(); }
                    });
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                synchronized (lock) {
                    if (!isBound || serviceConnection != this || bindingGeneration.get() != gen) return;
                    remoteBinder = null;
                    remoteApi = null;
                    if (bindLatch != null) bindLatch.countDown();
                    bindLatch = new CountDownLatch(1);
                }
            }

            @Override
            public void onBindingDied(ComponentName name) {
                handleDeadConn(this, gen);
            }

            @Override
            public void onNullBinding(ComponentName name) {
                handleDeadConn(this, gen);
            }
        };
    }

    private void handleDeadConn(ServiceConnection conn, long gen) {
        boolean unbind = false;
        synchronized (lock) {
            if (isBound && serviceConnection == conn && bindingGeneration.get() == gen) {
                resetBindingLocked(true);
                unbind = true;
            }
        }
        if (unbind) {
            try { appContext.unbindService(conn); } catch (Throwable ignored) {}
        }
    }

    private void invalidateBinding(ServiceConnection expected, boolean recordTimeout) {
        ServiceConnection toUnbind = null;
        synchronized (lock) {
            if (expected != null && serviceConnection != expected) return;
            if (recordTimeout && remoteBinder != null) return;
            toUnbind = serviceConnection;
            resetBindingLocked(recordTimeout);
        }
        if (toUnbind != null) {
            try { appContext.unbindService(toUnbind); } catch (Throwable ignored) {}
        }
    }

    private void resetBindingLocked(boolean recordTimeout) {
        isBound = false;
        serviceConnection = null;
        remoteBinder = null;
        remoteApi = null;
        if (bindLatch != null) bindLatch.countDown();
        bindLatch = null;
        bindingGeneration.incrementAndGet();
        lastTimeoutAtMs = recordTimeout ? SystemClock.uptimeMillis() : 0L;
    }

    private Object createRemoteProxy(IBinder binder) {
        try {
            Class<?> stub = loadFactoryClass(REMOTE_STUB);
            return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Class<?> loadFactoryClass(String name) throws Exception {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {}
        if (factoryCodeContext == null) {
            factoryCodeContext = appContext.createPackageContext(
                    OEM_PACKAGE, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        }
        return Class.forName(name, true, factoryCodeContext.getClassLoader());
    }

    private void notifyStatus(String msg) {
        if (statusCallback != null) statusCallback.onStatus(msg);
    }

    private static boolean isBinderAliveSafe(IBinder b) {
        try { return b != null && b.isBinderAlive(); } catch (Throwable ignored) { return false; }
    }

    private static void tryInvoke(Object target, String name, Class<?> paramType, Object arg) {
        try { target.getClass().getMethod(name, paramType).invoke(target, arg); } catch (Throwable ignored) {}
    }

    private static Integer readIntGetter(Object target, String... names) {
        for (String n : names) {
            try {
                Object val = target.getClass().getMethod(n).invoke(target);
                if (val instanceof Number) return ((Number) val).intValue();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String readStringGetter(Object target, String... names) {
        for (String n : names) {
            try {
                Object val = target.getClass().getMethod(n).invoke(target);
                if (val instanceof String) return ((String) val).trim();
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static boolean readBoolGetter(Object target, String... names) {
        for (String n : names) {
            try {
                Object val = target.getClass().getMethod(n).invoke(target);
                if (val instanceof Boolean) return (Boolean) val;
                if (val instanceof Number) return ((Number) val).intValue() != 0;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public void release() {
        cancelPendingTune();
        ServiceConnection toUnbind;
        synchronized (lock) {
            toUnbind = serviceConnection;
            resetBindingLocked(false);
        }
        tuneExecutor.shutdownNow();
        if (toUnbind != null) {
            try { appContext.unbindService(toUnbind); } catch (Throwable ignored) {}
        }
    }
}
