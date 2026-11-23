package com.dongfeng.shinecockpit.shinewave.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.dongfeng.shinecockpit.shinewave.core.AudioSourceType;
import com.dongfeng.shinecockpit.shinewave.core.RepeatMode;
import com.dongfeng.shinecockpit.shinewave.internet.LocalStreamProxy;
import com.dongfeng.shinecockpit.shinewave.internet.RadioStationStore;
import com.dongfeng.shinecockpit.shinewave.local.ArtworkCacheManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Integrated media playback engine backed by Android MediaPlayer and MediaSession.
 * Handles local audio files and live Internet Radio HTTP streams with instant 0ms controls,
 * queue persistence, RepeatMode (OFF / ALL / ONE), and Shuffle playback.
 */
public final class NativeStreamPlayer {
    private static final String TAG = "SWStreamPlayer";
    private static final String PREFS_NAME = "shine_wave_integrated_prefs";
    private static final String KEY_REPEAT = "repeat_mode";
    private static final String KEY_SHUFFLE = "shuffle_enabled";

    public static final class QueueItem {
        public final String id;
        public final String uriString;
        public final String title;
        public final String artist;
        public final String album;
        public final long albumId;
        public final long durationMs;
        public final boolean isStream;

        public QueueItem(String id, String uriString, String title, String artist, String album,
                         long albumId, long durationMs, boolean isStream) {
            this.id = id != null ? id : "";
            this.uriString = uriString != null ? uriString : "";
            this.title = title != null ? title : "";
            this.artist = artist != null ? artist : "";
            this.album = album != null ? album : "";
            this.albumId = albumId;
            this.durationMs = Math.max(0L, durationMs);
            this.isStream = isStream;
        }
    }

    public interface Listener {
        void onPlayerStateChanged(QueueItem currentItem, boolean playing, long posMs, long durMs,
                                  RepeatMode repeatMode, boolean shuffleEnabled, String artSig, String artDataUrl);
    }

    private final Context appContext;
    private final AudioManager audioManager;
    private final ArtworkCacheManager artworkCache;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService artExecutor = Executors.newSingleThreadExecutor();
    private final Random random = new Random();

    private MediaPlayer mediaPlayer;
    private MediaSession mediaSession;
    private AudioFocusRequest audioFocusRequest;

    private final List<QueueItem> queue = new ArrayList<>();
    private int currentIndex = -1;
    private AudioSourceType queueSource = AudioSourceType.LOCAL_MUSIC;
    private RepeatMode repeatMode;
    private boolean shuffleEnabled;

