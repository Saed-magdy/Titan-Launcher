package com.dongfeng.shinecockpit.shinewave.radio;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads OEM factory radio stations (ContentProvider + RadioService) and merges them
 * cleanly with user-saved FM/AM favorites stored inside the launcher preferences.
 */
public final class OemStationRepository {
    private static final String LOCAL_URI = "content://com.qinggan.radio.database/local_radio?notify=false";
    private static final String FAVORITES_URI = "content://com.qinggan.radio.database/love_radio?notify=false";
    private static final String PREFS = "shine_wave_integrated_prefs";

    private static final List<String> FREQ_COLS = Arrays.asList(
            "frequency", "freq", "fm_frequency", "am_frequency", "fmFreq", "amFreq", "channel_freq"
    );
    private static final List<String> NAME_COLS = Arrays.asList(
            "name", "station_name", "radio_name", "title", "channel_name", "program_name"
    );
    private static final List<String> BAND_COLS = Arrays.asList(
            "type", "band", "radio_type", "radioType", "band_type", "frequency_type"
    );

    public static final class Station {
        public final String name;
        public final int frequencyKhz;
        public final boolean isFavorite;

        public Station(String name, int frequencyKhz, boolean isFavorite) {
            this.name = name != null ? name.trim() : "";
            this.frequencyKhz = frequencyKhz;
            this.isFavorite = isFavorite;
        }
    }

    private final Context appContext;
    private final SharedPreferences prefs;

    public OemStationRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<Station> loadStations(int band, FactoryRadioBridge bridge) {
        boolean isFm = (band == FmAmTunerController.BAND_FM);
        Map<Integer, Station> merged = new LinkedHashMap<>();

        mergeInto(merged, queryProvider(LOCAL_URI, isFm, false), false);
        mergeInto(merged, queryProvider(FAVORITES_URI, isFm, true), false);
        if (bridge != null) {
            mergeInto(merged, bridge.getRadioList(band), false);
        }
        mergeInto(merged, loadLocalFavorites(band), true);

        List<Station> sorted = new ArrayList<>(merged.values());
        Collections.sort(sorted, new Comparator<Station>() {
            @Override
            public int compare(Station a, Station b) {
                if (a.isFavorite != b.isFavorite) return a.isFavorite ? -1 : 1;
                return Integer.compare(a.frequencyKhz, b.frequencyKhz);
            }
        });
        return sorted;
    }

    public boolean toggleLocalFavorite(int band, int frequencyKhz, String name) {
        List<Station> favs = loadLocalFavorites(band);
        boolean removed = false;
        List<Station> updated = new ArrayList<>();
        for (Station s : favs) {
            if (s.frequencyKhz == frequencyKhz) {
                removed = true;
            } else {
                updated.add(s);
            }
        }
        if (!removed) {
            updated.add(0, new Station(name, frequencyKhz, true));
        }
        saveLocalFavorites(band, updated);
        return !removed;
    }

    /**
     * Saves or renames a favorite radio frequency with a custom user name.
     */
    public void saveOrUpdateLocalFavorite(int band, int frequencyKhz, String name) {
        String cleanName = name != null ? name.trim() : "";
        List<Station> favs = loadLocalFavorites(band);
        List<Station> updated = new ArrayList<>();
        updated.add(new Station(cleanName, frequencyKhz, true));
        for (Station s : favs) {
            if (s.frequencyKhz != frequencyKhz) {
                updated.add(s);
            }
        }
        saveLocalFavorites(band, updated);
    }

