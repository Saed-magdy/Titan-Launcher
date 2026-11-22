package com.dongfeng.shinecockpit.shinewave.core;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Immutable-snapshot / thread-safe state container representing the active Now Playing state
 * across all Shine Wave audio sources for direct 0ms WebView serialization.
 */
public final class PlaybackStateModel {
    private AudioSourceType source = AudioSourceType.BLUETOOTH;
    private String title = "ShineWave Audio";
    private String artist = "";
    private String album = "";
    private boolean playing = false;
    private long positionSec = 0L;
    private long durationSec = 0L;
    private RepeatMode repeatMode = RepeatMode.OFF;
    private boolean shuffleEnabled = false;
    private String albumArtDataUrl = null;
    private String artworkSignature = "";

    private String radioType = "FM";
    private int radioFreqKhz = 87500;
    private String radioStatus = "";

    private String externalPackage = "";
    private String externalLabel = "";
    private boolean btConnected = false;

    public synchronized void setSource(AudioSourceType source) {
        if (source != null) {
            this.source = source;
        }
    }

    public synchronized AudioSourceType getSource() {
        return source;
    }

    public synchronized void setTrackInfo(String title, String artist, String album, long posSec, long durSec, boolean playing) {
        if (title != null) this.title = stripExtension(title);
        if (artist != null) this.artist = artist;
        if (album != null) this.album = album;
        this.positionSec = Math.max(0L, posSec);
        this.durationSec = Math.max(0L, durSec);
        this.playing = playing;
    }

    public synchronized void setPlaying(boolean playing) {
        this.playing = playing;
    }

    public synchronized boolean isPlaying() {
        return playing;
    }

    public synchronized void setPositionSec(long positionSec) {
        this.positionSec = Math.max(0L, positionSec);
    }

    public synchronized void setDurationSec(long durationSec) {
        this.durationSec = Math.max(0L, durationSec);
    }

    public synchronized void setRepeatMode(RepeatMode repeatMode) {
        if (repeatMode != null) this.repeatMode = repeatMode;
    }

    public synchronized RepeatMode getRepeatMode() {
        return repeatMode;
    }

    public synchronized void setShuffleEnabled(boolean shuffleEnabled) {
        this.shuffleEnabled = shuffleEnabled;
    }

    public synchronized boolean isShuffleEnabled() {
        return shuffleEnabled;
    }

    public synchronized void setArtwork(String signature, String dataUrl) {
        this.artworkSignature = (signature != null) ? signature : "";
        this.albumArtDataUrl = dataUrl;
    }

    public synchronized void clearArtwork() {
        this.artworkSignature = "";
        this.albumArtDataUrl = null;
    }

    public synchronized String getArtworkSignature() {
        return artworkSignature;
    }

    public synchronized void setRadioState(String radioType, int freqKhz, String statusLabel) {
        if (radioType != null) this.radioType = radioType;
        if (freqKhz > 0) this.radioFreqKhz = freqKhz;
        if (statusLabel != null) this.radioStatus = statusLabel;
    }

    public synchronized String getRadioType() {
        return radioType;
    }

    public synchronized int getRadioFreqKhz() {
        return radioFreqKhz;
    }

    public synchronized void setExternalApp(String pkg, String label) {
        this.externalPackage = (pkg != null) ? pkg : "";
        this.externalLabel = (label != null) ? label : "";
    }

    public synchronized void setBtConnected(boolean connected) {
        this.btConnected = connected;
    }

    public synchronized boolean isBtConnected() {
        return btConnected;
    }

    public synchronized String getTitle() {
        return title;
    }

    public synchronized String getArtist() {
        return artist;
    }

    /**
     * Serializes the current audio state for immediate delivery to window.onShineWaveState(json).
     */
    public synchronized JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("available", true);
            json.put("integrated", true);
            json.put("source", source.getKey());
            json.put("title", title != null ? title : "");
            json.put("artist", artist != null ? artist : "");
            json.put("album", album != null ? album : "");
            json.put("playing", playing);
            json.put("position", positionSec);
            json.put("duration", durationSec);
            json.put("repeatMode", repeatMode.getKey());
            json.put("shuffle", shuffleEnabled);
            json.put("radioType", radioType);
            json.put("radioFreqKhz", radioFreqKhz);
            json.put("radioStatus", radioStatus);
            json.put("btConnected", btConnected);
            json.put("externalPackage", externalPackage);
            json.put("externalLabel", externalLabel);
            if (albumArtDataUrl != null && !albumArtDataUrl.isEmpty()) {
                json.put("albumArt", albumArtDataUrl);
            } else {
                json.put("albumArt", JSONObject.NULL);
            }
        } catch (JSONException ignored) {}
        return json;
    }

    public static String stripExtension(String rawTitle) {
        if (rawTitle == null || rawTitle.isEmpty()) return "";
        return rawTitle.replaceAll("(?i)\\.(mp3|m4a|flac|wav|aac|ogg|wma|ape|alac|opus|mp4)$", "").trim();
    }
}