    private volatile boolean prepared = false;
    private volatile boolean playWhenPrepared = false;
    private volatile long pendingSeekMs = 0L;
    private volatile boolean ducked = false;
    private String currentArtSig = "";
    private String currentArtDataUrl = null;
    private int prepareGeneration = 0;

    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            synchronized (NativeStreamPlayer.this) {
                if (mediaPlayer != null && prepared && isPlaying()) {
                    notifyStateChanged();
                    mainHandler.postDelayed(this, 1000L);
                }
            }
        }
    };

    private void startProgressTicker() {
        mainHandler.removeCallbacks(progressTicker);
        mainHandler.postDelayed(progressTicker, 1000L);
    }

    private void stopProgressTicker() {
        mainHandler.removeCallbacks(progressTicker);
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleAudioFocusChange(focusChange);
                }
            });
        }
    };

    public NativeStreamPlayer(Context context, ArtworkCacheManager artworkCache, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        this.artworkCache = artworkCache;
        this.listener = listener;
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.repeatMode = RepeatMode.fromKey(prefs.getString(KEY_REPEAT, "OFF"));
        this.shuffleEnabled = prefs.getBoolean(KEY_SHUFFLE, false);

        initMediaSession();
        restoreQueueSnapshot(AudioSourceType.LOCAL_MUSIC, false);
    }

    private void initMediaSession() {
        try {
            mediaSession = new MediaSession(appContext, "TitanShineWave");
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { play(); }
                @Override public void onPause() { pause(); }
                @Override public void onSkipToNext() { next(); }
                @Override public void onSkipToPrevious() { previous(); }
                @Override public void onSeekTo(long pos) { seekToMs(pos); }
                @Override public void onStop() { stopPlayback(); }
            }, mainHandler);
            mediaSession.setActive(false);
        } catch (Throwable t) {
            Log.w(TAG, "MediaSession init note: " + t.getMessage());
        }
    }

    public synchronized void setQueueAndPlay(List<QueueItem> items, int startIndex, AudioSourceType source) {
        if (items == null || items.isEmpty()) return;
        queue.clear();
        queue.addAll(items);
        queueSource = (source != null) ? source : AudioSourceType.LOCAL_MUSIC;
        currentIndex = Math.max(0, Math.min(startIndex, queue.size() - 1));
        saveQueueSnapshot(0L);
        prepareCurrentItem(true, 0L);
    }

    public synchronized void setQueuePaused(List<QueueItem> items, int startIndex, AudioSourceType source) {
        if (items == null || items.isEmpty()) return;
        queue.clear();
        queue.addAll(items);
        queueSource = (source != null) ? source : AudioSourceType.LOCAL_MUSIC;
        currentIndex = Math.max(0, Math.min(startIndex, queue.size() - 1));
        pendingSeekMs = 0L;
        saveQueueSnapshot(0L);
        notifyStateChanged();
    }

    public synchronized boolean resumeSavedQueue(AudioSourceType source) {
        return restoreQueueSnapshot(source, true);
    }

    public synchronized boolean hasSavedQueue(AudioSourceType source) {
        String raw = prefs.getString("saved_queue_" + source.getKey(), null);
        return raw != null && raw.length() > 4;
    }

    public synchronized void play() {
        if (queue.isEmpty() || currentIndex < 0 || currentIndex >= queue.size()) {
            return;
        }
        if (!requestAudioFocus()) {
            Log.w(TAG, "Audio focus denied");
        }
        if (mediaSession != null) mediaSession.setActive(true);

        if (mediaPlayer != null && prepared) {
            try {
                if (ducked) {
                    mediaPlayer.setVolume(1.0f, 1.0f);
                    ducked = false;
                }
                mediaPlayer.start();
                playWhenPrepared = false;
                startProgressTicker();
                notifyStateChanged();
                return;
            } catch (Throwable t) {
                Log.w(TAG, "play() restart attempt: " + t.getMessage());
            }
        }
        prepareCurrentItem(true, Math.max(0L, pendingSeekMs));
    }

    public synchronized void pause() {
        playWhenPrepared = false;
        stopProgressTicker();
        if (mediaPlayer != null && prepared) {
            try {
                long pos = getCurrentPositionMs();
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
                pendingSeekMs = pos;
                saveQueueSnapshot(pos);
            } catch (Throwable ignored) {}
        }
        notifyStateChanged();
    }

    public synchronized void togglePlayPause() {
        if (isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    public synchronized void stopPlayback() {
        playWhenPrepared = false;
        long pos = getCurrentPositionMs();
        saveQueueSnapshot(pos);
        releaseMediaPlayerOnly();
        abandonAudioFocus();
        if (mediaSession != null) {
            try {
                mediaSession.setActive(false);
            } catch (Throwable ignored) {}
        }
        notifyStateChanged();
    }

    public synchronized void next() {
        if (queue.isEmpty()) return;
        int nextIdx = resolveNextIndex(true);
        if (nextIdx >= 0) {
            currentIndex = nextIdx;
            saveQueueSnapshot(0L);
            prepareCurrentItem(true, 0L);
        }
    }

    public synchronized void previous() {
        if (queue.isEmpty()) return;
        QueueItem cur = getCurrentItem();
        if (cur != null && !cur.isStream && getCurrentPositionMs() > 4000L) {
            seekToMs(0L);
            return;
        }
        int prevIdx;
        if (shuffleEnabled && queue.size() > 1) {
            prevIdx = pickRandomOtherIndex();
        } else {
            prevIdx = currentIndex - 1;
            if (prevIdx < 0) {
                prevIdx = queue.size() - 1;
            }
        }
        currentIndex = prevIdx;
        saveQueueSnapshot(0L);
        prepareCurrentItem(true, 0L);
    }

    public synchronized void seekToMs(long positionMs) {
        QueueItem cur = getCurrentItem();
        if (cur == null || cur.isStream) return;
        long dur = getDurationMs();
        long clamped = (dur > 0L) ? Math.max(0L, Math.min(positionMs, dur)) : Math.max(0L, positionMs);
        pendingSeekMs = clamped;
        if (mediaPlayer != null && prepared) {
            try {
                mediaPlayer.seekTo((int) clamped);
            } catch (Throwable ignored) {}
        }
        notifyStateChanged();
    }

    public synchronized void seekByMs(long deltaMs) {
        seekToMs(getCurrentPositionMs() + deltaMs);
    }

    public synchronized RepeatMode cycleRepeatMode() {
        repeatMode = repeatMode.next();
        prefs.edit().putString(KEY_REPEAT, repeatMode.getKey()).apply();
        if (mediaPlayer != null && prepared) {
            try {
                QueueItem cur = getCurrentItem();
                mediaPlayer.setLooping(repeatMode == RepeatMode.ONE && cur != null && !cur.isStream);
            } catch (Throwable ignored) {}
        }
        notifyStateChanged();
        return repeatMode;
    }

    public synchronized boolean toggleShuffle() {
        shuffleEnabled = !shuffleEnabled;
        prefs.edit().putBoolean(KEY_SHUFFLE, shuffleEnabled).apply();
        notifyStateChanged();
        return shuffleEnabled;
    }

    public synchronized RepeatMode getRepeatMode() {
        return repeatMode;
    }

    public synchronized boolean isShuffleEnabled() {
        return shuffleEnabled;
    }

    public synchronized boolean isPlaying() {
        if (playWhenPrepared && !prepared) return true;
        if (mediaPlayer == null || !prepared) return false;
        try {
            return mediaPlayer.isPlaying();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public synchronized long getCurrentPositionMs() {
        if (mediaPlayer != null && prepared) {
            try {
                return Math.max(0L, mediaPlayer.getCurrentPosition());
            } catch (Throwable ignored) {}
        }
        return Math.max(0L, pendingSeekMs);
    }

    public synchronized long getDurationMs() {
        QueueItem cur = getCurrentItem();
        if (cur != null && cur.isStream) return 0L;
        if (mediaPlayer != null && prepared) {
            try {
                int d = mediaPlayer.getDuration();
                if (d > 0) return d;
            } catch (Throwable ignored) {}
        }
        return cur != null ? cur.durationMs : 0L;
    }

    public synchronized QueueItem getCurrentItem() {
        if (currentIndex < 0 || currentIndex >= queue.size()) return null;
        return queue.get(currentIndex);
    }

    public synchronized AudioSourceType getQueueSource() {
        return queueSource;
    }

    public synchronized List<QueueItem> getQueueSnapshot() {
        return new ArrayList<>(queue);
    }

    public synchronized int getCurrentIndex() {
        return currentIndex;
    }

    private void prepareCurrentItem(final boolean autoPlay, final long seekMs) {
        final QueueItem item = getCurrentItem();
        if (item == null || item.uriString.isEmpty()) return;

        final int gen = ++prepareGeneration;
        releaseMediaPlayerOnly();
        prepared = false;
        playWhenPrepared = autoPlay;
        pendingSeekMs = Math.max(0L, seekMs);

        resolveArtworkAsync(item);
        if (autoPlay) {
            requestAudioFocus();
            if (mediaSession != null) mediaSession.setActive(true);
        }

        final MediaPlayer mp = new MediaPlayer();
        mediaPlayer = mp;
        try {
            mp.setWakeMode(appContext, PowerManager.PARTIAL_WAKE_LOCK);
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());

            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer player) {
                    synchronized (NativeStreamPlayer.this) {
                        if (gen != prepareGeneration || mediaPlayer != player) return;
                        prepared = true;
                        try {
                            player.setLooping(repeatMode == RepeatMode.ONE && !item.isStream);
                            if (!item.isStream && pendingSeekMs > 0L) {
                                int dur = player.getDuration();
                                if (dur <= 0 || pendingSeekMs < dur) {
                                    player.seekTo((int) pendingSeekMs);
                                }
                            }
                            if (playWhenPrepared) {
                                player.start();
                                startProgressTicker();
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "onPrepared action warning: " + t.getMessage());
                        }
                        notifyStateChanged();
                    }
                }
            });

            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer player) {
                    synchronized (NativeStreamPlayer.this) {
                        if (gen != prepareGeneration || mediaPlayer != player) return;
                        handleTrackCompleted(item);
                    }
                }
            });

            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer player, int what, int extra) {
                    Log.w(TAG, "MediaPlayer error what=" + what + " extra=" + extra + " uri=" + item.uriString);
                    synchronized (NativeStreamPlayer.this) {
                        if (gen != prepareGeneration || mediaPlayer != player) return true;
                        prepared = false;
                        playWhenPrepared = false;
                        notifyStateChanged();
                    }
                    return true;
                }
            });

            if (item.uriString.startsWith("http://") || item.uriString.startsWith("https://")) {
                String proxied = LocalStreamProxy.getInstance().getProxyStreamUrl(item.uriString);
                mp.setDataSource(proxied);
            } else {
                mp.setDataSource(appContext, Uri.parse(item.uriString));
            }
            mp.prepareAsync();
            notifyStateChanged();
        } catch (Throwable t) {
            Log.e(TAG, "prepareCurrentItem failed: " + t.getMessage());
            releaseMediaPlayerOnly();
            playWhenPrepared = false;
            notifyStateChanged();
        }
    }

    private void handleTrackCompleted(QueueItem finishedItem) {
        stopProgressTicker();
        if (finishedItem.isStream) {
            playWhenPrepared = false;
            notifyStateChanged();
            return;
        }
        if (repeatMode == RepeatMode.ONE) {
            seekToMs(0L);
            if (mediaPlayer != null) mediaPlayer.start();
            notifyStateChanged();
            return;
        }
        int nextIdx = resolveNextIndex(false);
        if (nextIdx >= 0) {
            currentIndex = nextIdx;
            saveQueueSnapshot(0L);
            prepareCurrentItem(true, 0L);
        } else {
            playWhenPrepared = false;
            pendingSeekMs = 0L;
            seekToMs(0L);
            if (mediaPlayer != null && prepared) {
                try {
                    mediaPlayer.pause();
                } catch (Throwable ignored) {}
            }
            saveQueueSnapshot(0L);
            notifyStateChanged();
        }
    }

    private int resolveNextIndex(boolean manualUserNext) {
        if (queue.isEmpty()) return -1;
        if (shuffleEnabled && queue.size() > 1) {
            return pickRandomOtherIndex();
        }
        int next = currentIndex + 1;
        if (next < queue.size()) {
            return next;
        }
        if (repeatMode == RepeatMode.ALL || manualUserNext) {
            return 0;
        }
        return -1;
    }

    private int pickRandomOtherIndex() {
        if (queue.size() <= 1) return 0;
        int candidate;
        do {
            candidate = random.nextInt(queue.size());
        } while (candidate == currentIndex);
        return candidate;
    }

    private void resolveArtworkAsync(final QueueItem item) {
        final String sig = item.id + "|" + item.uriString;
        currentArtSig = sig;
        if (item.isStream) {
            currentArtDataUrl = null;
            return;
        }
        String cached = artworkCache.getCachedDataUrl(sig);
        if (cached != null) {
            currentArtDataUrl = cached;
            return;
        }
        currentArtDataUrl = null;
        artExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final String loaded = artworkCache.resolveLocalTrackArtwork(sig, item.uriString, item.albumId);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (NativeStreamPlayer.this) {
                            if (sig.equals(currentArtSig)) {
                                currentArtDataUrl = loaded;
                                notifyStateChanged();
                            }
                        }
                    }
                });
            }
        });
    }

    private void notifyStateChanged() {
        final QueueItem item = getCurrentItem();
        final boolean playing = isPlaying();
        final long posMs = getCurrentPositionMs();
        final long durMs = getDurationMs();
        updateMediaSessionState(item, playing, posMs, durMs);
        if (listener != null) {
            listener.onPlayerStateChanged(item, playing, posMs, durMs, repeatMode, shuffleEnabled, currentArtSig, currentArtDataUrl);
        }
    }

    private void updateMediaSessionState(QueueItem item, boolean playing, long posMs, long durMs) {
        if (mediaSession == null) return;
        try {
            long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                    PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT |
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
            PlaybackState ps = new PlaybackState.Builder()
                    .setActions(actions)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            posMs, playing ? 1.0f : 0.0f)
                    .build();
            mediaSession.setPlaybackState(ps);

            if (item != null) {
                MediaMetadata meta = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, item.title)
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, item.artist)
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, item.album)
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, durMs)
                        .build();
                mediaSession.setMetadata(meta);
            }
        } catch (Throwable ignored) {}
    }

    private void handleAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                pause();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (mediaPlayer != null && prepared) {
                    try {
                        mediaPlayer.setVolume(0.25f, 0.25f);
                        ducked = true;
                    } catch (Throwable ignored) {}
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (mediaPlayer != null && prepared && ducked) {
                    try {
                        mediaPlayer.setVolume(1.0f, 1.0f);
                        ducked = false;
                    } catch (Throwable ignored) {}
                }
                break;
        }
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return true;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                if (audioFocusRequest == null) {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build();
                    audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                            .setAudioAttributes(attrs)
                            .setWillPauseWhenDucked(false)
                            .setOnAudioFocusChangeListener(focusListener)
                            .build();
                }
                return audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            } else {
                @SuppressWarnings("deprecation")
                int res = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
                return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
        } catch (Throwable t) {
            return true;
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            } else {
                @SuppressWarnings("deprecation")
                int ignored = audioManager.abandonAudioFocus(focusListener);
            }
        } catch (Throwable ignored) {}
    }

    private void saveQueueSnapshot(long posMs) {
        if (queue.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            for (QueueItem q : queue) {
                arr.put(new JSONObject()
                        .put("id", q.id)
                        .put("uri", q.uriString)
                        .put("title", q.title)
                        .put("artist", q.artist)
                        .put("album", q.album)
                        .put("albumId", q.albumId)
                        .put("dur", q.durationMs)
                        .put("stream", q.isStream));
            }
            prefs.edit()
                    .putString("saved_queue_" + queueSource.getKey(), arr.toString())
                    .putInt("saved_idx_" + queueSource.getKey(), Math.max(0, currentIndex))
                    .putLong("saved_pos_" + queueSource.getKey(), Math.max(0L, posMs))
                    .apply();
        } catch (Throwable ignored) {}
    }

    private boolean restoreQueueSnapshot(AudioSourceType source, boolean autoPlay) {
        String raw = prefs.getString("saved_queue_" + source.getKey(), null);
        if (raw == null || raw.isEmpty()) return false;
        try {
            JSONArray arr = new JSONArray(raw);
            if (arr.length() == 0) return false;
            List<QueueItem> restored = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String uri = o.optString("uri", "");
                if (uri.isEmpty()) continue;
                if (source == AudioSourceType.INTERNET_RADIO && RadioStationStore.isLegacyDeadUrl(uri)) {
                    continue;
                }
                restored.add(new QueueItem(
                        o.optString("id", uri),
                        uri,
                        o.optString("title", "Unknown Track"),
                        o.optString("artist", ""),
                        o.optString("album", ""),
                        o.optLong("albumId", 0L),
                        o.optLong("dur", 0L),
                        o.optBoolean("stream", false)
                ));
            }
            if (restored.isEmpty()) return false;

            queue.clear();
            queue.addAll(restored);
            queueSource = source;
            int idx = prefs.getInt("saved_idx_" + source.getKey(), 0);
            currentIndex = Math.max(0, Math.min(idx, queue.size() - 1));
            long pos = Math.max(0L, prefs.getLong("saved_pos_" + source.getKey(), 0L));
            prepareCurrentItem(autoPlay, pos);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "restoreQueueSnapshot failed: " + t.getMessage());
            return false;
        }
    }

    private void releaseMediaPlayerOnly() {
        stopProgressTicker();
        prepared = false;
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            } catch (Throwable ignored) {}
            try {
                mediaPlayer.release();
            } catch (Throwable ignored) {}
            mediaPlayer = null;
        }
    }

    public synchronized void release() {
        saveQueueSnapshot(getCurrentPositionMs());
        releaseMediaPlayerOnly();
        abandonAudioFocus();
        if (mediaSession != null) {
            try {
                mediaSession.release();
            } catch (Throwable ignored) {}
            mediaSession = null;
        }
        artExecutor.shutdownNow();
    }
}
