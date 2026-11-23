package com.dongfeng.shinecockpit.shinewave.internet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Radio Browser-compatible API client (radioss.app primary with radio-browser.info mirrors).
 */
public final class RadioBrowserClient {
    private static final List<String> API_SERVERS = Arrays.asList(
            "https://stations.radioss.app",
            "https://all.api.radio-browser.info",
            "https://de1.api.radio-browser.info"
    );

    public static final class Station {
        public final String stationUuid;
        public final String name;
        public final String streamUrl;
        public final String countryCode;

        public Station(String stationUuid, String name, String streamUrl, String countryCode) {
            this.stationUuid = stationUuid != null ? stationUuid.trim() : "";
            this.name = name != null ? name.trim() : "";
            this.streamUrl = streamUrl != null ? streamUrl.trim() : "";
            this.countryCode = countryCode != null ? countryCode.trim() : "";
        }

        public JSONObject toJson(boolean isFavorite) {
            JSONObject o = new JSONObject();
            try {
                o.put("uuid", stationUuid);
                o.put("name", name);
                o.put("url", streamUrl);
                o.put("country", countryCode);
                o.put("favorite", isFavorite);
            } catch (Throwable ignored) {}
            return o;
        }
    }

    public List<Station> searchStations(String query, boolean arabicOnly, int limit) {
        int safeLimit = Math.max(1, Math.min(80, limit));
        List<Station> apiResults = null;
        for (String server : API_SERVERS) {
            try {
                String url = buildSearchUrl(server, query, arabicOnly, safeLimit);
                List<Station> parsed = parseStations(getJson(url));
                if (!parsed.isEmpty()) {
                    apiResults = parsed;
                    break;
                }
            } catch (Throwable ignored) {
                // Fall back to next mirror server
            }
        }

        List<Station> curated = RadioStationStore.getCuratedArabicStations();
        String q = query != null ? query.trim().toLowerCase() : "";

        if (arabicOnly) {
            Map<String, Station> merged = new LinkedHashMap<>();
            for (Station s : curated) {
                if (q.isEmpty() || s.name.toLowerCase().contains(q)) {
                    merged.put(s.stationUuid, s);
                }
            }
            if (apiResults != null) {
                for (Station s : apiResults) {
                    if (!RadioStationStore.isLegacyDeadUrl(s.streamUrl) && !merged.containsKey(s.stationUuid)) {
                        merged.put(s.stationUuid, s);
                    }
                }
            }
            if (!merged.isEmpty()) {
                return new ArrayList<>(merged.values());
            }
        } else if (apiResults != null) {
            return apiResults;
        }

        List<Station> filtered = new ArrayList<>();
        for (Station s : curated) {
            if (q.isEmpty() || s.name.toLowerCase().contains(q)) {
                filtered.add(s);
            }
        }
        return filtered.isEmpty() ? curated : filtered;
    }

    public void reportClickAsync(final String stationUuid) {
        if (stationUuid == null || stationUuid.trim().isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (String server : API_SERVERS) {
                    HttpURLConnection conn = null;
                    try {
                        String encoded = URLEncoder.encode(stationUuid.trim(), "UTF-8");
                        conn = LocalStreamProxy.openFollowingRedirects(server + "/json/url/" + encoded, 4500, 4500);
                        int code = conn.getResponseCode();
                        if (code >= 200 && code < 300) return;
                    } catch (Throwable ignored) {
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                }
            }
        }, "SW-StationClick").start();
    }

    private String buildSearchUrl(String server, String query, boolean arabicOnly, int limit) throws Exception {
        StringBuilder sb = new StringBuilder(server).append("/json/stations/search?");
        if (query != null && !query.trim().isEmpty()) {
            sb.append("name=").append(URLEncoder.encode(query.trim(), "UTF-8")).append('&');
        }
        if (arabicOnly) {
            sb.append("language=arabic&");
        }
        sb.append("limit=").append(limit)
                .append("&hidebroken=true&order=clickcount&reverse=true");
        return sb.toString();
    }

    private String getJson(String urlString) throws Exception {
        HttpURLConnection conn = LocalStreamProxy.openFollowingRedirects(urlString, 6000, 8000);
        try {
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code);
            }
            StringBuilder out = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) out.append(line);
            }
            return out.toString();
        } finally {
            conn.disconnect();
        }
    }

    private List<Station> parseStations(String json) throws Exception {
        JSONArray array = new JSONArray(json);
        Map<String, Station> unique = new LinkedHashMap<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String uuid = obj.optString("stationuuid", "").trim();
            String name = obj.optString("name", "").trim();
            String url = obj.optString("url_resolved", "").trim();
            if (url.isEmpty()) url = obj.optString("url", "").trim();
            String cc = obj.optString("countrycode", "").trim();
            if (uuid.isEmpty() || name.isEmpty() || url.isEmpty()) continue;
            if (!unique.containsKey(uuid)) {
                unique.put(uuid, new Station(uuid, name, url, cc));
            }
        }
        return new ArrayList<>(unique.values());
    }
}
