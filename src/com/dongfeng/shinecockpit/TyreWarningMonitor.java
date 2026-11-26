package com.dongfeng.shinecockpit;

import android.content.SharedPreferences;

/**
 * Decides when to show the indirect TPMS tyre warning popup (Normal / Warning).
 *
 * Dongfeng Shine uses indirect wheel-speed TPMS, which reports per-wheel and/or
 * vehicle-wide warning status without real per-tyre bar readings.
 * - Channels 0..3 represent front-left, front-right, rear-left, rear-right flags.
 * - Channel 4 (SYSTEM_BIT) represents a general system TPMS warning when no specific wheel is flagged.
 * - Each channel must remain active for 20 seconds to warn (ignoring boot noise), warns once until
 *   cleared or normal for 60 seconds, and clears immediately on TPMS reset.
 */
final class TyreWarningMonitor {

    /** Individual tyre order: front-left, front-right, rear-left, rear-right. */
    static final int TYRES = 4;
    /** System-wide fallback channel (no individual wheel identified). */
    static final int SYSTEM_BIT = 1 << TYRES;
    static final int CHANNELS = TYRES + 1;

    private static final long CONFIRM_MS = 20000L;
    private static final long REARM_MS = 60000L;
    private static final String PREF_WARNED = "tyre_warn_mask";

    private final SharedPreferences mPrefs;
    private int mWarnedMask;
    private final long[] mLowSince = new long[CHANNELS];
    private final long[] mOkSince = new long[CHANNELS];

    TyreWarningMonitor(SharedPreferences prefs) {
        mPrefs = prefs;
        mWarnedMask = prefs.getInt(PREF_WARNED, 0);
    }

    /** Clears all latched tyre warnings (for example after the driver triggers a TPMS reset). */
    synchronized void resetWarned() {
        mWarnedMask = 0;
        for (int i = 0; i < CHANNELS; i++) {
            mLowSince[i] = 0L;
            mOkSince[i] = 0L;
        }
        try {
            mPrefs.edit().putInt(PREF_WARNED, 0).apply();
        } catch (Exception ignored) {}
    }

    /**
     * Call with every REAL TPMS reading from the car (never with simulated placeholders).
     *
     * @param wheelFlags 4 per-wheel warning flags [FL, FR, RL, RR]
     * @param systemWarn vehicle-wide TPMS system or temperature warning flag
     * @param enabled    the "Tyre Pressure Warning" switch in Settings
     * @param nowMs      SystemClock.elapsedRealtime()
     * @return bitmask of channels to warn about now (bits 0..3 for wheels, SYSTEM_BIT for general)
     */
    synchronized int onReading(boolean[] wheelFlags, boolean systemWarn, boolean enabled, long nowMs) {
        if (!enabled) {
            for (int i = 0; i < CHANNELS; i++) mLowSince[i] = 0L;
            return 0;
        }
        boolean anyWheelFlag = false;
        for (int i = 0; i < TYRES; i++) {
            if (wheelFlags != null && i < wheelFlags.length && wheelFlags[i]) {
                anyWheelFlag = true;
                break;
            }
        }

        int toWarn = 0;
        int warned = mWarnedMask;
        for (int i = 0; i < CHANNELS; i++) {
            int bit = 1 << i;
            boolean active = (i < TYRES)
                    ? (wheelFlags != null && i < wheelFlags.length && wheelFlags[i])
                    : (systemWarn && !anyWheelFlag);
            if (active) {
                mOkSince[i] = 0L;
                if (mLowSince[i] == 0L) mLowSince[i] = nowMs;
                if ((warned & bit) == 0 && nowMs - mLowSince[i] >= CONFIRM_MS) toWarn |= bit;
            } else {
                mLowSince[i] = 0L;
                if (mOkSince[i] == 0L) mOkSince[i] = nowMs;
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
