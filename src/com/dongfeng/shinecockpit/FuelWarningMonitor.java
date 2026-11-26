package com.dongfeng.shinecockpit;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Decides when to show the "low fuel" popup.
 *
 * In plain words:
 * - The driver picks warning levels in Settings (default 50% and 25%).
 * - Each level warns ONCE when the tank goes down to it.
 * - The level is ready to warn again only after refuelling at least 5% above it,
 *   so the needle moving a little on bumps or hills does not cause repeated popups.
 * - The tank must stay at or below the level for 20 seconds before the popup shows,
 *   so short jumps of the gauge (for example right after starting the car) are ignored.
 * - What was already warned is saved, so restarting the launcher does not repeat a warning.
 *
 * This class only decides; MainActivity shows the popup.
 */
final class FuelWarningMonitor {

    static final int[] DEFAULT_LEVELS = {50, 25};
    static final int MAX_LEVELS = 2;
    /** The two levels are always at least this far apart, so they can never be the same. */
    static final int MIN_GAP = 5;
    static final int MIN_LEVEL = 5;
    static final int MAX_LEVEL = 95;

    /** Refuel this many percent above a level before it can warn again. */
    private static final int REARM_MARGIN = 5;
    /** The tank must stay at or below a level this long before the popup shows. */
    private static final long CONFIRM_MS = 20000L;

    private static final String PREF_LAST_WARNED = "fuel_warn_last_level";

    private final SharedPreferences mPrefs;

    /** Lowest level already warned since the last refuel (0 = none). */
    private int mLastWarned;
    private int mPendingLevel = 0;
    private long mPendingSinceMs = 0L;

    private String mCachedSettingsJson = null;
    private boolean mCachedEnabled = true;
    private int[] mCachedLevels = DEFAULT_LEVELS;

    FuelWarningMonitor(SharedPreferences prefs) {
        mPrefs = prefs;
        mLastWarned = prefs.getInt(PREF_LAST_WARNED, 0);
    }

    /**
     * Call with every REAL fuel reading from the car (never with a made-up value).
     *
     * @param pct          fuel in the tank, 0-100
     * @param nowMs        SystemClock.elapsedRealtime()
     * @param settingsJson the saved user settings (may be empty: defaults are used)
     * @return the level to warn about now, or 0 for no popup
     */
    synchronized int onReading(float pct, long nowMs, String settingsJson) {
        readSettings(settingsJson);
        if (!mCachedEnabled || pct <= 0f || pct > 100f) {
            mPendingLevel = 0;
            return 0;
        }

        // Refuelled? Make levels ready again once the tank is 5% above them.
        int stillWarned = 0;
        if (mLastWarned > 0) {
            for (int level : mCachedLevels) {
                if (level >= mLastWarned && pct < level + REARM_MARGIN) {
                    if (stillWarned == 0 || level < stillWarned) stillWarned = level;
                }
            }
            if (stillWarned != mLastWarned) saveLastWarned(stillWarned);
        }

        // Lowest level the tank has reached that was not warned yet.
        int candidate = 0;
        for (int level : mCachedLevels) {
            if (pct <= level && (mLastWarned == 0 || level < mLastWarned)) {
                if (candidate == 0 || level < candidate) candidate = level;
            }
        }

        if (candidate == 0) {
            mPendingLevel = 0;
            return 0;
        }
        if (candidate != mPendingLevel) {
            mPendingLevel = candidate;
            mPendingSinceMs = nowMs;
            return 0;
        }
        if (nowMs - mPendingSinceMs < CONFIRM_MS) return 0;

        mPendingLevel = 0;
        saveLastWarned(candidate);
        return candidate;
    }

    private void saveLastWarned(int level) {
        mLastWarned = level;
        try {
            mPrefs.edit().putInt(PREF_LAST_WARNED, level).apply();
        } catch (Exception ignored) {}
    }

    /** Reads "fuelWarnEnabled" and "fuelWarnLevels" from the saved settings. */
    private void readSettings(String json) {
        if (json == null) json = "";
        if (json.equals(mCachedSettingsJson)) return;
        mCachedSettingsJson = json;
        mCachedEnabled = true;
        mCachedLevels = DEFAULT_LEVELS;
        if (json.isEmpty()) return;
        try {
            JSONObject s = new JSONObject(json);
            mCachedEnabled = s.optBoolean("fuelWarnEnabled", true);
            JSONArray arr = s.optJSONArray("fuelWarnLevels");
            if (arr != null) mCachedLevels = cleanLevels(arr);
        } catch (Exception ignored) {
            // Broken settings: keep the defaults rather than stop warning.
        }
    }

    /** True for the lowest level: that popup is orange and says "Refuel soon". */
    synchronized boolean isLowestLevel(int level) {
        int lowest = Integer.MAX_VALUE;
        for (int l : mCachedLevels) lowest = Math.min(lowest, l);
        return level <= lowest;
    }

    /**
     * Keeps only sensible levels: 5-95%, highest first, no duplicates, at least 5% apart,
     * at most 2. (The settings screen already does this; this is a second safety net.)
     */
    static int[] cleanLevels(JSONArray arr) {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            int v = arr.optInt(i, -1);
            if (v >= MIN_LEVEL && v <= MAX_LEVEL) list.add(v);
        }
        Collections.sort(list, Collections.<Integer>reverseOrder());
        List<Integer> kept = new ArrayList<>();
        for (int v : list) {
            if (kept.size() >= MAX_LEVELS) break;
            if (kept.isEmpty() || kept.get(kept.size() - 1) - v >= MIN_GAP) kept.add(v);
        }
        if (kept.isEmpty()) return DEFAULT_LEVELS;
        int[] out = new int[kept.size()];
        for (int i = 0; i < out.length; i++) out[i] = kept.get(i);
        return out;
    }
}