    private List<Station> loadLocalFavorites(int band) {
        String key = "radio_favs_" + (band == FmAmTunerController.BAND_FM ? "fm" : "am");
        String raw = prefs.getString(key, "[]");
        List<Station> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                int freq = o.optInt("freq", 0);
                if (freq > 0) {
                    list.add(new Station(o.optString("name", ""), freq, true));
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    private void saveLocalFavorites(int band, List<Station> stations) {
        String key = "radio_favs_" + (band == FmAmTunerController.BAND_FM ? "fm" : "am");
        JSONArray arr = new JSONArray();
        for (Station s : stations) {
            try {
                arr.put(new JSONObject().put("freq", s.frequencyKhz).put("name", s.name));
            } catch (Throwable ignored) {}
        }
        prefs.edit().putString(key, arr.toString()).apply();
    }

    private void mergeInto(Map<Integer, Station> map, List<Station> incoming, boolean overrideName) {
        if (incoming == null) return;
        for (Station st : incoming) {
            Station prev = map.get(st.frequencyKhz);
            if (prev == null) {
                map.put(st.frequencyKhz, st);
            } else {
                String name = (overrideName && !st.name.isEmpty())
                        ? st.name
                        : (!prev.name.isEmpty() ? prev.name : st.name);
                map.put(st.frequencyKhz, new Station(name, st.frequencyKhz, prev.isFavorite || st.isFavorite));
            }
        }
    }

    private List<Station> queryProvider(String uriText, boolean requestedFm, boolean fav) {
        List<Station> result = new ArrayList<>();
        try (Cursor c = appContext.getContentResolver().query(Uri.parse(uriText), null, null, null, null)) {
            if (c == null) return result;
            String[] cols = c.getColumnNames();
            String freqCol = findCol(cols, FREQ_COLS);
            if (freqCol == null) return result;
            String nameCol = findCol(cols, NAME_COLS);
            String bandCol = findCol(cols, BAND_COLS);

            while (c.moveToNext()) {
                Double raw = readNum(c, freqCol);
                if (raw == null) continue;
                Boolean storedFm = bandCol != null ? classifyBand(readStr(c, bandCol)) : null;
                if (storedFm != null && storedFm != requestedFm) continue;
                if (requestedFm && storedFm == null && (raw % 1.0 == 0.0) && raw >= 531.0 && raw <= 1701.0) {
                    continue;
                }
                int norm = normalizeFreq(raw, requestedFm);
                if (norm <= 0) continue;
                String name = nameCol != null ? readStr(c, nameCol) : "";
                result.add(new Station(name, norm, fav));
            }
        } catch (Throwable ignored) {}
        return result;
    }

    private static String findCol(String[] cols, List<String> candidates) {
        if (cols == null) return null;
        for (String cand : candidates) {
            for (String c : cols) {
                if (c != null && c.equalsIgnoreCase(cand)) return c;
            }
        }
        return null;
    }

    private static Double readNum(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        if (idx < 0 || c.isNull(idx)) return null;
        try {
            int type = c.getType(idx);
            if (type == Cursor.FIELD_TYPE_INTEGER) return (double) c.getLong(idx);
            if (type == Cursor.FIELD_TYPE_FLOAT) return c.getDouble(idx);
            String str = c.getString(idx);
            if (str != null) return Double.parseDouble(str.trim().replace(',', '.'));
        } catch (Throwable ignored) {}
        return null;
    }

    private static String readStr(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        if (idx < 0 || c.isNull(idx)) return "";
        String s = c.getString(idx);
        return s != null ? s.trim() : "";
    }

    private static Boolean classifyBand(String raw) {
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return null;
        if (v.contains("fm")) return true;
        if (v.contains("am")) return false;
        if ("1".equals(v)) return true;
        if ("0".equals(v) || "2".equals(v)) return false;
        return null;
    }

    private static int normalizeFreq(double raw, boolean isFm) {
        int val;
        if (isFm) {
            if (raw >= 60.0 && raw <= 110.0) val = (int) Math.round(raw * 1000.0);
            else if (raw >= 650.0 && raw <= 1100.0) val = (int) Math.round(raw * 100.0);
            else if (raw >= 6500.0 && raw <= 11000.0) val = (int) Math.round(raw * 10.0);
            else val = (int) Math.round(raw);
            return (val >= 87500 && val <= 108000) ? val : -1;
        } else {
            val = (int) Math.round(raw);
            return (val >= 531 && val <= 1629) ? val : -1;
        }
    }
}
