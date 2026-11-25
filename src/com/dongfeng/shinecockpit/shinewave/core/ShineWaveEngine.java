package com.dongfeng.shinecockpit.shinewave.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.dongfeng.shinecockpit.shinewave.bluetooth.AvrcpMetadataTracker;
import com.dongfeng.shinecockpit.shinewave.bluetooth.OnlineCoverArtFetcher;
import com.dongfeng.shinecockpit.shinewave.bluetooth.QingganBtController;
import com.dongfeng.shinecockpit.shinewave.external.ExternalPlayerRouter;
import com.dongfeng.shinecockpit.shinewave.internet.LocalStreamProxy;
import com.dongfeng.shinecockpit.shinewave.internet.RadioBrowserClient;
import com.dongfeng.shinecockpit.shinewave.internet.RadioStationStore;
import com.dongfeng.shinecockpit.shinewave.local.ArtworkCacheManager;
import com.dongfeng.shinecockpit.shinewave.local.LocalMusicScanner;
import com.dongfeng.shinecockpit.shinewave.player.NativeStreamPlayer;
import com.dongfeng.shinecockpit.shinewave.radio.DabBrowserController;
import com.dongfeng.shinecockpit.shinewave.radio.FmAmTunerController;
import com.dongfeng.shinecockpit.shinewave.radio.OemStationRepository;
import com.dongfeng.shinecockpit.shinewave.radio.RadioAudioPolicyRouter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Central in-process orchestrator for the integrated Shine Wave audio engine.
 * Coordinates Local/USB playback, Bluetooth A2DP/AVRCP, Car Radio (FM/AM/DAB),
 * Internet Radio, and filtered External Media Apps with direct 0ms IPC-free state updates.
 */
public final class ShineWaveEngine {
    private static final String TAG = "ShineWaveEngine";
    private static final String PREFS = "shine_wave_integrated_prefs";
    private static final String KEY_SOURCE = "selected_source";
    private static final String KEY_RADIO_TYPE = "radio_type";
    private static final String KEY_FM_BAND = "radio_fm_band";
    private static final String KEY_FM_FREQ = "radio_fm_freq_khz";
    private static final String KEY_LAST_FM_FREQ = "radio_last_fm_freq_khz";
    private static final String KEY_LAST_AM_FREQ = "radio_last_am_freq_khz";

    public interface Listener {
        void onStateChanged(JSONObject stateJson);
        void onRadioDataChanged(JSONObject radioJson);
    }

    public interface SeekUiCallback {
        void onSeekTick(int direction);
        void onSeekStopped();
    }

    private static volatile ShineWaveEngine instance;

    public static ShineWaveEngine getInstance(Context context) {
        ShineWaveEngine current = instance;
        if (current == null || current.bgExecutor.isShutdown()) {
            synchronized (ShineWaveEngine.class) {
                current = instance;
                if (current == null || current.bgExecutor.isShutdown()) {
                    current = new ShineWaveEngine(context.getApplicationContext());
                    instance = current;
                }
            }
        }
        return current;
    }

    private final Context appContext;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExecutor = Executors.newCachedThreadPool();
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();

    private final PlaybackStateModel stateModel = new PlaybackStateModel();
    private final ArtworkCacheManager artworkCache;
    private final LocalMusicScanner localScanner;
    private final NativeStreamPlayer streamPlayer;
    private final QingganBtController qingganBt;
    private final AvrcpMetadataTracker avrcpTracker;
    private final OnlineCoverArtFetcher onlineCoverFetcher;
    private final FmAmTunerController fmAmTuner;
    private final RadioAudioPolicyRouter radioPolicyRouter;
    private final OemStationRepository oemStationRepo;
    private final DabBrowserController dabController;
    private final RadioBrowserClient internetClient;
    private final RadioStationStore internetStore;
    private final ExternalPlayerRouter externalRouter;
    private final CallInterruptionManager callManager;

    private boolean pausedByCall = false;
    private boolean dabConnected = false;
    private JSONArray dabItemsJson = new JSONArray();
    private String lastExternalSignature = "";
    private int longSeekDir = 0;
    private long longSeekStartedAtMs = 0L;
    private SeekUiCallback seekUiCallback = null;

