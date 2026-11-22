package com.dongfeng.shinecockpit.shinewave.local;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import com.dongfeng.shinecockpit.shinewave.player.NativeStreamPlayer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scans local internal storage, MediaStore, and mounted USB drives for audio files,
 * maintaining an in-memory folder index for instant glass browser navigation.
 */
public final class LocalMusicScanner {
    private static final String TAG = "SWLocalScanner";
    private static final long MIN_TRACK_DURATION_MS = 45_000L;

    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "wma"
    ));

    public static final class LocalTrack {
        public final String id;
        public final String title;
        public final String artist;
        public final String album;
        public final long albumId;
        public final String uriString;
        public final String filePath;
        public final String folderPath;
        public final long durationMs;

        public LocalTrack(String id, String title, String artist, String album, long albumId,
                          String uriString, String filePath, String folderPath, long durationMs) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.albumId = albumId;
            this.uriString = uriString;
            this.filePath = filePath;
            this.folderPath = folderPath;
            this.durationMs = durationMs;
        }

        public NativeStreamPlayer.QueueItem toQueueItem() {
            return new NativeStreamPlayer.QueueItem(id, uriString, title, artist, album, albumId, durationMs, false);
        }
    }

    private static final class FolderNode {
        final String path;
        final String name;
        int trackCount = 0;

        FolderNode(String path, String name) {
            this.path = path;
            this.name = name;
        }
    }

    private final Context appContext;
    private final Object scanLock = new Object();
    private List<LocalTrack> cachedTracks = new ArrayList<>();
    private final Map<String, FolderNode> foldersByPath = new LinkedHashMap<>();
    private final Map<String, List<FolderNode>> childFoldersByParent = new HashMap<>();
    private final Map<String, List<LocalTrack>> directTracksByFolder = new HashMap<>();
    private long lastScanAtMs = 0L;

    public LocalMusicScanner(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Ensures tracks are scanned, forcing a refresh when requested or cache is older than 60s.
     */
    public void ensureScanned(boolean forceRefresh) {
        synchronized (scanLock) {
            long now = android.os.SystemClock.uptimeMillis();
            if (!forceRefresh && !cachedTracks.isEmpty() && (now - lastScanAtMs) < 60_000L) {
                return;
            }
            List<LocalTrack> scanned = performScan();
            cachedTracks = scanned;
            rebuildFolderIndexLocked(scanned);
            lastScanAtMs = now;
        }
    }

    /**
     * Returns folder/track JSON for the Shine Wave Glass Local Browser.
     */
    public JSONObject browseFolder(String folderPath, String searchQuery, boolean hasSavedQueue, boolean forceRescan) {
        ensureScanned(forceRescan);
        JSONObject result = new JSONObject();
        String safeFolder = normalizePath(folderPath);
        String query = searchQuery != null ? searchQuery.trim().toLowerCase(Locale.ROOT) : "";

        try {
            synchronized (scanLock) {
                result.put("totalTracks", cachedTracks.size());
                result.put("hasSavedQueue", hasSavedQueue);
                result.put("currentFolder", safeFolder);

                if (!query.isEmpty()) {
                    JSONArray tracksJson = new JSONArray();
                    int count = 0;
                    for (LocalTrack t : cachedTracks) {
                        if (matchesQuery(t, query)) {
                            tracksJson.put(trackToJson(t));
                            count++;
                            if (count >= 150) break;
                        }
                    }
                    result.put("parentFolder", "");
                    result.put("currentFolderName", "");
                    result.put("folders", new JSONArray());
                    result.put("tracks", tracksJson);
                    result.put("searchMode", true);
                    return result;
                }

                FolderNode curNode = foldersByPath.get(safeFolder);
                result.put("currentFolderName", curNode != null ? curNode.name : "");
                result.put("parentFolder", safeFolder.isEmpty() ? "" : parentFolderKey(safeFolder));

                JSONArray foldersArr = new JSONArray();
                List<FolderNode> children = childFoldersByParent.get(safeFolder);
                if (children != null) {
                    for (FolderNode child : children) {
                        JSONObject fObj = new JSONObject();
                        fObj.put("path", child.path);
                        fObj.put("name", child.name);
                        fObj.put("count", child.trackCount);
                        foldersArr.put(fObj);
                    }
                }
                result.put("folders", foldersArr);

                JSONArray tracksArr = new JSONArray();
                List<LocalTrack> direct = directTracksByFolder.get(safeFolder);
                if (direct != null) {
                    int limit = Math.min(direct.size(), 150);
                    for (int i = 0; i < limit; i++) {
                        tracksArr.put(trackToJson(direct.get(i)));
                    }
                }
                result.put("tracks", tracksArr);
                result.put("searchMode", false);
            }
        } catch (JSONException e) {
            Log.w(TAG, "browseFolder json error: " + e.getMessage());
        }
        return result;
    }

    /**
     * Builds a QueueItem list matching the user's current folder or search context.
     */
    public List<NativeStreamPlayer.QueueItem> buildQueue(String folderPath, String searchQuery) {
        ensureScanned(false);
        String safeFolder = normalizePath(folderPath);
        String query = searchQuery != null ? searchQuery.trim().toLowerCase(Locale.ROOT) : "";
        List<NativeStreamPlayer.QueueItem> queue = new ArrayList<>();

        synchronized (scanLock) {
            List<LocalTrack> sourceList;
            if (!query.isEmpty()) {
                sourceList = new ArrayList<>();
                for (LocalTrack t : cachedTracks) {
                    if (matchesQuery(t, query)) {
                        sourceList.add(t);
                        if (sourceList.size() >= 150) break;
                    }
                }
            } else {
                List<LocalTrack> direct = directTracksByFolder.get(safeFolder);
                sourceList = (direct != null && !direct.isEmpty()) ? direct : cachedTracks;
            }
            int limit = Math.min(sourceList.size(), 150);
            for (int i = 0; i < limit; i++) {
                queue.add(sourceList.get(i).toQueueItem());
            }
        }
        return queue;
    }

    private List<LocalTrack> performScan() {
        List<LocalTrack> result = new ArrayList<>();
        Set<String> knownPaths = new HashSet<>();

        ContentResolver resolver = appContext.getContentResolver();
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] projection = new String[]{
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DATA
        };

        try (Cursor cursor = resolver.query(collection, projection, null, null,
                MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                int durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);
                int dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA);

                while (cursor.moveToNext()) {
                    String mime = cursor.getString(mimeCol);
                    if (mime != null && !mime.isEmpty() && !mime.startsWith("audio/")) continue;

                    long dur = cursor.getLong(durCol);
                    if (dur < MIN_TRACK_DURATION_MS) continue;

                    String rawPath = dataCol >= 0 ? cursor.getString(dataCol) : null;
                    String canonicalPath = rawPath != null && !rawPath.isEmpty() ? canonicalize(rawPath) : "";
                    if (!canonicalPath.isEmpty()) knownPaths.add(canonicalPath);

                    long id = cursor.getLong(idCol);
                    String title = nonBlankOr(cursor.getString(titleCol), "Unknown Track");
                    String artist = cleanUnknown(cursor.getString(artistCol), "Unknown Artist");
                    String album = cleanUnknown(cursor.getString(albumCol), "Unknown Album");
                    long albumId = cursor.getLong(albumIdCol);
                    Uri contentUri = ContentUris.withAppendedId(collection, id);
                    String folder = canonicalPath.isEmpty() ? "" : normalizePath(new File(canonicalPath).getParent());

                    result.add(new LocalTrack(
                            String.valueOf(id), title, artist, album, albumId,
                            contentUri.toString(), canonicalPath, folder, dur
                    ));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "MediaStore query warning: " + t.getMessage());
        }

        // Direct scan fallback for internal storage and mounted USB storage roots
        for (File root : collectStorageRoots()) {
            scanDirectory(root, knownPaths, result, 0);
        }

        Collections.sort(result, new Comparator<LocalTrack>() {
            @Override
            public int compare(LocalTrack a, LocalTrack b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        return result;
    }

    private List<File> collectStorageRoots() {
        List<File> roots = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        File primary = new File("/storage/emulated/0");
        if (primary.isDirectory() && primary.canRead()) {
            roots.add(primary);
            seen.add(canonicalize(primary.getAbsolutePath()));
        }

        File[] scanParents = new File[]{new File("/storage"), new File("/mnt/usb_storage"), new File("/mnt")};
        for (File parent : scanParents) {
            if (!parent.isDirectory()) continue;
            File[] children = parent.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (!child.isDirectory() || !child.canRead()) continue;
                String name = child.getName().toLowerCase(Locale.ROOT);
                if ("emulated".equals(name) || "self".equals(name) || "obb".equals(name) || "asec".equals(name)) {
                    continue;
                }
                String canon = canonicalize(child.getAbsolutePath());
                if (seen.add(canon)) {
                    roots.add(child);
                }
            }
        }
        return roots;
    }

    private void scanDirectory(File dir, Set<String> knownPaths, List<LocalTrack> output, int depth) {
        if (depth > 6) return;
        File[] children;
        try {
            children = dir.listFiles();
        } catch (SecurityException e) {
            return;
        }
        if (children == null) return;

        for (File file : children) {
            String name = file.getName();
            if (name.startsWith(".")) continue;
            if (file.isDirectory()) {
                if (!"Android".equalsIgnoreCase(name)) {
                    scanDirectory(file, knownPaths, output, depth + 1);
                }
                continue;
            }
            if (!file.isFile() || !isSupportedExtension(name)) continue;
            String canon = canonicalize(file.getAbsolutePath());
            if (!knownPaths.add(canon)) continue;

            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(canon);
                String durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long dur = durStr != null ? Long.parseLong(durStr) : 0L;
                if (dur < MIN_TRACK_DURATION_MS) continue;

                String title = nonBlankOr(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), stripExt(name));
                String artist = cleanUnknown(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST), "Unknown Artist");
                String album = cleanUnknown(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM), "Unknown Album");
                long synthId = -1L - (canon.hashCode() & 0x7fffffffL);
                String folder = normalizePath(file.getParent());

                output.add(new LocalTrack(
                        String.valueOf(synthId), title, artist, album, 0L,
                        Uri.fromFile(file).toString(), canon, folder, dur
                ));
            } catch (Throwable ignored) {
            } finally {
                try {
                    retriever.release();
                } catch (Throwable ignored) {}
            }
        }
    }

    private void rebuildFolderIndexLocked(List<LocalTrack> tracks) {
        foldersByPath.clear();
        childFoldersByParent.clear();
        directTracksByFolder.clear();

        Map<String, Set<String>> folderDescendantIds = new HashMap<>();

        for (LocalTrack track : tracks) {
            String folder = normalizePath(track.folderPath);
            String root = storageVolumeRoot(folder);
            String targetFolder = (folder.isEmpty() || folder.equals(root)) ? "" : folder;

            List<LocalTrack> direct = directTracksByFolder.get(targetFolder);
            if (direct == null) {
                direct = new ArrayList<>();
                directTracksByFolder.put(targetFolder, direct);
            }
            direct.add(track);

            String current = targetFolder;
            while (!current.isEmpty() && !current.equals(root)) {
                FolderNode node = foldersByPath.get(current);
                if (node == null) {
                    node = new FolderNode(current, new File(current).getName());
                    foldersByPath.put(current, node);
                }
                Set<String> ids = folderDescendantIds.get(current);
                if (ids == null) {
                    ids = new HashSet<>();
                    folderDescendantIds.put(current, ids);
                }
                ids.add(track.id);

                String parent = parentFolderKey(current);
                if (parent.equals(current)) break;
                current = parent;
            }
        }

        for (Map.Entry<String, FolderNode> e : foldersByPath.entrySet()) {
            Set<String> ids = folderDescendantIds.get(e.getKey());
            e.getValue().trackCount = ids != null ? ids.size() : 0;

            String parentKey = parentFolderKey(e.getKey());
            List<FolderNode> children = childFoldersByParent.get(parentKey);
            if (children == null) {
                children = new ArrayList<>();
                childFoldersByParent.put(parentKey, children);
            }
            children.add(e.getValue());
        }

        for (List<FolderNode> list : childFoldersByParent.values()) {
            Collections.sort(list, new Comparator<FolderNode>() {
                @Override
                public int compare(FolderNode a, FolderNode b) {
                    return a.name.compareToIgnoreCase(b.name);
                }
            });
        }
    }

    private JSONObject trackToJson(LocalTrack t) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", t.id);
        o.put("title", t.title);
        o.put("artist", t.artist);
        o.put("album", t.album);
        o.put("durationSec", Math.max(0L, t.durationMs / 1000L));
        o.put("folderPath", t.folderPath);
        return o;
    }

    private static boolean matchesQuery(LocalTrack t, String query) {
        return t.title.toLowerCase(Locale.ROOT).contains(query)
                || t.artist.toLowerCase(Locale.ROOT).contains(query)
                || t.album.toLowerCase(Locale.ROOT).contains(query);
    }

    private static boolean isSupportedExtension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return AUDIO_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String normalizePath(String path) {
        if (path == null) return "";
        String norm = path.replace('\\', '/').trim();
        if (norm.isEmpty() || "/".equals(norm)) return "";
        while (norm.endsWith("/") && norm.length() > 1) {
            norm = norm.substring(0, norm.length() - 1);
        }
        return norm;
    }

    private static String storageVolumeRoot(String path) {
        String norm = normalizePath(path);
        String trimmed = norm.startsWith("/") ? norm.substring(1) : norm;
        String[] parts = trimmed.split("/");
        if (parts.length >= 3 && "storage".equals(parts[0]) && "emulated".equals(parts[1])) {
            return "/storage/emulated/" + parts[2];
        }
        if (parts.length >= 2 && ("storage".equals(parts[0]) || "mnt".equals(parts[0]))) {
            return "/" + parts[0] + "/" + parts[1];
        }
        return normalizePath(Environment.getExternalStorageDirectory().getAbsolutePath());
    }

    private static String parentFolderKey(String folderPath) {
        String norm = normalizePath(folderPath);
        if (norm.isEmpty()) return "";
        String volumeRoot = storageVolumeRoot(norm);
        String rawParent = new File(norm).getParent();
        String parent = normalizePath(rawParent);
        return (parent.isEmpty() || parent.equals(volumeRoot)) ? "" : parent;
    }

    private static String canonicalize(String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (Throwable ignored) {
            return new File(path).getAbsolutePath();
        }
    }

    private static String nonBlankOr(String val, String fallback) {
        return (val != null && !val.trim().isEmpty()) ? val.trim() : fallback;
    }

    private static String cleanUnknown(String val, String fallback) {
        if (val == null) return fallback;
        String trimmed = val.trim();
        if (trimmed.isEmpty() || "<unknown>".equalsIgnoreCase(trimmed)) return fallback;
        return trimmed;
    }
}
