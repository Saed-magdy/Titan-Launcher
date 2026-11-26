package com.dongfeng.shinecockpit;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.Locale;

/**
 * Speaks the low-fuel warning out loud, using the head unit's own text-to-speech voice.
 *
 * In plain words:
 * - The message is spoken once, in the launcher's language (Arabic, English or Russian).
 * - Music gets quieter while it speaks (like map directions), then comes back.
 * - If the head unit has no voice for that language, a short chime plays instead,
 *   so the driver still hears something. An English voice never reads Arabic text.
 * - The voice is prepared only the first time it is needed, so starting the launcher
 *   is not slowed down.
 */
final class FuelWarningVoice {

    private static final String TAG = "FuelWarningVoice";
    private static final String UTTERANCE_ID = "titan_low_fuel";

    /** Told whether the words were spoken or only the chime played (used by the "Try" button). */
    interface ResultListener {
        void onResult(boolean spoken);
    }

    private final Context mContext;
    private final AudioManager mAudio;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private TextToSpeech mTts;
    private boolean mTtsReady = false;
    private boolean mTtsFailed = false;

    // A message waiting for the voice engine to finish starting.
    private String mPendingText;
    private String mPendingLang;
    private ResultListener mPendingListener;

    private final AudioManager.OnAudioFocusChangeListener mFocusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) { /* nothing to do */ }
            };

    FuelWarningVoice(Context context) {
        mContext = context.getApplicationContext();
        mAudio = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Speak the text in the given language ("ar", "en" or "ru"), or play a chime if there is no voice. */
    void speak(final String text, final String lang, final ResultListener listener) {
        mMain.post(new Runnable() {
            @Override
            public void run() {
                if (mTtsFailed) {
                    chime(listener);
                    return;
                }
                if (mTts == null || !mTtsReady) {
                    mPendingText = text;
                    mPendingLang = lang;
                    mPendingListener = listener;
                    if (mTts == null) startEngine();
                    return;
                }
                speakNow(text, lang, listener);
            }
        });
    }

    private void startEngine() {
        try {
            mTts = new TextToSpeech(mContext, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(final int status) {
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            if (status == TextToSpeech.SUCCESS) {
                                mTtsReady = true;
                                setupEngine();
                            } else {
                                Log.w(TAG, "No text-to-speech engine on this head unit (status " + status + ")");
                                mTtsFailed = true;
                            }
                            if (mPendingText != null) {
                                String t = mPendingText, l = mPendingLang;
                                ResultListener r = mPendingListener;
                                mPendingText = null;
                                mPendingLang = null;
                                mPendingListener = null;
                                if (mTtsReady) speakNow(t, l, r);
                                else chime(r);
                            }
                        }
                    });
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Could not start text-to-speech", e);
            mTtsFailed = true;
            ResultListener r = mPendingListener;
            mPendingText = null;
            mPendingListener = null;
            chime(r);
        }
    }

    private void setupEngine() {
        try {
            mTts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { releaseFocus(); }
                @Override @SuppressWarnings("deprecation") public void onError(String id) { releaseFocus(); }
                @Override public void onError(String id, int errorCode) { releaseFocus(); }
            });
        } catch (Exception e) {
            Log.w(TAG, "Text-to-speech setup problem", e);
        }
    }

    private void speakNow(String text, String lang, ResultListener listener) {
        Locale locale = "ar".equals(lang) ? new Locale("ar")
                : "ru".equals(lang) ? new Locale("ru") : Locale.US;
        int res;
        try {
            res = mTts.setLanguage(locale);
        } catch (Exception e) {
            res = TextToSpeech.LANG_NOT_SUPPORTED;
        }
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "No " + locale + " voice on this head unit; playing a chime instead");
            chime(listener);
            return;
        }
        requestFocus();
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
        int ok = mTts.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID);
        if (ok != TextToSpeech.SUCCESS) {
            releaseFocus();
            chime(listener);
            return;
        }
        if (listener != null) listener.onResult(true);
    }

    /** Just the two short beeps (used by the speed alert when its Voice switch is off). */
    void chimeOnly() {
        mMain.post(new Runnable() {
            @Override
            public void run() {
                chime(null);
            }
        });
    }

    /** Two short beeps, used when no voice is available. */
    private void chime(ResultListener listener) {
        try {
            requestFocus();
            final ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 600);
            mMain.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try { tone.release(); } catch (Exception ignored) {}
                    releaseFocus();
                }
            }, 900);
        } catch (Exception e) {
            Log.w(TAG, "Could not play the chime", e);
            releaseFocus();
        }
        if (listener != null) listener.onResult(false);
    }

    @SuppressWarnings("deprecation")
    private void requestFocus() {
        try {
            if (mAudio != null) {
                mAudio.requestAudioFocus(mFocusListener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("deprecation")
    private void releaseFocus() {
        try {
            if (mAudio != null) mAudio.abandonAudioFocus(mFocusListener);
        } catch (Exception ignored) {}
    }

    void shutdown() {
        try {
            if (mTts != null) {
                mTts.stop();
                mTts.shutdown();
            }
        } catch (Exception ignored) {}
        mTts = null;
        mTtsReady = false;
        releaseFocus();
    }
}
