package com.dongfeng.shinecockpit.shinewave.local;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/**
 * Provides fast, signature-keyed memory and disk caching for track cover artwork,
 * preventing redundant bitmap decoding and Base64 conversion across playback ticks.
 */
public final class ArtworkCacheManager {
    private static final String TAG = "SWArtworkCache";
    private static final int MAX_MEMORY_ENTRIES = 48;
    private static final int TARGET_SIZE_PX = 240;

    private final Context appContext;
    private final LruCache<String, String> memoryCache = new LruCache<>(MAX_MEMORY_ENTRIES);

    public ArtworkCacheManager(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Returns a cached Base64 Data URL for the given signature if present in memory.
     */
    public String getCachedDataUrl(String signature) {
        if (signature == null || signature.isEmpty()) return null;
        return memoryCache.get(signature);
    }

    /**
     * Stores an already-decoded Bitmap into the memory cache after scaling and encoding.
     */
    public String cacheBitmap(String signature, Bitmap bitmap) {
        if (signature == null || signature.isEmpty() || bitmap == null || bitmap.isRecycled()) {
            return null;
        }
        String cached = memoryCache.get(signature);
        if (cached != null) return cached;

        String dataUrl = encodeBitmapToDataUrl(bitmap);
        if (dataUrl != null) {
            memoryCache.put(signature, dataUrl);
        }
        return dataUrl;
    }

    /**
     * Extracts cover art for a local audio item from embedded ID3 tags or MediaStore album art.
     */
    public String resolveLocalTrackArtwork(String signature, String uriStr, long albumId) {
        if (signature == null || signature.isEmpty()) return null;
        String cached = memoryCache.get(signature);
        if (cached != null) return cached;

        byte[] rawBytes = null;
        if (uriStr != null && !uriStr.isEmpty()) {
            rawBytes = extractEmbeddedBytes(Uri.parse(uriStr));
        }

        if (rawBytes == null && albumId > 0L) {
            rawBytes = readAlbumArtUriBytes(albumId);
        }

        if (rawBytes == null || rawBytes.length == 0) {
            return null;
        }

        Bitmap decoded = decodeSampledBitmap(rawBytes);
        if (decoded == null) return null;

        String dataUrl = encodeBitmapToDataUrl(decoded);
        if (!decoded.isRecycled()) {
            decoded.recycle();
        }
        if (dataUrl != null) {
            memoryCache.put(signature, dataUrl);
        }
        return dataUrl;
    }

    private byte[] extractEmbeddedBytes(Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                if (path != null && !path.isEmpty()) {
                    retriever.setDataSource(path);
                }
            } else {
                android.os.ParcelFileDescriptor pfd = appContext.getContentResolver().openFileDescriptor(uri, "r");
                if (pfd != null) {
                    try {
                        retriever.setDataSource(pfd.getFileDescriptor());
                    } finally {
                        pfd.close();
                    }
                }
            }
            byte[] embedded = retriever.getEmbeddedPicture();
            if (embedded != null && embedded.length > 0) {
                return embedded;
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {}
        }

        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try {
                ContentResolver cr = appContext.getContentResolver();
                try (Cursor c = cr.query(uri, new String[]{MediaStore.Audio.Media.ALBUM_ID}, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        long aid = c.getLong(0);
                        if (aid > 0L) return readAlbumArtUriBytes(aid);
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private byte[] readAlbumArtUriBytes(long albumId) {
        Uri artUri = Uri.parse("content://media/external/audio/albumart/" + albumId);
        try (InputStream is = appContext.getContentResolver().openInputStream(artUri)) {
            if (is == null) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = is.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
            byte[] res = buffer.toByteArray();
            return res.length > 0 ? res : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Bitmap decodeSampledBitmap(byte[] bytes) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);

            int sampleSize = 1;
            int maxDim = Math.max(opts.outWidth, opts.outHeight);
            while (maxDim / sampleSize > TARGET_SIZE_PX * 2) {
                sampleSize *= 2;
            }

            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = sampleSize;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decodeOpts);
        } catch (Throwable t) {
            Log.w(TAG, "decodeSampledBitmap failed: " + t.getMessage());
            return null;
        }
    }

    private String encodeBitmapToDataUrl(Bitmap source) {
        try {
            int w = source.getWidth();
            int h = source.getHeight();
            if (w <= 0 || h <= 0) return null;

            Bitmap target = source;
            if (w > TARGET_SIZE_PX || h > TARGET_SIZE_PX) {
                float scale = Math.min((float) TARGET_SIZE_PX / w, (float) TARGET_SIZE_PX / h);
                int nw = Math.max(1, Math.round(w * scale));
                int nh = Math.max(1, Math.round(h * scale));
                target = Bitmap.createScaledBitmap(source, nw, nh, true);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            target.compress(Bitmap.CompressFormat.JPEG, 84, baos);
            if (target != source && !target.isRecycled()) {
                target.recycle();
            }
            return "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable t) {
            Log.w(TAG, "encodeBitmapToDataUrl failed: " + t.getMessage());
            return null;
        }
    }

    public File cacheOnlineArtToDisk(String cacheKey, Bitmap bitmap) {
        if (cacheKey == null || bitmap == null || bitmap.isRecycled()) return null;
        try {
            File file = new File(appContext.getCacheDir(), "sw_online_art_" + cacheKey.hashCode() + ".jpg");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, fos);
            }
            return file;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
