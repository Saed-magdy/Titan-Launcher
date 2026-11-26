/*
 * Titan Cockpit Launcher
 * Created by Dr. Saed
 * For more info contact: saedmagdysms@gmail.com
 */
package com.dongfeng.shinecockpit;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.dongfeng.shinecockpit.shinewave.core.AudioSourceType;
import com.dongfeng.shinecockpit.shinewave.core.ShineWaveEngine;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main cockpit launcher Activity hosting the hardware-accelerated WebView UI,
 * JavaScript bridge, CAN telemetry loop, and integrated ShineWave audio coordinator.
 */
public class MainActivity extends Activity implements CockpitMediaListenerService.MediaEventListener {
    private static final String TAG = "DongfengCockpit";
    private static final String PREF_HUB = "ShineHubPrefs";

    private static android.content.MutableContextWrapper sWebViewContext;
    private static WebView sSharedWebView;
    private static volatile MainActivity sActiveInstance;
    private static volatile boolean sRestoreScheduled = false;

    private long mStartupStartMs;
    private boolean mFinishedWhileForeground = false;
    private WebView mWebView;
    private CanBusClient mCanBusClient;
    private ShineWaveEngine mShineWave;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mNetExecutor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService mTelemetryExecutor = Executors.newSingleThreadScheduledExecutor();

    private final AudioSpectrumAnalyzer mAudioAnalyzer = new AudioSpectrumAnalyzer();
    private final AppRepository mAppRepo = new AppRepository();
    private SteeringWheelKeyHandler mSwcHandler;

    private volatile boolean mIsForeground = true;
    private volatile int mLastAvmState = -1;
    private volatile long mLastTimeGuardCheckMs = 0L;
    private int mSimSpeed = 0;
    private boolean mSpeedIncreasing = true;
    private double mLastSentFuelRemain = -1.0;
    private int mLastSentFuelPct = -1;
    private String mLastFastSig = "";
    private long mLastFullTelemetryMs = 0L;

