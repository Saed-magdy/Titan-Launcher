package com.dongfeng.shinecockpit.shinewave.core;

/**
 * Playback queue repeat mode for local media and playlist playback.
 */
public enum RepeatMode {
    OFF("OFF"),
    ALL("ALL"),
    ONE("ONE");

    private final String key;

    RepeatMode(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    /**
     * Advances to the next repeat state in order: OFF -> ALL -> ONE -> OFF.
     */
    public RepeatMode next() {
        switch (this) {
            case OFF:
                return ALL;
            case ALL:
                return ONE;
            case ONE:
            default:
                return OFF;
        }
    }

    public static RepeatMode fromKey(String raw) {
        if (raw == null) return OFF;
        String upper = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if ("ALL".equals(upper)) return ALL;
        if ("ONE".equals(upper)) return ONE;
        return OFF;
    }
}
