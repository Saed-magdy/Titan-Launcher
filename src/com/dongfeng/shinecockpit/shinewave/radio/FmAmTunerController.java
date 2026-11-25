package com.dongfeng.shinecockpit.shinewave.radio;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hardware client for the vehicle's qg.tuner_service (ITunerService AIDL + raw Binder fallback).
 * Mirrors the proven factory sequences from Shine Wave's FmAmController:
 * FM (87500-108000 kHz, 100 kHz step) and AM (531-1629 kHz, 9 kHz step, default 999),
 * OEM boundary-retry seek, seekSet(min) + seekDown auto-scan, mute, and tuner status readback.
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

    public interface AutoScanListener {
        void onScanProgress(int currentFreqKhz, int stationsFound);
        void onStationFound(int freqKhz, String name);
        void onScanComplete(int totalStations);
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
    private final AtomicLong tuneGen = new AtomicLong(0L);

    private volatile IBinder binder;
    private volatile Object tunerApi;
    private volatile int currentBand = BAND_FM;
    private volatile int currentFreqKhz = 87500;
    private volatile boolean boundRequested = false;
    private volatile boolean autoScanning = false;

    private Integer pendingBand = null;
    private Integer pendingFreqKhz = null;
    private long pendingGen = -1L;

    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service == null) return;
            final Integer reqBand;
            final Integer reqFreq;
            final long reqGen;
            synchronized (commandLock) {
                binder = service;
                tunerApi = createTunerApi(service);
                linkDeathLocked(service);
                reqBand = pendingBand;
                reqFreq = pendingFreqKhz;
                reqGen = pendingGen;
                pendingBand = null;
                pendingFreqKhz = null;
            }
            if (listener != null) listener.onConnectionChanged(true);
            if (reqBand != null && reqFreq != null && reqGen == tuneGen.get()) {
                executeTuneWorker(reqBand, reqFreq, reqGen);
            } else {
                refreshCurrentStateAsync();
            }
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
        executeWorker(new Runnable() {
            @Override
            public void run() {
                if (connectSync()) {
                    if (listener != null) listener.onConnectionChanged(true);
                    readCurrentStateInternal();
                }
            }
        });
    }

    public void rememberDesiredBandAndFreq(int band, int frequencyKhz) {
        if (band != BAND_FM && band != BAND_AM) return;
        currentBand = band;
        currentFreqKhz = clampFrequency(band, frequencyKhz);
    }

    public void tuneAsync(final int band, final int frequencyKhz) {
        final int targetBand = (band == BAND_AM) ? BAND_AM : BAND_FM;
        final int targetFreq = clampFrequency(targetBand, frequencyKhz);
        final long gen = tuneGen.incrementAndGet();
        currentBand = targetBand;
        currentFreqKhz = targetFreq;
        synchronized (commandLock) {
            pendingBand = targetBand;
            pendingFreqKhz = targetFreq;
            pendingGen = gen;
        }
        executeTuneWorker(targetBand, targetFreq, gen);
    }

    private void executeTuneWorker(final int targetBand, final int targetFreq, final long gen) {
        executeWorker(new Runnable() {
            @Override
            public void run() {
                if (gen != tuneGen.get()) return;
                boolean connected = connectSync();
                if (!connected || gen != tuneGen.get()) return;
                synchronized (commandLock) {
                    if (pendingGen == gen) {
                        pendingBand = null;
                        pendingFreqKhz = null;
                    }
                }

                factoryBridge.cancelPendingTune();
                setBandInternal(targetBand);
                boolean sentViaBridge = factoryBridge.playLocalRadio(targetBand, targetFreq);
                if (!sentViaBridge) {
                    // Cancel any async pending tune in FactoryRadioBridge so a late bind
                    // cannot overwrite our direct ITunerService command.
                    factoryBridge.cancelPendingTune();
                    sendTuneDirect(targetFreq);
                    scheduleTuneVerify(targetBand, targetFreq, gen, 900L, false);
                } else {
                    if (listener != null) listener.onFrequencyChanged(targetFreq);
                    scheduleTuneVerify(targetBand, targetFreq, gen, 750L, true);
                    scheduleTuneVerify(targetBand, targetFreq, gen, 1800L, true);
                }
            }
        });
    }

    public void seekAsync(final boolean up) {
        final long gen = tuneGen.incrementAndGet();
        final int expectedBand = currentBand;
        final int beforeFreq = currentFreqKhz;
        executeWorker(new Runnable() {
            @Override
            public void run() {
                if (gen != tuneGen.get()) return;
                if (!connectSync()) {
                    if (listener != null) listener.onStatusMessage("موالف السيارة غير متصل");
                    return;
                }
                factoryBridge.cancelPendingTune();
                setSeekStatusDirect(1);

                String method = up ? "seekUp" : "seekDown";
                int txCode = up ? TR_SEEK_UP : TR_SEEK_DOWN;
                Integer returnedFreq = invokeSeekCommand(txCode, method);
                boolean ok = (returnedFreq != null);

                // Match factory LocalRadioEngine.seekPre(): retry once from max frequency
                // if seekUp returns a frequency outside this band's valid range.
                if (ok && up && !isOemFrequencyValid(expectedBand, returnedFreq)) {
                    int maxFreq = (expectedBand == BAND_FM) ? 108000 : 1629;
                    if (sendPlayOnly(maxFreq)) {
                        Integer retryFreq = invokeSeekCommand(TR_SEEK_UP, "seekUp");
                        if (retryFreq != null) {
                            returnedFreq = retryFreq;
                        }
                    }
                }

                if (ok) {
                    if (listener != null) {
                        listener.onStatusMessage(up ? "جاري البحث للأعلى…" : "جاري البحث للأسفل…");
                    }
                    if (returnedFreq != null && isValidFreq(expectedBand, returnedFreq) && returnedFreq != beforeFreq) {
                        applyConfirmedState(expectedBand, returnedFreq);
                    }
                    scheduleSeekReadbackSeries(expectedBand, beforeFreq, gen);
                } else {
                    if (listener != null) listener.onStatusMessage("موالف السيارة غير متصل");
                }
            }
        });
    }

    public synchronized void startAutoScanAndSave(final OemStationRepository repo, final AutoScanListener scanListener) {
        if (autoScanning) return;
        final long gen = tuneGen.incrementAndGet();
        autoScanning = true;
        final int scanBand = currentBand;
        final int restoreFreq = clampFrequency(scanBand, currentFreqKhz);

        executeWorker(new Runnable() {
            @Override
            public void run() {
                Set<Integer> discovered = new LinkedHashSet<>();
                try {
                    if (!connectSync()) {
                        return;
                    }
                    factoryBridge.cancelPendingTune();
                    setBandInternal(scanBand);

                    int minFreq = (scanBand == BAND_FM) ? 87500 : 531;
                    int maxFreq = (scanBand == BAND_FM) ? 108000 : 1629;

                    // Mirror factory LocalRadioEngine.scanChannel(): seekSet(min) first.
                    boolean startConfirmed = false;
                    if (sendSeekSet(minFreq)) {
                        for (int w = 0; w < 10 && autoScanning && gen == tuneGen.get(); w++) {
                            sleepQuietly(150L);
                            TunerStatus st = readTunerStatusInternal();
                            if (st != null && st.frequencyKhz == minFreq) {
                                startConfirmed = true;
                                break;
                            }
                        }
                    }
                    if (!startConfirmed && autoScanning && gen == tuneGen.get()) {
                        sendTuneDirect(minFreq);
                        sleepQuietly(300L);
                    }

                    TunerStatus initialSt = readTunerStatusInternal();
                    if (initialSt != null && initialSt.validStation && initialSt.frequencyKhz == minFreq) {
                        discovered.add(minFreq);
                    }

                    Integer anchorFreq = discovered.isEmpty() ? null : minFreq;
                    boolean sawAwayFromAnchor = false;
                    Integer prevReturned = null;
                    int consecutiveUnchanged = 0;
                    int steps = 0;
                    long startedAt = System.currentTimeMillis();

                    while (autoScanning && gen == tuneGen.get()
                            && (System.currentTimeMillis() - startedAt) < 120_000L
                            && steps < 210) {
                        steps++;
                        Integer returned = invokeSeekCommand(TR_SEEK_DOWN, "seekDown");
                        if (returned == null && steps == 1) {
                            setSeekStatusDirect(1);
                            returned = invokeSeekCommand(TR_SEEK_DOWN, "seekDown");
                        }
                        if (!autoScanning || gen != tuneGen.get() || returned == null) break;

                        int freq = returned;
                        if (freq < minFreq || freq > maxFreq) break;

                        // Confirm readback matches or settles on candidate frequency.
                        TunerStatus st = null;
                        for (int w = 0; w < 6 && autoScanning && gen == tuneGen.get(); w++) {
                            st = readTunerStatusInternal();
                            if (st != null && st.frequencyKhz == freq) break;
                            sleepQuietly(130L);
                        }
                        if (!autoScanning || gen != tuneGen.get()) break;

                        if (prevReturned != null && prevReturned == freq) {
                            consecutiveUnchanged++;
                        } else {
                            consecutiveUnchanged = 0;
                        }
                        if (anchorFreq == null) {
                            anchorFreq = freq;
                        }
                        if (freq != anchorFreq) {
                            sawAwayFromAnchor = true;
                        }
                        if (freq == anchorFreq && sawAwayFromAnchor && (prevReturned == null || prevReturned != freq)) {
                            break;
                        }
                        if (consecutiveUnchanged >= 2) {
                            break;
                        }
                        prevReturned = freq;

                        if (!discovered.contains(freq)) {
                            discovered.add(freq);
                            final List<Integer> listCopy = new ArrayList<>(discovered);
                            repo.saveScannedStations(scanBand, listCopy);
                            final String name = formatBandFreqName(scanBand, freq);
                            final int fKhz = freq;
                            final int fCount = listCopy.size();
                            if (listener != null) {
                                listener.onFrequencyChanged(fKhz);
                            }
                            if (scanListener != null) {
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        scanListener.onStationFound(fKhz, name);
                                        scanListener.onScanProgress(fKhz, fCount);
                                    }
                                });
                            }
                        }
                    }

                    // Restore original frequency (or first discovered station if original was invalid).
                    if (gen == tuneGen.get()) {
                        int targetRestore = restoreFreq;
                        if (!isValidFreq(scanBand, targetRestore) && !discovered.isEmpty()) {
                            targetRestore = discovered.iterator().next();
                        }
                        setBandInternal(scanBand);
                        sendTuneDirect(targetRestore);
                        currentBand = scanBand;
                        currentFreqKhz = targetRestore;
                    }
                } finally {
                    autoScanning = false;
                    final int total = discovered.size();
                    if (scanListener != null) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                scanListener.onScanComplete(total);
                            }
                        });
                    }
                }
            }
        });
    }

    public void stopAutoScan() {
        autoScanning = false;
    }

    public boolean isAutoScanning() {
        return autoScanning;
    }

    public void setMuteAsync(final boolean muted) {
        executeWorker(new Runnable() {
            @Override
            public void run() {
                if (!connectSync()) return;
                if (tunerApi != null) {
                    try {
                        invokeApiResult("setMute", muted);
                        return;
                    } catch (Throwable ignored) {}
                }
                transactIntArg(TR_SET_MUTE, muted ? 1 : 0);
            }
        });
    }

    public void refreshCurrentStateAsync() {
        final long gen = tuneGen.get();
        executeWorker(new Runnable() {
            @Override
            public void run() {
                if (autoScanning || gen != tuneGen.get()) return;
                if (!connectSync()) return;
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

    private void scheduleTuneVerify(final int band, final int freqKhz, final long gen,
                                    final long delayMs, final boolean fallbackOnMismatch) {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != tuneGen.get() || autoScanning) return;
                executeWorker(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != tuneGen.get() || autoScanning) return;
                        Integer b = readBandInternal();
                        TunerStatus st = readTunerStatusInternal();
                        if (b != null && b == band && st != null && st.frequencyKhz == freqKhz) {
                            applyConfirmedState(b, st.frequencyKhz);
                            if (listener != null) {
                                listener.onStatusMessage(st.validStation
                                        ? (st.stereo ? "إشارة قوية • ستيريو" : "محطة صالحة")
                                        : formatBandFreqName(band, freqKhz));
                            }
                        } else if (fallbackOnMismatch) {
                            setBandInternal(band);
                            sendTuneDirect(freqKhz);
                        }
                    }
                });
            }
        }, delayMs);
    }

    private void scheduleSeekReadbackSeries(final int expectedBand, final int beforeFreq, final long gen) {
        long[] delays = new long[]{400L, 900L, 1600L, 2600L};
        for (final long d : delays) {
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (gen != tuneGen.get() || autoScanning) return;
                    executeWorker(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != tuneGen.get() || autoScanning) return;
                            Integer b = readBandInternal();
                            TunerStatus st = readTunerStatusInternal();
                            int resolvedBand = (b != null && (b == BAND_FM || b == BAND_AM)) ? b : expectedBand;
                            if (st != null && isValidFreq(resolvedBand, st.frequencyKhz)) {
                                applyConfirmedState(resolvedBand, st.frequencyKhz);
                                if (listener != null && st.frequencyKhz != beforeFreq) {
                                    listener.onStatusMessage(st.validStation
                                            ? (st.stereo ? "إشارة قوية • ستيريو" : "محطة متاحة")
                                            : formatBandFreqName(resolvedBand, st.frequencyKhz));
                                }
                            }
                        }
                    });
                }
            }, d);
        }
    }

    private void applyConfirmedState(int band, int freqKhz) {
        boolean bandChanged = (currentBand != band);
        boolean freqChanged = (currentFreqKhz != freqKhz);
        currentBand = band;
        currentFreqKhz = freqKhz;
        if (bandChanged && listener != null) {
            listener.onBandChanged(band);
        }
        if (freqChanged && listener != null) {
            listener.onFrequencyChanged(freqKhz);
        }
    }

    private void executeWorker(Runnable r) {
        if (!worker.isShutdown()) {
            try {
                worker.execute(r);
            } catch (Throwable ignored) {}
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
                linkDeathLocked(found);
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

    private void linkDeathLocked(final IBinder target) {
        try {
            target.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    boolean lost = false;
                    synchronized (commandLock) {
                        if (binder == target) {
                            binder = null;
                            tunerApi = null;
                            lost = true;
                        }
                    }
                    if (lost && listener != null) {
                        listener.onConnectionChanged(false);
                    }
                }
            }, 0);
        } catch (Throwable ignored) {}
    }

    private IBinder findServiceBinder() {
        String[] managers = new String[]{"com.qinggan.os.ServiceManager", "android.os.ServiceManager"};
        for (String clsName : managers) {
            try {
                Class<?> cls = Class.forName(clsName);
                Method m;
                try {
                    m = cls.getDeclaredMethod("getService", String.class);
                } catch (NoSuchMethodException e) {
                    m = cls.getMethod("getService", String.class);
                }
                try { m.setAccessible(true); } catch (Throwable ignored) {}
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
            try {
                invokeApiResult("setBand", band);
                if (listener != null) listener.onBandChanged(band);
                return;
            } catch (Throwable ignored) {}
        }
        if (transactIntArg(TR_SET_BAND, band) && listener != null) {
            listener.onBandChanged(band);
        }
    }

    private void setSeekStatusDirect(int status) {
        if (tunerApi != null) {
            try {
                invokeApiResult("setSeekStatus", status);
                return;
            } catch (Throwable ignored) {}
        }
        transactIntArg(TR_SET_SEEK_STATUS, status);
    }

    private boolean sendSeekSet(int freqKhz) {
        if (tunerApi != null) {
            try {
                invokeApiResult("seekSet", freqKhz);
                return true;
            } catch (Throwable ignored) {}
        }
        return transactIntArg(TR_SEEK_SET, freqKhz);
    }

    private boolean sendPlayOnly(int freqKhz) {
        if (tunerApi != null) {
            try {
                if (findMatchingMethod("play", freqKhz) != null) {
                    invokeApiResult("play", freqKhz);
                    return true;
                }
                if (findMatchingMethod("setFrequency", freqKhz) != null && findMatchingMethod("play") != null) {
                    invokeApiResult("setFrequency", freqKhz);
                    invokeApiResult("play");
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return transactIntArg(TR_PLAY, freqKhz);
    }

    private boolean sendTuneDirect(int freqKhz) {
        setSeekStatusDirect(1);
        boolean ok = sendPlayOnly(freqKhz);
        if (ok && listener != null) {
            listener.onFrequencyChanged(freqKhz);
            listener.onStatusMessage("تم ضبط التردد");
        }
        return ok;
    }

    private Integer invokeSeekCommand(int txCode, String apiMethod) {
        if (tunerApi != null && apiMethod != null) {
            try {
                Object res = invokeApiResult(apiMethod);
                if (res instanceof Number) return ((Number) res).intValue();
                return -1;
            } catch (Throwable ignored) {}
        }
        return transactWithIntResult(txCode);
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
                listener.onStatusMessage(st.validStation
                        ? (st.stereo ? "إشارة قوية • ستيريو" : "محطة متاحة")
                        : "بانتظار إشارة");
            }
        }
    }

    private Integer readBandInternal() {
        if (tunerApi != null) {
            try {
                Object val = invokeApiResult("getBand");
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
                Object st = invokeApiResult("getTunerStatus");
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

    private Integer transactWithIntResult(int code) {
        IBinder b = binder;
        if (b == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if (!b.transact(code, data, reply, 0)) return null;
            reply.readException();
            if (reply.dataAvail() >= 4) {
                return reply.readInt();
            }
            return -1;
        } catch (Throwable ignored) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private Method findMatchingMethod(String methodName, Object... args) {
        Object api = tunerApi;
        if (api == null) return null;
        for (Method m : api.getClass().getMethods()) {
            if (!methodName.equals(m.getName())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length != args.length) continue;
            boolean match = true;
            for (int i = 0; i < params.length; i++) {
                Class<?> p = params[i];
                Object a = args[i];
                if (a == null) {
                    if (p.isPrimitive()) { match = false; break; }
                } else if (p.isInstance(a)) {
                    continue;
                } else if (p == int.class && a instanceof Integer) {
                    continue;
                } else if (p == boolean.class && a instanceof Boolean) {
                    continue;
                } else if (p == long.class && a instanceof Long) {
                    continue;
                } else {
                    match = false;
                    break;
                }
            }
            if (match) return m;
        }
        return null;
    }

    private Object invokeApiResult(String methodName, Object... args) throws Exception {
        if (tunerApi == null) throw new IllegalStateException("tunerApi null");
        Method m = findMatchingMethod(methodName, args);
        if (m == null) {
            throw new NoSuchMethodException("ITunerService." + methodName + "(" + args.length + ")");
        }
        try {
            return m.invoke(tunerApi, args);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getTargetException();
            if (cause instanceof Exception) throw (Exception) cause;
            throw ite;
        }
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
        if (band == BAND_FM) {
            int clamped = Math.max(87500, Math.min(108000, freqKhz));
            return 87500 + Math.round((clamped - 87500) / 100.0f) * 100;
        }
        int clamped = Math.max(531, Math.min(1629, freqKhz));
        return 531 + Math.round((clamped - 531) / 9.0f) * 9;
    }

    public static boolean isValidFreq(int band, int freqKhz) {
        return band == BAND_FM ? (freqKhz >= 87500 && freqKhz <= 108000) : (freqKhz >= 531 && freqKhz <= 1629);
    }

    private static boolean isOemFrequencyValid(int band, int freqKhz) {
        if (band == BAND_FM) return freqKhz >= 87500 && freqKhz <= 108000;
        return (freqKhz >= 144 && freqKhz <= 288) || (freqKhz >= 531 && freqKhz <= 1629);
    }

    private static String formatBandFreqName(int band, int freqKhz) {
        return (band == BAND_FM)
                ? String.format(Locale.US, "FM %.1f MHz", freqKhz / 1000.0)
                : ("AM " + freqKhz + " kHz");
    }

    private static boolean isAlive(IBinder b) {
        try { return b != null && b.isBinderAlive(); } catch (Throwable ignored) { return false; }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    public void release() {
        tuneGen.incrementAndGet();
        autoScanning = false;
        mainHandler.removeCallbacksAndMessages(null);
        if (boundRequested) {
            try { appContext.unbindService(serviceConn); } catch (Throwable ignored) {}
            boundRequested = false;
        }
        factoryBridge.release();
        worker.shutdownNow();
    }
}
