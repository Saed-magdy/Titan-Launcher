/*
 * Titan Cockpit Launcher
 * Created by Dr. Saed
 */
package com.dongfeng.shinecockpit;

import android.media.audiofx.Visualizer;
import android.os.SystemClock;
import android.util.Log;

/**
 * Captures real-time system audio RMS energy, bass beat pulse, and a 16-band
 * smoothed FFT spectrum from session 0 with zero per-frame String allocations.
 */
public final class AudioSpectrumAnalyzer {
    private static final String TAG = "TitanAudioAnalyzer";
    private static final String SILENT_CSV = "0.000,0.000,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0";
    private static final long RETRY_COOLDOWN_MS = 4000L;
    private static final int NUM_BANDS = 16;

    private Visualizer mVisualizer = null;
    private final float[] mAudioBands = new float[NUM_BANDS];
    private volatile float mAudioEnergy = 0.0f;
    private volatile float mBeatPulse = 0.0f;
    private long mLastInitAttemptMs = 0L;

    public synchronized void ensureInitialized(boolean isForeground) {
        if (mVisualizer != null || !isForeground) return;
        long now = SystemClock.uptimeMillis();
        if (now - mLastInitAttemptMs < RETRY_COOLDOWN_MS) return;
        mLastInitAttemptMs = now;
        try {
            Visualizer viz = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            int capSize = Math.max(128, range[0]);
            viz.setCaptureSize(capSize);
            viz.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                    if (waveform == null || waveform.length == 0) return;
                    long sum = 0L;
                    for (int i = 0; i < waveform.length; i++) {
                        int diff = (((int) waveform[i]) & 0xFF) - 128;
                        sum += diff * diff;
                    }
                    float rms = (float) Math.sqrt((double) sum / waveform.length) / 128.0f;
                    mAudioEnergy = Math.min(1.0f, rms * 1.5f);
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    if (fft == null || fft.length < 16) return;
                    int n = fft.length / 2;
                    int binsPerBand = Math.max(1, n / NUM_BANDS);
                    float bassSum = 0.0f;
                    for (int b = 0; b < NUM_BANDS; b++) {
                        float bandEnergy = 0.0f;
                        int start = b * binsPerBand;
                        int end = Math.min(n, (b + 1) * binsPerBand);
                        for (int k = start; k < end; k++) {
                            int r = fft[2 * k];
                            int im = fft[2 * k + 1];
                            bandEnergy += (float) Math.hypot((double) r, (double) im);
                        }
                        float avg = (end > start) ? (bandEnergy / (end - start)) : 0.0f;
                        float norm = Math.min(1.0f, avg / (32.0f + b * 2.0f));
                        float smoothed = (mAudioBands[b] * 0.35f) + (norm * 0.65f);
                        mAudioBands[b] = smoothed;
                        if (b < 4) bassSum += smoothed;
                    }
                    mBeatPulse = Math.min(1.0f, (bassSum / 4.0f) * 1.4f);
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, true);
            viz.setEnabled(true);
            mVisualizer = viz;
            Log.i(TAG, "Audio Visualizer bound successfully to session 0");
        } catch (Throwable t) {
            Log.w(TAG, "Audio Visualizer bind note: " + t.getMessage());
            mVisualizer = null;
        }
    }

    public synchronized void release() {
        if (mVisualizer != null) {
            try {
                mVisualizer.setEnabled(false);
                mVisualizer.release();
            } catch (Throwable ignored) {}
            mVisualizer = null;
        }
        mAudioEnergy = 0.0f;
        mBeatPulse = 0.0f;
    }

    /** Returns live beat pulse, RMS energy, and 16 normalized FFT bands as CSV. */
    public String getSpectrumCsv(boolean isForeground) {
        if (!isForeground) return SILENT_CSV;
        ensureInitialized(true);
        StringBuilder sb = new StringBuilder(112);
        appendFixed3(sb, mBeatPulse);
        sb.append(',');
        appendFixed3(sb, mAudioEnergy);
        for (int i = 0; i < NUM_BANDS; i++) {
            sb.append(',');
            appendFixed3(sb, mAudioBands[i]);
        }
        return sb.toString();
    }

    private static void appendFixed3(StringBuilder sb, float val) {
        int scaled = Math.round(Math.max(0.0f, Math.min(9.999f, val)) * 1000.0f);
        int whole = scaled / 1000;
        int frac = scaled % 1000;
        sb.append(whole).append('.');
        if (frac < 100) sb.append('0');
        if (frac < 10) sb.append('0');
        sb.append(frac);
    }
}
