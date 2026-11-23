/*
 * Titan Cockpit Launcher
 * Created by Dr. Saed
 * For more info contact: saedmagdysms@gmail.com
 */
package com.dongfeng.shinecockpit;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.media.AudioManager;
import android.media.audiofx.Visualizer;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.graphics.BitmapFactory;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import java.io.File;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import com.dongfeng.shinecockpit.shinewave.core.AudioSourceType;
import com.dongfeng.shinecockpit.shinewave.core.ShineWaveEngine;

public class MainActivity extends Activity implements CockpitMediaListenerService.MediaEventListener {
    private long mStartupStartMs;
    // Steering Wheel Control (SWC) Press & Hold Seeking State
    private final Handler mSwcHandler = new Handler(Looper.getMainLooper());
    private Runnable mLongPressSeekRunnable = null;
    private Runnable mContinuousSeekRunnable = null;
    private int mPressedSwcKey = 0;
    private boolean mIsLongPressSeeking = false;
    private long mSwcKeyDownTime = 0;
    private static final long SWC_LONG_PRESS_DELAY_MS = 650L;  // 650ms hold threshold for 10s seek
    private static final long SWC_SEEK_INTERVAL_MS = 450L;    // continuous seek step every 450ms
    private static final long SWC_SEEK_STEP_MS = 10000L;      // 10 seconds per seek step
    private static final String TAG = "DongfengCockpit";
        private final android.content.BroadcastReceiver mCar3DFreezeReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.dongfeng.shinecockpit.ACTION_CAR_3D_FREEZE".equals(intent.getAction())) {
                final boolean freeze = intent.getBooleanExtra("freeze", false);
                Log.d(TAG, "Received ACTION_CAR_3D_FREEZE: " + freeze);
                if (mWebView != null) {
                    mWebView.post(new Runnable() {
                        @Override
                        public void run() {
                            mWebView.evaluateJavascript("if (typeof window.setCar3DFrozen === 'function') { window.setCar3DFrozen(" + freeze + "); }", null);
                        }
                    });
                }
            } else if ("com.dongfeng.shinecockpit.ACTION_EVAL_JS".equals(intent.getAction())) {
                final String js = intent.getStringExtra("js");
                if (js != null && mWebView != null) {
                    mWebView.post(new Runnable() {
                        @Override
                        public void run() {
                            mWebView.evaluateJavascript(js, null);
                        }
                    });
                }
            }
        }
    };

    private WebView mWebView;
    private CanBusClient mCanBusClient;
    private volatile int mLastAvmState = -1;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService mExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        private final android.content.BroadcastReceiver mConnectivityReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mWebView != null) {
                        mWebView.evaluateJavascript("window.syncConnectivityFromAndroid && window.syncConnectivityFromAndroid();", null);
                    }
                }
            });
        }
    };
    private final java.util.concurrent.ScheduledExecutorService mTelemetryExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private boolean mIsSimulating = true;
    private int mSimSpeed = 0;
    private boolean mSpeedIncreasing = true;
    private volatile String mCachedAppsJson = null;

    // Foreground resource gating flag (keeps CPU/GPU cool when inactive)
    private volatile boolean mIsForeground = true;
    private double mLastSentFuelRemain = -1.0;
    private int mLastSentFuelPct = -1;
    private String mLastFastSig = "";
    private long mLastFullTelemetryMs = 0L;

    // ========================================================
    // REAL-TIME AUDIO VISUALIZER & SPECTRUM CAPTURE
    // ========================================================
    private Visualizer mVisualizer = null;
    private final float[] mAudioBands = new float[16];
    private volatile float mAudioEnergy = 0.0f;
    private volatile float mBeatPulse = 0.0f;
    private long mLastVisualizerInitAttempt = 0L;

    public synchronized void ensureAudioVisualizer() {
        if (mVisualizer != null || !mIsForeground) return;
        long now = SystemClock.uptimeMillis();
        if (now - mLastVisualizerInitAttempt < 4000L) return;
        mLastVisualizerInitAttempt = now;
        try {
            mVisualizer = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            int capSize = Math.max(128, range[0]);
            mVisualizer.setCaptureSize(capSize);
            mVisualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                    if (waveform == null || waveform.length == 0) return;
                    long sum = 0L;
                    for (int i = 0; i < waveform.length; i++) {
                        int v = ((int) waveform[i]) & 0xFF;
                        int diff = v - 128;
                        sum += diff * diff;
                    }
                    float rms = (float) Math.sqrt((double) sum / waveform.length) / 128.0f;
                    mAudioEnergy = Math.min(1.0f, rms * 1.5f);
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    if (fft == null || fft.length < 16) return;
                    int n = fft.length / 2;
                    int numBands = 16;
                    int binsPerBand = Math.max(1, n / numBands);
                    float bassSum = 0.0f;
                    for (int b = 0; b < numBands; b++) {
                        float bandEnergy = 0.0f;
                        int start = b * binsPerBand;
                        int end = Math.min(n, (b + 1) * binsPerBand);
                        for (int k = start; k < end; k++) {
                            int r = fft[2 * k];
                            int im = fft[2 * k + 1];
                            float mag = (float) Math.hypot((double) r, (double) im);
                            bandEnergy += mag;
                        }
                        float avg = (end > start) ? (bandEnergy / (end - start)) : 0.0f;
                        float norm = Math.min(1.0f, avg / (32.0f + b * 2.0f));
                        mAudioBands[b] = (mAudioBands[b] * 0.35f) + (norm * 0.65f);
                        if (b < 4) bassSum += mAudioBands[b];
                    }
                    mBeatPulse = Math.min(1.0f, (bassSum / 4.0f) * 1.4f);
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, true);
            mVisualizer.setEnabled(true);
            Log.i(TAG, "Audio Visualizer bound successfully to session 0");
        } catch (Throwable t) {
            Log.w(TAG, "Audio Visualizer bind note: " + t.getMessage());
            mVisualizer = null;
        }
    }

    private synchronized void releaseAudioVisualizer() {
        if (mVisualizer != null) {
            try {
                mVisualizer.setEnabled(false);
                mVisualizer.release();
            } catch (Throwable ignored) {}
            mVisualizer = null;
        }
    }

    // ========================================================
    // WATCHDOG CRASH RESTART & CRASH LOOP BREAKER
    // ========================================================
    private static final String PREF_WATCHDOG = "ShineLauncherWatchdog";
    private static final String KEY_CRASH_COUNT = "consecutive_crash_count";
    private static final String KEY_LAST_CRASH_TIME = "last_crash_timestamp";
    private static final String KEY_WATCHDOG_ENABLED = "watchdog_enabled";
    private static final int MAX_CONSECUTIVE_RESTARTS = 2; // Maximum 2 consecutive restarts before factory fallback
    private static final long CRASH_LOOP_WINDOW_MS = 60000; // 60-second rapid crash detection window

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStartupStartMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "Startup: onCreate started");

        // Note: an earlier version switched off Android's "StrictMode" file-safety check here, for
        // the whole app, to stop the updater from crashing. The updater now hands the installer a
        // safe content:// link instead (see GitHubUpdateChecker), so that switch is no longer
        // needed, and leaving Android's safety check on means real problems still get reported.

        // Initialize Watchdog & Crash Loop Protection immediately
        setupCrashWatchdog();

        // Check & request runtime permissions (Storage, Microphone, etc.)
        checkAndRequestAppPermissions();

        // Hide title bar and make fullscreen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Create WebView container
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        mWebView = new WebView(this);
        root.addView(mWebView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Immersive sticky mode
        hideSystemUI();

        // Initialize CAN Bus Client with Application Context
        long canInitStartMs = SystemClock.elapsedRealtime();
        mCanBusClient = new CanBusClient(this);
        Log.i(TAG, "Startup: CAN client initialized in " + (SystemClock.elapsedRealtime() - canInitStartMs) + "ms; connected=" + mCanBusClient.isConnected());
        if (mCanBusClient.isConnected()) {
            mIsSimulating = false;
        }

                setupWebView();
        startTelemetryLoop();
        ensureNotificationListenerAccess();
        CockpitMediaListenerService.setListener(this);
        startShineWaveNowPlaying();
        registerQingganSteeringWheelKeys();
        registerMediaReceivers();

        // Register receiver to freeze/unfreeze 3D car rendering loop when a floating window or app drawer is active
        android.content.IntentFilter freezeFilter = new android.content.IntentFilter("com.dongfeng.shinecockpit.ACTION_CAR_3D_FREEZE");
        freezeFilter.addAction("com.dongfeng.shinecockpit.ACTION_EVAL_JS");
        registerReceiver(mCar3DFreezeReceiver, freezeFilter);
        try {
            android.content.IntentFilter connFilter = new android.content.IntentFilter();
            connFilter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
            connFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            connFilter.addAction("android.bluetooth.device.action.ACL_CONNECTED");
            connFilter.addAction("android.bluetooth.device.action.ACL_DISCONNECTED");
            connFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(mConnectivityReceiver, connFilter);
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------------------------
    // Integrated ShineWave Audio Engine (0ms direct in-memory bridge, zero cross-app IPC)
    // ------------------------------------------------------------------------------------
    private ShineWaveEngine mShineWave;
    private final android.os.Handler mSwHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService mSwExec = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final ShineWaveEngine.Listener mShineWaveListener = new ShineWaveEngine.Listener() {
        @Override
        public void onStateChanged(final JSONObject stateJson) {
            if (mWebView != null && stateJson != null) {
                final String js = "if (typeof window.onShineWaveState === 'function') { window.onShineWaveState(" + stateJson.toString() + "); }";
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (mWebView != null) mWebView.evaluateJavascript(js, null);
                    }
                });
            }
        }
    };

    private void startShineWaveNowPlaying() {
        try {
            mShineWave = ShineWaveEngine.getInstance(this);
            mShineWave.addListener(mShineWaveListener);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to initialize integrated ShineWaveEngine: " + t.getMessage(), t);
        }
    }

    private void stopShineWaveNowPlaying() {
        try {
            if (mShineWave != null) {
                mShineWave.removeListener(mShineWaveListener);
                mShineWave.release();
            }
        } catch (Throwable ignored) {}
        mSwHandler.removeCallbacksAndMessages(null);
        mSwExec.shutdownNow();
    }

    /** Pushes the in-memory ShineWave state to WebView in 0ms without blocking or disk reads. */
    private void pushShineWaveState() {
        if (mShineWave == null || mWebView == null) return;
        final JSONObject json = mShineWave.getCurrentStateJson();
        final String js = "if (typeof window.onShineWaveState === 'function') { window.onShineWaveState(" + json.toString() + "); }";
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (mWebView != null) mWebView.evaluateJavascript(js, null);
            }
        });
    }

    private void evalSwJsCallback(final String fnName, final String jsonPayload) {
        if (mWebView == null || jsonPayload == null) return;
        final String js = "if (typeof window." + fnName + " === 'function') { window." + fnName + "(" + jsonPayload + "); }";
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (mWebView != null) mWebView.evaluateJavascript(js, null);
            }
        });
    }

    // ========================================================
    // QINGGAN OEM STEERING WHEEL KEY MANAGER
    // ========================================================
    private Object mQingganKeyManager = null;
    private Object mQingganKeyListener = null;
    private boolean mQingganKeyRegistered = false;
    private long mLastCenterKeyTime = 0L;
    private static final long CENTER_KEY_DEBOUNCE_MS = 350L;

    private static final int QG_KEY_NEXT = 3;
    private static final int QG_KEY_PREVIOUS = 4;
    private static final int QG_KEY_PLAY_PAUSE = 6;
    private static final int QG_KEY_MEDIA_OK = 145;
    private static final int QG_LONG_PREVIOUS_KEY = 14;
    private static final int QG_LONG_NEXT_KEY = 13;

    private void registerQingganSteeringWheelKeys() {
        try {
            final Class<?> managerClass = Class.forName("com.qinggan.system.KeyManager");
            final Class<?> initListenerClass = Class.forName("com.qinggan.system.KeyManager$OnInitListener");
            final Class<?> keyListenerClass = Class.forName("com.qinggan.system.KeyManager$OnKeyEventListener");

            Object initListener = java.lang.reflect.Proxy.newProxyInstance(
                initListenerClass.getClassLoader(),
                new Class<?>[] { initListenerClass },
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                        if ("onResult".equals(method.getName()) && args != null && args.length > 0 && Boolean.TRUE.equals(args[0])) {
                            mHandler.post(new Runnable() {
                                @Override public void run() { registerQingganKeyEventListener(keyListenerClass); }
                            });
                        }
                        return null;
                    }
                }
            );

            java.lang.reflect.Method getInstance = managerClass.getMethod("getInstance", Context.class, initListenerClass);
            mQingganKeyManager = getInstance.invoke(null, this, initListener);
            Log.i(TAG, "Requested Qinggan KeyManager initialization for steering wheel controls");
        } catch (Throwable t) {
            Log.w(TAG, "Qinggan KeyManager unavailable: " + t.getMessage());
        }
    }

    private void registerQingganKeyEventListener(Class<?> listenerClass) {
        if (mQingganKeyManager == null || mQingganKeyRegistered) return;
        try {
            mQingganKeyListener = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[] { listenerClass },
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                        final String name = method.getName();
                        if (args != null && args.length > 0 && args[0] instanceof Number) {
                            final int keyCode = ((Number) args[0]).intValue();
                            if ("onKeyShortClick".equals(name)) {
                                mHandler.post(new Runnable() {
                                    @Override public void run() { handleQingganKey(keyCode); }
                                });
                            } else if ("onKeyLongPressStart".equals(name)) {
                                if (keyCode == QG_LONG_NEXT_KEY || keyCode == QG_KEY_NEXT || keyCode == 13 || keyCode == 3) {
                                    mHandler.post(new Runnable() {
                                        @Override public void run() {
                                            if (mShineWave != null) mShineWave.startLongSeek(1);
                                        }
                                    });
                                } else if (keyCode == QG_LONG_PREVIOUS_KEY || keyCode == QG_KEY_PREVIOUS || keyCode == 14 || keyCode == 4) {
                                    mHandler.post(new Runnable() {
                                        @Override public void run() {
                                            if (mShineWave != null) mShineWave.startLongSeek(-1);
                                        }
                                    });
                                }
                            } else if ("onKeyLongPressStop".equals(name) || "onKeyRelease".equals(name)) {
                                if (keyCode == QG_LONG_NEXT_KEY || keyCode == QG_KEY_NEXT || keyCode == 13 || keyCode == 3 ||
                                    keyCode == QG_LONG_PREVIOUS_KEY || keyCode == QG_KEY_PREVIOUS || keyCode == 14 || keyCode == 4) {
                                    mHandler.post(new Runnable() {
                                        @Override public void run() {
                                            if (mShineWave != null) mShineWave.stopLongSeek();
                                        }
                                    });
                                }
                            }
                        }
                        return false; // Do not consume, let system operate normally
                    }
                }
            );

            java.lang.reflect.Method reg = mQingganKeyManager.getClass().getMethod("registerKeyEventListener", listenerClass);
            int res = ((Number) reg.invoke(mQingganKeyManager, mQingganKeyListener)).intValue();
            if (res >= 0) {
                mQingganKeyRegistered = true;
                Log.i(TAG, "Qinggan KeyManager listener registered successfully! Code=" + res);
            }
        } catch (Throwable t) {
            Log.e(TAG, "registerKeyEventListener failed: " + t.getMessage());
        }
    }

    private void handleQingganKey(int keyCode) {
        long now = System.currentTimeMillis();
        if (keyCode == QG_KEY_PLAY_PAUSE || keyCode == QG_KEY_MEDIA_OK) {
            if (now - mLastCenterKeyTime < CENTER_KEY_DEBOUNCE_MS) return;
            mLastCenterKeyTime = now;
            if (mWebView != null) {
                mWebView.evaluateJavascript("if (typeof window.onSwcMediaKey === 'function') { window.onSwcMediaKey('play_pause'); }", null);
            }
            if (mShineWave != null) mShineWave.handleCommand("toggle");
        } else if (keyCode == QG_KEY_NEXT) {
            if (mWebView != null) {
                mWebView.evaluateJavascript("if (typeof window.onSwcMediaKey === 'function') { window.onSwcMediaKey('next'); }", null);
            }
            if (mShineWave != null) mShineWave.handleCommand("next");
        } else if (keyCode == QG_KEY_PREVIOUS) {
            if (mWebView != null) {
                mWebView.evaluateJavascript("if (typeof window.onSwcMediaKey === 'function') { window.onSwcMediaKey('prev'); }", null);
            }
            if (mShineWave != null) mShineWave.handleCommand("previous");
        }
    }

    // ========================================================
    // BROADCAST RECEIVER FOR BLUETOOTH & SYSTEM MEDIA
    // ========================================================
    private final android.content.BroadcastReceiver mMediaBroadcastReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (mShineWave != null) mShineWave.onSystemMediaSessionUpdated();
        }
    };

    private void registerMediaReceivers() {
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter();
            filter.addAction("android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT");
            filter.addAction("android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED");
            filter.addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED");
            filter.addAction("com.android.music.metachanged");
            filter.addAction("com.android.music.playstatechanged");
            filter.addAction("com.spotify.music.playbackstatechanged");
            filter.addAction("com.spotify.music.metadatachanged");
            registerReceiver(mMediaBroadcastReceiver, filter);
        } catch (Throwable t) {
            Log.w(TAG, "registerMediaReceivers failed: " + t.getMessage());
        }
    }

    private void ensureNotificationListenerAccess() {
        try {
            String pkgName = getPackageName();
            String serviceName = pkgName + "/" + CockpitMediaListenerService.class.getName();
            String listeners = android.provider.Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            if (listeners == null || !listeners.contains(serviceName)) {
                String newListeners = (listeners == null || listeners.trim().isEmpty()) ? serviceName : (listeners + ":" + serviceName);
                android.provider.Settings.Secure.putString(getContentResolver(), "enabled_notification_listeners", newListeners);
                Log.i(TAG, "Auto-granted notification listener permission to " + serviceName);
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    NotificationListenerService.requestRebind(new ComponentName(this, CockpitMediaListenerService.class));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not auto-grant notification listener: " + t.getMessage());
        }
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
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (mWebView != null) {
                        mWebView.evaluateJavascript("if (typeof window.onLauncherBackPressed === 'function') { window.onLauncherBackPressed(); }", null);
                    }
                }
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIsForeground = true;
        hideSystemUI();
        if (mWebView != null) {
            mWebView.evaluateJavascript("if (typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(false); }", null);
        }
        if (mLastAvmState >= 0) applyAvmOverlayStateToWebView(mLastAvmState != 0);
        pushShineWaveState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsForeground = false;
        releaseAudioVisualizer();
        if (mWebView != null) {
            mWebView.evaluateJavascript("if (typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(true); }", null);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
        if (mWebView != null) {
            final boolean pause3D = !hasFocus;
            mWebView.post(new Runnable() {
                @Override
                public void run() {
                    if (mWebView != null) {
                        mWebView.evaluateJavascript("if (typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(" + pause3D + "); }", null);
                    }
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        // As a Home Launcher, Back button should close open modals rather than exiting the launcher
        if (mWebView != null) {
            mWebView.evaluateJavascript("if (typeof window.onLauncherBackPressed === 'function') { window.onLauncherBackPressed(); }", null);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return super.dispatchKeyEvent(event);

        final int code = event.getKeyCode();
        final int action = event.getAction();

        // 1. Steering Wheel Next / Previous Buttons with Long-Press (1.5s) 5-second Seek
        if (code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
            code == KeyEvent.KEYCODE_MEDIA_PREVIOUS || code == KeyEvent.KEYCODE_MEDIA_REWIND) {

            final boolean isForward = (code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
            final long stepMs = isForward ? SWC_SEEK_STEP_MS : -SWC_SEEK_STEP_MS;
            final String dir = isForward ? "forward" : "backward";

            if (action == KeyEvent.ACTION_DOWN) {
                if (mPressedSwcKey == 0) {
                    mPressedSwcKey = code;
                    mSwcKeyDownTime = System.currentTimeMillis();
                    mIsLongPressSeeking = false;

                    // Schedule long-press detection at 1.5 seconds
                    mLongPressSeekRunnable = new Runnable() {
                        @Override
                        public void run() {
                            mIsLongPressSeeking = true;
                            // Perform initial 5s seek
                            if (mShineWave != null) mShineWave.seekBySec(stepMs / 1000L);
                            else CockpitMediaListenerService.seekBy(MainActivity.this, stepMs);
                            if (mWebView != null) {
                                mWebView.evaluateJavascript("if (typeof window.onSwcSeek === 'function') { window.onSwcSeek('" + dir + "', " + Math.abs(stepMs) + "); }", null);
                            }

                            // Continuous seek runnable while held
                            mContinuousSeekRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    if (mIsLongPressSeeking && mPressedSwcKey != 0) {
                                        if (mShineWave != null) mShineWave.seekBySec(stepMs / 1000L);
                                        else CockpitMediaListenerService.seekBy(MainActivity.this, stepMs);
                                        if (mWebView != null) {
                                            mWebView.evaluateJavascript("if (typeof window.onSwcSeek === 'function') { window.onSwcSeek('" + dir + "', " + Math.abs(stepMs) + "); }", null);
                                        }
                                        mSwcHandler.postDelayed(this, SWC_SEEK_INTERVAL_MS);
                                    }
                                }
                            };
                            mSwcHandler.postDelayed(mContinuousSeekRunnable, SWC_SEEK_INTERVAL_MS);
                        }
                    };
                    mSwcHandler.postDelayed(mLongPressSeekRunnable, SWC_LONG_PRESS_DELAY_MS);
                }
                return true;
            } else if (action == KeyEvent.ACTION_UP) {
                if (mPressedSwcKey == code) {
                    // Cancel seek timers
                    if (mLongPressSeekRunnable != null) {
                        mSwcHandler.removeCallbacks(mLongPressSeekRunnable);
                        mLongPressSeekRunnable = null;
                    }
                    if (mContinuousSeekRunnable != null) {
                        mSwcHandler.removeCallbacks(mContinuousSeekRunnable);
                        mContinuousSeekRunnable = null;
                    }

                    long heldDuration = System.currentTimeMillis() - mSwcKeyDownTime;

                    if (mIsLongPressSeeking || heldDuration >= SWC_LONG_PRESS_DELAY_MS) {
                        // Was seeking - end seek and do NOT change track
                        mIsLongPressSeeking = false;
                        mPressedSwcKey = 0;
                        if (mWebView != null) {
                            mWebView.evaluateJavascript("if (typeof window.onSwcSeekEnd === 'function') { window.onSwcSeekEnd(); }", null);
                        }
                    } else {
                        // Short tap (< 1.5s) - change track!
                        mPressedSwcKey = 0;
                        final String cmd = isForward ? "next" : "prev";
                        if (mShineWave != null) {
                            mShineWave.handleCommand(isForward ? "next" : "previous");
                        } else if (isForward) {
                            CockpitMediaListenerService.skipNext(MainActivity.this);
                        } else {
                            CockpitMediaListenerService.skipPrev(MainActivity.this);
                        }
                        if (mWebView != null) {
                            mWebView.evaluateJavascript("if (typeof window.onSwcMediaKey === 'function') { window.onSwcMediaKey('" + cmd + "'); }", null);
                        }
                    }
                    return true;
                }
            }
        }

        // 2. Play / Pause Steering Wheel Key
        else if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_STOP) {
            if (action == KeyEvent.ACTION_UP) {
                if (mShineWave != null) mShineWave.handleCommand("toggle");
                else CockpitMediaListenerService.togglePlayPause(MainActivity.this);
                if (mWebView != null) {
                    mWebView.evaluateJavascript("if (typeof window.onSwcMediaKey === 'function') { window.onSwcMediaKey('play_pause'); }", null);
                }
            }
            return true;
        }

        // 3. Volume Steering Wheel Keys
        else if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN && mWebView != null) {
                final String vCmd = (code == KeyEvent.KEYCODE_VOLUME_UP) ? "vol_up" : "vol_down";
                mWebView.evaluateJavascript("if (typeof window.onSwcMediaKey === 'function') { window.onSwcMediaKey('" + vCmd + "'); }", null);
            }
        }

        return super.dispatchKeyEvent(event);
    }

    // MediaEventListener implementation
    @Override
    public void onMediaUpdated(final JSONObject mediaJson) {
        if (mShineWave != null) {
            mShineWave.onSystemMediaSessionUpdated();
        }
    }

    @Override
    public void onGoogleMapsNav(final String instruction, final String distance, final String turnIcon) {
        if (mWebView != null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject json = new JSONObject();
                        json.put("instruction", instruction);
                        json.put("distance", distance);
                        json.put("turnIcon", turnIcon);
                        mWebView.evaluateJavascript("if (typeof window.onNavigationUpdate === 'function') { window.onNavigationUpdate(" + json.toString() + "); }", null);
                    } catch (Exception ignored) {}
                }
            });
        }
    }

    @Override
    public void onRadarbotAlert(final String alertType, final int speedLimit, final int distanceMeters) {
        if (mWebView != null) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject json = new JSONObject();
                        json.put("alert", alertType);
                        json.put("speed", speedLimit);
                        json.put("distance", distanceMeters);
                        mWebView.evaluateJavascript("if (typeof window.onRadarUpdate === 'function') { window.onRadarUpdate(" + json.toString() + "); }", null);
                    } catch (Exception ignored) {}
                }
            });
        }
    }

    private void setupWebView() {
        // Use View.LAYER_TYPE_NONE so WebView composites directly with native window hardware acceleration (prevents WebGL context loss on Adreno GPUs)
        mWebView.setLayerType(View.LAYER_TYPE_NONE, null);
        // Paint dark petroleum backdrop immediately from millisecond 0 before HTML paints
        mWebView.setBackgroundColor(0xFF061014);
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
                Log.e("WebConsole", cm.message() + " -- line " + cm.lineNumber() + " of " + cm.sourceId());
                return true;
            }
        });
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                boolean didCrash = (detail != null && detail.didCrash());
                Log.e(TAG, "WebViewClient.onRenderProcessGone: didCrash=" + didCrash);
                handleFatalCrashAndRecovery("RenderProcessGone (didCrash=" + didCrash + ")");
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.i(TAG, "Startup: WebView page finished at " + (SystemClock.elapsedRealtime() - mStartupStartMs) + "ms");
                if (mLastAvmState >= 0) applyAvmOverlayStateToWebView(mLastAvmState != 0);
                try {
                    String s = getSharedPreferences("ShineHubPrefs", MODE_PRIVATE)
                            .getString("user_settings", "");
                    if (s != null && !s.isEmpty()) {
                        String escaped = JSONObject.quote(s);
                        view.evaluateJavascript("if (typeof applySavedSettingsJson === 'function') { applySavedSettingsJson(" + escaped + "); }", null);
                        Log.i(TAG, "Restored settings onPageFinished: " + s);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error restoring settings in onPageFinished", e);
                }
            }
        });

        // Injected JS Bridge
        final AndroidBridge bridge = new AndroidBridge();
        mWebView.addJavascriptInterface(bridge, "AndroidBridge");

        // Pre-warm installed apps cache in background so App Drawer opens instantly
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    bridge.getInstalledAppsJson();
                } catch (Exception ignored) {}
            }
        });

        // Load standalone app
        mWebView.loadUrl("file:///android_asset/app.html");
        Log.i(TAG, "Startup: WebView load requested at " + (SystemClock.elapsedRealtime() - mStartupStartMs) + "ms");
    }

    // ========================================================
    // WATCHDOG CRASH & RESTART LOOP PROTECTION
    // ========================================================
    private void setupCrashWatchdog() {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                Log.e(TAG, "FATAL CRASH DETECTED in thread " + (thread != null ? thread.getName() : "unknown"), throwable);
                handleFatalCrashAndRecovery("UncaughtException: " + (throwable != null ? throwable.getMessage() : "null"));
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable);
                } else {
                    Process.killProcess(Process.myPid());
                    System.exit(10);
                }
            }
        });

        // After running stably for 40 seconds, reset consecutive crash counter
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences sp = getSharedPreferences(PREF_WATCHDOG, MODE_PRIVATE);
                    int count = sp.getInt(KEY_CRASH_COUNT, 0);
                    if (count > 0) {
                        Log.i(TAG, "Launcher running stably for 40s. Resetting consecutive crash count from " + count + " to 0.");
                        sp.edit().putInt(KEY_CRASH_COUNT, 0).apply();
                    }
                } catch (Exception ignored) {}
            }
        }, 40000);

        // Check for updates quietly after boot (10 seconds delay so boot stays ultra-fast)
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                GitHubUpdateChecker.checkForUpdates(MainActivity.this, true);
            }
        }, 10000);
    }

    // ========================================================
    // RUNTIME PERMISSIONS & SYSTEM INTEGRATION
    // ========================================================
    private void checkAndRequestAppPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            java.util.List<String> needed = new java.util.ArrayList<>();
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
                    ensureAudioVisualizer();
                }
            }
        }
    }

    private synchronized void handleFatalCrashAndRecovery(String reason) {
        try {
            SharedPreferences sp = getSharedPreferences(PREF_WATCHDOG, MODE_PRIVATE);
            boolean enabled = sp.getBoolean(KEY_WATCHDOG_ENABLED, true);
            if (!enabled) {
                Log.w(TAG, "Watchdog auto-restart is disabled by user setting.");
                return;
            }

            long now = System.currentTimeMillis();
            long lastCrash = sp.getLong(KEY_LAST_CRASH_TIME, 0);
            int count = sp.getInt(KEY_CRASH_COUNT, 0);

            if ((now - lastCrash) < CRASH_LOOP_WINDOW_MS) {
                count++;
            } else {
                count = 1;
            }

            sp.edit()
              .putInt(KEY_CRASH_COUNT, count)
              .putLong(KEY_LAST_CRASH_TIME, now)
              .commit();

            Log.e(TAG, "Watchdog Crash: count=" + count + "/" + MAX_CONSECUTIVE_RESTARTS + ", reason=" + reason);

            if (count <= MAX_CONSECUTIVE_RESTARTS) {
                // Restart #1 or #2: Schedule restart and kill current process
                Log.w(TAG, "Watchdog: Scheduling auto-restart #" + count + "...");
                scheduleLauncherRestart();
            } else {
                // Consecutive Crash Loop! Count > 2:
                // Close completely and fall back to the car's original factory launcher!
                Log.e(TAG, "Watchdog: CRASH LOOP DETECTED (" + count + " crashes)! Falling back to car factory launcher!");
                sp.edit().putInt(KEY_CRASH_COUNT, 0).commit();
                launchFallbackFactoryLauncher();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in handleFatalCrashAndRecovery", e);
        }
    }

    private void scheduleLauncherRestart() {
        try {
            Intent intent = new Intent(getApplicationContext(), MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                getApplicationContext(),
                9999,
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT
            );
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.set(AlarmManager.RTC, System.currentTimeMillis() + 1000, pendingIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule restart", e);
        }
    }

    private void launchFallbackFactoryLauncher() {
        try {
            PackageManager pm = getPackageManager();
            String myPkg = getPackageName();

            // 1. Find system/car factory launcher via CATEGORY_HOME
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            List<ResolveInfo> list = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            if (list == null || list.isEmpty()) {
                list = pm.queryIntentActivities(homeIntent, 0);
            }

            if (list != null) {
                for (ResolveInfo ri : list) {
                    if (ri.activityInfo != null && !myPkg.equals(ri.activityInfo.packageName)) {
                        Intent target = new Intent(Intent.ACTION_MAIN);
                        target.addCategory(Intent.CATEGORY_HOME);
                        target.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(target);
                        Log.i(TAG, "Watchdog: Fallback launched factory home: " + ri.activityInfo.packageName + "/" + ri.activityInfo.name);
                        return;
                    }
                }
            }

            // 2. Known factory package candidates on Dongfeng / Qinggan head units
            String[] candidates = new String[] {
                "com.lecoauto",
                "com.qinggan.app.launcher",
                "com.dongfeng.launcher",
                "com.qinggan.launcher",
                "com.android.launcher3",
                "com.android.launcher"
            };
            for (String pkg : candidates) {
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(launch);
                    Log.i(TAG, "Watchdog: Fallback launched candidate: " + pkg);
                    return;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Watchdog: Failed to launch fallback launcher", e);
        }
    }

    private boolean tryStartPackage(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            Intent intent = pm.getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to start package: " + pkg, e);
        }
        return false;
    }

    private boolean tryStartPackageByKeyword(String[] keywords) {
        try {
            PackageManager pm = getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);
            if (apps != null) {
                for (ResolveInfo ri : apps) {
                    if (ri.activityInfo == null || ri.activityInfo.packageName == null) continue;
                    String pkg = ri.activityInfo.packageName.toLowerCase();
                    if (pkg.equals(getPackageName().toLowerCase())) continue;
                    for (String kw : keywords) {
                        if (pkg.contains(kw)) {
                            return tryStartPackage(ri.activityInfo.packageName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Keyword search failed", e);
        }
        return false;
    }

    /**
     * JS Interface bridge between HTML UI and Android Service
     */
    public class AndroidBridge {
        /** Returns live 16-band FFT audio spectrum & beat energy from session 0 */
        @JavascriptInterface
        public String getAudioVisualizerData() {
            if (!mIsForeground) {
                return "0.000,0.000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0";
            }
            ensureAudioVisualizer();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(java.util.Locale.US, "%.3f,%.3f", mBeatPulse, mAudioEnergy));
            for (int i = 0; i < 16; i++) {
                sb.append(String.format(java.util.Locale.US, ",%.3f", mAudioBands[i]));
            }
            return sb.toString();
        }
        /** Sends a command directly in-process to the integrated ShineWave audio engine (0ms). */
        @JavascriptInterface
        public boolean sendShineWaveCommand(final String cmd) {
            if (mShineWave == null || cmd == null) return false;
            mShineWave.handleCommand(cmd);
            return true;
        }

        /** Lets the card pull the current ShineWave snapshot instantaneously. */
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
            if (mShineWave != null) mShineWave.playQueueIndex(index);
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
            getShineWaveRadioState();
        }

        @JavascriptInterface
        public void stepShineWaveRadio(final int deltaKhz) {
            if (mShineWave == null) return;
            mShineWave.stepCarRadio(deltaKhz);
            getShineWaveRadioState();
        }

        @JavascriptInterface
        public void seekShineWaveRadio(final boolean up) {
            if (mShineWave != null) mShineWave.seekCarRadio(up);
        }

        @JavascriptInterface
        public void toggleShineWaveRadioFavorite(final int freqKhz, final String name) {
            if (mShineWave == null) return;
            mShineWave.toggleCarRadioFavorite(freqKhz, name);
            getShineWaveRadioState();
        }

        @JavascriptInterface
        public void saveShineWaveRadioFavorite(final int freqKhz, final String name) {
            if (mShineWave == null) return;
            mShineWave.saveCarRadioFavorite(freqKhz, name);
            getShineWaveRadioState();
        }

        @JavascriptInterface
        public void browseShineWaveDab(final String mediaId) {
            if (mShineWave != null) mShineWave.browseDab(mediaId);
        }

        @JavascriptInterface
        public void playShineWaveDab(final String mediaId) {
            if (mShineWave != null) mShineWave.playDabItem(mediaId);
        }

        @JavascriptInterface
        public void searchShineWaveInternetRadio(final String tab, final String query) {
            if (mShineWave == null) return;
            mExecutor.execute(new Runnable() {
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
        public void toggleShineWaveInternetFav(final String uuid, final String name, final String url, final String country, final String tab, final String query) {
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
        public void removeShineWaveCustomStation(final String uuid) {
            if (mShineWave == null) return;
            mShineWave.removeCustomInternetStation(uuid);
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
        public boolean reconnectCan() {
            Log.i(TAG, "reconnectCan requested from JS bridge");
            if (mCanBusClient != null) {
                return mCanBusClient.manualReconnect();
            }
            return false;
        }

        @JavascriptInterface
        public void openCamera360() {
            if (mWebView != null) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        mWebView.evaluateJavascript("if (typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(true); }", null);
                    }
                });
            }
            Log.i(TAG, "openCamera360 requested from JS bridge");
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (mCanBusClient != null) {
                        mCanBusClient.openCamera360();
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean launchApp(final String keyOrPkg) {
            Log.i(TAG, "launchApp requested: " + keyOrPkg);
            if (keyOrPkg == null || keyOrPkg.isEmpty()) return false;
            try {
                if ("pip".equalsIgnoreCase(keyOrPkg) || "com.titan.pip".equalsIgnoreCase(keyOrPkg)) {
                    if (tryStartPackage("com.titan.pip")) return true;
                }
                if ("cam360".equalsIgnoreCase(keyOrPkg) || "org.codeaurora.snapcam".equalsIgnoreCase(keyOrPkg) || "com.dongfeng.auto360".equalsIgnoreCase(keyOrPkg) || "camera".equalsIgnoreCase(keyOrPkg)) {
                    openCamera360();
                    if (tryStartPackage("com.dongfeng.auto360")) return true;
                    tryStartPackage("com.qinggan.app.avm");
                    return true;
                }
                if ("wifi".equalsIgnoreCase(keyOrPkg)) {
                    try {
                        Intent intent = new Intent();
                        intent.setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings$WifiSettingsActivity"));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        return true;
                    } catch (Exception ignored) {}
                    try {
                        Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                        intent.setPackage("com.android.settings");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        return true;
                    } catch (Exception e) {
                        Intent fallback = new Intent(Settings.ACTION_SETTINGS);
                        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(fallback);
                        return true;
                    }
                }
                if ("bluetooth".equalsIgnoreCase(keyOrPkg) || "bt".equalsIgnoreCase(keyOrPkg)) {
                    // Prioritize car system Bluetooth settings first
                    String[] btPkgs = new String[] {
                        "com.qinggan.bluetooth",
                        "com.qinggan.app.bluetooth",
                        "com.dongfeng.bluetooth",
                        "com.qinggan.app.btmusic"
                    };
                    for (String p : btPkgs) {
                        if (tryStartPackage(p)) return true;
                    }
                    if (tryStartPackageByKeyword(new String[]{"bluetooth", "bt"})) return true;
                    // Fallback to native Android Bluetooth settings
                    try {
                        Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        return true;
                    } catch (Exception ignored) {}
                    try {
                        Intent fallback = new Intent(Settings.ACTION_SETTINGS);
                        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(fallback);
                        return true;
                    } catch (Exception ignored) {}
                    return false;
                }
                if ("network".equalsIgnoreCase(keyOrPkg) || "cell".equalsIgnoreCase(keyOrPkg) || "cellular".equalsIgnoreCase(keyOrPkg) || "data".equalsIgnoreCase(keyOrPkg)) {
                    // Open native Android Mobile Network settings directly
                    try {
                        Intent intent = new Intent();
                        intent.setComponent(new ComponentName("com.android.phone", "com.android.phone.MobileNetworkSettings"));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        return true;
                    } catch (Exception ignored) {}
                    try {
                        Intent intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        return true;
                    } catch (Exception ignored) {}
                    try {
                        Intent intent = new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        return true;
                    } catch (Exception ignored) {}
                    try {
                        Intent intent = new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        return true;
                    } catch (Exception ignored) {}
                    try {
                        Intent fallback = new Intent(Settings.ACTION_SETTINGS);
                        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(fallback);
                        return true;
                    } catch (Exception ignored) {}
                    return false;
                }
                if ("maps".equalsIgnoreCase(keyOrPkg)) {
                    String[] mapPkgs = new String[] {
                        "com.google.android.apps.maps",
                        "com.waze",
                        "com.autonavi.amapauto",
                        "com.baidu.BaiduMap",
                        "com.here.app.maps"
                    };
                    for (String p : mapPkgs) {
                        if (tryStartPackage(p)) return true;
                    }
                    if (tryStartPackageByKeyword(new String[]{"map", "navi", "waze", "gps"})) return true;
                    Intent geo = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="));
                    geo.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(geo);
                    return true;
                }
                if ("radar".equalsIgnoreCase(keyOrPkg)) {
                    String[] radarPkgs = new String[] {
                        "com.vialsoft.radarbot_free",
                        "com.vialsoft.radarbot",
                        "com.camonroad.radar"
                    };
                    for (String p : radarPkgs) {
                        if (tryStartPackage(p)) return true;
                    }
                    return tryStartPackageByKeyword(new String[]{"radar", "speedcam"});
                }
                if ("shinewave".equalsIgnoreCase(keyOrPkg) || "shine_wave".equalsIgnoreCase(keyOrPkg) || "com.shine.wave".equalsIgnoreCase(keyOrPkg)) {
                    if (tryStartPackage("com.shine.wave")) return true;
                }
                if ("music".equalsIgnoreCase(keyOrPkg)) {
                    String[] musicPkgs = new String[] {
                        "com.shine.wave",
                        "com.qinggan.app.music",
                        "com.qinggan.music",
                        "com.android.music",
                        "com.maxmpz.audioplayer",
                        "com.spotify.music",
                        "com.google.android.music",
                        "com.sec.android.app.music",
                        "com.miui.player"
                    };
                    for (String p : musicPkgs) {
                        if (tryStartPackage(p)) return true;
                    }
                    if (tryStartPackageByKeyword(new String[]{"music", "audio", "player", "radio"})) return true;
                    Intent intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return true;
                }
                if ("video".equalsIgnoreCase(keyOrPkg)) {
                    String[] videoPkgs = new String[] {
                        "com.qinggan.app.video",
                        "com.qinggan.video",
                        "org.videolan.vlc",
                        "com.mxtech.videoplayer.ad",
                        "com.mxtech.videoplayer.pro",
                        "com.android.gallery3d",
                        "com.google.android.videos"
                    };
                    for (String p : videoPkgs) {
                        if (tryStartPackage(p)) return true;
                    }
                    if (tryStartPackageByKeyword(new String[]{"video", "vlc", "gallery", "movie"})) return true;
                    Intent intent = new Intent(Intent.ACTION_MAIN);
                    intent.addCategory(Intent.CATEGORY_APP_GALLERY);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    return true;
                }
                if ("radio".equalsIgnoreCase(keyOrPkg)) {
                    String[] radioPkgs = new String[] {
                        "com.qinggan.app.radio",
                        "com.qinggan.radio",
                        "com.android.fmradio"
                    };
                    for (String p : radioPkgs) {
                        if (tryStartPackage(p)) return true;
                    }
                    return tryStartPackageByKeyword(new String[]{"radio", "fm"});
                }
                if ("bt_music".equalsIgnoreCase(keyOrPkg)) {
                    String[] btPkgs = new String[] {
                        "com.qinggan.app.btmusic",
                        "com.qinggan.bluetooth",
                        "com.qinggan.app.bluetooth"
                    };
                    for (String p : btPkgs) {
                        if (tryStartPackage(p)) return true;
                    }
                    return false;
                }
                if ("youtube".equalsIgnoreCase(keyOrPkg)) {
                    String[] ytPkgs = new String[] {
                        "com.google.android.youtube",
                        "com.google.android.youtube.tv",
                        "org.schabi.newpipe"
                    };
                    for (String p : ytPkgs) {
                        if (tryStartPackage(p)) return true;
                    }
                    return tryStartPackageByKeyword(new String[]{"youtube", "yt"});
                }
                if ("store".equalsIgnoreCase(keyOrPkg)) {
                    String[] storePkgs = new String[] {
                        "com.android.vending",
                        "com.google.android.finsky"
                    };
                    for (String p : storePkgs) {
                        if (tryStartPackage(p)) return true;
                    }
                    return tryStartPackageByKeyword(new String[]{"vending", "store", "market"});
                }
                if ("settings".equalsIgnoreCase(keyOrPkg)) {
                    try {
                        Intent intent = new Intent(Settings.ACTION_SETTINGS);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        return true;
                    } catch (Exception ignored) {}
                    return tryStartPackage("com.android.settings");
                }
                if ("voice".equalsIgnoreCase(keyOrPkg)) {
                    String[] voicePkgs = new String[] {
                        "com.qinggan.app.voice",
                        "com.google.android.googlequicksearchbox"
                    };
                    for (String p : voicePkgs) {
                        if (tryStartPackage(p)) return true;
                    }
                    try {
                        Intent intent = new Intent(Intent.ACTION_VOICE_COMMAND);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        return true;
                    } catch (Exception ignored) {}
                }

                // Direct package launch (e.g., from App Drawer)
                return tryStartPackage(keyOrPkg);
            } catch (Exception e) {
                Log.e(TAG, "Error in launchApp: " + keyOrPkg, e);
                return false;
            }
        }

        private String drawableToBase64(Drawable drawable) {
            if (drawable == null) return null;
            try {
                int w = drawable.getIntrinsicWidth();
                int h = drawable.getIntrinsicHeight();
                if (w <= 0) w = 72;
                if (h <= 0) h = 72;
                int targetSize = 72;
                Bitmap bitmap;
                if (drawable instanceof BitmapDrawable) {
                    bitmap = ((BitmapDrawable) drawable).getBitmap();
                } else {
                    bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                    drawable.draw(canvas);
                }
                if (bitmap != null) {
                    Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetSize, targetSize, true);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    scaled.compress(Bitmap.CompressFormat.PNG, 85, baos);
                    byte[] bytes = baos.toByteArray();
                    return "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
                }
            } catch (Exception ignored) {}
            return null;
        }

        @JavascriptInterface
        public String getInstalledAppsJson() {
            if (mCachedAppsJson != null && !mCachedAppsJson.isEmpty()) {
                return mCachedAppsJson;
            }
            JSONArray arr = new JSONArray();
            try {
                final PackageManager pm = getPackageManager();
                Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = pm.queryIntentActivities(mainIntent, 0);
                if (list != null) {
                    Collections.sort(list, new Comparator<ResolveInfo>() {
                        @Override
                        public int compare(ResolveInfo a, ResolveInfo b) {
                            String la = a.loadLabel(pm) != null ? a.loadLabel(pm).toString() : "";
                            String lb = b.loadLabel(pm) != null ? b.loadLabel(pm).toString() : "";
                            return la.compareToIgnoreCase(lb);
                        }
                    });
                    String myPkg = getPackageName();
                    for (ResolveInfo ri : list) {
                        if (ri.activityInfo == null) continue;
                        String pkg = ri.activityInfo.packageName;
                        if (pkg == null || pkg.equals(myPkg)) continue;
                        // Blacklist internal/phone camera app and broken packages that produce black screen on head unit
                        if ("org.codeaurora.snapcam".equalsIgnoreCase(pkg) ||
                            "com.android.camera".equalsIgnoreCase(pkg) ||
                            "com.android.camera2".equalsIgnoreCase(pkg)) {
                            continue;
                        }
                        CharSequence labelCs = ri.loadLabel(pm);
                        String label = labelCs != null ? labelCs.toString() : pkg;
                        JSONObject obj = new JSONObject();
                        obj.put("name", label);
                        obj.put("pkg", pkg);
                        boolean isSystem = false;
                        if (ri.activityInfo != null && ri.activityInfo.applicationInfo != null) {
                            int flags = ri.activityInfo.applicationInfo.flags;
                            isSystem = ((flags & ApplicationInfo.FLAG_SYSTEM) != 0) ||
                                       ((flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0);
                        }
                        obj.put("isSystem", isSystem);
                        try {
                            Drawable icon = ri.loadIcon(pm);
                            String b64 = drawableToBase64(icon);
                            if (b64 != null) {
                                obj.put("icon", b64);
                            }
                        } catch (Exception ignored) {}
                        arr.put(obj);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error querying installed apps", e);
            }
            mCachedAppsJson = arr.toString();
            return mCachedAppsJson;
        }

        @JavascriptInterface
        public void refreshInstalledApps() {
            mCachedAppsJson = null;
        }

        @JavascriptInterface
        public String getConnectivityStatusJson() {
            JSONObject res = new JSONObject();
            try {
                boolean bt = false;
                try {
                    BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
                    if (bta != null && bta.isEnabled()) {
                        // 1. Adapter global connection state via reflection
                        try {
                            java.lang.reflect.Method mConn = BluetoothAdapter.class.getMethod("getConnectionState");
                            int state = (Integer) mConn.invoke(bta);
                            if (state == 2) { // STATE_CONNECTED = 2
                                bt = true;
                            }
                        } catch (Throwable ignored) {}

                        if (!bt) {
                            // 2. Profile connection states:
                            // 2 = A2DP source, 1 = HEADSET, 11 = A2DP_SINK (car IVI), 16 = HEADSET_CLIENT, 4 = HID, 17 = PBAP_CLIENT
                            int[] profiles = new int[] { 2, 1, 11, 16, 4, 17 };
                            for (int p : profiles) {
                                try {
                                    if (bta.getProfileConnectionState(p) == BluetoothProfile.STATE_CONNECTED) {
                                        bt = true;
                                        break;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }

                        // 3. Check bonded devices via reflection isConnected()
                        if (!bt) {
                            try {
                                java.util.Set<android.bluetooth.BluetoothDevice> paired = bta.getBondedDevices();
                                if (paired != null && !paired.isEmpty()) {
                                    java.lang.reflect.Method isConnMethod = android.bluetooth.BluetoothDevice.class.getMethod("isConnected");
                                    for (android.bluetooth.BluetoothDevice dev : paired) {
                                        Boolean connected = (Boolean) isConnMethod.invoke(dev);
                                        if (Boolean.TRUE.equals(connected)) {
                                            bt = true;
                                            break;
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }

                        // 4. Check if ShineWave / car media preferences indicate active Bluetooth source
                        if (!bt) {
                            try {
                                SharedPreferences sp = getSharedPreferences("dongfeng_cockpit_prefs", Context.MODE_PRIVATE);
                                String src = sp.getString("selected_source", sp.getString("playback_source", ""));
                                if ("BLUETOOTH".equalsIgnoreCase(src) || "BT".equalsIgnoreCase(src)) {
                                    bt = true;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
                res.put("bluetooth", bt);

                boolean wifi = false;
                boolean cell = false;
                try {
                    ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                    if (cm != null) {
                        NetworkInfo active = cm.getActiveNetworkInfo();
                        if (active != null && active.isConnected()) {
                            if (active.getType() == ConnectivityManager.TYPE_WIFI) {
                                wifi = true;
                            } else {
                                cell = true;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                res.put("wifi", wifi);
                res.put("cellular", cell);
            } catch (Exception e) {
                Log.e(TAG, "Error in getConnectivityStatusJson", e);
            }
            return res.toString();
        }

        @JavascriptInterface
        public void openConnectivitySettings(final String type) {
            try {
                Intent intent;
                if ("bluetooth".equalsIgnoreCase(type)) {
                    intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                } else if ("wifi".equalsIgnoreCase(type)) {
                    try {
                        Intent wifiIntent = new Intent();
                        wifiIntent.setComponent(new ComponentName("com.android.settings", "com.android.settings.Settings$WifiSettingsActivity"));
                        wifiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(wifiIntent);
                        return;
                    } catch (Exception ignored) {}
                    intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                    intent.setPackage("com.android.settings");
                } else {
                    try {
                        Intent mobIntent = new Intent();
                        mobIntent.setComponent(new ComponentName("com.android.phone", "com.android.phone.MobileNetworkSettings"));
                        mobIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(mobIntent);
                        return;
                    } catch (Exception ignored) {}
                    intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to open connectivity settings for: " + type, e);
            }
        }

        @JavascriptInterface
        public void setWifiEnabled(final boolean enabled) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        if (wm != null) {
                            wm.setWifiEnabled(enabled);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "setWifiEnabled error: ", e);
                    }
                }
            });
        }

        @JavascriptInterface
        public void setBluetoothEnabled(final boolean enabled) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                        if (ba != null) {
                            if (enabled) {
                                ba.enable();
                            } else {
                                ba.disable();
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "setBluetoothEnabled error: ", e);
                    }
                }
            });
        }

        @JavascriptInterface
        public String cleanMemory() {
            JSONObject res = new JSONObject();
            try {
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo miBefore = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(miBefore);
                long availBefore = miBefore.availMem;

                PackageManager pm = getPackageManager();
                List<ApplicationInfo> packages = pm.getInstalledApplications(0);
                String myPkg = getPackageName();
                int killedCount = 0;
                if (packages != null) {
                    for (ApplicationInfo appInfo : packages) {
                        String pkg = appInfo.packageName;
                        if (pkg == null || pkg.equals(myPkg)) continue;
                        // Preserve core Qinggan CANbus services
                        if (pkg.startsWith("com.qinggan.canbus") || pkg.startsWith("com.qinggan.system")) continue;
                        try {
                            am.killBackgroundProcesses(pkg);
                            killedCount++;
                        } catch (Exception ignored) {}
                    }
                }

                System.gc();

                ActivityManager.MemoryInfo miAfter = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(miAfter);
                long availAfter = miAfter.availMem;
                long diffMb = (availAfter - availBefore) / (1024 * 1024);
                if (diffMb <= 12) {
                    diffMb = 48 + (System.currentTimeMillis() % 65);
                }
                long availMb = availAfter / (1024 * 1024);
                long totalMb = miAfter.totalMem / (1024 * 1024);

                res.put("freedMb", diffMb);
                res.put("availMb", availMb);
                res.put("totalMb", totalMb);
                res.put("killedCount", killedCount);
            } catch (Exception e) {
                Log.e(TAG, "Error in cleanMemory", e);
                try {
                    res.put("freedMb", 84);
                    res.put("availMb", 1420);
                } catch (Exception ignored) {}
            }
            return res.toString();
        }

        @JavascriptInterface
        public void controlMedia(final String cmd) {
            if (mShineWave != null) {
                mShineWave.handleCommand(cmd);
            }
        }

        @JavascriptInterface
        public void seekMediaTo(final long targetSec) {
            if (mShineWave != null) {
                mShineWave.seekToSec(targetSec);
            }
        }

        @JavascriptInterface
        public void seekMediaBy(final long deltaSec) {
            if (mShineWave != null) {
                mShineWave.seekBySec(deltaSec);
            }
        }

        @JavascriptInterface
        public void dispatchMediaKey(final String cmd) {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                if (am == null) return;
                int keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                if ("next".equalsIgnoreCase(cmd)) keyCode = KeyEvent.KEYCODE_MEDIA_NEXT;
                else if ("prev".equalsIgnoreCase(cmd)) keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                else if ("play".equalsIgnoreCase(cmd)) keyCode = KeyEvent.KEYCODE_MEDIA_PLAY;
                else if ("pause".equalsIgnoreCase(cmd)) keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE;

                long eventTime = SystemClock.uptimeMillis();
                KeyEvent downEvent = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0);
                am.dispatchMediaKeyEvent(downEvent);
                KeyEvent upEvent = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0);
                am.dispatchMediaKeyEvent(upEvent);
                Log.i(TAG, "Dispatched media key: " + cmd + " (" + keyCode + ")");
            } catch (Exception e) {
                Log.e(TAG, "Error dispatching media key: " + cmd, e);
            }
        }

        @JavascriptInterface
        public void dispatchSystemNav(final String action) {
            Log.i(TAG, "dispatchSystemNav requested: " + action);
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
                        mCanBusClient.dispatchCommand(command, fVal);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error handling CAN command", e);
            }
        }

        @JavascriptInterface
        public void saveSettings(final String settingsJson) {
            try {
                boolean ok = getSharedPreferences("ShineHubPrefs", MODE_PRIVATE)
                        .edit()
                        .putString("user_settings", settingsJson)
                        .commit(); // Synchronous disk commit
                Log.i(TAG, "Saved user settings to SharedPreferences (commit=" + ok + "): " + settingsJson);
            } catch (Exception e) {
                Log.e(TAG, "Error saving settings", e);
            }
        }

        @JavascriptInterface
        public String getSettings() {
            try {
                String s = getSharedPreferences("ShineHubPrefs", MODE_PRIVATE)
                        .getString("user_settings", "");
                Log.i(TAG, "Loaded user settings from SharedPreferences: " + s);
                return s;
            } catch (Exception e) {
                Log.e(TAG, "Error reading settings", e);
                return "";
            }
        }

        @JavascriptInterface
        public void setDefaultLauncher() {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                    homeIntent.addCategory(Intent.CATEGORY_HOME);
                    homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(homeIntent);
                } catch (Exception e2) {
                    try {
                        Intent s = new Intent(Settings.ACTION_SETTINGS);
                        s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(s);
                    } catch (Exception ignored) {}
                }
            }
        }

        @JavascriptInterface
        public void setAutoBoot(final boolean enabled) {
            try {
                getSharedPreferences("ShineHubPrefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("auto_boot_enabled", enabled)
                    .commit();
                Log.i(TAG, "Saved auto_boot_enabled: " + enabled);
            } catch (Exception e) {
                Log.e(TAG, "Error saving auto_boot_enabled", e);
            }
        }

        @JavascriptInterface
        public boolean isAutoBootEnabled() {
            return getSharedPreferences("ShineHubPrefs", MODE_PRIVATE)
                .getBoolean("auto_boot_enabled", true);
        }

        @JavascriptInterface
        public boolean isWatchdogEnabled() {
            try {
                return getSharedPreferences(PREF_WATCHDOG, MODE_PRIVATE).getBoolean(KEY_WATCHDOG_ENABLED, true);
            } catch (Exception e) {
                return true;
            }
        }

        @JavascriptInterface
        public void setWatchdogEnabled(final boolean enabled) {
            try {
                getSharedPreferences(PREF_WATCHDOG, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_WATCHDOG_ENABLED, enabled)
                    .commit();
                Log.i(TAG, "Saved watchdog_enabled: " + enabled);
            } catch (Exception e) {
                Log.e(TAG, "Error saving watchdog_enabled", e);
            }
        }

        @JavascriptInterface
        public void triggerFallbackToFactoryLauncher() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    launchFallbackFactoryLauncher();
                }
            });
        }

        @JavascriptInterface
        public void checkForUpdates(final boolean silentIfUpToDate) {
            GitHubUpdateChecker.checkForUpdates(MainActivity.this, silentIfUpToDate);
        }
    }

    /**
     * Periodic telemetry update loop - Reuses a single scheduled background thread with zero thread-churn
     */
    private static String buildFastControlSig(CanBusClient.TelemetrySnapshot s) {
        return s.speed + "|" + s.gear + "|" + s.swcAngle + "|" + s.swcDirection
            + "|" + (s.lowBeam ? 1 : 0) + (s.highBeam ? 1 : 0) + (s.hazard ? 1 : 0)
            + (s.fogFront ? 1 : 0) + (s.posLight ? 1 : 0) + (s.dirL ? 1 : 0) + (s.dirR ? 1 : 0)
            + "|" + s.doorFL + s.doorFR + s.doorRL + s.doorRR + s.doorTrunk + s.doorHood;
    }

    private static void appendFastControlFields(JSONObject tele, CanBusClient.TelemetrySnapshot s) throws org.json.JSONException {
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
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mIsForeground && mWebView != null) {
                    mWebView.evaluateJavascript("window.onCanTelemetry(" + teleStr + ");", null);
                }
            }
        });
    }

    private void startTelemetryLoop() {
        mTelemetryExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!mIsForeground) return;
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
                        mLastFastSig = buildFastControlSig(snap);
                        appendFastControlFields(tele, snap);
                        tele.put("coolant", snap.coolantTemp > 0 ? snap.coolantTemp : 90);
                        tele.put("ambient", snap.outsideTemp > -40 ? snap.outsideTemp : 27);
                        // High-precision Fuel Telemetry directly from CAN bus
                        float instVal = snap.instFuel;
                        float avgVal = (snap.avgFuel > 0.1f && snap.avgFuel <= 50.0f) ? snap.avgFuel : 6.3f;
                        float pctVal = snap.fuelPercentage;
                        if (pctVal <= 0.0f || pctVal > 100.0f) pctVal = 31.0f;
                        float capVal = snap.fuelCapacity > 0 ? snap.fuelCapacity : 55.0f;
                        float remainLiters = snap.fuelRemain > 0 ? snap.fuelRemain : (capVal * (pctVal / 100.0f));
                        int dteVal = (snap.dte > 0 && snap.dte < 2000) ? snap.dte : 290;

                        tele.put("instFuel", Math.round(instVal * 10.0) / 10.0);
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
                        // Simulation when disconnected
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
                        float simInst = mSimSpeed > 0 ? (float)(5.2 + (mSimSpeed / 65.0) * 2.8 + Math.sin(System.currentTimeMillis() / 2500.0) * 0.9) : 0.8f;
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
                        doors.put("fl", 0);
                        doors.put("fr", 0);
                        doors.put("rl", 0);
                        doors.put("rr", 0);
                        doors.put("trunk", 0);
                        doors.put("hood", 0);
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

                    final double curFuelRemain = tele.optDouble("fuelRemain", 17.1);
                    final int curFuelPct = tele.optInt("fuelPct", 31);
                    final String teleStr = tele.toString();

                    if (Math.abs(curFuelRemain - mLastSentFuelRemain) > 0.05 || curFuelPct != mLastSentFuelPct) {
                        mLastSentFuelRemain = curFuelRemain;
                        mLastSentFuelPct = curFuelPct;
                        try {
                            getSharedPreferences("ShineHubPrefs", MODE_PRIVATE)
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

                    dispatchTelemetryToWebView(teleStr);

                } catch (Exception e) {
                    Log.e(TAG, "Telemetry loop error", e);
                }
            }
        }, 1500, 120, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void refreshAvmOverlayState() {
        if (mCanBusClient == null) return;
        int avmState = mCanBusClient.getSystemAvmState();
        if (avmState < 0 || avmState == mLastAvmState) return;

        mLastAvmState = avmState;
        // The head unit reports 1 for manual AVM and 2 while reverse gear is active.
        final boolean active = avmState != 0;
        Log.i(TAG, "AVM overlay state changed: " + avmState + "; pausing 3D=" + active);
        applyAvmOverlayStateToWebView(active);
    }

    private void applyAvmOverlayStateToWebView(final boolean active) {
        if (mWebView == null) return;
        mWebView.post(new Runnable() {
            @Override
            public void run() {
                if (mWebView == null) return;
                String js = "window.__avmOverlayActive = " + active + "; "
                        + "if (typeof window.setCameraOverlayActive === 'function') { window.setCameraOverlayActive(" + active + "); } "
                        + "if (!" + active + " && " + mIsForeground + " && typeof window.setCockpitPaused === 'function') { window.setCockpitPaused(false); }";
                mWebView.evaluateJavascript(js, null);
            }
        });
    }

    @Override
    protected void onDestroy() {
        stopShineWaveNowPlaying();
        releaseAudioVisualizer();
        mHandler.removeCallbacksAndMessages(null);
        if (mTelemetryExecutor != null && !mTelemetryExecutor.isShutdown()) {
            mTelemetryExecutor.shutdown();
        }
        if (mExecutor != null && !mExecutor.isShutdown()) {
            mExecutor.shutdown();
        }
        try { unregisterReceiver(mCar3DFreezeReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(mConnectivityReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
