/*
 * Titan Cockpit Launcher
 * Created by Dr. Saed
 * For more info contact: saedmagdysms@gmail.com
 */
package com.dongfeng.shinecockpit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
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
    private TripComputer mTrip;
    private SteeringWheelKeyHandler mSwcHandler;

    private volatile boolean mIsForeground = true;
    private volatile int mLastAvmState = -1;
    private volatile long mLastTimeGuardCheckMs = 0L;
    private int mSimSpeed = 0;
    private boolean mSpeedIncreasing = true;
    private double mLastSentFuelRemain = -1.0;
    private int mLastSentFuelPct = -1;
    private String mLastFastSig = "";
    private FuelWarningMonitor mFuelWarning;
    private TyreWarningMonitor mTyreWarning;
    private final SpeedAlertMonitor mSpeedAlert = new SpeedAlertMonitor();
    private long mLastBackgroundSpeedCheckMs = 0L;
    private long mLastBackgroundFuelCheckMs = 0L;
    private Dialog mFuelWarningDialog;
    private FuelWarningVoice mFuelVoice;
    /** How long the low-fuel popup stays on screen if OK is not pressed. */
    private static final long FUEL_POPUP_AUTO_CLOSE_MS = 20000L;
    /** While another app (e.g. Maps) is on screen, check the fuel this often. */
    private static final long BACKGROUND_FUEL_CHECK_MS = 15000L;
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
        mFuelWarning = new FuelWarningMonitor(getSharedPreferences(PREF_HUB, MODE_PRIVATE));
        mTyreWarning = new TyreWarningMonitor(getSharedPreferences(PREF_HUB, MODE_PRIVATE));
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
        startTripLoop();
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

    /** Small HTTPS GET for the weather card only (Open-Meteo addresses built in the methods above). */
    private static String weatherHttpGet(String url) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return null;
            try (java.io.InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                    if (buf.size() > 65536) return null;
                }
                return buf.toString("UTF-8");
            }
        } catch (Exception e) {
            Log.w(TAG, "Weather request failed (no internet?)", e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
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

        /**
         * "Try" button in Settings: says the low-fuel warning out loud, so the driver can hear it.
         * Tells the page whether the words were spoken or only the chime played (no voice installed).
         */
        /** "Find a petrol station" button in the launcher's low-fuel popup. */
        @JavascriptInterface
        public void openFuelStationSearch(final String lang) {
            final String safeLang = ("ar".equals(lang) || "ru".equals(lang)) ? lang : "en";
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.openFuelStationSearch(safeLang);
                }
            });
        }

        /** "Try" button of the speed alert: plays the alert (voice or chime) for the chosen speed. */
        @JavascriptInterface
        public void previewSpeedAlert(final int limit, final boolean voice, final String lang) {
            final int safeLimit = Math.max(SpeedAlertMonitor.MIN_LIMIT, Math.min(SpeedAlertMonitor.MAX_LIMIT, limit));
            final String safeLang = ("ar".equals(lang) || "ru".equals(lang)) ? lang : "en";
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    playSpeedAlertSound(safeLimit, safeLang, voice, voice ? new FuelWarningVoice.ResultListener() {
                        @Override
                        public void onResult(boolean spoken) {
                            evalWebViewJs("if (typeof window.onFuelVoicePreviewResult === 'function') { window.onFuelVoicePreviewResult("
                                    + spoken + "); }");
                        }
                    } : null);
                }
            });
        }

        /** "Try" button of the tyre warning: says an example tyre warning out loud. */
        @JavascriptInterface
        public void previewTyreWarningVoice(final String lang) {
            final String safeLang = ("ar".equals(lang) || "ru".equals(lang)) ? lang : "en";
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    speakTyreWarning(1, safeLang, new FuelWarningVoice.ResultListener() {
                        @Override
                        public void onResult(boolean spoken) {
                            evalWebViewJs("if (typeof window.onFuelVoicePreviewResult === 'function') { window.onFuelVoicePreviewResult("
                                    + spoken + "); }");
                        }
                    });
                }
            });
        }

        @JavascriptInterface
        public void previewFuelWarningVoice(final boolean urgent, final int pct, final String lang) {
            final int safePct = Math.max(0, Math.min(100, pct));
            final String safeLang = ("ar".equals(lang) || "ru".equals(lang)) ? lang : "en";
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    speakFuelWarning(urgent, safePct, safeLang, new FuelWarningVoice.ResultListener() {
                        @Override
                        public void onResult(boolean spoken) {
                            evalWebViewJs("if (typeof window.onFuelVoicePreviewResult === 'function') { window.onFuelVoicePreviewResult("
                                    + spoken + "); }");
                        }
                    });
                }
            });
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

        /** "Back up" button in Settings: saves the given settings file (the page builds it). */
        @JavascriptInterface
        public void backupSettings(final String backupJson) {
            if (backupJson == null || backupJson.length() > SETTINGS_BACKUP_MAX_BYTES) return;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    startSettingsBackup(backupJson);
                }
            });
        }

        /** "Restore" button in Settings: lets the driver pick a backup file; the page checks it and asks first. */
        @JavascriptInterface
        public void restoreSettings() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    startSettingsRestore();
                }
            });
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

        /**
         * Weather card: today's forecast for the city the driver chose, from Open-Meteo
         * (free, no account). Only the city's coordinates are sent, never the car's position.
         * The answer goes to window.onWeatherData(text).
         */
        @JavascriptInterface
        public void fetchWeather(final double lat, final double lon) {
            if (!(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)) return;
            mNetExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    String url = String.format(java.util.Locale.US,
                            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f"
                                    + "&current=temperature_2m,weather_code"
                                    + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                                    + "&timezone=auto&forecast_days=1", lat, lon);
                    String body = weatherHttpGet(url);
                    evalWebViewJs("if (typeof window.onWeatherData === 'function') { window.onWeatherData("
                            + (body == null ? "null" : JSONObject.quote(body)) + "); }");
                }
            });
        }

        /** Weather settings "Find": looks up a city name. The answer goes to window.onWeatherCities(text). */
        @JavascriptInterface
        public void searchWeatherCity(final String name, final String lang) {
            if (name == null) return;
            final String q = name.trim();
            if (q.isEmpty() || q.length() > 60) return;
            final String l = ("ar".equals(lang) || "ru".equals(lang)) ? lang : "en";
            mNetExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    String body = null;
                    try {
                        String url = "https://geocoding-api.open-meteo.com/v1/search?count=5&format=json&language=" + l
                                + "&name=" + java.net.URLEncoder.encode(q, "UTF-8");
                        body = weatherHttpGet(url);
                    } catch (Exception ignored) {}
                    evalWebViewJs("if (typeof window.onWeatherCities === 'function') { window.onWeatherCities("
                            + (body == null ? "null" : JSONObject.quote(body)) + "); }");
                }
            });
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

        /** Trip computer popup: the current trips A and B as JSON. */
        @JavascriptInterface
        public String getTripData() {
            return mTrip == null ? "{}" : mTrip.toJson();
        }

        /** Trip computer "Reset" button: "a" or "b". */
        @JavascriptInterface
        public void resetTrip(final String which) {
            if (mTrip != null) mTrip.reset("b".equals(which) ? 1 : 0);
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

    /**
     * Trip computer: reads odometer, fuel and speed every 10 seconds, also while another app
     * is on screen, so trips keep counting over Maps. Uses the same single background thread
     * as the telemetry loop, so the two never talk to the car at the same moment.
     */
    private void startTripLoop() {
        mTrip = new TripComputer(getSharedPreferences(PREF_HUB, MODE_PRIVATE));
        mTelemetryExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mCanBusClient == null) return;
                    CanBusClient.TelemetrySnapshot s = mCanBusClient.getTelemetrySnapshot(true);
                    if (!s.canConnected) return;
                    float cap = s.fuelCapacity > 0 ? s.fuelCapacity : 55.0f;
                    double litres = cap * (s.fuelPercentage / 100.0);
                    mTrip.onReading(s.odometerValid, s.totalMileage, s.fuelPctValid, litres,
                            s.speed, SystemClock.elapsedRealtime());
                } catch (Exception e) {
                    Log.e(TAG, "Trip computer update failed", e);
                }
            }
        }, 5, 10, TimeUnit.SECONDS);
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
                    if (!mIsForeground) {
                        backgroundFuelCheck();
                        backgroundSpeedCheck();
                        return;
                    }
                    refreshAvmOverlayState();
                    final boolean needFullPoll = (mLastFullTelemetryMs == 0L) || ((nowElapsed - mLastFullTelemetryMs) >= 1000L);
                    final JSONObject tele = new JSONObject();

                    if (mCanBusClient != null && mCanBusClient.isConnected()) {
                        if (!needFullPoll) {
                            CanBusClient.TelemetrySnapshot fastSnap = mCanBusClient.getFastControlSnapshot();
                            checkSpeedAlert(fastSnap.speed);
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
                        if (snap.fuelPctValid) checkFuelWarning(snap.fuelPercentage);
                        if (snap.tpmsValid) checkTyreWarning(snap);
                        checkSpeedAlert(snap.speed);
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

    /* ========================================================
       LOW FUEL WARNING POPUP
       ======================================================== */

    /** While another app is on screen, the normal 1-second poll is paused; check fuel and tyres every 15 s instead. */
    private void backgroundFuelCheck() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastBackgroundFuelCheckMs < BACKGROUND_FUEL_CHECK_MS) return;
        mLastBackgroundFuelCheckMs = now;
        if (mCanBusClient == null) return;
        try {
            CanBusClient.TelemetrySnapshot snap = mCanBusClient.getTelemetrySnapshot(true);
            if (snap.canConnected && snap.fuelPctValid) checkFuelWarning(snap.fuelPercentage);
            CanBusClient.TelemetrySnapshot tyres = mCanBusClient.getTpmsSnapshot();
            if (tyres.canConnected && tyres.tpmsValid) checkTyreWarning(tyres);
        } catch (Exception e) {
            Log.e(TAG, "Background fuel/tyre check failed", e);
        }
    }

    /** Called with every real fuel reading; shows the popup when a warning level is reached. */
    private void checkFuelWarning(final float pct) {
        if (mFuelWarning == null) return;
        String settings = "";
        try {
            settings = getSharedPreferences(PREF_HUB, MODE_PRIVATE).getString("user_settings", "");
        } catch (Exception ignored) {}
        final int level = mFuelWarning.onReading(pct, SystemClock.elapsedRealtime(), settings);
        if (level <= 0) return;
        final int shownPct = Math.max(0, Math.min(100, Math.round(pct)));
        final String lang = readSettingString(settings, "lang", "en");
        final boolean eastern = "eastern".equals(readSettingString(settings, "numFormat", "western"));
        final boolean urgent = mFuelWarning.isLowestLevel(level);
        final boolean voice = readSettingBoolean(settings, "fuelWarnVoice", true);
        Log.i(TAG, "Low fuel warning: tank " + shownPct + "% reached level " + level + "%");
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (voice) speakFuelWarning(urgent, shownPct, lang, null);
                if (mIsForeground && mWebView != null) {
                    // Launcher on screen: the launcher's own popup (OK button, closes after 20 s).
                    mWebView.evaluateJavascript("if (typeof window.showFuelWarning === 'function') { window.showFuelWarning("
                            + level + ", " + shownPct + "); }", null);
                } else {
                    // Another app on screen: a system popup on top of it.
                    showFuelWarningOverOtherApps(urgent, shownPct, lang, eastern);
                }
            }
        });
    }

    private static boolean readSettingBoolean(String json, String key, boolean fallback) {
        try {
            if (json != null && !json.isEmpty()) return new JSONObject(json).optBoolean(key, fallback);
        } catch (Exception ignored) {}
        return fallback;
    }

    private static String readSettingString(String json, String key, String fallback) {
        try {
            if (json != null && !json.isEmpty()) return new JSONObject(json).optString(key, fallback);
        } catch (Exception ignored) {}
        return fallback;
    }

    /**
     * The warning words, in the launcher's language: {title, message, OK button}.
     * First level: "Plan a stop at a petrol station." Lowest level: "Refuel soon."
     * forSpeech: no fuel-pump emoji, plain digits, and "percent" as a word so the voice reads it well.
     */
    private static String[] fuelWarningTexts(boolean urgent, int pct, String lang, boolean eastern, boolean forSpeech) {
        String p = digits(pct, eastern && !forSpeech);
        String pump = forSpeech ? "" : "⛽ ";
        if ("ar".equals(lang)) {
            return new String[] {
                    pump + "وقود منخفض",
                    "الوقود في الخزان " + p + (forSpeech ? " بالمئة" : "٪") + ".\n"
                            + (urgent ? "تزوّد بالوقود قريباً." : "خطط للتوقف في محطة وقود."),
                    "حسناً" };
        } else if ("ru".equals(lang)) {
            return new String[] {
                    pump + "Мало топлива",
                    "В баке " + p + (forSpeech ? " процентов" : "%") + ".\n"
                            + (urgent ? "Заправьтесь как можно скорее." : "Запланируйте заправку."),
                    "ОК" };
        }
        return new String[] {
                pump + "Low fuel",
                "Fuel tank is at " + p + (forSpeech ? " percent" : "%") + ".\n"
                        + (urgent ? "Refuel soon." : "Plan a stop at a petrol station."),
                "OK" };
    }

    /** Says the warning out loud (or plays a chime if the head unit has no voice for this language). */
    private void speakFuelWarning(boolean urgent, int pct, String lang, FuelWarningVoice.ResultListener listener) {
        if (mFuelVoice == null) mFuelVoice = new FuelWarningVoice(this);
        String[] t = fuelWarningTexts(urgent, pct, lang, false, true);
        mFuelVoice.speak(t[0] + ". " + t[1].replace('\n', ' '), lang, listener);
    }

    /** Text of the "Find a petrol station" button, in the launcher's language. */
    private static String findStationLabel(String lang) {
        if ("ar".equals(lang)) return "ابحث عن محطة وقود";
        if ("ru".equals(lang)) return "Найти АЗС";
        return "Find a petrol station";
    }

    /**
     * Opens the map app searching for petrol stations near the car.
     * Uses the standard "geo:" link, so it works with Google Maps, Waze or the car's own maps,
     * whichever the head unit uses for maps. If no map app is installed, it says so.
     */
    private void openFuelStationSearch(String lang) {
        String query = "ar".equals(lang) ? "محطة وقود" : "ru".equals(lang) ? "АЗС" : "petrol station";
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(query)));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivity(i);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not open maps for petrol stations", e);
        }
        String msg = "ar".equals(lang) ? "لا يوجد تطبيق خرائط على الشاشة"
                : "ru".equals(lang) ? "Нет приложения карт" : "No maps app found on this head unit";
        try {
            Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) {}
    }

    private void showFuelWarningOverOtherApps(boolean urgent, int pct, final String lang, boolean eastern) {
        String[] t = fuelWarningTexts(urgent, pct, lang, eastern, false);
        // Second (lowest) level only: a button that opens Maps at the nearest petrol stations.
        Runnable findStation = urgent ? new Runnable() {
            @Override
            public void run() {
                openFuelStationSearch(lang);
            }
        } : null;
        showAlertOverOtherApps(t[0], t[1], t[2], urgent ? findStationLabel(lang) : null, findStation);
    }

    /**
     * Popup shown above other apps (Maps, music...) for the fuel and tyre warnings:
     * about 65% of the screen wide and 25% high. OK closes it; it also closes by itself
     * after 20 seconds. extraLabel/extraAction add an optional second button.
     */
    private void showAlertOverOtherApps(String title, String body, String ok,
                                        String extraLabel, final Runnable extraAction) {
        try {
            dismissFuelWarningDialog();
            @SuppressWarnings("deprecation")
            AlertDialog.Builder builder = new AlertDialog.Builder(getApplicationContext(), AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle(title)
                    .setMessage(body)
                    .setPositiveButton(ok, null);
            if (extraLabel != null && extraAction != null) {
                builder.setNegativeButton(extraLabel, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        extraAction.run();
                    }
                });
            }
            AlertDialog dialog = builder.create();
            Window w = dialog.getWindow();
            if (w != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                } else {
                    @SuppressWarnings("deprecation")
                    int phoneType = WindowManager.LayoutParams.TYPE_PHONE;
                    w.setType(phoneType);
                }
            }
            dialog.show();
            if (w != null) {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                // 65% of the screen wide; at least 25% of the screen high (taller only if the text needs it).
                w.setLayout((int) (dm.widthPixels * 0.65f), ViewGroup.LayoutParams.WRAP_CONTENT);
                w.getDecorView().setMinimumHeight((int) (dm.heightPixels * 0.25f));
            }
            mFuelWarningDialog = dialog;
            final Dialog shown = dialog;
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (mFuelWarningDialog == shown) dismissFuelWarningDialog();
                }
            }, FUEL_POPUP_AUTO_CLOSE_MS);
        } catch (Exception e) {
            // No permission to draw over other apps: fall back to a plain message.
            Log.w(TAG, "Popup over other apps not allowed; showing a toast instead", e);
            try {
                Toast.makeText(getApplicationContext(), title + "\n" + body, Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
        }
    }

    private void dismissFuelWarningDialog() {
        try {
            if (mFuelWarningDialog != null && mFuelWarningDialog.isShowing()) mFuelWarningDialog.dismiss();
        } catch (Exception ignored) {}
        mFuelWarningDialog = null;
    }

    private static String digits(int value, boolean eastern) {
        String s = String.valueOf(value);
        if (!eastern) return s;
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c >= '0' && c <= '9' ? (char) ('٠' + (c - '0')) : c);
        return b.toString();
    }

    /* ========================================================
       TYRE PRESSURE WARNING POPUP
       ======================================================== */

    /** Called with every real tyre reading; shows the popup when a tyre is low. */
    private void checkTyreWarning(CanBusClient.TelemetrySnapshot snap) {
        if (mTyreWarning == null) return;
        String settings = "";
        try {
            settings = getSharedPreferences(PREF_HUB, MODE_PRIVATE).getString("user_settings", "");
        } catch (Exception ignored) {}
        final boolean[] flags = { snap.tpmsFLWarn, snap.tpmsFRWarn, snap.tpmsRLWarn, snap.tpmsRRWarn };
        final float[] bars = { snap.tpmsFL, snap.tpmsFR, snap.tpmsRL, snap.tpmsRR };
        final boolean[] valid = snap.tpmsPressureValid.clone();
        boolean enabled = readSettingBoolean(settings, "tyreWarnEnabled", true);
        final int mask = mTyreWarning.onReading(flags, bars, valid, enabled, SystemClock.elapsedRealtime());
        if (mask == 0) return;
        final String lang = readSettingString(settings, "lang", "en");
        final boolean eastern = "eastern".equals(readSettingString(settings, "numFormat", "western"));
        final boolean voice = readSettingBoolean(settings, "tyreWarnVoice", true);
        Log.i(TAG, "Tyre pressure warning for tyres (mask) " + mask);
        // For the launcher's own popup: [{"pos":"fl","bar":1.6}, ...] (bar is null when the car gave no pressure).
        final StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < TyreWarningMonitor.TYRES; i++) {
            if ((mask & (1 << i)) == 0) continue;
            if (json.length() > 1) json.append(',');
            json.append("{\"pos\":\"").append(TYRE_KEYS[i]).append("\",\"bar\":")
                    .append(valid[i] ? String.valueOf(bars[i]) : "null").append('}');
        }
        json.append(']');
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (voice) speakTyreWarning(mask, lang, null);
                if (mIsForeground && mWebView != null) {
                    mWebView.evaluateJavascript("if (typeof window.showTyreWarning === 'function') { window.showTyreWarning("
                            + json + "); }", null);
                } else {
                    String[] t = tyreWarningTexts(mask, bars, valid, lang, eastern, false);
                    showAlertOverOtherApps(t[0], t[1], t[2], null, null);
                }
            }
        });
    }

    private static final String[] TYRE_KEYS = { "fl", "fr", "rl", "rr" };

    /**
     * The tyre warning words, in the launcher's language: {title, message, OK button}.
     * Example: "Check the tyre pressure: front-left 1.6 bar."
     * forSpeech: no warning sign and no numbers, so the voice reads it clearly.
     */
    private static String[] tyreWarningTexts(int mask, float[] bars, boolean[] valid, String lang,
                                             boolean eastern, boolean forSpeech) {
        String[] names;
        String unit;
        if ("ar".equals(lang)) {
            names = new String[] { "الأمامي الأيسر", "الأمامي الأيمن", "الخلفي الأيسر", "الخلفي الأيمن" };
            unit = " بار";
        } else if ("ru".equals(lang)) {
            names = new String[] { "переднее левое", "переднее правое", "заднее левое", "заднее правое" };
            unit = " бар";
        } else {
            names = new String[] { "front-left", "front-right", "rear-left", "rear-right" };
            unit = " bar";
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < TyreWarningMonitor.TYRES; i++) {
            if ((mask & (1 << i)) == 0) continue;
            if (list.length() > 0) list.append("ar".equals(lang) ? "، " : ", ");
            list.append(names[i]);
            if (!forSpeech && valid[i]) {
                String v = String.valueOf(Math.round(bars[i] * 10f) / 10f);
                if (eastern) {
                    StringBuilder e = new StringBuilder();
                    for (char c : v.toCharArray()) {
                        e.append(c >= '0' && c <= '9' ? (char) ('٠' + (c - '0')) : (c == '.' ? '٫' : c));
                    }
                    v = e.toString();
                }
                list.append(' ').append(v).append(unit);
            }
        }
        String sign = forSpeech ? "" : "⚠ ";
        if ("ar".equals(lang)) {
            return new String[] { sign + "ضغط الإطارات",
                    "افحص ضغط الإطارات: " + list + ".", "حسناً" };
        } else if ("ru".equals(lang)) {
            return new String[] { sign + "Давление в шинах",
                    "Проверьте давление в шинах: " + list + ".", "ОК" };
        }
        return new String[] { sign + "Tyre pressure",
                "Check the tyre pressure: " + list + ".", "OK" };
    }

    /** Says the tyre warning out loud (or a chime if the head unit has no voice for this language). */
    private void speakTyreWarning(int mask, String lang, FuelWarningVoice.ResultListener listener) {
        if (mFuelVoice == null) mFuelVoice = new FuelWarningVoice(this);
        String[] t = tyreWarningTexts(mask, new float[4], new boolean[4], lang, false, true);
        mFuelVoice.speak(t[0] + ". " + t[1], lang, listener);
    }

    /* ========================================================
       SPEED ALERT
       ======================================================== */

    /** While another app is on screen, read the speed once a second, but only if the alert is on. */
    private void backgroundSpeedCheck() {
        if (mSpeedAlert == null || mCanBusClient == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - mLastBackgroundSpeedCheckMs < 1000L) return;
        mLastBackgroundSpeedCheckMs = now;
        try {
            String settings = getSharedPreferences(PREF_HUB, MODE_PRIVATE).getString("user_settings", "");
            if (!mSpeedAlert.isEnabled(settings)) return;
            int kmh = mCanBusClient.getSpeed();
            if (kmh >= 0) checkSpeedAlert(kmh);
        } catch (Exception e) {
            Log.e(TAG, "Background speed check failed", e);
        }
    }

    /** Called with every speed reading; gives the alert when the chosen speed is passed. */
    private void checkSpeedAlert(int kmh) {
        if (mSpeedAlert == null) return;
        String settings = "";
        try {
            settings = getSharedPreferences(PREF_HUB, MODE_PRIVATE).getString("user_settings", "");
        } catch (Exception ignored) {}
        final int limit = mSpeedAlert.onSpeed(kmh, SystemClock.elapsedRealtime(), settings);
        if (limit <= 0) return;
        final String lang = readSettingString(settings, "lang", "en");
        final boolean eastern = "eastern".equals(readSettingString(settings, "numFormat", "western"));
        final boolean voice = mSpeedAlert.useVoice();
        Log.i(TAG, "Speed alert: over " + limit + " km/h");
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                playSpeedAlertSound(limit, lang, voice, null);
                if (mIsForeground && mWebView != null) {
                    mWebView.evaluateJavascript("if (typeof window.showSpeedAlert === 'function') { window.showSpeedAlert("
                            + limit + "); }", null);
                } else {
                    // Another app on screen (e.g. Maps): a short message that does not block the map.
                    try {
                        Toast.makeText(getApplicationContext(), speedAlertText(limit, lang, eastern, false), Toast.LENGTH_LONG).show();
                    } catch (Exception ignored) {}
                }
            }
        });
    }

    /** "Speed over 120 km/h" in the launcher's language (forSpeech: no sign, no unit). */
    private static String speedAlertText(int limit, String lang, boolean eastern, boolean forSpeech) {
        String n = digits(limit, eastern && !forSpeech);
        String sign = forSpeech ? "" : "⚠ ";
        if ("ar".equals(lang)) return sign + "السرعة فوق " + n + (forSpeech ? "" : " كم/س");
        if ("ru".equals(lang)) return sign + "Скорость выше " + n + (forSpeech ? "" : " км/ч");
        return sign + "Speed over " + n + (forSpeech ? "" : " km/h");
    }

    /** The spoken alert ("Speed over 120"), or just a short chime when the Voice switch is off. */
    private void playSpeedAlertSound(int limit, String lang, boolean voice, FuelWarningVoice.ResultListener listener) {
        if (mFuelVoice == null) mFuelVoice = new FuelWarningVoice(this);
        if (voice) {
            mFuelVoice.speak(speedAlertText(limit, lang, false, true) + ".", lang, listener);
        } else {
            mFuelVoice.chimeOnly();
        }
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

    /* ========================================================
       BACK UP AND RESTORE SETTINGS
       The page builds the backup (only the launcher's own settings: quick apps, car
       colour, language, alerts... never passwords or Wi-Fi). Java only saves or opens
       the file. Android's file picker is used, so the driver can choose a USB stick or
       the head unit's storage. If the head unit has no file picker, the file goes to
       Download/titan-launcher-settings.json instead.
       ======================================================== */
    private static final int REQ_SETTINGS_BACKUP = 7301;
    private static final int REQ_SETTINGS_RESTORE = 7302;
    private static final int SETTINGS_BACKUP_MAX_BYTES = 256 * 1024;
    private static final String SETTINGS_BACKUP_FALLBACK_NAME = "titan-launcher-settings.json";
    private String mPendingBackupJson;

    private void startSettingsBackup(String json) {
        mPendingBackupJson = json;
        String name = "titan-launcher-settings-"
                + new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date()) + ".json";
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE, name);
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(i, REQ_SETTINGS_BACKUP);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "No file picker for the settings backup", e);
        }
        // No file picker on this head unit: save to the Download folder.
        java.io.File f = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), SETTINGS_BACKUP_FALLBACK_NAME);
        boolean ok = false;
        try (java.io.OutputStream out = new java.io.FileOutputStream(f)) {
            out.write(json.getBytes("UTF-8"));
            ok = true;
        } catch (Exception e) {
            Log.e(TAG, "Settings backup to Download failed", e);
        }
        mPendingBackupJson = null;
        reportBackupResult(ok, ok ? "Download/" + SETTINGS_BACKUP_FALLBACK_NAME : "");
    }

    private void startSettingsRestore() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            if (i.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(i, REQ_SETTINGS_RESTORE);
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "No file picker for the settings restore", e);
        }
        // No file picker: try the file a backup without a picker would have written.
        java.io.File f = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), SETTINGS_BACKUP_FALLBACK_NAME);
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            deliverRestoreFile(readLimited(in));
        } catch (Exception e) {
            Log.e(TAG, "No settings backup found in Download", e);
            deliverRestoreFile(null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SETTINGS_BACKUP) {
            String json = mPendingBackupJson;
            mPendingBackupJson = null;
            if (resultCode != RESULT_OK || data == null || data.getData() == null || json == null) {
                reportBackupResult(false, "cancelled");
                return;
            }
            boolean ok = false;
            try (java.io.OutputStream out = getContentResolver().openOutputStream(data.getData(), "w")) {
                if (out != null) {
                    out.write(json.getBytes("UTF-8"));
                    ok = true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Settings backup failed", e);
            }
            reportBackupResult(ok, "");
        } else if (requestCode == REQ_SETTINGS_RESTORE) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return; // cancelled: nothing to do
            try (java.io.InputStream in = getContentResolver().openInputStream(data.getData())) {
                deliverRestoreFile(in == null ? null : readLimited(in));
            } catch (Exception e) {
                Log.e(TAG, "Could not read the settings backup", e);
                deliverRestoreFile(null);
            }
        }
    }

    /** Reads a backup file, refusing anything bigger than 256 KB (a real backup is a few KB). */
    private static String readLimited(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > SETTINGS_BACKUP_MAX_BYTES) return null;
        }
        return buf.toString("UTF-8");
    }

    private void reportBackupResult(boolean ok, String where) {
        evalWebViewJs("if (typeof window.onSettingsBackupResult === 'function') { window.onSettingsBackupResult("
                + ok + ", " + JSONObject.quote(where == null ? "" : where) + "); }");
    }

    /** Hands the file text to the page, which checks it and asks the driver before replacing anything. */
    private void deliverRestoreFile(String text) {
        evalWebViewJs("if (typeof window.onSettingsRestoreFile === 'function') { window.onSettingsRestoreFile("
                + (text == null ? "null" : JSONObject.quote(text)) + "); }");
    }

    @Override
    protected void onDestroy() {
        stopShineWaveEngine();
        mAudioAnalyzer.release();
        if (mSwcHandler != null) mSwcHandler.release();
        if (mCanBusClient != null) mCanBusClient.release();
        mHandler.removeCallbacksAndMessages(null);
        dismissFuelWarningDialog();
        if (mFuelVoice != null) mFuelVoice.shutdown();
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
