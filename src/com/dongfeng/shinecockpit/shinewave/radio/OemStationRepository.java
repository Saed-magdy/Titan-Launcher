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
 * Reads OEM factory radio stations (ContentProvider + RadioService), persists a read-only
 * OEM cache + app-owned auto-scan stations, and merges them cleanly with user favorites.
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
    private static final List<String> FAV_COLS = Arrays.asList(
            "is_collected", "iscollected", "collected", "is_favorite", "isfavorite", "favorite"
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
        Map<Integer, Station> oemMerged = new LinkedHashMap<>();

        mergeInto(oemMerged, queryProvider(LOCAL_URI, isFm, false), false);
        mergeInto(oemMerged, queryProvider(FAVORITES_URI, isFm, true), false);
        if (bridge != null) {
            mergeInto(oemMerged, bridge.getRadioList(band), false);
        }

        List<Station> oemList;
        if (!oemMerged.isEmpty()) {
            oemList = new ArrayList<>(oemMerged.values());
            saveCachedOemStations(band, oemList);
        } else {
            oemList = loadCachedOemStations(band);
        }

        Map<Integer, Station> merged = new LinkedHashMap<>();
        mergeInto(merged, oemList, false);
        mergeInto(merged, loadScannedStations(band), false);
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
        int norm = FmAmTunerController.clampFrequency(band, frequencyKhz);
        List<Station> favs = loadLocalFavorites(band);
        boolean removed = false;
        List<Station> updated = new ArrayList<>();
        for (Station s : favs) {
            if (s.frequencyKhz == norm) {
                removed = true;
            } else {
                updated.add(s);
            }
        }
        if (!removed) {
            updated.add(0, new Station(name, norm, true));
        }
        saveLocalFavorites(band, updated);
        return !removed;
    }

    /**
     * Saves or renames a favorite radio frequency with a custom user name.
     */
    public void saveOrUpdateLocalFavorite(int band, int frequencyKhz, String name) {
        int norm = FmAmTunerController.clampFrequency(band, frequencyKhz);
        String cleanName = name != null ? name.trim() : "";
        List<Station> favs = loadLocalFavorites(band);
        List<Station> updated = new ArrayList<>();
        updated.add(new Station(cleanName, norm, true));
        for (Station s : favs) {
            if (s.frequencyKhz != norm) {
                updated.add(s);
            }
        }
        saveLocalFavorites(band, updated);
    }

    /**
     * Persists auto-scanned station frequencies separately from user favorites.
     */
    public void saveScannedStations(int band, List<Integer> frequenciesKhz) {
        if (frequenciesKhz == null || frequenciesKhz.isEmpty()) return;
        String key = "radio_scanned_" + (band == FmAmTunerController.BAND_FM ? "fm" : "am");
        JSONArray arr = new JSONArray();
        Map<Integer, Boolean> seen = new LinkedHashMap<>();
        for (Integer f : frequenciesKhz) {
            if (f == null || !FmAmTunerController.isValidFreq(band, f)) continue;
            int snapped = FmAmTunerController.clampFrequency(band, f);
            if (!seen.containsKey(snapped)) {
                seen.put(snapped, Boolean.TRUE);
                arr.put(snapped);
            }
        }
        if (arr.length() > 0) {
            prefs.edit().putString(key, arr.toString()).apply();
        }
    }

    public List<Station> loadScannedStations(int band) {
        String key = "radio_scanned_" + (band == FmAmTunerController.BAND_FM ? "fm" : "am");
        String raw = prefs.getString(key, "[]");
        List<Station> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                int freq = arr.optInt(i, 0);
                if (FmAmTunerController.isValidFreq(band, freq)) {
                    list.add(new Station("", freq, false));
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    private List<Station> loadCachedOemStations(int band) {
        String key = "oem_cache_" + (band == FmAmTunerController.BAND_FM ? "fm" : "am");
        return readStationArrayJson(prefs.getString(key, "[]"), band, false);
    }

    private void saveCachedOemStations(int band, List<Station> stations) {
        if (stations == null || stations.isEmpty()) return;
        String key = "oem_cache_" + (band == FmAmTunerController.BAND_FM ? "fm" : "am");
        prefs.edit().putString(key, writeStationArrayJson(stations)).apply();
    }

    private List<Station> loadLocalFavorites(int band) {
        String key = "radio_favs_" + (band == FmAmTunerController.BAND_FM ? "fm" : "am");
        return readStationArrayJson(prefs.getString(key, "[]"), band, true);
    }

    private void saveLocalFavorites(int band, List<Station> stations) {
        String key = "radio_favs_" + (band == FmAmTunerController.BAND_FM ? "fm" : "am");
        prefs.edit().putString(key, writeStationArrayJson(stations)).apply();
    }

    private static List<Station> readStationArrayJson(String raw, int band, boolean forceFavorite) {
        List<Station> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                int freq = o.optInt("freq", 0);
                if (FmAmTunerController.isValidFreq(band, freq)) {
                    boolean fav = forceFavorite || o.optBoolean("fav", false);
                    list.add(new Station(o.optString("name", ""), freq, fav));
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    private static String writeStationArrayJson(List<Station> stations) {
        JSONArray arr = new JSONArray();
        for (Station s : stations) {
            try {
                arr.put(new JSONObject()
                        .put("freq", s.frequencyKhz)
                        .put("name", s.name)
                        .put("fav", s.isFavorite));
            } catch (Throwable ignored) {}
        }
        return arr.toString();
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

    private List<Station> queryProvider(String uriText, boolean requestedFm, boolean forceFav) {
        List<Station> result = new ArrayList<>();
        try (Cursor c = appContext.getContentResolver().query(Uri.parse(uriText), null, null, null, null)) {
            if (c == null) return result;
            String[] cols = c.getColumnNames();
            String freqCol = findCol(cols, FREQ_COLS);
            if (freqCol == null) return result;
            String nameCol = findCol(cols, NAME_COLS);
            String bandCol = findCol(cols, BAND_COLS);
            String favCol = findCol(cols, FAV_COLS);

            while (c.moveToNext()) {
                Double raw = readNum(c, freqCol);
                if (raw == null) continue;

                Boolean explicitBandFm = bandCol != null ? classifyExplicitBandText(readStr(c, bandCol)) : null;
                boolean rowIsFm;
                int normFreq;

                if (raw >= 87500.0 && raw <= 108000.0) {
                    rowIsFm = true;
                    normFreq = (int) Math.round(raw);
                } else if (raw >= 8750.0 && raw <= 10800.0) {
                    rowIsFm = true;
                    normFreq = (int) Math.round(raw * 10.0);
                } else if (raw >= 87.5 && raw <= 108.0) {
                    rowIsFm = true;
                    normFreq = (int) Math.round(raw * 1000.0);
                } else if (raw >= 531.0 && raw <= 1629.0) {
                    if (Boolean.TRUE.equals(explicitBandFm) && raw >= 875.0 && raw <= 1080.0) {
                        rowIsFm = true;
                        normFreq = (int) Math.round(raw * 100.0);
                    } else {
                        rowIsFm = false;
                        normFreq = (int) Math.round(raw);
                    }
                } else {
                    continue;
                }

                if (explicitBandFm != null && explicitBandFm != rowIsFm) continue;
                if (rowIsFm != requestedFm) continue;
                if (rowIsFm && (normFreq < 87500 || normFreq > 108000)) continue;
                if (!rowIsFm && (normFreq < 531 || normFreq > 1629)) continue;

                String name = nameCol != null ? readStr(c, nameCol) : "";
                boolean isFav = forceFav || (favCol != null && readBool(c, favCol));
                result.add(new Station(name, normFreq, isFav));
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

    private static boolean readBool(Cursor c, String col) {
        int idx = c.getColumnIndex(col);
        if (idx < 0 || c.isNull(idx)) return false;
        try {
            int type = c.getType(idx);
            if (type == Cursor.FIELD_TYPE_INTEGER) return c.getLong(idx) != 0L;
            String s = c.getString(idx);
            return s != null && ("1".equals(s.trim()) || "true".equalsIgnoreCase(s.trim()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Resolves unambiguous text band markers without relying on database schema ordinals
     * (OEM ITunerService / TunerManager / LocalRadioAdapter use FM=0, AM=1).
     */
    private static Boolean classifyExplicitBandText(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty()) return null;
        if (v.contains("fm") || raw.contains("调频")) return Boolean.TRUE;
        if (v.contains("am") || v.contains("mw") || raw.contains("中波")) return Boolean.FALSE;
        return null;
    }
}
