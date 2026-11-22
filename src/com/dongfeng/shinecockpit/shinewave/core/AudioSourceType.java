package com.dongfeng.shinecockpit.shinewave.core;

/**
 * Supported audio playback sources managed by the integrated Shine Wave engine.
 */
public enum AudioSourceType {
    LOCAL_MUSIC("LOCAL_MUSIC"),
    BLUETOOTH("BLUETOOTH"),
    CAR_RADIO("CAR_RADIO"),
    INTERNET_RADIO("INTERNET_RADIO"),
    EXTERNAL_APP("EXTERNAL_APP");

    private final String key;

    AudioSourceType(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    /**
     * Resolves an audio source from a persisted key or command string.
     * Supports legacy "FILES" key mapping to LOCAL_MUSIC.
     */
    public static AudioSourceType fromKey(String raw) {
        if (raw == null) return BLUETOOTH;
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        switch (normalized) {
            case "FILES":
            case "LOCAL_MUSIC":
            case "SOURCE_LOCAL":
                return LOCAL_MUSIC;
            case "CAR_RADIO":
            case "SOURCE_CAR":
                return CAR_RADIO;
            case "INTERNET_RADIO":
            case "SOURCE_INTERNET":
                return INTERNET_RADIO;
            case "EXTERNAL_APP":
            case "SOURCE_APPS":
                return EXTERNAL_APP;
            case "BLUETOOTH":
            case "SOURCE_BLUETOOTH":
            default:
                return BLUETOOTH;
        }
    }
}
