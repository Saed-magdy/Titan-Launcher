package com.dongfeng.shinecockpit.shinewave.radio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hardware client for the vehicle's qg.tuner_service (ITunerService AIDL + raw Binder fallback).
 * Supports FM (87.5-108.0 MHz, kHz*1000) and AM (531-1629 kHz), seek up/down, mute, and tuner status.
 */
public final class FmAmTunerController {
    public static final int BAND_FM = 1;
    public static final int BAND_AM = 0;

    private static final String SERVICE_NAME = "qg.tuner_service";
    private static final String DESCRIPTOR = "com.qinggan.tuner.ITunerService";
    private static final String FACTORY_ACTION = "com.qinggan.tuner.action.TUNER_SERVICE";
    private static final String FACTORY_PKG = "com.qinggan.tuner.service";

    private static final int TR_PLAY = IBinder.FIRST_CALL_TRANSACTION;
    private static final int TR_SEEK_SET = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int TR_SET_BAND = IBinder.FIRST_CALL_TRANSACTION + 2;
    private static final int TR_GET_BAND = IBinder.FIRST_CALL_TRANSACTION + 3;
    private static final int TR_SEEK_UP = IBinder.FIRST_CALL_TRANSACTION + 5;
    private static final int TR_SEEK_DOWN = IBinder.FIRST_CALL_TRANSACTION + 6;
    private static final int TR_SET_SEEK_STATUS = IBinder.FIRST_CALL_TRANSACTION + 7;
    private static final int TR_GET_TUNER_STATUS = IBinder.FIRST_CALL_TRANSACTION + 8;
    private static final int TR_SET_MUTE = IBinder.FIRST_CALL_TRANSACTION + 9;

    public interface Listener {
        void onBandChanged(int band);
        void onFrequencyChanged(int frequencyKhz);
        void onStatusMessage(String status);
        void onConnectionChanged(boolean connected);
    }

    public static final class TunerStatus {
        public final int frequencyKhz;
        public final int signalLevel;
        public final boolean stereo;
        public final boolean validStation;

        public TunerStatus(int frequencyKhz, int signalLevel, boolean stereo, boolean validStation) {
            this.frequencyKhz = frequencyKhz;
            this.signalLevel = signalLevel;
            this.stereo = stereo;
            this.validStation = validStation;
        }
    }

    private final Context appContext;
    private final Listener listener;
    private final FactoryRadioBridge factoryBridge;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object commandLock = new Object();

