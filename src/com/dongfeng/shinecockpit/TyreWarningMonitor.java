package com.dongfeng.shinecockpit;

import android.content.SharedPreferences;

/**
 * Decides when to show the "tyre pressure" popup.
 *
 * In plain words:
 * - A tyre counts as low when the car's own tyre system (TPMS) flags it, or its pressure is
 *   under 1.9 bar. This is the same rule the launcher's tyre badge already uses, so the
 *   popup and the badge always agree.
 * - Each tyre warns ONCE. It can warn again only after it has been fine for 60 seconds
 *   (for example after pumping it up).
 * - A tyre must stay low for 20 seconds before the popup shows, so a short wrong reading
 *   (for example right after starting the car) is ignored.
 * - If several tyres are low, they are shown together in one popup.
 * - What was already warned is saved, so restarting the launcher does not repeat a warning.
 *
 * This class only decides; MainActivity shows the popup.
 */
final class TyreWarningMonitor {

    /** Tyre order everywhere: front-left, front-right, rear-left, rear-right. */
    static final int TYRES = 4;
    /** Same limit as the tyre badge in cockpit-ui.js (updateTpmsTire). */
    static final float LOW_BAR = 1.9f;
    private static final long CONFIRM_MS = 20000L;
    private static final long REARM_MS = 60000L;
    private static final String PREF_WARNED = "tyre_warn_mask";

    private final SharedPreferences mPrefs;
    private int mWarnedMask;
    private final long[] mLowSince = new long[TYRES];
    private final long[] mOkSince = new long[TYRES];

    TyreWarningMonitor(SharedPreferences prefs) {
        mPrefs = prefs;
        mWarnedMask = prefs.getInt(PREF_WARNED, 0);
    }

    /**
     * Call with every REAL tyre reading from the car (never with made-up values).
     *
     * @param carFlags      the car's own warning per tyre
     * @param pressures     pressure per tyre, in bar
     * @param pressureValid true where the pressure is a real reading
     * @param enabled       the "Tyre Pressure Warning" switch in Settings
     * @param nowMs         SystemClock.elapsedRealtime()
     * @return the tyres to warn about now, as bits (1 = front-left, 2 = front-right,
     *         4 = rear-left, 8 = rear-right), or 0 for no popup
     */
    synchronized int onReading(boolean[] carFlags, float[] pressures, boolean[] pressureValid,
                               boolean enabled, long nowMs) {
        if (!enabled) {
            for (int i = 0; i < TYRES; i++) mLowSince[i] = 0L;
            return 0;
        }
        int toWarn = 0;
        int warned = mWarnedMask;
        for (int i = 0; i < TYRES; i++) {
            int bit = 1 << i;
            boolean low = carFlags[i] || (pressureValid[i] && pressures[i] > 0f && pressures[i] < LOW_BAR);
            if (low) {
                mOkSince[i] = 0L;
                if (mLowSince[i] == 0L) mLowSince[i] = nowMs;
                if ((warned & bit) == 0 && nowMs - mLowSince[i] >= CONFIRM_MS) toWarn |= bit;
            } else {
                mLowSince[i] = 0L;
                if (mOkSince[i] == 0L) mOkSince[i] = nowMs;
                // Fine again for a minute: this tyre may warn again later.
                if ((warned & bit) != 0 && nowMs - mOkSince[i] >= REARM_MS) warned &= ~bit;
            }
        }
        warned |= toWarn;
        if (warned != mWarnedMask) {
            mWarnedMask = warned;
            try {
                mPrefs.edit().putInt(PREF_WARNED, warned).apply();
            } catch (Exception ignored) {}
        }
        return toWarn;
    }
}
