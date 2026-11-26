package com.dongfeng.shinecockpit;

import org.json.JSONObject;

/**
 * Decides when to give the speed alert.
 *
 * In plain words:
 * - The driver turns it on in Settings and picks a speed (40-200 km/h, default 120).
 *   It is OFF by default.
 * - The alert comes when the car stays above that speed for 3 seconds, so a short
 *   overtake does not trigger it.
 * - It does not repeat while the car stays above the speed. It can alert again only
 *   after the speed has dropped at least 5 km/h below it.
 *
 * This class only decides; MainActivity shows the message and plays the sound.
 */
final class SpeedAlertMonitor {

    static final int DEFAULT_LIMIT = 120;
    static final int MIN_LIMIT = 40;
    static final int MAX_LIMIT = 200;
    private static final long CONFIRM_MS = 3000L;
    private static final int REARM_BELOW = 5;

    private boolean mArmed = true;
    private long mOverSinceMs = 0L;

    private String mCachedJson = null;
    private boolean mEnabled = false;
    private int mLimit = DEFAULT_LIMIT;
    private boolean mVoice = true;

    /**
     * Call with every speed reading from the car.
     *
     * @param kmh          current speed in km/h (0 or less = unknown)
     * @param nowMs        SystemClock.elapsedRealtime()
     * @param settingsJson the saved user settings
     * @return the speed limit to alert about now, or 0 for no alert
     */
    synchronized int onSpeed(int kmh, long nowMs, String settingsJson) {
        readSettings(settingsJson);
        if (!mEnabled || kmh <= 0) {
            mOverSinceMs = 0L;
            if (!mEnabled || kmh <= mLimit - REARM_BELOW) mArmed = true;
            return 0;
        }
        if (kmh > mLimit) {
            if (mOverSinceMs == 0L) mOverSinceMs = nowMs;
            if (mArmed && nowMs - mOverSinceMs >= CONFIRM_MS) {
                mArmed = false;
                return mLimit;
            }
        } else {
            mOverSinceMs = 0L;
            if (kmh <= mLimit - REARM_BELOW) mArmed = true;
        }
        return 0;
    }

    /** True when the alert is switched on (so the background check knows whether to read the speed). */
    synchronized boolean isEnabled(String settingsJson) {
        readSettings(settingsJson);
        return mEnabled;
    }

    /** True when the alert should be spoken; false = a short chime only. */
    synchronized boolean useVoice() {
        return mVoice;
    }

    private void readSettings(String json) {
        if (json == null) json = "";
        if (json.equals(mCachedJson)) return;
        mCachedJson = json;
        mEnabled = false;
        mLimit = DEFAULT_LIMIT;
        mVoice = true;
        if (json.isEmpty()) return;
        try {
            JSONObject s = new JSONObject(json);
            mEnabled = s.optBoolean("speedAlertEnabled", false);
            int limit = s.optInt("speedAlertLimit", DEFAULT_LIMIT);
            if (limit >= MIN_LIMIT && limit <= MAX_LIMIT) mLimit = limit;
            mVoice = s.optBoolean("speedAlertVoice", true);
        } catch (Exception ignored) {
            // Broken settings: stay off rather than alert at a wrong speed.
            mEnabled = false;
        }
    }
}