    private volatile IBinder binder;
    private volatile Object tunerApi;
    private volatile int currentBand = BAND_FM;
    private volatile int currentFreqKhz = 87500;
    private volatile boolean boundRequested = false;

    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service == null) return;
            synchronized (commandLock) {
                binder = service;
                tunerApi = createTunerApi(service);
            }
            if (listener != null) listener.onConnectionChanged(true);
            refreshCurrentStateAsync();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (commandLock) {
                binder = null;
                tunerApi = null;
            }
            if (listener != null) listener.onConnectionChanged(false);
        }
    };

    public FmAmTunerController(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        this.factoryBridge = new FactoryRadioBridge(appContext, new FactoryRadioBridge.StatusCallback() {
            @Override
            public void onStatus(String message) {
                if (FmAmTunerController.this.listener != null) {
                    FmAmTunerController.this.listener.onStatusMessage(message);
                }
            }
        });
    }

    public void connectAsync() {
        factoryBridge.prewarm();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (connectSync()) {
                    if (listener != null) listener.onConnectionChanged(true);
                    readCurrentStateInternal();
                }
            }
        });
    }

    public void tuneAsync(final int band, final int frequencyKhz) {
        currentBand = band;
        currentFreqKhz = clampFrequency(band, frequencyKhz);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                connectSync();
                factoryBridge.cancelPendingTune();
                setBandInternal(currentBand);
                boolean sentViaBridge = factoryBridge.playLocalRadio(currentBand, currentFreqKhz);
                if (!sentViaBridge) {
                    sendTuneDirect(currentFreqKhz);
                } else {
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            verifyAndFallbackTune(currentBand, currentFreqKhz);
                        }
                    }, 900L);
                }
                if (listener != null) listener.onFrequencyChanged(currentFreqKhz);
            }
        });
    }

    public void seekAsync(final boolean up) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                connectSync();
                factoryBridge.cancelPendingTune();
                setSeekStatusDirect(1);
                boolean ok = transactSimple(up ? TR_SEEK_UP : TR_SEEK_DOWN, up ? "seekUp" : "seekDown");
                if (ok) {
                    if (listener != null) listener.onStatusMessage(up ? "جاري البحث للأعلى…" : "جاري البحث للأسفل…");
                    scheduleReadbackSeries();
                } else {
                    if (listener != null) listener.onStatusMessage("موالف السيارة غير متصل");
                }
            }
        });
    }

    public void setMuteAsync(final boolean muted) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (tunerApi != null) {
                    try { invokeApi("setMute", muted); return; } catch (Throwable ignored) {}
                }
                transactIntArg(TR_SET_MUTE, muted ? 1 : 0);
            }
        });
    }

    public void refreshCurrentStateAsync() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                connectSync();
                readCurrentStateInternal();
            }
        });
    }

    public FactoryRadioBridge getFactoryBridge() {
        return factoryBridge;
    }

    public int getCurrentBand() {
        return currentBand;
    }

    public int getCurrentFreqKhz() {
        return currentFreqKhz;
    }

    private void verifyAndFallbackTune(final int band, final int freqKhz) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                TunerStatus st = readTunerStatusInternal();
                if (st == null || st.frequencyKhz != freqKhz) {
                    setBandInternal(band);
                    sendTuneDirect(freqKhz);
                }
            }
        });
    }

    private void scheduleReadbackSeries() {
        long[] delays = new long[]{450L, 1200L, 2500L};
        for (long d : delays) {
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    worker.execute(new Runnable() {
                        @Override public void run() { readCurrentStateInternal(); }
                    });
                }
            }, d);
        }
    }

    private boolean connectSync() {
        synchronized (commandLock) {
            if (binder != null && isAlive(binder)) return true;
        }
        IBinder found = findServiceBinder();
        if (found != null) {
            synchronized (commandLock) {
                binder = found;
                tunerApi = createTunerApi(found);
            }
            return true;
        }
        if (!boundRequested) {
            try {
                Intent i = new Intent(FACTORY_ACTION).setPackage(FACTORY_PKG);
                try { appContext.startService(i); } catch (Throwable ignored) {}
                boundRequested = appContext.bindService(i, serviceConn, Context.BIND_AUTO_CREATE);
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private IBinder findServiceBinder() {
        String[] managers = new String[]{"com.qinggan.os.ServiceManager", "android.os.ServiceManager"};
        for (String clsName : managers) {
            try {
                Class<?> cls = Class.forName(clsName);
                Method m = cls.getDeclaredMethod("getService", String.class);
                m.setAccessible(true);
                Object res = m.invoke(null, SERVICE_NAME);
                if (res instanceof IBinder) return (IBinder) res;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private Object createTunerApi(IBinder b) {
        try {
            Class<?> stub = Class.forName("com.qinggan.tuner.ITunerService$Stub");
            return stub.getMethod("asInterface", IBinder.class).invoke(null, b);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void setBandInternal(int band) {
        if (tunerApi != null) {
            try { invokeApi("setBand", band); if (listener != null) listener.onBandChanged(band); return; }
            catch (Throwable ignored) {}
        }
        if (transactIntArg(TR_SET_BAND, band) && listener != null) {
            listener.onBandChanged(band);
        }
    }

    private void setSeekStatusDirect(int status) {
        if (tunerApi != null) {
            try { invokeApi("setSeekStatus", status); return; } catch (Throwable ignored) {}
        }
        transactIntArg(TR_SET_SEEK_STATUS, status);
    }

    private boolean sendTuneDirect(int freqKhz) {
        setSeekStatusDirect(1);
        boolean ok;
        if (tunerApi != null) {
            try {
                invokeApi("play", freqKhz);
                ok = true;
            } catch (Throwable t) {
                ok = transactIntArg(TR_PLAY, freqKhz);
            }
        } else {
            ok = transactIntArg(TR_PLAY, freqKhz);
        }
        if (ok && listener != null) {
            listener.onFrequencyChanged(freqKhz);
            listener.onStatusMessage("تم ضبط التردد");
        }
        return ok;
    }

    private void readCurrentStateInternal() {
        Integer band = readBandInternal();
        TunerStatus st = readTunerStatusInternal();
        if (band != null && (band == BAND_FM || band == BAND_AM)) {
            currentBand = band;
            if (listener != null) listener.onBandChanged(band);
        }
        if (st != null && isValidFreq(currentBand, st.frequencyKhz)) {
            currentFreqKhz = st.frequencyKhz;
            if (listener != null) {
                listener.onFrequencyChanged(st.frequencyKhz);
                listener.onStatusMessage(st.validStation ? (st.stereo ? "إشارة قوية • ستيريو" : "محطة متاحة") : "بانتظار إشارة");
            }
        }
    }

    private Integer readBandInternal() {
        if (tunerApi != null) {
            try {
                Object val = invokeApi("getBand");
                if (val instanceof Number) return ((Number) val).intValue();
            } catch (Throwable ignored) {}
        }
        IBinder b = binder;
        if (b == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!b.transact(TR_GET_BAND, data, reply, 0)) return null;
            reply.readException();
            return reply.readInt();
        } catch (Throwable ignored) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private TunerStatus readTunerStatusInternal() {
        if (tunerApi != null) {
            try {
                Object st = invokeApi("getTunerStatus");
                if (st != null) {
                    int freq = readInt(st, "getFrequency", 0);
                    int level = readInt(st, "getLevel", 0);
                    boolean stereo = readInt(st, "getStereo", 0) != 0;
                    boolean valid = readBool(st, "isValidStation");
                    return new TunerStatus(freq, level, stereo, valid);
                }
            } catch (Throwable ignored) {}
        }
        IBinder b = binder;
        if (b == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!b.transact(TR_GET_TUNER_STATUS, data, reply, 0)) return null;
            reply.readException();
            if (reply.readInt() == 0) return null;
            int freq = reply.readInt();
            boolean valid = reply.readInt() != 0;
            int level = reply.readInt();
            reply.readInt(); // usn
            reply.readInt(); // wam
            reply.readInt(); // fof
            boolean stereo = reply.readInt() != 0;
            return new TunerStatus(freq, level, stereo, valid);
        } catch (Throwable ignored) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean transactIntArg(int code, int arg) {
        IBinder b = binder;
        if (b == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(arg);
            if (!b.transact(code, data, reply, 0)) return false;
            reply.readException();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private boolean transactSimple(int code, String apiMethod) {
        if (tunerApi != null && apiMethod != null) {
            try {
                invokeApi(apiMethod);
                return true;
            } catch (Throwable ignored) {}
        }
        IBinder b = binder;
        if (b == null) return false;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!b.transact(code, data, reply, 0)) return false;
            reply.readException();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private Object invokeApi(String methodName, Object... args) throws Exception {
        if (tunerApi == null) throw new IllegalStateException("tunerApi null");
        for (Method m : tunerApi.getClass().getMethods()) {
            if (!methodName.equals(m.getName()) || m.getParameterTypes().length != args.length) continue;
            return m.invoke(tunerApi, args);
        }
        throw new NoSuchMethodException("ITunerService." + methodName);
    }

    private static int readInt(Object target, String name, int def) {
        try {
            Object v = target.getClass().getMethod(name).invoke(target);
            return v instanceof Number ? ((Number) v).intValue() : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static boolean readBool(Object target, String name) {
        try {
            Object v = target.getClass().getMethod(name).invoke(target);
            return (v instanceof Boolean && (Boolean) v) || (v instanceof Number && ((Number) v).intValue() != 0);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static int clampFrequency(int band, int freqKhz) {
        if (band == BAND_FM) return Math.max(87500, Math.min(108000, freqKhz));
        return Math.max(531, Math.min(1629, freqKhz));
    }

    private static boolean isValidFreq(int band, int freqKhz) {
        return band == BAND_FM ? (freqKhz >= 87500 && freqKhz <= 108000) : (freqKhz >= 531 && freqKhz <= 1629);
    }

    private static boolean isAlive(IBinder b) {
        try { return b != null && b.isBinderAlive(); } catch (Throwable ignored) { return false; }
    }

    public void release() {
        mainHandler.removeCallbacksAndMessages(null);
        if (boundRequested) {
            try { appContext.unbindService(serviceConn); } catch (Throwable ignored) {}
            boundRequested = false;
        }
        factoryBridge.release();
        worker.shutdownNow();
    }
}
