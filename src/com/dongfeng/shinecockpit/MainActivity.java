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

    private long mStartupStartMs;
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
    private int mSimSpeed = 0;
    private boolean mSpeedIncreasing = true;
    private double mLastSentFuelRemain = -1.0;
    private int mLastSentFuelPct = -1;
    private String mLastFastSig = "";
    private FuelWarningMonitor mFuelWarning;
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

    private final BroadcastReceiver mConnectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            evalWebViewJs("window.syncConnectivityFromAndroid && window.syncConnectivityFromAndroid();");
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
        mStartupStartMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "Startup: onCreate started");

        LauncherWatchdog.install(this, mHandler);
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
        mWebView = new WebView(this);
        root.addView(mWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        hideSystemUI();

        long canInitStartMs = SystemClock.elapsedRealtime();
        mCanBusClient = new CanBusClient(this);
        mFuelWarning = new FuelWarningMonitor(getSharedPreferences(PREF_HUB, MODE_PRIVATE));
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

        setupWebView();
        startTelemetryLoop();
        SystemControlHelper.ensureNotificationListenerAccess(this);
        CockpitMediaListenerService.setListener(this);
        startShineWaveEngine();
        mSwcHandler.registerQingganKeys();
        registerReceivers();
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
                mShineWave.release();
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
        evalWebViewJs("if (typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(false); }");
        if (mLastAvmState >= 0) applyAvmOverlayStateToWebView(mLastAvmState != 0);
        pushShineWaveState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsForeground = false;
        mAudioAnalyzer.release();
        evalWebViewJs("if (typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(true); }");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
        evalWebViewJs("if (typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(" + (!hasFocus) + "); }");
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
                LauncherWatchdog.handleFatalCrashAndRecovery(MainActivity.this, "RenderProcessGone (didCrash=" + didCrash + ")");
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.i(TAG, "Startup: WebView page finished at " + (SystemClock.elapsedRealtime() - mStartupStartMs) + "ms");
                if (mLastAvmState >= 0) applyAvmOverlayStateToWebView(mLastAvmState != 0);
                try {
                    String s = getSharedPreferences(PREF_HUB, MODE_PRIVATE).getString("user_settings", "");
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

        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    mAppRepo.getInstalledAppsJson(MainActivity.this);
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
        @JavascriptInterface
        public String getAudioVisualizerData() {
            return mAudioAnalyzer.getSpectrumCsv(mIsForeground);
        }

        @JavascriptInterface
        public boolean sendShineWaveCommand(final String cmd) {
            if (mShineWave == null || cmd == null) return false;
            mShineWave.handleCommand(cmd);
            return true;
        }

        @JavascriptInterface
        public void requestShineWaveState() {
            pushShineWaveState();
        }

        @JavascriptInterface
        public void cycleShineWaveRepeat() {
            if (mShineWave != null) mShineWave.handleCommand("repeat_cycle");
        }

        @JavascriptInterface
        public void toggleShineWaveShuffle() {
            if (mShineWave != null) mShineWave.handleCommand("shuffle_toggle");
        }

        @JavascriptInterface
        public void browseShineWaveLocal(final String folderPath, final String query, final boolean forceRescan) {
            if (mShineWave == null) return;
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    JSONObject data = mShineWave.browseLocalMusic(folderPath, query, forceRescan);
                    evalSwJsCallback("onShineWaveLocalData", data.toString());
                }
            });
        }

        @JavascriptInterface
        public void playShineWaveLocalTrack(final String folderPath, final String query, final String trackId) {
            if (mShineWave != null) mShineWave.playLocalTrack(folderPath, query, trackId);
        }

        @JavascriptInterface
        public void resumeShineWaveQueue(final String source) {
            if (mShineWave != null) mShineWave.selectSource(AudioSourceType.fromKey(source), true);
        }

        @JavascriptInterface
        public void requestShineWaveQueue() {
            if (mShineWave == null) return;
            evalSwJsCallback("onShineWaveQueueData", mShineWave.getActiveQueueJson().toString());
        }

        @JavascriptInterface
        public void playShineWaveQueueIndex(final int index) {
            if (mShineWave == null) return;
            mShineWave.playQueueIndex(index);
        }

        @JavascriptInterface
        public void getShineWaveRadioState() {
            if (mShineWave == null) return;
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    JSONObject data = mShineWave.buildRadioDataJson();
                    evalSwJsCallback("onShineWaveRadioData", data.toString());
                }
            });
        }

        @JavascriptInterface
        public void tuneShineWaveRadio(final String band, final int freqKhz) {
            if (mShineWave == null) return;
            mShineWave.tuneCarRadio(band, freqKhz);
        }

        @JavascriptInterface
        public void selectShineWaveRadioBand(final String band) {
            if (mShineWave == null) return;
            mShineWave.selectCarRadioBand(band);
        }

        @JavascriptInterface
        public void stepShineWaveRadio(final int deltaKhz) {
            if (mShineWave == null) return;
            mShineWave.stepCarRadio(deltaKhz);
        }

        @JavascriptInterface
        public void seekShineWaveRadio(final boolean up) {
            if (mShineWave != null) mShineWave.seekCarRadio(up);
        }

        @JavascriptInterface
        public void startShineWaveRadioAutoScan() {
            if (mShineWave != null) mShineWave.startRadioAutoScan();
        }

        @JavascriptInterface
        public void saveShineWaveRadioFavorite(final int freqKhz, final String name) {
            if (mShineWave == null) return;
            mShineWave.saveCarRadioFavorite(freqKhz, name);
            getShineWaveRadioState();
        }

        @JavascriptInterface
        public void toggleShineWaveRadioFav(final int freqKhz, final String name) {
            if (mShineWave == null) return;
            mShineWave.toggleCarRadioFavorite(freqKhz, name);
            getShineWaveRadioState();
        }

        @JavascriptInterface
        public void playShineWaveDab(final String mediaId) {
            if (mShineWave != null) mShineWave.playDabItem(mediaId);
        }

        @JavascriptInterface
        public void searchShineWaveInternetRadio(final String tab, final String query) {
            if (mShineWave == null) return;
            mNetExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    JSONObject data = mShineWave.queryInternetRadio(tab, query);
                    evalSwJsCallback("onShineWaveInternetData", data.toString());
                }
            });
        }

        @JavascriptInterface
        public void playShineWaveInternetStation(final String uuid, final String name, final String url, final String country) {
            if (mShineWave != null) mShineWave.playInternetStation(uuid, name, url, country);
        }

        @JavascriptInterface
        public void toggleShineWaveInternetFav(final String uuid, final String name, final String url,
                                               final String country, final String tab, final String query) {
            if (mShineWave == null) return;
            mShineWave.toggleInternetFavorite(uuid, name, url, country);
            searchShineWaveInternetRadio(tab, query);
        }

        @JavascriptInterface
        public void addShineWaveCustomStation(final String name, final String url) {
            if (mShineWave == null) return;
            mShineWave.addCustomInternetStation(name, url);
            searchShineWaveInternetRadio("custom", "");
        }

        @JavascriptInterface
        public void getShineWaveExternalApps() {
            if (mShineWave == null) return;
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    JSONObject data = mShineWave.getExternalMediaAppsJson();
                    evalSwJsCallback("onShineWaveAppsData", data.toString());
                }
            });
        }

        @JavascriptInterface
        public void selectShineWaveExternalApp(final String pkg, final String label, final boolean launchNow) {
            if (mShineWave == null) return;
            mShineWave.selectExternalMediaApp(pkg, label, launchNow);
            getShineWaveExternalApps();
        }

        @JavascriptInterface
        public void openCamera360() {
            evalWebViewJs("if (typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(true); }");
            Log.i(TAG, "openCamera360 requested from JS bridge");
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (mCanBusClient != null) mCanBusClient.openCamera360();
                }
            });
        }

        @JavascriptInterface
        public boolean launchApp(final String keyOrPkg) {
            Log.i(TAG, "launchApp requested: " + keyOrPkg);
            return mAppRepo.launchApp(MainActivity.this, keyOrPkg, new Runnable() {
                @Override
                public void run() {
                    openCamera360();
                }
            });
        }

        @JavascriptInterface
        public String getInstalledAppsJson() {
            return mAppRepo.getInstalledAppsJson(MainActivity.this);
        }

        @JavascriptInterface
        public String getConnectivityStatusJson() {
            return SystemControlHelper.getConnectivityStatusJson(MainActivity.this);
        }

        @JavascriptInterface
        public void openConnectivitySettings(final String type) {
            SystemControlHelper.openConnectivitySettings(MainActivity.this, type);
        }

        @JavascriptInterface
        public void setWifiEnabled(final boolean enabled) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    SystemControlHelper.setWifiEnabled(MainActivity.this, enabled);
                }
            });
        }

        @JavascriptInterface
        public void setBluetoothEnabled(final boolean enabled) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    SystemControlHelper.setBluetoothEnabled(enabled);
                }
            });
        }

        @JavascriptInterface
        public String cleanMemory() {
            return SystemControlHelper.cleanMemory(MainActivity.this);
        }

        @JavascriptInterface
        public void controlMedia(final String cmd) {
            if (mShineWave != null) mShineWave.handleCommand(cmd);
        }

        @JavascriptInterface
        public void seekMediaTo(final long targetSec) {
            if (mShineWave != null) mShineWave.seekToSec(targetSec);
        }

        @JavascriptInterface
        public void seekMediaBy(final long deltaSec) {
            if (mShineWave != null) mShineWave.seekBySec(deltaSec);
        }

        @JavascriptInterface
        public void dispatchSystemNav(final String action) {
            if (action == null || action.isEmpty()) return;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if ("back".equalsIgnoreCase(action)) {
                            onBackPressed();
                        } else if ("home".equalsIgnoreCase(action)) {
                            Intent startMain = new Intent(Intent.ACTION_MAIN);
                            startMain.addCategory(Intent.CATEGORY_HOME);
                            startMain.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(startMain);
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
                mExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        int result = mCanBusClient.dispatchCommand(command, fVal);
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
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    evalWebViewJs("if (typeof window.__onTrunkRefused === 'function') window.__onTrunkRefused();");
                                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
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
                getSharedPreferences(PREF_HUB, MODE_PRIVATE)
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
                return getSharedPreferences(PREF_HUB, MODE_PRIVATE).getString("user_settings", "");
            } catch (Exception e) {
                Log.e(TAG, "Error reading settings", e);
                return "";
            }
        }

        @JavascriptInterface
        public void setDefaultLauncher() {
            SystemControlHelper.openDefaultLauncherSettings(MainActivity.this);
        }

        @JavascriptInterface
        public void setAutoBoot(final boolean enabled) {
            try {
                getSharedPreferences(PREF_HUB, MODE_PRIVATE)
                        .edit()
                        .putBoolean("auto_boot_enabled", enabled)
                        .apply();
            } catch (Exception e) {
                Log.e(TAG, "Error saving auto_boot_enabled", e);
            }
        }

        @JavascriptInterface
        public boolean isWatchdogEnabled() {
            return LauncherWatchdog.isEnabled(MainActivity.this);
        }

        @JavascriptInterface
        public void setWatchdogEnabled(final boolean enabled) {
            LauncherWatchdog.setEnabled(MainActivity.this, enabled);
        }

        @JavascriptInterface
        public void checkForUpdates(final boolean silentIfUpToDate) {
            GitHubUpdateChecker.checkForUpdates(MainActivity.this, silentIfUpToDate);
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
                    if (!mIsForeground) {
                        backgroundFuelCheck();
                        return;
                    }
                    refreshAvmOverlayState();
                    final long nowElapsed = SystemClock.elapsedRealtime();
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
                        if (snap.fuelPctValid) checkFuelWarning(snap.fuelPercentage);
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

    /** While another app is on screen, the normal 1-second poll is paused; check the fuel every 15 s instead. */
    private void backgroundFuelCheck() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastBackgroundFuelCheckMs < BACKGROUND_FUEL_CHECK_MS) return;
        mLastBackgroundFuelCheckMs = now;
        if (mCanBusClient == null) return;
        try {
            CanBusClient.TelemetrySnapshot snap = mCanBusClient.getTelemetrySnapshot(true);
            if (snap.canConnected && snap.fuelPctValid) checkFuelWarning(snap.fuelPercentage);
        } catch (Exception e) {
            Log.e(TAG, "Background fuel check failed", e);
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
     * Popup shown above other apps (Maps, music...): about 65% of the screen wide and 25% high.
     * OK closes it; it also closes by itself after 20 seconds.
     */
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

    private void showFuelWarningOverOtherApps(boolean urgent, int pct, String lang, boolean eastern) {
        String[] t = fuelWarningTexts(urgent, pct, lang, eastern, false);
        String title = t[0], body = t[1], ok = t[2];
        try {
            dismissFuelWarningDialog();
            @SuppressWarnings("deprecation")
            AlertDialog dialog = new AlertDialog.Builder(getApplicationContext(), AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle(title)
                    .setMessage(body)
                    .setPositiveButton(ok, null)
                    .create();
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
            Log.w(TAG, "Fuel popup over other apps not allowed; showing a toast instead", e);
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
        dismissFuelWarningDialog();
        if (mFuelVoice != null) mFuelVoice.shutdown();
        if (!mTelemetryExecutor.isShutdown()) mTelemetryExecutor.shutdown();
        if (!mExecutor.isShutdown()) mExecutor.shutdown();
        if (!mNetExecutor.isShutdown()) mNetExecutor.shutdownNow();
        unregisterReceiverSafe(mCar3DFreezeReceiver);
        unregisterReceiverSafe(mConnectivityReceiver);
        unregisterReceiverSafe(mMediaBroadcastReceiver);
        super.onDestroy();
    }
}