    private void executeBg(Runnable task) {
        if (task != null && !bgExecutor.isShutdown()) {
            try {
                bgExecutor.execute(task);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {}
        }
    }

    private final Runnable externalPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (stateModel.getSource() != AudioSourceType.EXTERNAL_APP) return;
            syncExternalSession(false);
            mainHandler.postDelayed(this, 1000L);
        }
    };

    private final Runnable radioSyncRunnable = new Runnable() {
        @Override
        public void run() {
            if (stateModel.getSource() != AudioSourceType.CAR_RADIO || "DAB".equals(stateModel.getRadioType())) {
                return;
            }
            if (!fmAmTuner.isAutoScanning()) {
                fmAmTuner.refreshCurrentStateAsync();
            }
            mainHandler.postDelayed(this, 3000L);
        }
    };

    private final Runnable longSeekRunnable = new Runnable() {
        @Override
        public void run() {
            if (longSeekDir == 0) return;
            if ((SystemClock.elapsedRealtime() - longSeekStartedAtMs) > 15000L) {
                stopLongSeek();
                return;
            }
            seekBySec(longSeekDir * 10L);
            if (seekUiCallback != null) {
                seekUiCallback.onSeekTick(longSeekDir);
            }
            mainHandler.postDelayed(this, 450L);
        }
    };

    private ShineWaveEngine(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        this.artworkCache = new ArtworkCacheManager(appContext);
        this.localScanner = new LocalMusicScanner(appContext);

        AudioSourceType savedSource = AudioSourceType.fromKey(prefs.getString(KEY_SOURCE, "BLUETOOTH"));
        stateModel.setSource(savedSource);
        int initBand = prefs.getInt(KEY_FM_BAND, FmAmTunerController.BAND_FM);
        int initFreq = getSavedFreqForBand(initBand);
        stateModel.setRadioState(
                prefs.getString(KEY_RADIO_TYPE, initBand == FmAmTunerController.BAND_AM ? "AM" : "FM"),
                initFreq,
                "جاهز"
        );

        this.onlineCoverFetcher = new OnlineCoverArtFetcher(artworkCache);

        this.streamPlayer = new NativeStreamPlayer(appContext, artworkCache, new NativeStreamPlayer.Listener() {
            @Override
            public void onPlayerStateChanged(NativeStreamPlayer.QueueItem item, boolean playing, long posMs, long durMs,
                                             RepeatMode repeatMode, boolean shuffle, String artSig, String artDataUrl) {
                stateModel.setRepeatMode(repeatMode);
                stateModel.setShuffleEnabled(shuffle);
                AudioSourceType curSrc = stateModel.getSource();
                if (curSrc == AudioSourceType.LOCAL_MUSIC || curSrc == AudioSourceType.INTERNET_RADIO) {
                    if (item != null) {
                        stateModel.setTrackInfo(item.title, item.artist, item.album, posMs / 1000L, durMs / 1000L, playing);
                        if (artDataUrl != null) {
                            stateModel.setArtwork(artSig, artDataUrl);
                        } else {
                            stateModel.clearArtwork();
                        }
                    } else {
                        stateModel.setTrackInfo("لا يوجد تشغيل حالي", "", "", 0L, 0L, false);
                        stateModel.clearArtwork();
                    }
                    emitState();
                } else {
                    emitState();
                }
            }
        });

        this.qingganBt = QingganBtController.getInstance(appContext);
        this.qingganBt.addListener(new QingganBtController.Listener() {
            @Override
            public void onVendorBtStateChanged(QingganBtController.BtState bt) {
                stateModel.setBtConnected(bt.connected);
                if (stateModel.getSource() == AudioSourceType.BLUETOOTH) {
                    stateModel.setPlaying(bt.playing);
                    emitState();
                }
            }
        });

        this.avrcpTracker = new AvrcpMetadataTracker(appContext, new AvrcpMetadataTracker.Callback() {
            @Override
            public void onAvrcpStateChanged(AvrcpMetadataTracker.AvrcpSnapshot snap) {
                if (stateModel.getSource() != AudioSourceType.BLUETOOTH) return;
                handleBluetoothSnapshot(snap);
            }
        });

        this.radioPolicyRouter = new RadioAudioPolicyRouter(appContext, new RadioAudioPolicyRouter.StatusCallback() {
            @Override
            public void onStatus(String message) {
                if (stateModel.getSource() == AudioSourceType.CAR_RADIO) {
                    stateModel.setRadioState(null, 0, message);
                    emitState();
                }
            }
        });

        this.fmAmTuner = new FmAmTunerController(appContext, new FmAmTunerController.Listener() {
            @Override
            public void onBandChanged(int band) {
                String rType = (band == FmAmTunerController.BAND_FM) ? "FM" : "AM";
                prefs.edit().putInt(KEY_FM_BAND, band).apply();
                if (!"DAB".equals(stateModel.getRadioType())) {
                    stateModel.setRadioState(rType, 0, null);
                    if (stateModel.getSource() == AudioSourceType.CAR_RADIO) {
                        updateCarRadioTitle();
                        emitState();
                        emitRadioDataAsync();
                    }
                }
            }

            @Override
            public void onFrequencyChanged(int frequencyKhz) {
                int activeBand = fmAmTuner.getCurrentBand();
                saveFreqForBand(activeBand, frequencyKhz);
                stateModel.setRadioState(null, frequencyKhz, null);
                if (stateModel.getSource() == AudioSourceType.CAR_RADIO) {
                    updateCarRadioTitle();
                    emitState();
                    emitRadioDataAsync();
                }
            }

            @Override
            public void onStatusMessage(String status) {
                stateModel.setRadioState(null, 0, status);
                if (stateModel.getSource() == AudioSourceType.CAR_RADIO) {
                    emitState();
                    emitRadioDataAsync();
                }
            }

            @Override
            public void onConnectionChanged(boolean connected) {
                if (stateModel.getSource() == AudioSourceType.CAR_RADIO) {
                    stateModel.setPlaying(connected);
                    emitState();
                    emitRadioDataAsync();
                }
            }
        });

        this.oemStationRepo = new OemStationRepository(appContext);
        this.dabController = new DabBrowserController(appContext, new DabBrowserController.Callback() {
            @Override
            public void onDabUpdated(boolean connected, String title, boolean playing, String status, JSONArray itemsJson) {
                dabConnected = connected;
                dabItemsJson = itemsJson != null ? itemsJson : new JSONArray();
                if (stateModel.getSource() == AudioSourceType.CAR_RADIO && "DAB".equals(stateModel.getRadioType())) {
                    stateModel.setTrackInfo(title, "DAB+ Digital Radio", "Car Tuner", 0L, 0L, playing);
                    stateModel.setRadioState("DAB", 0, status);
                    emitState();
                }
            }
        });

        this.internetClient = new RadioBrowserClient();
        this.internetStore = new RadioStationStore(appContext);
        this.externalRouter = new ExternalPlayerRouter(appContext, artworkCache);
        stateModel.setExternalApp(externalRouter.getSelectedPackage(), externalRouter.getSelectedLabel());

        this.callManager = new CallInterruptionManager(appContext, new CallInterruptionManager.Callback() {
            @Override
            public void onCallStarted() {
                mainHandler.post(new Runnable() {
                    @Override public void run() { handleCallStarted(); }
                });
            }

            @Override
            public void onCallEnded() {
                mainHandler.post(new Runnable() {
                    @Override public void run() { handleCallEnded(); }
                });
            }
        });
        this.callManager.start();
        fmAmTuner.rememberDesiredBandAndFreq(initBand, initFreq);

        activateSourceInternal(savedSource, false);
    }

    public void addListener(Listener l) {
        if (l == null) return;
        listeners.add(l);
        l.onStateChanged(getCurrentStateJson());
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public JSONObject getCurrentStateJson() {
        if (stateModel.getSource() == AudioSourceType.LOCAL_MUSIC) {
            stateModel.setPositionSec(streamPlayer.getCurrentPositionMs() / 1000L);
        }
        return stateModel.toJson();
    }

    public boolean supportsAdvancedControls() {
        AudioSourceType s = stateModel.getSource();
        return s == AudioSourceType.LOCAL_MUSIC || s == AudioSourceType.EXTERNAL_APP;
    }

    /**
     * Executes transport or source-selection commands immediately (0ms in-memory dispatch).
     */
    public void handleCommand(final String cmd) {
        if (cmd == null || cmd.isEmpty()) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(new Runnable() {
                @Override public void run() { handleCommand(cmd); }
            });
            return;
        }

        switch (cmd) {
            case "source_local":
                selectSource(AudioSourceType.LOCAL_MUSIC, true);
                break;
            case "source_bluetooth":
                selectSource(AudioSourceType.BLUETOOTH, true);
                break;
            case "source_car":
                selectSource(AudioSourceType.CAR_RADIO, true);
                break;
            case "source_internet":
                selectSource(AudioSourceType.INTERNET_RADIO, true);
                break;
            case "source_apps":
                selectSource(AudioSourceType.EXTERNAL_APP, true);
                break;
            case "toggle":
            case "play_pause":
            case "play":
            case "pause":
            case "next":
            case "previous":
            case "prev":
            case "seek_back":
            case "seek_forward":
                handleTransport(normalizeTransport(cmd));
                break;
            case "repeat_cycle": {
                AudioSourceType src = stateModel.getSource();
                if (src == AudioSourceType.LOCAL_MUSIC) {
                    streamPlayer.cycleRepeatMode();
                } else if (src == AudioSourceType.EXTERNAL_APP) {
                    externalRouter.cycleRepeatMode();
                    updateRepeatShuffleFromExternal();
                }
                break;
            }
            case "shuffle_toggle": {
                AudioSourceType src = stateModel.getSource();
                if (src == AudioSourceType.LOCAL_MUSIC) {
                    streamPlayer.toggleShuffle();
                } else if (src == AudioSourceType.EXTERNAL_APP) {
                    externalRouter.toggleShuffleMode();
                    updateRepeatShuffleFromExternal();
                }
                break;
            }
        }
    }

    public void selectSource(AudioSourceType target, boolean autoResumeIfApplicable) {
        if (target == null) return;
        AudioSourceType prev = stateModel.getSource();
        if (prev != target) {
            deactivateSource(prev);
        }
        stateModel.setSource(target);
        prefs.edit().putString(KEY_SOURCE, target.getKey()).apply();
        activateSourceInternal(target, autoResumeIfApplicable);
        emitState();
    }

    private void deactivateSource(AudioSourceType prev) {
        avrcpTracker.setActivePolling(false);
        mainHandler.removeCallbacks(externalPollRunnable);
        mainHandler.removeCallbacks(radioSyncRunnable);
        if (prev == AudioSourceType.LOCAL_MUSIC || prev == AudioSourceType.INTERNET_RADIO) {
            streamPlayer.stopPlayback();
        } else if (prev == AudioSourceType.BLUETOOTH) {
            executeBg(new Runnable() {
                @Override public void run() {
                    qingganBt.pause();
                    avrcpTracker.sendPassThroughFallback("pause");
                }
            });
        } else if (prev == AudioSourceType.CAR_RADIO) {
            fmAmTuner.setMuteAsync(true);
            radioPolicyRouter.abandonRadioPolicy();
        } else if (prev == AudioSourceType.EXTERNAL_APP) {
            externalRouter.pauseSelected();
        }
    }

    private void activateSourceInternal(AudioSourceType target, boolean autoActivate) {
        switch (target) {
            case LOCAL_MUSIC: {
                QingganAudioPolicyBridge.getInstance(appContext).routeToMusic();
                avrcpTracker.setActivePolling(false);
                mainHandler.removeCallbacks(externalPollRunnable);
                if (streamPlayer.getQueueSource() != AudioSourceType.LOCAL_MUSIC || streamPlayer.getCurrentItem() == null) {
                    if (!autoActivate) {
                        streamPlayer.resumeSavedQueue(AudioSourceType.LOCAL_MUSIC, false);
                    } else if (!streamPlayer.resumeSavedQueue(AudioSourceType.LOCAL_MUSIC, true)) {
                        stateModel.setTrackInfo("جاري تجهيز ملفات الفلاشة…", "الملفات المحلية", "", 0L, 0L, false);
                        stateModel.clearArtwork();
                        executeBg(new Runnable() {
                            @Override
                            public void run() {
                                final List<NativeStreamPlayer.QueueItem> scanned = localScanner.buildQueue("", "");
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (stateModel.getSource() != AudioSourceType.LOCAL_MUSIC) return;
                                        if (!scanned.isEmpty()) {
                                            streamPlayer.setQueueAndPlay(scanned, 0, AudioSourceType.LOCAL_MUSIC);
                                        } else {
                                            stateModel.setTrackInfo("اختر ملفاً من الفلاشة أو الذاكرة", "الملفات المحلية", "", 0L, 0L, false);
                                            emitState();
                                        }
                                    }
                                });
                            }
                        });
                    }
                } else {
                    if (autoActivate) streamPlayer.play();
                }
                break;
            }
            case BLUETOOTH: {
                mainHandler.removeCallbacks(externalPollRunnable);
                avrcpTracker.setActivePolling(true);
                executeBg(new Runnable() {
                    @Override
                    public void run() {
                        // Crucial: Route car hardware audio DSP policy to Bluetooth (streamType 4)
                        QingganAudioPolicyBridge.getInstance(appContext).routeToBluetooth();

                        qingganBt.refresh();
                        if (autoActivate && !callManager.isCallInProgress()) {
                            qingganBt.play();
                            avrcpTracker.sendPassThroughFallback("play");
                        }
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                avrcpTracker.refresh();
                                stateModel.setRepeatMode(RepeatMode.OFF);
                                stateModel.setShuffleEnabled(false);
                                handleBluetoothSnapshot(avrcpTracker.getSnapshot());
                            }
                        });
                    }
                });
                break;
            }
            case CAR_RADIO: {
                avrcpTracker.setActivePolling(false);
                mainHandler.removeCallbacks(externalPollRunnable);
                stateModel.setRepeatMode(RepeatMode.OFF);
                stateModel.setShuffleEnabled(false);
                stateModel.clearArtwork();
                String rType = prefs.getString(KEY_RADIO_TYPE, "FM");
                if ("DAB".equals(rType)) {
                    mainHandler.removeCallbacks(radioSyncRunnable);
                    stateModel.setRadioState("DAB", 0, "جاري الاتصال بـ DAB");
                    dabController.connectAndBrowse(null);
                } else {
                    radioPolicyRouter.requestRadioPolicy();
                    fmAmTuner.connectAsync();
                    fmAmTuner.setMuteAsync(false);
                    int band = prefs.getInt(KEY_FM_BAND, FmAmTunerController.BAND_FM);
                    int freq = getSavedFreqForBand(band);
                    fmAmTuner.rememberDesiredBandAndFreq(band, freq);
                    stateModel.setRadioState(band == FmAmTunerController.BAND_FM ? "FM" : "AM", freq, "راديو السيارة");
                    updateCarRadioTitle();
                    if (autoActivate) {
                        fmAmTuner.tuneAsync(band, freq);
                    }
                    mainHandler.removeCallbacks(radioSyncRunnable);
                    mainHandler.postDelayed(radioSyncRunnable, 3000L);
                    emitRadioDataAsync();
                }
                break;
            }
            case INTERNET_RADIO: {
                QingganAudioPolicyBridge.getInstance(appContext).routeToMusic();
                avrcpTracker.setActivePolling(false);
                mainHandler.removeCallbacks(externalPollRunnable);
                stateModel.setRepeatMode(RepeatMode.OFF);
                stateModel.setShuffleEnabled(false);
                stateModel.clearArtwork();
                if (streamPlayer.getQueueSource() != AudioSourceType.INTERNET_RADIO || streamPlayer.getCurrentItem() == null) {
                    if (!autoActivate) {
                        if (!streamPlayer.resumeSavedQueue(AudioSourceType.INTERNET_RADIO, false)) {
                            stateModel.setTrackInfo("اختر محطة راديو إنترنت", "Internet Radio", "", 0L, 0L, false);
                        }
                    } else if (!streamPlayer.resumeSavedQueue(AudioSourceType.INTERNET_RADIO, true)) {
                        List<RadioBrowserClient.Station> curated = RadioStationStore.getCuratedArabicStations();
                        if (!curated.isEmpty()) {
                            RadioBrowserClient.Station first = curated.get(0);
                            playInternetStation(first.stationUuid, first.name, first.streamUrl, first.countryCode);
                        } else {
                            stateModel.setTrackInfo("اختر محطة راديو إنترنت", "Internet Radio", "", 0L, 0L, false);
                        }
                    }
                } else {
                    if (autoActivate) streamPlayer.play();
                }
                break;
            }
            case EXTERNAL_APP: {
                QingganAudioPolicyBridge.getInstance(appContext).routeToMusic();
                avrcpTracker.setActivePolling(false);
                stateModel.setExternalApp(externalRouter.getSelectedPackage(), externalRouter.getSelectedLabel());
                syncExternalSession(true);
                mainHandler.removeCallbacks(externalPollRunnable);
                mainHandler.postDelayed(externalPollRunnable, 900L);
                if (autoActivate && !externalRouter.getSelectedPackage().isEmpty()) {
                    externalRouter.dispatchCommand("play");
                }
                break;
            }
        }
    }

    private void handleTransport(final String cmd) {
        if (callManager.isCallInProgress() && ("toggle".equals(cmd) || "play".equals(cmd))) {
            return;
        }
        AudioSourceType src = stateModel.getSource();
        switch (src) {
            case LOCAL_MUSIC:
                switch (cmd) {
                    case "toggle": streamPlayer.togglePlayPause(); break;
                    case "play": streamPlayer.play(); break;
                    case "pause": streamPlayer.pause(); break;
                    case "next": streamPlayer.next(); break;
                    case "previous": streamPlayer.previous(); break;
                    case "seek_back": streamPlayer.seekByMs(-10_000L); break;
                    case "seek_forward": streamPlayer.seekByMs(10_000L); break;
                }
                break;

            case INTERNET_RADIO:
                switch (cmd) {
                    case "toggle": streamPlayer.togglePlayPause(); break;
                    case "play": streamPlayer.play(); break;
                    case "pause": streamPlayer.pause(); break;
                    case "next": streamPlayer.next(); break;
                    case "previous": streamPlayer.previous(); break;
                }
                break;

            case BLUETOOTH:
                executeBg(new Runnable() {
                    @Override
                    public void run() {
                        boolean ok = false;
                        switch (cmd) {
                            case "toggle": ok = qingganBt.togglePlayPause(); break;
                            case "play": ok = qingganBt.play(); break;
                            case "pause": ok = qingganBt.pause(); break;
                            case "next": ok = qingganBt.next(); break;
                            case "previous": ok = qingganBt.previous(); break;
                        }
                        // Always send through AVRCP / Bluetooth MediaSession to ensure the phone
                        // resumes even if local vendor state reported unhandled
                        avrcpTracker.sendPassThroughFallback(cmd);
                        mainHandler.postDelayed(new Runnable() {
                            @Override public void run() { avrcpTracker.refresh(); }
                        }, 180L);
                    }
                });
                break;

            case CAR_RADIO:
                if ("DAB".equals(stateModel.getRadioType())) {
                    if ("toggle".equals(cmd)) dabController.togglePlayPause();
                    else if ("next".equals(cmd)) dabController.next();
                    else if ("previous".equals(cmd)) dabController.previous();
                } else {
                    if ("next".equals(cmd)) fmAmTuner.seekAsync(true);
                    else if ("previous".equals(cmd)) fmAmTuner.seekAsync(false);
                    else if ("toggle".equals(cmd)) {
                        boolean nextMuted = stateModel.isPlaying();
                        fmAmTuner.setMuteAsync(nextMuted);
                        stateModel.setPlaying(!nextMuted);
                        emitState();
                    }
                }
                break;

            case EXTERNAL_APP:
                externalRouter.dispatchCommand(cmd);
                mainHandler.postDelayed(new Runnable() {
                    @Override public void run() { syncExternalSession(true); }
                }, 150L);
                break;
        }
    }

    public void seekToSec(long sec) {
        AudioSourceType src = stateModel.getSource();
        if (src == AudioSourceType.LOCAL_MUSIC) {
            streamPlayer.seekToMs(sec * 1000L);
        } else if (src == AudioSourceType.EXTERNAL_APP) {
            externalRouter.seekToSec(sec);
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() { syncExternalSession(true); }
            }, 120L);
        }
    }

    public void seekBySec(long deltaSec) {
        AudioSourceType src = stateModel.getSource();
        if (src == AudioSourceType.LOCAL_MUSIC) {
            streamPlayer.seekByMs(deltaSec * 1000L);
        } else if (src == AudioSourceType.EXTERNAL_APP) {
            externalRouter.seekBySec(deltaSec);
            mainHandler.postDelayed(new Runnable() {
                @Override public void run() { syncExternalSession(true); }
            }, 120L);
        }
    }

    public void setSeekUiCallback(SeekUiCallback cb) {
        this.seekUiCallback = cb;
    }

    public void startLongSeek(int direction) {
        if (!supportsAdvancedControls() || direction == 0 || this.longSeekDir == direction) {
            return;
        }
        this.longSeekDir = direction;
        this.longSeekStartedAtMs = SystemClock.elapsedRealtime();
        seekBySec(direction * 10L);
        if (seekUiCallback != null) {
            seekUiCallback.onSeekTick(direction);
        }
        mainHandler.removeCallbacks(longSeekRunnable);
        mainHandler.postDelayed(longSeekRunnable, 450L);
    }

    public void stopLongSeek() {
        boolean wasActive = (longSeekDir != 0);
        longSeekDir = 0;
        mainHandler.removeCallbacks(longSeekRunnable);
        if (wasActive && seekUiCallback != null) {
            seekUiCallback.onSeekStopped();
        }
    }

    private void handleBluetoothSnapshot(AvrcpMetadataTracker.AvrcpSnapshot snap) {
        QingganBtController.BtState bt = qingganBt.snapshot();
        boolean connected = snap.connected || bt.connected;
        boolean playing = bt.available ? bt.playing : snap.playing;
        stateModel.setBtConnected(connected);

        String title = snap.title;
        String artist = snap.artist;
        if (title.isEmpty()) {
            title = connected ? (!snap.deviceName.isEmpty() ? snap.deviceName : "Bluetooth Audio") : "لا يوجد جهاز بلوتوث متصل";
        }
        if (artist.isEmpty()) {
            artist = connected ? "Bluetooth Audio" : "";
        }
        stateModel.setTrackInfo(title, artist, snap.album, snap.positionMs / 1000L, snap.durationMs / 1000L, playing);

        final String sig = "bt|" + snap.title + "|" + snap.artist;
        if (snap.artwork != null && !snap.artwork.isRecycled()) {
            String cached = artworkCache.cacheBitmap(sig, snap.artwork);
            stateModel.setArtwork(sig, cached);
        } else if (!snap.title.isEmpty()) {
            String cached = artworkCache.getCachedDataUrl(sig);
            if (cached != null) {
                stateModel.setArtwork(sig, cached);
            } else {
                if (!sig.equals(stateModel.getArtworkSignature())) {
                    stateModel.clearArtwork();
                }
                onlineCoverFetcher.fetchAsync(sig, snap.title, snap.artist, new OnlineCoverArtFetcher.Callback() {
                    @Override
                    public void onCoverArtFetched(String fetchedSig, final String dataUrl) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (stateModel.getSource() == AudioSourceType.BLUETOOTH
                                        && fetchedSig.equals("bt|" + stateModel.getTitle() + "|" + stateModel.getArtist())) {
                                    stateModel.setArtwork(fetchedSig, dataUrl);
                                    emitState();
                                }
                            }
                        });
                    }
                });
            }
        } else {
            stateModel.clearArtwork();
        }
        emitState();
    }

    public void onSystemMediaSessionUpdated() {
        if (stateModel.getSource() == AudioSourceType.EXTERNAL_APP) {
            syncExternalSession(false);
        }
    }

    private void syncExternalSession(boolean forceEmit) {
        ExternalPlayerRouter.SessionSnapshot snap = externalRouter.readSelectedSession();
        if (snap == null) return;
        String sig = snap.packageName + "|" + snap.title + "|" + snap.artist + "|" + snap.playing + "|" + snap.positionSec + "|" + snap.durationSec;
        if (!forceEmit && sig.equals(lastExternalSignature)) return;
        lastExternalSignature = sig;
        stateModel.setExternalApp(snap.packageName, snap.label);
        stateModel.setTrackInfo(snap.title, snap.artist, snap.album, snap.positionSec, snap.durationSec, snap.playing);
        if (snap.artDataUrl != null) {
            stateModel.setArtwork(snap.artSig, snap.artDataUrl);
        } else {
            stateModel.clearArtwork();
        }
        updateRepeatShuffleFromExternal();
        emitState();
    }

    private void updateRepeatShuffleFromExternal() {
        int rMode = externalRouter.getRepeatMode();
        RepeatMode rm = RepeatMode.OFF;
        if (rMode == ExternalPlayerRouter.REPEAT_MODE_ALL) rm = RepeatMode.ALL;
        else if (rMode == ExternalPlayerRouter.REPEAT_MODE_ONE) rm = RepeatMode.ONE;
        stateModel.setRepeatMode(rm);

        int sMode = externalRouter.getShuffleMode();
        boolean shuffle = (sMode != ExternalPlayerRouter.SHUFFLE_MODE_NONE);
        stateModel.setShuffleEnabled(shuffle);
        emitState();
    }

    // ==================== STUDIO DATA / ACTIONS API ====================

    public JSONObject browseLocalMusic(String folderPath, String query, boolean forceRescan) {
        return localScanner.browseFolder(folderPath, query, streamPlayer.hasSavedQueue(AudioSourceType.LOCAL_MUSIC), forceRescan);
    }

    public void playLocalTrack(final String folderPath, final String query, final String trackId) {
        executeBg(new Runnable() {
            @Override
            public void run() {
                final List<NativeStreamPlayer.QueueItem> queue = localScanner.buildQueue(folderPath, query);
                int idx = 0;
                for (int i = 0; i < queue.size(); i++) {
                    if (queue.get(i).id.equals(trackId)) {
                        idx = i;
                        break;
                    }
                }
                final int startIdx = idx;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (queue.isEmpty()) return;
                        AudioSourceType prev = stateModel.getSource();
                        if (prev != AudioSourceType.LOCAL_MUSIC) {
                            deactivateSource(prev);
                        }
                        QingganAudioPolicyBridge.getInstance(appContext).routeToMusic();
                        stateModel.setSource(AudioSourceType.LOCAL_MUSIC);
                        prefs.edit().putString(KEY_SOURCE, AudioSourceType.LOCAL_MUSIC.getKey()).apply();
                        streamPlayer.setQueueAndPlay(queue, startIdx, AudioSourceType.LOCAL_MUSIC);
                    }
                });
            }
        });
    }

    public void playQueueIndex(int index) {
        AudioSourceType src = stateModel.getSource();
        if (src == AudioSourceType.LOCAL_MUSIC) {
            List<NativeStreamPlayer.QueueItem> q = streamPlayer.getQueueSnapshot();
            if (index >= 0 && index < q.size()) {
                streamPlayer.setQueueAndPlay(q, index, AudioSourceType.LOCAL_MUSIC);
            }
        } else if (src == AudioSourceType.EXTERNAL_APP) {
            JSONObject queueJson = externalRouter.getQueueJson();
            if (queueJson != null && queueJson.has("items")) {
                JSONArray items = queueJson.optJSONArray("items");
                if (items != null && index >= 0 && index < items.length()) {
                    JSONObject it = items.optJSONObject(index);
                    if (it != null) {
                        long qId = it.optLong("id", -1L);
                        if (qId != -1L) {
                            externalRouter.skipToQueueItem(qId);
                        }
                    }
                }
            }
        }
    }

    public JSONObject getActiveQueueJson() {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        AudioSourceType src = stateModel.getSource();
        int cur = 0;
        try {
            if (src == AudioSourceType.LOCAL_MUSIC) {
                List<NativeStreamPlayer.QueueItem> q = streamPlayer.getQueueSnapshot();
                cur = streamPlayer.getCurrentIndex();
                for (int i = 0; i < q.size(); i++) {
                    NativeStreamPlayer.QueueItem item = q.get(i);
                    arr.put(new JSONObject()
                            .put("index", i)
                            .put("id", item.id)
                            .put("title", item.title)
                            .put("artist", item.artist)
                            .put("durationSec", item.durationMs / 1000L)
                            .put("active", i == cur));
                }
                root.put("source", "LOCAL_MUSIC");
                root.put("currentIndex", cur);
                root.put("items", arr);
                return root;
            } else if (src == AudioSourceType.EXTERNAL_APP) {
                JSONObject ext = externalRouter.getQueueJson();
                if (ext != null && ext.has("items") && ext.getJSONArray("items").length() > 0) {
                    ext.put("source", "EXTERNAL_APP");
                    return ext;
                }
                ExternalPlayerRouter.SessionSnapshot snap = externalRouter.readSelectedSession();
                if (snap != null) {
                    arr.put(new JSONObject()
                            .put("index", 0)
                            .put("id", snap.packageName)
                            .put("title", snap.title.isEmpty() ? snap.label : snap.title)
                            .put("artist", snap.artist)
                            .put("durationSec", snap.durationSec)
                            .put("active", true));
                }
                root.put("source", "EXTERNAL_APP");
                root.put("currentIndex", 0);
                root.put("items", arr);
                return root;
            }
            root.put("source", src.getKey());
            root.put("currentIndex", 0);
            root.put("items", arr);
        } catch (Throwable ignored) {}
        return root;
    }

    private int getSavedFreqForBand(int band) {
        int defFreq = (band == FmAmTunerController.BAND_FM) ? 87500 : 999;
        String key = (band == FmAmTunerController.BAND_FM) ? KEY_LAST_FM_FREQ : KEY_LAST_AM_FREQ;
        int raw = prefs.getInt(key, -1);
        if (raw <= 0) {
            int legacy = prefs.getInt(KEY_FM_FREQ, defFreq);
            raw = FmAmTunerController.isValidFreq(band, legacy) ? legacy : defFreq;
        }
        return FmAmTunerController.clampFrequency(band, raw);
    }

    private void saveFreqForBand(int band, int freqKhz) {
        if (!FmAmTunerController.isValidFreq(band, freqKhz)) return;
        int snapped = FmAmTunerController.clampFrequency(band, freqKhz);
        String key = (band == FmAmTunerController.BAND_FM) ? KEY_LAST_FM_FREQ : KEY_LAST_AM_FREQ;
        prefs.edit().putInt(KEY_FM_FREQ, snapped).putInt(key, snapped).apply();
    }

    public JSONObject buildRadioDataJson() {
        JSONObject root = new JSONObject();
        try {
            String rType = prefs.getString(KEY_RADIO_TYPE, "FM");
            int band = prefs.getInt(KEY_FM_BAND, FmAmTunerController.BAND_FM);
            int curTunerFreq = fmAmTuner.getCurrentFreqKhz();
            int freq = (fmAmTuner.getCurrentBand() == band && FmAmTunerController.isValidFreq(band, curTunerFreq))
                    ? curTunerFreq
                    : getSavedFreqForBand(band);
            List<OemStationRepository.Station> stations = oemStationRepo.loadStations(band, fmAmTuner.getFactoryBridge());
            String activeStationName = "";
            JSONArray stArr = new JSONArray();
            for (OemStationRepository.Station s : stations) {
                if (s.frequencyKhz == freq && !s.name.isEmpty()) {
                    activeStationName = s.name;
                }
                stArr.put(new JSONObject()
                        .put("name", s.name)
                        .put("freq", s.frequencyKhz)
                        .put("favorite", s.isFavorite));
            }
            if (!activeStationName.isEmpty() && stateModel.getSource() == AudioSourceType.CAR_RADIO && !"DAB".equals(rType)) {
                String label = (band == FmAmTunerController.BAND_FM)
                        ? String.format(Locale.US, "%s · %.1f MHz", activeStationName, freq / 1000.0)
                        : (activeStationName + " · " + freq + " kHz");
                stateModel.setTrackInfo(label, "راديو السيارة", "", 0L, 0L, true);
                emitState();
            }
            root.put("radioType", rType);
            root.put("band", band == FmAmTunerController.BAND_FM ? "FM" : "AM");
            root.put("freqKhz", freq);
            root.put("scanning", fmAmTuner.isAutoScanning());
            root.put("status", stateModel.toJson().optString("radioStatus", ""));
            root.put("stations", stArr);
            root.put("dabConnected", dabConnected);
            root.put("dabItems", dabItemsJson);
        } catch (Throwable ignored) {}
        return root;
    }

    public void selectCarRadioBand(String bandOrType) {
        selectSource(AudioSourceType.CAR_RADIO, false);
        if ("DAB".equalsIgnoreCase(bandOrType)) {
            mainHandler.removeCallbacks(radioSyncRunnable);
            prefs.edit().putString(KEY_RADIO_TYPE, "DAB").apply();
            stateModel.setRadioState("DAB", 0, "DAB / DAB+");
            dabController.connectAndBrowse(null);
            emitState();
            emitRadioDataAsync();
            return;
        }
        int prevBand = prefs.getInt(KEY_FM_BAND, FmAmTunerController.BAND_FM);
        int prevFreq = prefs.getInt(KEY_FM_FREQ, prevBand == FmAmTunerController.BAND_FM ? 87500 : 999);
        if (FmAmTunerController.isValidFreq(prevBand, prevFreq)) {
            saveFreqForBand(prevBand, prevFreq);
        }
        int targetBand = "AM".equalsIgnoreCase(bandOrType) ? FmAmTunerController.BAND_AM : FmAmTunerController.BAND_FM;
        int targetFreq = getSavedFreqForBand(targetBand);
        String rType = (targetBand == FmAmTunerController.BAND_FM) ? "FM" : "AM";
        prefs.edit().putString(KEY_RADIO_TYPE, rType).putInt(KEY_FM_BAND, targetBand).apply();
        saveFreqForBand(targetBand, targetFreq);
        stateModel.setRadioState(rType, targetFreq, "جاري الضبط…");
        updateCarRadioTitle();
        fmAmTuner.tuneAsync(targetBand, targetFreq);
        mainHandler.removeCallbacks(radioSyncRunnable);
        mainHandler.postDelayed(radioSyncRunnable, 3000L);
        emitState();
        emitRadioDataAsync();
    }

    public void tuneCarRadio(String bandOrType, int freqKhz) {
        selectSource(AudioSourceType.CAR_RADIO, false);
        if ("DAB".equalsIgnoreCase(bandOrType)) {
            mainHandler.removeCallbacks(radioSyncRunnable);
            prefs.edit().putString(KEY_RADIO_TYPE, "DAB").apply();
            stateModel.setRadioState("DAB", 0, "DAB / DAB+");
            dabController.connectAndBrowse(null);
            emitState();
            emitRadioDataAsync();
            return;
        }
        int band = "AM".equalsIgnoreCase(bandOrType) ? FmAmTunerController.BAND_AM : FmAmTunerController.BAND_FM;
        int clamped = FmAmTunerController.clampFrequency(band, freqKhz);
        String rType = (band == FmAmTunerController.BAND_FM) ? "FM" : "AM";
        prefs.edit().putString(KEY_RADIO_TYPE, rType).putInt(KEY_FM_BAND, band).apply();
        saveFreqForBand(band, clamped);
        stateModel.setRadioState(rType, clamped, "جاري الضبط…");
        updateCarRadioTitle();
        fmAmTuner.tuneAsync(band, clamped);
        emitState();
        emitRadioDataAsync();
    }

    public void stepCarRadio(int deltaKhz) {
        int band = prefs.getInt(KEY_FM_BAND, FmAmTunerController.BAND_FM);
        int cur = getSavedFreqForBand(band);
        int step = (deltaKhz != 0) ? deltaKhz : (band == FmAmTunerController.BAND_FM ? 100 : 9);
        tuneCarRadio(band == FmAmTunerController.BAND_FM ? "FM" : "AM", cur + step);
    }

    public void seekCarRadio(boolean up) {
        selectSource(AudioSourceType.CAR_RADIO, false);
        fmAmTuner.seekAsync(up);
    }

    public void startRadioAutoScan() {
        if (fmAmTuner.isAutoScanning()) {
            stopRadioAutoScan();
            return;
        }
        selectSource(AudioSourceType.CAR_RADIO, false);
        int band = prefs.getInt(KEY_FM_BAND, FmAmTunerController.BAND_FM);
        int curFreq = getSavedFreqForBand(band);
        fmAmTuner.rememberDesiredBandAndFreq(band, curFreq);
        String rType = (band == FmAmTunerController.BAND_FM) ? "FM" : "AM";
        stateModel.setRadioState(rType, curFreq, "جاري البحث التلقائي…");
        emitState();
        emitRadioDataAsync();
        fmAmTuner.startAutoScanAndSave(oemStationRepo, new FmAmTunerController.AutoScanListener() {
            @Override
            public void onScanProgress(int currentFreqKhz, int stationsFound) {
                String rT = prefs.getString(KEY_RADIO_TYPE, "FM");
                stateModel.setRadioState(rT, currentFreqKhz, "بحث تلقائي (" + stationsFound + " محطة)…");
                emitState();
                emitRadioDataAsync();
            }

            @Override
            public void onStationFound(int freqKhz, String name) {
                stateModel.setTrackInfo(name, "راديو السيارة", "FM", 0L, 0L, true);
                emitState();
                emitRadioDataAsync();
            }

            @Override
            public void onScanComplete(int totalStations) {
                String rT = prefs.getString(KEY_RADIO_TYPE, "FM");
                int finalFreq = fmAmTuner.getCurrentFreqKhz();
                saveFreqForBand(fmAmTuner.getCurrentBand(), finalFreq);
                stateModel.setRadioState(rT, finalFreq, "اكتمل البحث (" + totalStations + " محطة)");
                updateCarRadioTitle();
                emitState();
                emitRadioDataAsync();
            }
        });
    }

    public void stopRadioAutoScan() {
        fmAmTuner.stopAutoScan();
        stateModel.setRadioState(prefs.getString(KEY_RADIO_TYPE, "FM"), fmAmTuner.getCurrentFreqKhz(), "تم إيقاف البحث");
        emitState();
        emitRadioDataAsync();
    }

    public boolean isRadioAutoScanning() {
        return fmAmTuner.isAutoScanning();
    }

    public boolean toggleCarRadioFavorite(int freqKhz, String name) {
        int band = prefs.getInt(KEY_FM_BAND, FmAmTunerController.BAND_FM);
        boolean res = oemStationRepo.toggleLocalFavorite(band, freqKhz, name);
        emitRadioDataAsync();
        return res;
    }

    public void saveCarRadioFavorite(int freqKhz, String name) {
        int band = prefs.getInt(KEY_FM_BAND, FmAmTunerController.BAND_FM);
        oemStationRepo.saveOrUpdateLocalFavorite(band, freqKhz, name);
        emitRadioDataAsync();
    }

    public void browseDab(String mediaId) {
        selectSource(AudioSourceType.CAR_RADIO, false);
        prefs.edit().putString(KEY_RADIO_TYPE, "DAB").apply();
        dabController.connectAndBrowse(mediaId);
    }

    public void playDabItem(String mediaId) {
        dabController.playMediaId(mediaId);
    }

    public JSONObject queryInternetRadio(String tab, String query) {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            List<RadioBrowserClient.Station> list;
            switch (tab != null ? tab : "popular") {
                case "arabic":
                    list = internetClient.searchStations(query, true, 40);
                    break;
                case "favorites":
                    list = internetStore.getFavorites();
                    break;
                case "recent":
                    list = internetStore.getRecent();
                    break;
                case "custom":
                    list = internetStore.getCustom();
                    break;
                case "popular":
                default:
                    list = internetClient.searchStations(query, false, 40);
                    break;
            }
            for (RadioBrowserClient.Station s : list) {
                arr.put(s.toJson(internetStore.isFavorite(s.stationUuid)));
            }
            root.put("tab", tab);
            root.put("stations", arr);
            root.put("customStations", stationsToJsonArray(internetStore.getCustom()));
        } catch (Throwable ignored) {}
        return root;
    }

    private JSONArray stationsToJsonArray(List<RadioBrowserClient.Station> list) {
        JSONArray arr = new JSONArray();
        for (RadioBrowserClient.Station s : list) {
            arr.put(s.toJson(internetStore.isFavorite(s.stationUuid)));
        }
        return arr;
    }

    public void playInternetStation(String uuid, String name, String url, String country) {
        if (url == null || url.trim().isEmpty()) return;
        AudioSourceType prev = stateModel.getSource();
        if (prev != AudioSourceType.INTERNET_RADIO) {
            deactivateSource(prev);
        }
        QingganAudioPolicyBridge.getInstance(appContext).routeToMusic();
        RadioBrowserClient.Station station = new RadioBrowserClient.Station(uuid, name, url, country);
        internetStore.addRecent(station);
        internetClient.reportClickAsync(uuid);

        stateModel.setSource(AudioSourceType.INTERNET_RADIO);
        prefs.edit().putString(KEY_SOURCE, AudioSourceType.INTERNET_RADIO.getKey()).apply();

        NativeStreamPlayer.QueueItem item = new NativeStreamPlayer.QueueItem(
                station.stationUuid, station.streamUrl, station.name,
                country != null && !country.isEmpty() ? ("Internet Radio · " + country) : "Internet Radio",
                "Internet Radio", 0L, 0L, true
        );
        streamPlayer.setQueueAndPlay(Collections.singletonList(item), 0, AudioSourceType.INTERNET_RADIO);
    }

    public boolean toggleInternetFavorite(String uuid, String name, String url, String country) {
        return internetStore.toggleFavorite(new RadioBrowserClient.Station(uuid, name, url, country));
    }

    public void addCustomInternetStation(String name, String url) {
        internetStore.addCustomStation(name, url);
    }

    public void removeCustomInternetStation(String uuid) {
        internetStore.removeCustomStation(uuid);
    }

    public JSONObject getExternalMediaAppsJson() {
        return externalRouter.getFilteredMediaAppsJson();
    }

    public void selectExternalMediaApp(String pkg, String label, boolean launchNow) {
        externalRouter.selectApp(pkg, label, launchNow);
        stateModel.setExternalApp(pkg, label);
        selectSource(AudioSourceType.EXTERNAL_APP, true);
    }

    private void updateCarRadioTitle() {
        String rType = stateModel.getRadioType();
        int freq = stateModel.getRadioFreqKhz();
        String label = "DAB".equals(rType) ? "DAB / DAB+"
                : ("FM".equals(rType) ? String.format(Locale.US, "FM %.1f MHz", freq / 1000.0) : ("AM " + freq + " kHz"));
        stateModel.setTrackInfo(label, "راديو السيارة", "", 0L, 0L, true);
    }

    private void handleCallStarted() {
        AudioSourceType src = stateModel.getSource();
        if (src != AudioSourceType.CAR_RADIO && stateModel.isPlaying()) {
            pausedByCall = true;
            handleTransport("pause");
        }
    }

    private void handleCallEnded() {
        if (pausedByCall) {
            pausedByCall = false;
            handleTransport("play");
        }
    }

    private static String normalizeTransport(String cmd) {
        if ("play_pause".equals(cmd)) return "toggle";
        if ("prev".equals(cmd)) return "previous";
        return cmd;
    }

    private void emitState() {
        final JSONObject payload = getCurrentStateJson();
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) {
                    try {
                        l.onStateChanged(payload);
                    } catch (Throwable t) {
                        Log.w(TAG, "Listener callback failed: " + t.getMessage());
                    }
                }
            }
        });
    }

    private void emitRadioDataAsync() {
        executeBg(new Runnable() {
            @Override
            public void run() {
                final JSONObject payload = buildRadioDataJson();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        for (Listener l : listeners) {
                            try {
                                l.onRadioDataChanged(payload);
                            } catch (Throwable t) {
                                Log.w(TAG, "Radio listener callback failed: " + t.getMessage());
                            }
                        }
                    }
                });
            }
        });
    }

    public void release() {
        synchronized (ShineWaveEngine.class) {
            if (instance == this) {
                instance = null;
            }
        }
        callManager.stop();
        mainHandler.removeCallbacksAndMessages(null);
        streamPlayer.release();
        avrcpTracker.release();
        fmAmTuner.release();
        dabController.disconnect();
        onlineCoverFetcher.shutdown();
        LocalStreamProxy.getInstance().shutdown();
        bgExecutor.shutdownNow();
    }
}
