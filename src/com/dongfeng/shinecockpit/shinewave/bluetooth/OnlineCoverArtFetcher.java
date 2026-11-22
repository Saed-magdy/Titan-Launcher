package com.dongfeng.shinecockpit.shinewave.bluetooth;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.dongfeng.shinecockpit.shinewave.local.ArtworkCacheManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches online album cover artwork (iTunes then Deezer) when Bluetooth AVRCP 1.4
 * provides title/artist metadata without an embedded cover bitmap.
 */
public final class OnlineCoverArtFetcher {
    private static final String TAG = "SWOnlineArt";

    public interface Callback {
        void onCoverArtFetched(String signature, String dataUrl);
    }

    private final ArtworkCacheManager artworkCache;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile String inFlightKey = "";

    public OnlineCoverArtFetcher(ArtworkCacheManager artworkCache) {
        this.artworkCache = artworkCache;
    }

    public void fetchAsync(final String signature, final String title, final String artist, final Callback callback) {
        final String cleanTitle = title != null ? title.trim() : "";
        if (cleanTitle.isEmpty()) return;
        final String cached = artworkCache.getCachedDataUrl(signature);
        if (cached != null) {
            callback.onCoverArtFetched(signature, cached);
            return;
        }
        final String queryKey = (artist != null ? artist.trim().toLowerCase(Locale.ROOT) : "") + "|" + cleanTitle.toLowerCase(Locale.ROOT);
        if (queryKey.equals(inFlightKey)) return;
        inFlightKey = queryKey;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String artUrl = findFromItunes(cleanTitle, artist);
                    if (artUrl == null) artUrl = findFromDeezer(cleanTitle, artist);
                    if (artUrl == null) return;

                    Bitmap bmp = downloadBitmap(artUrl);
                    if (bmp == null) return;

                    String dataUrl = artworkCache.cacheBitmap(signature, bmp);
                    artworkCache.cacheOnlineArtToDisk(queryKey, bmp);
                    if (!bmp.isRecycled()) bmp.recycle();
                    if (dataUrl != null) {
                        callback.onCoverArtFetched(signature, dataUrl);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Online art fetch note: " + t.getMessage());
                } finally {
                    if (queryKey.equals(inFlightKey)) inFlightKey = "";
                }
            }
        });
    }

    private String findFromItunes(String title, String artist) {
        try {
            String term = buildQueryTerm(title, artist);
            JSONObject json = getJson("https://itunes.apple.com/search?media=music&entity=song&limit=1&term=" + term);
            if (json == null) return null;
            JSONArray results = json.optJSONArray("results");
            if (results == null || results.length() == 0) return null;
            String url = results.optJSONObject(0).optString("artworkUrl100", "");
            return url.isEmpty() ? null : url.replace("100x100", "600x600");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String findFromDeezer(String title, String artist) {
        try {
            String term = buildQueryTerm(title, artist);
            JSONObject json = getJson("https://api.deezer.com/search?limit=1&q=" + term);
            if (json == null) return null;
            JSONArray data = json.optJSONArray("data");
            if (data == null || data.length() == 0) return null;
            JSONObject album = data.optJSONObject(0).optJSONObject("album");
            if (album == null) return null;
            String url = album.optString("cover_xl", "");
            if (url.isEmpty()) url = album.optString("cover_big", "");
            return url.isEmpty() ? null : url;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String buildQueryTerm(String title, String artist) throws Exception {
        String combined = (artist != null && !artist.trim().isEmpty())
                ? (artist.trim() + " " + title.trim())
                : title.trim();
        return URLEncoder.encode(combined, "UTF-8");
    }

    private JSONObject getJson(String address) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) return null;
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                return new JSONObject(baos.toString("UTF-8"));
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private Bitmap downloadBitmap(String address) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            try (InputStream is = conn.getInputStream()) {
                return BitmapFactory.decodeStream(is);
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
