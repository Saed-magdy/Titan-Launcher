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

    public static boolean isLegacyDeadUrl(String url) {
        if (url == null) return true;
        String u = url.trim().toLowerCase();
        return u.contains("reach-audio.nrpstream.com")
                || u.contains("radiomasr.radioca.st")
                || u.endsWith("/9090fmegy")
                || u.contains("mbgradio.com")
                || u.contains("0tpy1h0kxtzuv")
                || u.contains("k2y9wzpmdg8uv")
                || u.contains("46yqdtpmdg8uv")
                || u.contains("v2m5ytpmdg8uv")
                || u.contains("9uvy63pmdg8uv");
    }

    public static List<RadioBrowserClient.Station> getCuratedArabicStations() {
        List<RadioBrowserClient.Station> list = new ArrayList<>();
        list.add(new RadioBrowserClient.Station("curated_quran", "إذاعة القرآن الكريم - القاهرة", "http://stream.radiojar.com/8s5u5tpdtwzuv", "EG"));
        list.add(new RadioBrowserClient.Station("curated_9090", "راديو 9090 مصر", "http://9090streaming.mobtada.com/9090FMEGYPT", "EG"));
        list.add(new RadioBrowserClient.Station("curated_nogoum", "نجوم إف إم 100.6", "http://stream.zeno.fm/qb1zvsykm98uv", "EG"));
        list.add(new RadioBrowserClient.Station("curated_nrj", "NRJ Egypt 92.1", "http://nrjstreaming.ahmed-melege.com/nrjegypt", "EG"));
        list.add(new RadioBrowserClient.Station("curated_alafasy", "إذاعة مشاري العفاسي", "https://qurango.net/radio/mishary_alafasi", "KW"));
        list.add(new RadioBrowserClient.Station("curated_mustafa", "إذاعة الشيخ مصطفى إسماعيل", "https://qurango.net/radio/mustafa_ismail", "EG"));
        list.add(new RadioBrowserClient.Station("curated_omkalthom", "إذاعة أم كلثوم", "http://stream.zeno.fm/zsgrfxg71s8uv", "EG"));
        list.add(new RadioBrowserClient.Station("curated_amrdiab", "إذاعة عمرو دياب", "http://stream.zeno.fm/xa4yhh4k838uv", "EG"));
        list.add(new RadioBrowserClient.Station("curated_90sfm", "إذاعة التسعينات FM", "http://eu1.fastcast4u.com/proxy/prontofm", "EG"));
        list.add(new RadioBrowserClient.Station("curated_suwaisi", "إذاعة الشيخ علي حجاج السويسي", "http://live.mp3quran.net:9842/", "EG"));
        return list;
    }

    public synchronized List<RadioBrowserClient.Station> getFavorites() {
        List<RadioBrowserClient.Station> rawFavs = readList(KEY_FAVS);
        List<RadioBrowserClient.Station> cleanFavs = new ArrayList<>();
        for (RadioBrowserClient.Station s : rawFavs) {
            if (!isLegacyDeadUrl(s.streamUrl)) {
                cleanFavs.add(s);
            }
        }
        if (cleanFavs.isEmpty()) {
            return getCuratedArabicStations();
        }
        return cleanFavs;
    }

    public synchronized List<RadioBrowserClient.Station> getRecent() {
        List<RadioBrowserClient.Station> rawRecent = readList(KEY_RECENT);
        List<RadioBrowserClient.Station> clean = new ArrayList<>();
        for (RadioBrowserClient.Station s : rawRecent) {
            if (!isLegacyDeadUrl(s.streamUrl)) {
                clean.add(s);
            }
        }
        return clean;
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
