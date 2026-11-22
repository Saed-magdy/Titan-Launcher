package com.dongfeng.shinecockpit.shinewave.internet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists Internet Radio favorites, recently played stations, and user-added custom stream URLs.
 */
public final class RadioStationStore {
    private static final String PREFS = "shine_wave_integrated_prefs";
    private static final String KEY_FAVS = "inet_favorites";
    private static final String KEY_RECENT = "inet_recent";
    private static final String KEY_CUSTOM = "inet_custom";
    private static final int MAX_ITEMS = 40;

    private final SharedPreferences prefs;

    public RadioStationStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<RadioBrowserClient.Station> getFavorites() {
        return readList(KEY_FAVS);
    }

    public synchronized List<RadioBrowserClient.Station> getRecent() {
        return readList(KEY_RECENT);
    }

    public synchronized List<RadioBrowserClient.Station> getCustom() {
        return readList(KEY_CUSTOM);
    }

    public synchronized boolean isFavorite(String uuid) {
        if (uuid == null || uuid.isEmpty()) return false;
        for (RadioBrowserClient.Station s : getFavorites()) {
            if (uuid.equals(s.stationUuid)) return true;
        }
        return false;
    }

    public synchronized boolean toggleFavorite(RadioBrowserClient.Station station) {
        if (station == null || station.stationUuid.isEmpty()) return false;
        List<RadioBrowserClient.Station> list = getFavorites();
        for (int i = 0; i < list.size(); i++) {
            if (station.stationUuid.equals(list.get(i).stationUuid)) {
                list.remove(i);
                writeList(KEY_FAVS, list);
                return false;
            }
        }
        list.add(0, station);
        if (list.size() > MAX_ITEMS) list = list.subList(0, MAX_ITEMS);
        writeList(KEY_FAVS, list);
        return true;
    }

    public synchronized void addRecent(RadioBrowserClient.Station station) {
        if (station == null || station.stationUuid.isEmpty()) return;
        List<RadioBrowserClient.Station> list = getRecent();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (station.stationUuid.equals(list.get(i).stationUuid)) list.remove(i);
        }
        list.add(0, station);
        if (list.size() > 20) list = list.subList(0, 20);
        writeList(KEY_RECENT, list);
    }

    public synchronized void addCustomStation(String name, String streamUrl) {
        String cleanName = name != null ? name.trim() : "";
        String cleanUrl = streamUrl != null ? streamUrl.trim() : "";
        if (cleanName.isEmpty() || cleanUrl.isEmpty()) return;
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "http://" + cleanUrl;
        }
        String uuid = "custom_" + cleanUrl.hashCode();
        List<RadioBrowserClient.Station> list = getCustom();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (uuid.equals(list.get(i).stationUuid)) list.remove(i);
        }
        list.add(0, new RadioBrowserClient.Station(uuid, cleanName, cleanUrl, "CUSTOM"));
        writeList(KEY_CUSTOM, list);
    }

    public synchronized void removeCustomStation(String uuid) {
        if (uuid == null || uuid.isEmpty()) return;
        List<RadioBrowserClient.Station> list = getCustom();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (uuid.equals(list.get(i).stationUuid)) list.remove(i);
        }
        writeList(KEY_CUSTOM, list);
    }

    private List<RadioBrowserClient.Station> readList(String key) {
        List<RadioBrowserClient.Station> result = new ArrayList<>();
        String raw = prefs.getString(key, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                RadioBrowserClient.Station s = new RadioBrowserClient.Station(
                        o.optString("uuid", ""),
                        o.optString("name", ""),
                        o.optString("url", ""),
                        o.optString("country", "")
                );
                if (!s.stationUuid.isEmpty() && !s.name.isEmpty() && !s.streamUrl.isEmpty()) {
                    result.add(s);
                }
            }
        } catch (Throwable ignored) {}
        return result;
    }

    private void writeList(String key, List<RadioBrowserClient.Station> list) {
        JSONArray arr = new JSONArray();
        for (RadioBrowserClient.Station s : list) {
            try {
                arr.put(new JSONObject()
                        .put("uuid", s.stationUuid)
                        .put("name", s.name)
                        .put("url", s.streamUrl)
                        .put("country", s.countryCode));
            } catch (Throwable ignored) {}
        }
        prefs.edit().putString(key, arr.toString()).apply();
    }
}
