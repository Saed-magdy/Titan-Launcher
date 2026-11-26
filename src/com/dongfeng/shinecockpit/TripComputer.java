package com.dongfeng.shinecockpit;

import android.content.SharedPreferences;

import org.json.JSONObject;

/**
 * Trip computer: two trips (A and B) that the driver can reset separately.
 *
 * In plain words:
 * - Distance comes from the car's own odometer, so it is counted even while another app
 *   (e.g. Maps) is on screen or the launcher was closed.
 * - Fuel used comes from the drop in the fuel level. Refuelling (a rise of more than
 *   3 litres) is recognised and not counted. Small up-and-down movements of the fuel
 *   needle are ignored (it must drop 0.5 litre before it counts).
 * - Driving time counts only while the car is moving.
 * - Average consumption is shown only after 5 km, so it is not a wild number at the start.
 * - Everything is saved, so the trips survive a launcher restart.
 *
 * MainActivity calls onReading() about every 10 seconds; the page reads toJson().
 */
final class TripComputer {

    private static final double REFUEL_LITRES = 3.0;
    private static final double FUEL_STEP_LITRES = 0.5;
    private static final long MAX_GAP_MS = 20000L;
    private static final int MIN_KM_FOR_AVERAGE = 5;

    private static final class Trip {
        int startOdoKm = -1;
        double fuelUsedL = 0;
        double lastFuelL = -1;
        long driveMs = 0;
        long startedAtWallMs = System.currentTimeMillis();
    }

    private final SharedPreferences mPrefs;
    private final Trip[] mTrips = { new Trip(), new Trip() };
    private int mLastOdoKm = -1;
    private long mLastReadingMs = 0L;

    TripComputer(SharedPreferences prefs) {
        mPrefs = prefs;
        load(0, "trip_a");
        load(1, "trip_b");
    }

    /** Called with each reading from the car. Values that are not real readings are passed as invalid. */
    synchronized void onReading(boolean odoValid, int odoKm, boolean fuelValid, double fuelLitres,
                                int speedKmh, long nowMs) {
        long gap = mLastReadingMs == 0L ? 0L : Math.min(MAX_GAP_MS, Math.max(0L, nowMs - mLastReadingMs));
        mLastReadingMs = nowMs;
        if (odoValid) mLastOdoKm = odoKm;
        for (Trip t : mTrips) {
            if (odoValid && t.startOdoKm < 0) t.startOdoKm = odoKm;
            if (fuelValid) {
                if (t.lastFuelL < 0) {
                    t.lastFuelL = fuelLitres;
                } else if (fuelLitres > t.lastFuelL + REFUEL_LITRES) {
                    t.lastFuelL = fuelLitres; // refuelled: start counting from the new level
                } else if (fuelLitres <= t.lastFuelL - FUEL_STEP_LITRES) {
                    t.fuelUsedL += t.lastFuelL - fuelLitres;
                    t.lastFuelL = fuelLitres;
                }
            }
            if (speedKmh > 0) t.driveMs += gap;
        }
        save();
    }

    /** "Reset" button: index 0 = trip A, 1 = trip B. */
    synchronized void reset(int index) {
        if (index < 0 || index > 1) return;
        Trip old = mTrips[index];
        Trip t = new Trip();
        t.startOdoKm = mLastOdoKm;
        t.lastFuelL = old.lastFuelL;
        mTrips[index] = t;
        save();
    }

    /** {"a": {...}, "b": {...}} with km, minutes, fuel used, average (null under 5 km) and start time. */
    synchronized String toJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("a", tripJson(mTrips[0]));
            root.put("b", tripJson(mTrips[1]));
            root.put("live", mLastOdoKm >= 0);
        } catch (Exception ignored) {}
        return root.toString();
    }

    private JSONObject tripJson(Trip t) throws org.json.JSONException {
        int km = (t.startOdoKm >= 0 && mLastOdoKm >= t.startOdoKm) ? mLastOdoKm - t.startOdoKm : 0;
        JSONObject o = new JSONObject();
        o.put("km", km);
        o.put("minutes", t.driveMs / 60000L);
        o.put("fuelL", Math.round(t.fuelUsedL * 10.0) / 10.0);
        if (km >= MIN_KM_FOR_AVERAGE && t.fuelUsedL > 0) {
            o.put("avg", Math.round(t.fuelUsedL / km * 1000.0) / 10.0);
        } else {
            o.put("avg", JSONObject.NULL);
        }
        o.put("since", t.startedAtWallMs);
        return o;
    }

    private void load(int i, String key) {
        try {
            String s = mPrefs.getString(key, "");
            if (s == null || s.isEmpty()) return;
            JSONObject o = new JSONObject(s);
            Trip t = mTrips[i];
            t.startOdoKm = o.optInt("startOdoKm", -1);
            t.fuelUsedL = o.optDouble("fuelUsedL", 0);
            t.lastFuelL = o.optDouble("lastFuelL", -1);
            t.driveMs = o.optLong("driveMs", 0);
            t.startedAtWallMs = o.optLong("startedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
    }

    private void save() {
        try {
            SharedPreferences.Editor e = mPrefs.edit();
            String[] keys = { "trip_a", "trip_b" };
            for (int i = 0; i < 2; i++) {
                Trip t = mTrips[i];
                JSONObject o = new JSONObject();
                o.put("startOdoKm", t.startOdoKm);
                o.put("fuelUsedL", t.fuelUsedL);
                o.put("lastFuelL", t.lastFuelL);
                o.put("driveMs", t.driveMs);
                o.put("startedAt", t.startedAtWallMs);
                e.putString(keys[i], o.toString());
            }
            e.apply();
        } catch (Exception ignored) {}
    }
}