    private final BroadcastReceiver mCar3DFreezeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || mWebView == null) return;
            String action = intent.getAction();
            if ("com.dongfeng.shinecockpit.ACTION_CAR_3D_FREEZE".equals(action)) {
                final boolean freeze = intent.getBooleanExtra("freeze", false);
                Log.d(TAG, "Received ACTION_CAR_3D_FREEZE: " + freeze);
                evalWebViewJs("if (typeof window.setCar3DFrozen === 'function') { window.setCar3DFrozen(" + freeze + "); }");
            } else if ("com.dongfeng.shinecockpit.ACTION_EVAL_JS".equals(action)) {
                final String js = intent.getStringExtra("js");
                if (js != null) evalWebViewJs(js);
            }
        }
    };

    private final Runnable mClockSyncPushRunnable = new Runnable() {
        @Override
        public void run() {
            evalWebViewJs("if (typeof window.onNativeClockSync === 'function') { window.onNativeClockSync(" + OfflineTimeKeeper.getClockSyncJson(MainActivity.this) + "); }");
        }
    };

    private final BroadcastReceiver mConnectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            evalWebViewJs("window.syncConnectivityFromAndroid && window.syncConnectivityFromAndroid();");
            OfflineTimeKeeper.enforceTimeZoneAndClock(MainActivity.this, "connectivity", mClockSyncPushRunnable);
        }
    };

    private final BroadcastReceiver mTimeGuardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String act = intent != null ? intent.getAction() : "";
            OfflineTimeKeeper.onSystemBroadcast(MainActivity.this, act, mClockSyncPushRunnable);
        }
    };

    private final BroadcastReceiver mMediaBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && mShineWave != null) {
                mShineWave.onSystemMediaSessionUpdated();
            }
        }
    };

    private final ShineWaveEngine.Listener mShineWaveListener = new ShineWaveEngine.Listener() {
        @Override
        public void onStateChanged(final JSONObject stateJson) {
            if (mWebView != null && stateJson != null) {
                evalWebViewJs("if (typeof window.onShineWaveState === 'function') { window.onShineWaveState(" + stateJson.toString() + "); }");
            }
        }

        @Override
        public void onRadioDataChanged(final JSONObject radioJson) {
            if (mWebView != null && radioJson != null) {
                evalSwJsCallback("onShineWaveRadioData", radioJson.toString());
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sActiveInstance = this;
        sRestoreScheduled = false;
        mFinishedWhileForeground = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null && am.getAppTasks() != null) {
                    for (android.app.ActivityManager.AppTask task : am.getAppTasks()) {
                        task.setExcludeFromRecents(true);
                    }
                }
            } catch (Throwable ignored) {}
        }
        mStartupStartMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "Startup: onCreate started");

        LauncherWatchdog.install(this, mHandler);
        OfflineTimeKeeper.enforceTimeZoneAndClock(this, "onCreate", mClockSyncPushRunnable);
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                GitHubUpdateChecker.checkForUpdates(MainActivity.this, true);
            }
        }, 10000L);

        checkAndRequestAppPermissions();

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        long canInitStartMs = SystemClock.elapsedRealtime();
        mCanBusClient = new CanBusClient(this);
        Log.i(TAG, "Startup: CAN client initialized in " + (SystemClock.elapsedRealtime() - canInitStartMs)
                + "ms; connected=" + mCanBusClient.isConnected());

        mSwcHandler = new SteeringWheelKeyHandler(this, new SteeringWheelKeyHandler.HostCallback() {
            @Override
            public ShineWaveEngine getShineWaveEngine() {
                return mShineWave;
            }

            @Override
            public void evaluateJs(String script) {
                evalWebViewJs(script);
            }
        });

        startTelemetryLoop();
        SystemControlHelper.ensureNotificationListenerAccess(this);
        CockpitMediaListenerService.setListener(this);
        startShineWaveEngine();
        mSwcHandler.registerQingganKeys();
        registerReceivers();

        if (sSharedWebView != null) {
            if (sWebViewContext != null) {
                sWebViewContext.setBaseContext(this);
            }
            ViewGroup prevParent = (sSharedWebView.getParent() instanceof ViewGroup)
                    ? (ViewGroup) sSharedWebView.getParent() : null;
            if (prevParent != null) {
                prevParent.removeView(sSharedWebView);
            }
            mWebView = sSharedWebView;
            root.addView(mWebView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(root);
            hideSystemUI();
            Log.i(TAG, "Startup: Reattached warm sSharedWebView in 0ms (3D WebGL context preserved)");
            evalWebViewJs("window.__cockpitPaused = false; if (typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(false); } if (typeof window.forceWakeAndRender3D === 'function') { window.forceWakeAndRender3D(); }");
            pushShineWaveState();
        } else {
            sWebViewContext = new android.content.MutableContextWrapper(getApplicationContext());
            sWebViewContext.setBaseContext(this);
            sSharedWebView = new WebView(sWebViewContext);
            mWebView = sSharedWebView;
            root.addView(mWebView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(root);
            hideSystemUI();
            setupWebView();
        }
    }

    private void startShineWaveEngine() {
        try {
            mShineWave = ShineWaveEngine.getInstance(this);
            mShineWave.addListener(mShineWaveListener);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize ShineWaveEngine: " + t.getMessage(), t);
        }
    }

    private void stopShineWaveEngine() {
        try {
            if (mShineWave != null) {
                mShineWave.removeListener(mShineWaveListener);
            }
        } catch (Throwable ignored) {}
    }

    private void registerReceivers() {
        try {
            IntentFilter freezeFilter = new IntentFilter("com.dongfeng.shinecockpit.ACTION_CAR_3D_FREEZE");
            freezeFilter.addAction("com.dongfeng.shinecockpit.ACTION_EVAL_JS");
            registerReceiver(mCar3DFreezeReceiver, freezeFilter);
        } catch (Throwable ignored) {}

        try {
            IntentFilter connFilter = new IntentFilter();
            connFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            connFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            connFilter.addAction("android.bluetooth.device.action.ACL_CONNECTED");
            connFilter.addAction("android.bluetooth.device.action.ACL_DISCONNECTED");
            connFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(mConnectivityReceiver, connFilter);
        } catch (Throwable ignored) {}

        try {
            IntentFilter timeFilter = new IntentFilter();
            timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
            registerReceiver(mTimeGuardReceiver, timeFilter);
        } catch (Throwable ignored) {}

        try {
            IntentFilter mediaFilter = new IntentFilter();
            mediaFilter.addAction("android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT");
            mediaFilter.addAction("android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED");
            mediaFilter.addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED");
            mediaFilter.addAction("com.android.music.metachanged");
            mediaFilter.addAction("com.android.music.playstatechanged");
            mediaFilter.addAction("com.spotify.music.playbackstatechanged");
            mediaFilter.addAction("com.spotify.music.metadatachanged");
            registerReceiver(mMediaBroadcastReceiver, mediaFilter);
        } catch (Throwable t) {
            Log.w(TAG, "registerMediaReceivers failed: " + t.getMessage());
        }
    }

    private void unregisterReceiverSafe(BroadcastReceiver receiver) {
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {}
    }

    private void evalWebViewJs(final String script) {
        if (mWebView == null || script == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mWebView.evaluateJavascript(script, null);
        } else {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mWebView != null) {
                        mWebView.evaluateJavascript(script, null);
                    }
                }
            });
        }
    }

    private void pushShineWaveState() {
        if (mShineWave == null || mWebView == null) return;
        final JSONObject json = mShineWave.getCurrentStateJson();
        evalWebViewJs("if (typeof window.onShineWaveState === 'function') { window.onShineWaveState(" + json.toString() + "); }");
    }

    private void evalSwJsCallback(final String fnName, final String jsonPayload) {
        if (mWebView == null || jsonPayload == null) return;
        evalWebViewJs("if (typeof window." + fnName + " === 'function') { window." + fnName + "(" + jsonPayload + "); }");
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            try {
                sendBroadcast(new Intent("com.titan.pip.ACTION_HOME_PRESSED"));
            } catch (Throwable ignored) {}
            evalWebViewJs("if (typeof window.onLauncherBackPressed === 'function') { window.onLauncherBackPressed(); }");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIsForeground = true;
        hideSystemUI();
        evalWebViewJs("window.__cockpitPaused = false; if (typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(false); } if (typeof window.forceWakeAndRender3D === 'function') { window.forceWakeAndRender3D(); }");
        if (mLastAvmState >= 0) applyAvmOverlayStateToWebView(mLastAvmState != 0);
        pushShineWaveState();
        mClockSyncPushRunnable.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        boolean wasFg = mIsForeground;
        mIsForeground = false;
        if (wasFg && isFinishing()) {
            mFinishedWhileForeground = true;
            Log.i(TAG, "Detected task clear while launcher in foreground; scheduling instant warm restore");
        }
        mAudioAnalyzer.release();
        if (!mFinishedWhileForeground) {
            evalWebViewJs("if (typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(true); }");
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
            evalWebViewJs("window.__cockpitPaused = false; if (typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(false); } if (typeof window.forceWakeAndRender3D === 'function') { window.forceWakeAndRender3D(); }");
        }
    }

    @Override
    public void onBackPressed() {
        evalWebViewJs("if (typeof window.onLauncherBackPressed === 'function') { window.onLauncherBackPressed(); }");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mSwcHandler != null && mSwcHandler.handleDispatchKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onMediaUpdated(final JSONObject mediaJson) {
        if (mShineWave != null) {
            mShineWave.onSystemMediaSessionUpdated();
        }
    }

    @Override
    public void onGoogleMapsNav(final String instruction, final String distance, final String turnIcon) {
        try {
            JSONObject json = new JSONObject();
            json.put("instruction", instruction);
            json.put("distance", distance);
            json.put("turnIcon", turnIcon);
            evalWebViewJs("if (typeof window.onNavigationUpdate === 'function') { window.onNavigationUpdate(" + json.toString() + "); }");
        } catch (Exception ignored) {}
    }

    @Override
    public void onRadarbotAlert(final String alertType, final int speedLimit, final int distanceMeters) {
        try {
            JSONObject json = new JSONObject();
            json.put("alert", alertType);
            json.put("speed", speedLimit);
            json.put("distance", distanceMeters);
            evalWebViewJs("if (typeof window.onRadarUpdate === 'function') { window.onRadarUpdate(" + json.toString() + "); }");
        } catch (Exception ignored) {}
    }

    private static boolean isLauncherOwnPage(String url) {
        if (url == null) return false;
        if (url.startsWith("file:///android_asset/") && !url.contains("..")) {
            return true;
        }
        Log.w(TAG, "Blocked an attempt to open an external page inside the launcher screen: " + url);
        return false;
    }

    private void setupWebView() {
        mWebView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        mWebView.setBackgroundColor(0xFF061014);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.e("WebConsole", cm.message() + " -- line " + cm.lineNumber() + " of " + cm.sourceId());
                return true;
            }
        });

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isLauncherOwnPage(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isLauncherOwnPage(request != null && request.getUrl() != null ? request.getUrl().toString() : null);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                boolean didCrash = (detail != null && detail.didCrash());
                Log.e(TAG, "WebViewClient.onRenderProcessGone: didCrash=" + didCrash);
                sSharedWebView = null;
                sWebViewContext = null;
                MainActivity act = sActiveInstance != null ? sActiveInstance : MainActivity.this;
                LauncherWatchdog.handleFatalCrashAndRecovery(act, "RenderProcessGone (didCrash=" + didCrash + ")");
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                MainActivity act = sActiveInstance != null ? sActiveInstance : MainActivity.this;
                Log.i(TAG, "Startup: WebView page finished at " + (SystemClock.elapsedRealtime() - act.mStartupStartMs) + "ms");
                view.evaluateJavascript("window.__cockpitPaused = " + (!act.mIsForeground) + "; if (typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(" + (!act.mIsForeground) + "); }", null);
                if (act.mLastAvmState >= 0) act.applyAvmOverlayStateToWebView(act.mLastAvmState != 0);
                act.pushShineWaveState();
                act.mClockSyncPushRunnable.run();
                try {
                    String s = act.getSharedPreferences(PREF_HUB, MODE_PRIVATE).getString("user_settings", "");
                    if (s != null && !s.isEmpty()) {
                        String escaped = JSONObject.quote(s);
                        view.evaluateJavascript("if (typeof applySavedSettingsJson === 'function') { applySavedSettingsJson(" + escaped + "); }", null);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error restoring settings in onPageFinished", e);
                }
            }
        });

        final AndroidBridge bridge = new AndroidBridge();
        mWebView.addJavascriptInterface(bridge, "AndroidBridge");

        final MainActivity initAct = sActiveInstance != null ? sActiveInstance : MainActivity.this;
        initAct.mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    initAct.mAppRepo.getInstalledAppsJson(initAct);
                } catch (Exception ignored) {}
            }
        });

        mWebView.loadUrl("file:///android_asset/app.html");
        Log.i(TAG, "Startup: WebView load requested at " + (SystemClock.elapsedRealtime() - mStartupStartMs) + "ms");
    }

    private void checkAndRequestAppPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            List<String> needed = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.RECORD_AUDIO);
            }
            if (!needed.isEmpty()) {
                try {
                    requestPermissions(needed.toArray(new String[0]), 101);
                } catch (Throwable t) {
                    Log.w(TAG, "Permission request note: " + t.getMessage());
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101 && permissions != null && grantResults != null) {
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.RECORD_AUDIO.equals(permissions[i]) && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    mAudioAnalyzer.ensureInitialized(mIsForeground);
                }
            }
        }
    }

    /**
     * JavaScript Bridge exposed as window.AndroidBridge in the WebView.
     */
    public class AndroidBridge {
        private MainActivity host() {
            MainActivity act = sActiveInstance;
            return act != null ? act : MainActivity.this;
        }

        @JavascriptInterface
        public String getAudioVisualizerData() {
            MainActivity h = host();
            return h.mAudioAnalyzer.getSpectrumCsv(h.mIsForeground);
        }

        @JavascriptInterface
        public boolean sendShineWaveCommand(final String cmd) {
            MainActivity h = host();
            if (h.mShineWave == null || cmd == null) return false;
            h.mShineWave.handleCommand(cmd);
            return true;
        }

        @JavascriptInterface
        public void requestShineWaveState() {
            host().pushShineWaveState();
        }

        @JavascriptInterface
        public void cycleShineWaveRepeat() {
            MainActivity h = host();
            if (h.mShineWave != null) h.mShineWave.handleCommand("repeat_cycle");
        }

        @JavascriptInterface
        public void toggleShineWaveShuffle() {
            MainActivity h = host();
            if (h.mShineWave != null) h.mShineWave.handleCommand("shuffle_toggle");
        }

        @JavascriptInterface
        public void browseShineWaveLocal(final String folderPath, final String query, final boolean forceRescan) {
            final MainActivity h = host();
            if (h.mShineWave == null) return;
            h.mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    JSONObject data = h.mShineWave.browseLocalMusic(folderPath, query, forceRescan);
                    h.evalSwJsCallback("onShineWaveLocalData", data.toString());
                }
            });
        }

        @JavascriptInterface
        public void playShineWaveLocalTrack(final String folderPath, final String query, final String trackId) {
            MainActivity h = host();
            if (h.mShineWave != null) h.mShineWave.playLocalTrack(folderPath, query, trackId);
        }

        @JavascriptInterface
        public void resumeShineWaveQueue(final String source) {
            MainActivity h = host();
            if (h.mShineWave != null) h.mShineWave.selectSource(AudioSourceType.fromKey(source), true);
        }

        @JavascriptInterface
        public void requestShineWaveQueue() {
            MainActivity h = host();
            if (h.mShineWave == null) return;
            h.evalSwJsCallback("onShineWaveQueueData", h.mShineWave.getActiveQueueJson().toString());
        }

        @JavascriptInterface
        public void playShineWaveQueueIndex(final int index) {
            MainActivity h = host();
            if (h.mShineWave == null) return;
            h.mShineWave.playQueueIndex(index);
        }

        @JavascriptInterface
        public void getShineWaveRadioState() {
            final MainActivity h = host();
            if (h.mShineWave == null) return;
            h.mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    JSONObject data = h.mShineWave.buildRadioDataJson();
                    h.evalSwJsCallback("onShineWaveRadioData", data.toString());
                }
            });
        }

        @JavascriptInterface
        public void tuneShineWaveRadio(final String band, final int freqKhz) {
            MainActivity h = host();
            if (h.mShineWave == null) return;
            h.mShineWave.tuneCarRadio(band, freqKhz);
        }

        @JavascriptInterface
        public void selectShineWaveRadioBand(final String band) {
            MainActivity h = host();
            if (h.mShineWave == null) return;
            h.mShineWave.selectCarRadioBand(band);
        }

        @JavascriptInterface
        public void stepShineWaveRadio(final int deltaKhz) {
            MainActivity h = host();
            if (h.mShineWave == null) return;
            h.mShineWave.stepCarRadio(deltaKhz);
        }

        @JavascriptInterface
        public void seekShineWaveRadio(final boolean up) {
            MainActivity h = host();
            if (h.mShineWave != null) h.mShineWave.seekCarRadio(up);
        }

        @JavascriptInterface
        public void startShineWaveRadioAutoScan() {
            MainActivity h = host();
            if (h.mShineWave != null) h.mShineWave.startRadioAutoScan();
        }

        @JavascriptInterface
        public void saveShineWaveRadioFavorite(final int freqKhz, final String name) {
            MainActivity h = host();
            if (h.mShineWave == null) return;
            h.mShineWave.saveCarRadioFavorite(freqKhz, name);
            getShineWaveRadioState();
        }

        @JavascriptInterface
        public void toggleShineWaveRadioFav(final int freqKhz, final String name) {
            MainActivity h = host();
            if (h.mShineWave == null) return;
            h.mShineWave.toggleCarRadioFavorite(freqKhz, name);
            getShineWaveRadioState();
        }

        @JavascriptInterface
        public void playShineWaveDab(final String mediaId) {
            MainActivity h = host();
            if (h.mShineWave != null) h.mShineWave.playDabItem(mediaId);
        }

        @JavascriptInterface
        public void searchShineWaveInternetRadio(final String tab, final String query) {
            final MainActivity h = host();
            if (h.mShineWave == null) return;
            h.mNetExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    JSONObject data = h.mShineWave.queryInternetRadio(tab, query);
                    h.evalSwJsCallback("onShineWaveInternetData", data.toString());
                }
            });
        }

        @JavascriptInterface
        public void playShineWaveInternetStation(final String uuid, final String name, final String url, final String country) {
            MainActivity h = host();
            if (h.mShineWave != null) h.mShineWave.playInternetStation(uuid, name, url, country);
        }

        @JavascriptInterface
        public void toggleShineWaveInternetFav(final String uuid, final String name, final String url,
                                               final String country, final String tab, final String query) {
            MainActivity h = host();
            if (h.mShineWave == null) return;
            h.mShineWave.toggleInternetFavorite(uuid, name, url, country);
            searchShineWaveInternetRadio(tab, query);
        }

        @JavascriptInterface
        public void addShineWaveCustomStation(final String name, final String url) {
            MainActivity h = host();
            if (h.mShineWave == null) return;
            h.mShineWave.addCustomInternetStation(name, url);
            searchShineWaveInternetRadio("custom", "");
        }

        @JavascriptInterface
        public void getShineWaveExternalApps() {
            final MainActivity h = host();
            if (h.mShineWave == null) return;
            h.mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    JSONObject data = h.mShineWave.getExternalMediaAppsJson();
                    h.evalSwJsCallback("onShineWaveAppsData", data.toString());
                }
            });
        }

        @JavascriptInterface
        public void selectShineWaveExternalApp(final String pkg, final String label, final boolean launchNow) {
            MainActivity h = host();
            if (h.mShineWave == null) return;
            h.mShineWave.selectExternalMediaApp(pkg, label, launchNow);
            getShineWaveExternalApps();
        }

        @JavascriptInterface
        public void openCamera360() {
            final MainActivity h = host();
            h.evalWebViewJs("if (typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(true); }");
            Log.i(TAG, "openCamera360 requested from JS bridge");
            h.mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (h.mCanBusClient != null) h.mCanBusClient.openCamera360();
                }
            });
        }

        @JavascriptInterface
        public boolean launchApp(final String keyOrPkg) {
            final MainActivity h = host();
            Log.i(TAG, "launchApp requested: " + keyOrPkg);
            return h.mAppRepo.launchApp(h, keyOrPkg, new Runnable() {
                @Override
                public void run() {
                    openCamera360();
                }
            });
        }

        @JavascriptInterface
        public String getInstalledAppsJson() {
            MainActivity h = host();
            return h.mAppRepo.getInstalledAppsJson(h);
        }

        @JavascriptInterface
        public String getConnectivityStatusJson() {
            return SystemControlHelper.getConnectivityStatusJson(host());
        }

        @JavascriptInterface
        public void openConnectivitySettings(final String type) {
            SystemControlHelper.openConnectivitySettings(host(), type);
        }

        @JavascriptInterface
        public void setWifiEnabled(final boolean enabled) {
            final MainActivity h = host();
            h.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    SystemControlHelper.setWifiEnabled(h, enabled);
                }
            });
        }

        @JavascriptInterface
        public void setBluetoothEnabled(final boolean enabled) {
            host().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    SystemControlHelper.setBluetoothEnabled(enabled);
                }
            });
        }

        @JavascriptInterface
        public String cleanMemory() {
            return SystemControlHelper.cleanMemory(host());
        }

        @JavascriptInterface
        public void controlMedia(final String cmd) {
            MainActivity h = host();
            if (h.mShineWave != null) h.mShineWave.handleCommand(cmd);
        }

        @JavascriptInterface
        public void seekMediaTo(final long targetSec) {
            MainActivity h = host();
            if (h.mShineWave != null) h.mShineWave.seekToSec(targetSec);
        }

        @JavascriptInterface
        public void seekMediaBy(final long deltaSec) {
            MainActivity h = host();
            if (h.mShineWave != null) h.mShineWave.seekBySec(deltaSec);
        }

        @JavascriptInterface
        public void dispatchSystemNav(final String action) {
            if (action == null || action.isEmpty()) return;
            final MainActivity h = host();
            h.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if ("back".equalsIgnoreCase(action)) {
                            h.onBackPressed();
                        } else if ("home".equalsIgnoreCase(action)) {
                            Intent startMain = new Intent(Intent.ACTION_MAIN);
                            startMain.addCategory(Intent.CATEGORY_HOME);
                            startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            h.startActivity(startMain);
                        } else if ("recents".equalsIgnoreCase(action) || "tasks".equalsIgnoreCase(action)) {
                            try {
                                Runtime.getRuntime().exec("input keyevent 187");
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "dispatchSystemNav error: " + action, e);
                    }
                }
            });
        }

        @JavascriptInterface
        public void postCanCommand(final String command, final String dataJson) {
            final MainActivity h = host();
            Log.i(TAG, "postCanCommand: " + command + " data: " + dataJson);
            try {
                int value = 0;
                try {
                    value = Integer.parseInt(dataJson.trim());
                } catch (Exception ignored) {
                    try {
                        JSONObject obj = new JSONObject(dataJson);
                        if (obj.has("state")) value = obj.getInt("state");
                    } catch (Exception ignored2) {}
                }

                final int fVal = value;
                h.mExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        int result = h.mCanBusClient.dispatchCommand(command, fVal);
                        final String message;
                        if (result == CanBusClient.COMMAND_REFUSED_MOVING) {
                            message = "لا يمكن فتح الشنطة أثناء سير السيارة"
                                    + "\nTrunk can only be opened when the car is stopped";
                        } else if (result == CanBusClient.COMMAND_REFUSED_SPEED_UNKNOWN) {
                            message = "تعذر قراءة سرعة السيارة الآن، حاول مرة أخرى بعد لحظات"
                                    + "\nCan't read the car's speed right now. Please try again in a moment";
                        } else {
                            message = null;
                        }
                        if (message != null) {
                            h.runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    h.evalWebViewJs("if (typeof window.__onTrunkRefused === 'function') window.__onTrunkRefused();");
                                    Toast.makeText(h, message, Toast.LENGTH_LONG).show();
                                }
                            });
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error handling CAN command", e);
            }
        }

        @JavascriptInterface
        public void saveSettings(final String settingsJson) {
            try {
                host().getSharedPreferences(PREF_HUB, MODE_PRIVATE)
                        .edit()
                        .putString("user_settings", settingsJson)
                        .apply();
            } catch (Exception e) {
                Log.e(TAG, "Error saving settings", e);
            }
        }

        @JavascriptInterface
        public String getSettings() {
            try {
                return host().getSharedPreferences(PREF_HUB, MODE_PRIVATE).getString("user_settings", "");
            } catch (Exception e) {
                Log.e(TAG, "Error reading settings", e);
                return "";
            }
        }

        @JavascriptInterface
        public void setDefaultLauncher() {
            SystemControlHelper.openDefaultLauncherSettings(host());
        }

        @JavascriptInterface
        public void setAutoBoot(final boolean enabled) {
            try {
                host().getSharedPreferences(PREF_HUB, MODE_PRIVATE)
                        .edit()
                        .putBoolean("auto_boot_enabled", enabled)
                        .apply();
            } catch (Exception e) {
                Log.e(TAG, "Error saving auto_boot_enabled", e);
            }
        }

        @JavascriptInterface
        public boolean isWatchdogEnabled() {
            return LauncherWatchdog.isEnabled(host());
        }

        @JavascriptInterface
        public void setWatchdogEnabled(final boolean enabled) {
            LauncherWatchdog.setEnabled(host(), enabled);
        }

        @JavascriptInterface
        public String getClockSyncJson() {
            return OfflineTimeKeeper.getClockSyncJson(host());
        }

        @JavascriptInterface
        public void setOfflineTimeGuard(final boolean enabled) {
            final MainActivity h = host();
            OfflineTimeKeeper.setEnabled(h, enabled, h.mClockSyncPushRunnable);
        }

        @JavascriptInterface
        public void setOfflineTimeZoneMode(final String mode) {
            final MainActivity h = host();
            OfflineTimeKeeper.setTimeZoneMode(h, mode, h.mClockSyncPushRunnable);
        }

        @JavascriptInterface
        public void checkForUpdates(final boolean silentIfUpToDate) {
            MainActivity h = host();
            GitHubUpdateChecker.checkForUpdates(h, silentIfUpToDate);
        }
    }

    private static String buildFastControlSig(CanBusClient.TelemetrySnapshot s) {
        return s.speed + "|" + s.gear + "|" + s.swcAngle + "|" + s.swcDirection
            + "|" + (s.lowBeam ? 1 : 0) + (s.highBeam ? 1 : 0) + (s.hazard ? 1 : 0)
            + (s.fogFront ? 1 : 0) + (s.posLight ? 1 : 0) + (s.dirL ? 1 : 0) + (s.dirR ? 1 : 0)
            + "|" + s.doorFL + s.doorFR + s.doorRL + s.doorRR + s.doorTrunk + s.doorHood;
    }

    private static void appendFastControlFields(JSONObject tele, CanBusClient.TelemetrySnapshot s) throws JSONException {
        tele.put("speed", s.speed);
        tele.put("canConnected", s.canConnected);
        JSONObject lights = new JSONObject();
        lights.put("lowBeam", s.lowBeam);
        lights.put("highBeam", s.highBeam);
        lights.put("hazard", s.hazard);
        lights.put("fogFront", s.fogFront);
        lights.put("posLight", s.posLight);
        lights.put("dirL", s.dirL);
        lights.put("dirR", s.dirR);
        tele.put("lights", lights);
        JSONObject doors = new JSONObject();
        doors.put("fl", s.doorFL);
        doors.put("fr", s.doorFR);
        doors.put("rl", s.doorRL);
        doors.put("rr", s.doorRR);
        doors.put("trunk", s.doorTrunk);
        doors.put("hood", s.doorHood);
        tele.put("doors", doors);
        tele.put("swcDirection", s.swcDirection);
        tele.put("swcAngle", s.swcAngle);
        tele.put("gear", s.gear);
    }

    private void dispatchTelemetryToWebView(final String teleStr) {
        evalWebViewJs("window.onCanTelemetry(" + teleStr + ");");
    }

    private void startTelemetryLoop() {
        mTelemetryExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    final long nowElapsed = SystemClock.elapsedRealtime();
                    if (mLastTimeGuardCheckMs == 0L || (nowElapsed - mLastTimeGuardCheckMs) >= 60000L) {
                        mLastTimeGuardCheckMs = nowElapsed;
                        OfflineTimeKeeper.enforceTimeZoneAndClock(MainActivity.this, "periodic", mClockSyncPushRunnable);
                    }
                    if (!mIsForeground) return;
                    refreshAvmOverlayState();
                    final boolean needFullPoll = (mLastFullTelemetryMs == 0L) || ((nowElapsed - mLastFullTelemetryMs) >= 1000L);
                    final JSONObject tele = new JSONObject();

                    if (mCanBusClient != null && mCanBusClient.isConnected()) {
                        if (!needFullPoll) {
                            CanBusClient.TelemetrySnapshot fastSnap = mCanBusClient.getFastControlSnapshot();
                            String fastSig = buildFastControlSig(fastSnap);
                            if (!fastSig.equals(mLastFastSig)) {
                                mLastFastSig = fastSig;
                                appendFastControlFields(tele, fastSnap);
                                dispatchTelemetryToWebView(tele.toString());
                            }
                            return;
                        }
                        mLastFullTelemetryMs = nowElapsed;
                        CanBusClient.TelemetrySnapshot snap = mCanBusClient.getTelemetrySnapshot(false);
                        mLastFastSig = buildFastControlSig(snap);
                        appendFastControlFields(tele, snap);
                        tele.put("coolant", snap.coolantTemp > 0 ? snap.coolantTemp : 90);
                        tele.put("ambient", snap.outsideTemp > -40 ? snap.outsideTemp : 27);

                        float avgVal = (snap.avgFuel > 0.1f && snap.avgFuel <= 50.0f) ? snap.avgFuel : 6.3f;
                        float pctVal = (snap.fuelPercentage > 0.0f && snap.fuelPercentage <= 100.0f) ? snap.fuelPercentage : 31.0f;
                        float capVal = snap.fuelCapacity > 0 ? snap.fuelCapacity : 55.0f;
                        float remainLiters = snap.fuelRemain > 0 ? snap.fuelRemain : (capVal * (pctVal / 100.0f));
                        int dteVal = (snap.dte > 0 && snap.dte < 2000) ? snap.dte : 290;

                        tele.put("instFuel", Math.round(snap.instFuel * 10.0) / 10.0);
                        tele.put("avgFuel", Math.round(avgVal * 10.0) / 10.0);
                        tele.put("fuelRemain", Math.round(remainLiters * 10.0) / 10.0);
                        tele.put("fuelCapacity", Math.round(capVal));
                        tele.put("fuelPct", Math.round(pctVal));
                        tele.put("range", dteVal);
                        tele.put("fuelShortage", snap.isFuelShortage);
                        tele.put("sportMode", snap.sportMode);

                        JSONObject tpms = new JSONObject();
                        JSONObject fl = new JSONObject(); fl.put("pressure", snap.tpmsFL); fl.put("warn", snap.tpmsFLWarn);
                        JSONObject fr = new JSONObject(); fr.put("pressure", snap.tpmsFR); fr.put("warn", snap.tpmsFRWarn);
                        JSONObject rl = new JSONObject(); rl.put("pressure", snap.tpmsRL); rl.put("warn", snap.tpmsRLWarn);
                        JSONObject rr = new JSONObject(); rr.put("pressure", snap.tpmsRR); rr.put("warn", snap.tpmsRRWarn);
                        tpms.put("fl", fl); tpms.put("fr", fr); tpms.put("rl", rl); tpms.put("rr", rr);
                        tele.put("tpms", tpms);
                        tele.put("sunroof", snap.sunroofState);
                        tele.put("sunroofPos", snap.sunroofPos);
                        tele.put("sunroofTilt", snap.sunroofTilt);

                        JSONObject locks = new JSONObject();
                        locks.put("speedLock", snap.speedLock);
                        locks.put("walkLock", snap.walkLock);
                        locks.put("offUnlock", snap.offUnlock);
                        tele.put("locks", locks);
                    } else {
                        if (!needFullPoll) return;
                        mLastFullTelemetryMs = nowElapsed;
                        buildSimulatedTelemetry(tele);
                    }

                    final double curFuelRemain = tele.optDouble("fuelRemain", 17.1);
                    final int curFuelPct = tele.optInt("fuelPct", 31);
                    if (Math.abs(curFuelRemain - mLastSentFuelRemain) > 0.05 || curFuelPct != mLastSentFuelPct) {
                        mLastSentFuelRemain = curFuelRemain;
                        mLastSentFuelPct = curFuelPct;
                        try {
                            getSharedPreferences(PREF_HUB, MODE_PRIVATE)
                                    .edit()
                                    .putFloat("last_fuel_remain", (float) curFuelRemain)
                                    .putInt("last_fuel_pct", curFuelPct)
                                    .apply();
                            Intent fuelIntent = new Intent("com.titan.pip.ACTION_FUEL_UPDATE");
                            fuelIntent.putExtra("fuelRemain", curFuelRemain);
                            fuelIntent.putExtra("fuelPct", curFuelPct);
                            sendBroadcast(fuelIntent);
                        } catch (Throwable ignored) {}
                    }

                    dispatchTelemetryToWebView(tele.toString());
                } catch (Exception e) {
                    Log.e(TAG, "Telemetry loop error", e);
                }
            }
        }, 1500, 120, TimeUnit.MILLISECONDS);
    }

    private void buildSimulatedTelemetry(JSONObject tele) throws JSONException {
        tele.put("canConnected", false);
        tele.put("sunroof", "close");
        tele.put("sunroofPos", 0);
        tele.put("sunroofTilt", 0);
        JSONObject locks = new JSONObject();
        locks.put("speedLock", -1);
        locks.put("walkLock", -1);
        locks.put("offUnlock", -1);
        tele.put("locks", locks);
        if (mSpeedIncreasing) {
            mSimSpeed += 2;
            if (mSimSpeed >= 65) mSpeedIncreasing = false;
        } else {
            mSimSpeed -= 2;
            if (mSimSpeed <= 0) {
                mSimSpeed = 0;
                mSpeedIncreasing = true;
            }
        }
        tele.put("speed", mSimSpeed);
        tele.put("coolant", 90);
        tele.put("ambient", 24);
        float simInst = mSimSpeed > 0 ? (float) (5.2 + (mSimSpeed / 65.0) * 2.8 + Math.sin(System.currentTimeMillis() / 2500.0) * 0.9) : 0.8f;
        tele.put("instFuel", Math.round(simInst * 10.0) / 10.0);
        tele.put("avgFuel", 6.3);
        tele.put("fuelRemain", 17.1);
        tele.put("fuelCapacity", 55);
        tele.put("fuelPct", 31);
        tele.put("range", 290);
        tele.put("fuelShortage", false);

        JSONObject lights = new JSONObject();
        lights.put("lowBeam", false);
        lights.put("highBeam", false);
        lights.put("hazard", false);
        lights.put("fogFront", false);
        lights.put("posLight", false);
        lights.put("dirL", false);
        lights.put("dirR", false);
        tele.put("lights", lights);

        JSONObject doors = new JSONObject();
        doors.put("fl", 0); doors.put("fr", 0); doors.put("rl", 0); doors.put("rr", 0);
        doors.put("trunk", 0); doors.put("hood", 0);
        tele.put("doors", doors);

        tele.put("swcDirection", 0);
        tele.put("swcAngle", 0);
        tele.put("sportMode", false);

        JSONObject tpms = new JSONObject();
        JSONObject fl = new JSONObject(); fl.put("pressure", 2.3); fl.put("warn", false);
        JSONObject fr = new JSONObject(); fr.put("pressure", 2.3); fr.put("warn", false);
        JSONObject rl = new JSONObject(); rl.put("pressure", 2.3); rl.put("warn", false);
        JSONObject rr = new JSONObject(); rr.put("pressure", 2.3); rr.put("warn", false);
        tpms.put("fl", fl); tpms.put("fr", fr); tpms.put("rl", rl); tpms.put("rr", rr);
        tele.put("tpms", tpms);
    }

    private void refreshAvmOverlayState() {
        if (mCanBusClient == null) return;
        int avmState = mCanBusClient.getSystemAvmState();
        if (avmState < 0 || avmState == mLastAvmState) return;
        mLastAvmState = avmState;
        final boolean active = avmState != 0;
        Log.i(TAG, "AVM overlay state changed: " + avmState + "; pausing 3D=" + active);
        applyAvmOverlayStateToWebView(active);
    }

    private void applyAvmOverlayStateToWebView(final boolean active) {
        String js = "window.__avmOverlayActive = " + active + "; "
                + "if (typeof window.setCameraOverlayActive === 'function') { window.setCameraOverlayActive(" + active + "); } "
                + "if (!" + active + " && " + mIsForeground + " && typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(false); }";
        evalWebViewJs(js);
    }

    @Override
    protected void onDestroy() {
        stopShineWaveEngine();
        mAudioAnalyzer.release();
        if (mSwcHandler != null) mSwcHandler.release();
        if (mCanBusClient != null) mCanBusClient.release();
        mHandler.removeCallbacksAndMessages(null);
        if (!mTelemetryExecutor.isShutdown()) mTelemetryExecutor.shutdown();
        if (!mExecutor.isShutdown()) mExecutor.shutdown();
        if (!mNetExecutor.isShutdown()) mNetExecutor.shutdownNow();
        unregisterReceiverSafe(mCar3DFreezeReceiver);
        unregisterReceiverSafe(mConnectivityReceiver);
        unregisterReceiverSafe(mTimeGuardReceiver);
        unregisterReceiverSafe(mMediaBroadcastReceiver);

        if (mWebView != null) {
            ViewGroup parent = (mWebView.getParent() instanceof ViewGroup)
                    ? (ViewGroup) mWebView.getParent() : null;
            if (parent != null) {
                parent.removeView(mWebView);
            }
            mWebView = null;
        }
        if (sActiveInstance == this) {
            sActiveInstance = null;
        }
        if (sWebViewContext != null) {
            sWebViewContext.setBaseContext(getApplicationContext());
        }

        final Context appCtx = getApplicationContext();
        if (mFinishedWhileForeground && !sRestoreScheduled) {
            sRestoreScheduled = true;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    sRestoreScheduled = false;
                    try {
                        Intent restore = new Intent(appCtx, MainActivity.class);
                        restore.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                                | Intent.FLAG_ACTIVITY_NO_ANIMATION
                                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                        appCtx.startActivity(restore);
                    } catch (Throwable t) {
                        Log.e(TAG, "Warm restore failed", t);
                    }
                }
            }, 40L);
        }
        super.onDestroy();
    }
}
