package com.dongfeng.shinecockpit.shinewave.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.dongfeng.shinecockpit.shinewave.internet.LocalStreamProxy;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Low-latency live HTTP audio decoder (ADTS AAC & MP3) built on MediaCodec + AudioTrack.
 * Starts internet radio playback in ~1-2 seconds (~200 ms PCM pre-roll) instead of waiting for
 * Android 8.1 MediaPlayer/NuCachedSource2's ~512 KB cache fill.
 */
public final class LiveHttpStreamEngine {
    private static final String TAG = "SWLiveHttp";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int STREAM_BUF_CAPACITY = 65536;
    private static final int PROBE_LIMIT_BYTES = 20480;
    private static final int MAX_RECONNECTS = 2;

    private static final int[] ADTS_SAMPLE_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000,
            24000, 22050, 16000, 12000, 11025, 8000
    };
    private static final int[] MP3_V1_BITRATES = {
            0, 32000, 40000, 48000, 56000, 64000, 80000, 96000,
            112000, 128000, 160000, 192000, 224000, 256000, 320000, 0
    };
    private static final int[] MP3_V2_BITRATES = {
            0, 8000, 16000, 24000, 32000, 40000, 48000, 56000,
            64000, 80000, 96000, 112000, 128000, 144000, 160000, 0
    };
    private static final int[][] MP3_SAMPLE_RATES = {
            {11025, 12000, 8000},   // MPEG 2.5 (id=0)
            null,                   // reserved (id=1)
            {22050, 24000, 16000},  // MPEG 2   (id=2)
            {44100, 48000, 32000}   // MPEG 1   (id=3)
    };

    public interface Callback {
        void onFirstAudioPlaying();
        void onFallbackRequired(String resolvedUrl);
        void onStreamError(String message);
    }

    private enum CodecKind {
        NONE, MP3, ADTS_AAC
    }

    private static final class FrameInfo {
        final int offset;
        final int frameLen;
        final int headerLen;
        final CodecKind kind;
        final int sampleRate;
        final int channelCount;
        final int profileObjType;
        final int freqIndex;
        final int chanCfg;

        FrameInfo(int offset, int frameLen, int headerLen, CodecKind kind,
                  int sampleRate, int channelCount, int profileObjType, int freqIndex, int chanCfg) {
            this.offset = offset;
            this.frameLen = frameLen;
            this.headerLen = headerLen;
            this.kind = kind;
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.profileObjType = profileObjType;
            this.freqIndex = freqIndex;
            this.chanCfg = chanCfg;
        }
    }

    private final Context appContext;
    private final String streamUrl;
    private final Callback callback;

    private Thread workerThread;
    private volatile boolean stopped = false;
    private volatile boolean playingNotified = false;
    private volatile float volume = 1.0f;
    private volatile long playStartElapsedMs = 0L;
    private volatile HttpURLConnection activeConn;
    private volatile AudioTrack audioTrack;

    public LiveHttpStreamEngine(Context context, String streamUrl, Callback callback) {
        this.appContext = context.getApplicationContext();
        this.streamUrl = streamUrl;
        this.callback = callback;
    }

    public synchronized void start() {
        if (stopped || workerThread != null) return;
        workerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runStreamLoop();
            }
        }, "SW-LiveHttpDecode");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    public void setVolume(float vol) {
        float clamped = Math.max(0.0f, Math.min(1.0f, vol));
        this.volume = clamped;
        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                if (Build.VERSION.SDK_INT >= 21) {
                    track.setVolume(clamped);
                } else {
                    @SuppressWarnings("deprecation")
                    int ignored = track.setStereoVolume(clamped, clamped);
                }
            } catch (Throwable ignored) {}
        }
    }

    public boolean isPlaying() {
        return !stopped && playingNotified;
    }

    public long getElapsedMs() {
        if (stopped || !playingNotified || playStartElapsedMs <= 0L) return 0L;
        return Math.max(0L, SystemClock.elapsedRealtime() - playStartElapsedMs);
    }

    public void stop() {
        stopped = true;
        Thread t = workerThread;
        if (t != null) {
            t.interrupt();
        }
        final HttpURLConnection connToClose = activeConn;
        activeConn = null;
        if (connToClose != null) {
            Thread closer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        connToClose.disconnect();
                    } catch (Throwable ignored) {}
                }
            }, "SW-HttpClose");
            closer.setDaemon(true);
            closer.start();
        }
    }

    private void runStreamLoop() {
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Titan:LiveRadioLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(3 * 60 * 60 * 1000L);
            }
        } catch (Throwable ignored) {}

        int reconnects = 0;
        String lastError = "Stream unavailable";
        try {
            while (!stopped) {
                boolean shouldRetry = false;
                HttpURLConnection conn = null;
                InputStream in = null;
                MediaCodec codec = null;
                AudioTrack track = null;
                try {
                    LocalStreamProxy.ResolvedStream resolved = LocalStreamProxy.openResolvedAudioStream(
                            streamUrl, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
                    conn = resolved.conn;
                    activeConn = conn;
                    in = resolved.inputStream;

                    if (stopped) return;

                    if (needsMediaPlayerFallback(resolved.finalUrl, resolved.contentType)) {
                        disconnectQuietly(conn);
                        activeConn = null;
                        if (!stopped && callback != null) {
                            callback.onFallbackRequired(resolved.finalUrl);
                        }
                        return;
                    }

                    byte[] buf = new byte[STREAM_BUF_CAPACITY];
                    int validLen = fillBuffer(in, buf, 0, Math.min(4096, STREAM_BUF_CAPACITY));
                    while (validLen < 4096 && !stopped) {
                        int n = in.read(buf, validLen, Math.min(4096, STREAM_BUF_CAPACITY - validLen));
                        if (n <= 0) break;
                        validLen += n;
                    }
                    if (stopped) return;

                    if (hasUnsupportedMagic(buf, validLen)) {
                        disconnectQuietly(conn);
                        activeConn = null;
                        if (!stopped && callback != null) {
                            callback.onFallbackRequired(resolved.finalUrl);
                        }
                        return;
                    }

                    int startPos = skipId3v2IfPresent(buf, validLen, in);
                    if (startPos > 0 && startPos <= validLen) {
                        validLen -= startPos;
                        System.arraycopy(buf, startPos, buf, 0, validLen);
                    } else if (startPos > validLen) {
                        validLen = 0;
                    }

                    while (validLen < 8192 && !stopped) {
                        int n = in.read(buf, validLen, Math.min(4096, STREAM_BUF_CAPACITY - validLen));
                        if (n <= 0) break;
                        validLen += n;
                    }
                    if (stopped) return;

                    FrameInfo probe = probeFirstValidFrames(buf, validLen);
                    int probedBytes = validLen;
                    while (probe == null && probedBytes < PROBE_LIMIT_BYTES && !stopped) {
                        if (validLen > STREAM_BUF_CAPACITY - 8192) {
                            int keep = 4096;
                            System.arraycopy(buf, validLen - keep, buf, 0, keep);
                            validLen = keep;
                        }
                        int n = in.read(buf, validLen, Math.min(4096, STREAM_BUF_CAPACITY - validLen));
                        if (n <= 0) break;
                        validLen += n;
                        probedBytes += n;
                        probe = probeFirstValidFrames(buf, validLen);
                    }

                    if (stopped) return;
                    if (probe == null) {
                        disconnectQuietly(conn);
                        activeConn = null;
                        if (!stopped && callback != null) {
                            callback.onFallbackRequired(resolved.finalUrl);
                        }
                        return;
                    }

                    if (probe.offset > 0) {
                        validLen -= probe.offset;
                        System.arraycopy(buf, probe.offset, buf, 0, validLen);
                    }

                    codec = createAndConfigureCodec(probe);
                    codec.start();

                    track = decodeLoop(in, buf, validLen, probe.kind, codec);
                    // Stream ended cleanly at EOF
                    if (!stopped && playingNotified && reconnects < MAX_RECONNECTS) {
                        reconnects++;
                        shouldRetry = true;
                        Log.i(TAG, "Stream EOF after playback; reconnect #" + reconnects);
                    }
                } catch (Throwable t) {
                    lastError = t.getMessage() != null ? t.getMessage() : "Stream error";
                    if (!stopped && playingNotified && reconnects < MAX_RECONNECTS) {
                        reconnects++;
                        shouldRetry = true;
                        Log.w(TAG, "Transient stream failure; reconnect #" + reconnects + ": " + lastError);
                    }
                } finally {
                    releaseAudioTrack(track);
                    audioTrack = null;
                    releaseCodec(codec);
                    closeQuietly(in);
                    disconnectQuietly(conn);
                    activeConn = null;
                }

                if (!shouldRetry || stopped) {
                    if (!stopped && !playingNotified && callback != null) {
                        callback.onStreamError(lastError);
                    } else if (!stopped && playingNotified && callback != null) {
                        callback.onStreamError(lastError);
                    }
                    break;
                }
                try {
                    Thread.sleep(250L);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        } finally {
            if (wakeLock != null) {
                try {
                    if (wakeLock.isHeld()) wakeLock.release();
                } catch (Throwable ignored) {}
            }
        }
    }

    private AudioTrack decodeLoop(
            InputStream in, byte[] buf, int initialLen, CodecKind kind, MediaCodec codec) throws IOException {
        int validLen = initialLen;
        boolean inputEof = false;
        AudioTrack track = null;
        int currentSampleRate = 44100;
        int currentChannels = 2;
        long pcmBytesWrittenBeforeStart = 0L;
        int preRollThresholdBytes = (currentSampleRate * currentChannels * 2) / 5; // ~200 ms
        boolean startedTrack = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long ptsUs = 0L;

        while (!stopped) {
            // 1. Feed available frames into MediaCodec
            boolean fedAny = true;
            while (fedAny && !stopped && !inputEof) {
                fedAny = false;
                FrameInfo frame = findNextFrame(buf, validLen, kind);
                if (frame != null && frame.offset > 0) {
                    validLen -= frame.offset;
                    System.arraycopy(buf, frame.offset, buf, 0, validLen);
                    frame = new FrameInfo(0, frame.frameLen, frame.headerLen, frame.kind,
                            frame.sampleRate, frame.channelCount, frame.profileObjType, frame.freqIndex, frame.chanCfg);
                }
                if (frame != null && frame.frameLen <= validLen) {
                    int inIdx = codec.dequeueInputBuffer(4000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = getInputBufferCompat(codec, inIdx);
                        if (inBuf != null) {
                            inBuf.clear();
                            int payloadOff = (kind == CodecKind.ADTS_AAC) ? frame.headerLen : 0;
                            int payloadLen = frame.frameLen - payloadOff;
                            if (payloadLen > 0 && payloadLen <= inBuf.capacity()) {
                                inBuf.put(buf, payloadOff, payloadLen);
                                codec.queueInputBuffer(inIdx, 0, payloadLen, ptsUs, 0);
                                ptsUs += 23220L;
                            } else {
                                codec.queueInputBuffer(inIdx, 0, 0, ptsUs, 0);
                            }
                        }
                        int remain = validLen - frame.frameLen;
                        if (remain > 0) {
                            System.arraycopy(buf, frame.frameLen, buf, 0, remain);
                        }
                        validLen = remain;
                        fedAny = true;
                    }
                } else if (validLen < STREAM_BUF_CAPACITY - 4096) {
                    int n = in.read(buf, validLen, STREAM_BUF_CAPACITY - validLen);
                    if (n < 0) {
                        inputEof = true;
                    } else if (n > 0) {
                        validLen += n;
                        fedAny = true;
                    }
                } else {
                    // Resync if garbage filled the buffer
                    System.arraycopy(buf, 1, buf, 0, validLen - 1);
                    validLen -= 1;
                }
            }

            // Fill more bytes if buffer is getting low
            if (!inputEof && validLen < 8192 && !stopped) {
                int n = in.read(buf, validLen, Math.min(8192, STREAM_BUF_CAPACITY - validLen));
                if (n < 0) {
                    inputEof = true;
                } else if (n > 0) {
                    validLen += n;
                }
            }

            // 2. Drain decoded PCM buffers from MediaCodec
            int outIdx = codec.dequeueOutputBuffer(info, 4000);
            while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (stopped) break;
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFmt = codec.getOutputFormat();
                    int newRate = outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : currentSampleRate;
                    int newCh = outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ? outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : currentChannels;
                    if (track == null || newRate != currentSampleRate || newCh != currentChannels) {
                        releaseAudioTrack(track);
                        currentSampleRate = Math.max(8000, newRate);
                        currentChannels = Math.max(1, Math.min(2, newCh));
                        preRollThresholdBytes = (currentSampleRate * currentChannels * 2) / 5;
                        pcmBytesWrittenBeforeStart = 0L;
                        startedTrack = false;
                        track = createAudioTrack(currentSampleRate, currentChannels);
                        audioTrack = track;
                    }
                } else if (outIdx >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        if (track == null) {
                            MediaFormat outFmt = codec.getOutputFormat();
                            if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                currentSampleRate = Math.max(8000, outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                            }
                            if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                currentChannels = Math.max(1, Math.min(2, outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)));
                            }
                            preRollThresholdBytes = (currentSampleRate * currentChannels * 2) / 5;
                            track = createAudioTrack(currentSampleRate, currentChannels);
                            audioTrack = track;
                        }
                        ByteBuffer outBuf = getOutputBufferCompat(codec, outIdx);
                        if (outBuf != null && track != null) {
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            byte[] pcm = new byte[info.size];
                            outBuf.get(pcm);
                            int written = 0;
                            while (written < pcm.length && !stopped) {
                                int w = track.write(pcm, written, pcm.length - written);
                                if (w <= 0) break;
                                written += w;
                            }
                            if (!startedTrack) {
                                pcmBytesWrittenBeforeStart += written;
                                if (pcmBytesWrittenBeforeStart >= preRollThresholdBytes) {
                                    track.play();
                                    startedTrack = true;
                                    if (!playingNotified) {
                                        playingNotified = true;
                                        playStartElapsedMs = SystemClock.elapsedRealtime();
                                        if (callback != null) {
                                            callback.onFirstAudioPlaying();
                                        }
                                    }
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                }
                if (stopped) break;
                outIdx = codec.dequeueOutputBuffer(info, 0);
            }

            if (inputEof && validLen < 4) {
                if (track != null && !startedTrack) {
                    track.play();
                    startedTrack = true;
                    if (!playingNotified) {
                        playingNotified = true;
                        playStartElapsedMs = SystemClock.elapsedRealtime();
                        if (callback != null) callback.onFirstAudioPlaying();
                    }
                }
                break;
            }
        }
        return track;
    }

    private AudioTrack createAudioTrack(int sampleRate, int channelCount) {
        int chMask = (channelCount == 1) ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = 16384;
        int bytesPerSec = sampleRate * channelCount * 2;
        int bufSize = Math.max(minBuf * 2, (bytesPerSec * 3) / 5);

        AudioTrack track;
        if (Build.VERSION.SDK_INT >= 26) {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(chMask)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufSize)
                    .build();
        } else {
            @SuppressWarnings("deprecation")
            AudioTrack legacy = new AudioTrack(
                    AudioManager.STREAM_MUSIC, sampleRate, chMask,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize, AudioTrack.MODE_STREAM);
            track = legacy;
        }
        float vol = this.volume;
        if (Build.VERSION.SDK_INT >= 21) {
            track.setVolume(vol);
        } else {
            @SuppressWarnings("deprecation")
            int ignored = track.setStereoVolume(vol, vol);
        }
        return track;
    }

    private static MediaCodec createAndConfigureCodec(FrameInfo probe) throws IOException {
        if (probe.kind == CodecKind.ADTS_AAC) {
            MediaCodec codec = MediaCodec.createDecoderByType("audio/mp4a-latm");
            int ch = (probe.chanCfg > 0) ? probe.chanCfg : 2;
            MediaFormat fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", probe.sampleRate, ch);
            int csd0 = ((probe.profileObjType & 0x1F) << 11)
                    | ((probe.freqIndex & 0x0F) << 7)
                    | ((ch & 0x0F) << 3);
            byte[] csdBytes = new byte[]{(byte) ((csd0 >> 8) & 0xFF), (byte) (csd0 & 0xFF)};
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csdBytes));
            codec.configure(fmt, null, null, 0);
            return codec;
        }
        MediaCodec codec = MediaCodec.createDecoderByType("audio/mpeg");
        MediaFormat fmt = MediaFormat.createAudioFormat("audio/mpeg", probe.sampleRate, probe.channelCount);
        codec.configure(fmt, null, null, 0);
        return codec;
    }

    private static boolean needsMediaPlayerFallback(String finalUrl, String contentType) {
        String u = finalUrl != null ? finalUrl.toLowerCase(Locale.US) : "";
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        String ct = contentType != null ? contentType.toLowerCase(Locale.US) : "";
        return u.endsWith(".m3u8")
                || u.endsWith(".ogg") || u.endsWith(".opus")
                || u.endsWith(".flac") || u.endsWith(".wav")
                || ct.contains("mpegurl")
                || ct.contains("ogg")
                || ct.contains("flac")
                || ct.contains("wav");
    }

    private static boolean hasUnsupportedMagic(byte[] buf, int len) {
        if (len < 7) return false;
        if (buf[0] == 'O' && buf[1] == 'g' && buf[2] == 'g' && buf[3] == 'S') return true;
        if (buf[0] == 'f' && buf[1] == 'L' && buf[2] == 'a' && buf[3] == 'C') return true;
        if (buf[0] == 'R' && buf[1] == 'I' && buf[2] == 'F' && buf[3] == 'F') return true;
        if (buf[0] == '#' && buf[1] == 'E' && buf[2] == 'X' && buf[3] == 'T' && buf[4] == 'M' && buf[5] == '3' && buf[6] == 'U') return true;
        return false;
    }

    private static int skipId3v2IfPresent(byte[] buf, int validLen, InputStream in) throws IOException {
        if (validLen < 10) return 0;
        if (buf[0] != 'I' || buf[1] != 'D' || buf[2] != '3') return 0;
        int tagSize = ((buf[6] & 0x7F) << 21)
                | ((buf[7] & 0x7F) << 14)
                | ((buf[8] & 0x7F) << 7)
                | (buf[9] & 0x7F);
        int totalSkip = 10 + tagSize + (((buf[5] & 0x10) != 0) ? 10 : 0);
        if (totalSkip <= validLen) {
            return totalSkip;
        }
        int remaining = totalSkip - validLen;
        byte[] discard = new byte[4096];
        while (remaining > 0) {
            int n = in.read(discard, 0, Math.min(discard.length, remaining));
            if (n < 0) break;
            remaining -= n;
        }
        return totalSkip;
    }

    private static FrameInfo probeFirstValidFrames(byte[] buf, int len) {
        for (int pos = 0; pos <= len - 8; pos++) {
            if ((buf[pos] & 0xFF) != 0xFF) continue;
            FrameInfo aac = tryParseAdts(buf, pos, len);
            if (aac != null && pos + aac.frameLen + 7 <= len && tryParseAdts(buf, pos + aac.frameLen, len) != null) {
                return aac;
            }
            FrameInfo mp3 = tryParseMp3(buf, pos, len);
            if (mp3 != null && pos + mp3.frameLen + 4 <= len && tryParseMp3(buf, pos + mp3.frameLen, len) != null) {
                return mp3;
            }
        }
        return null;
    }

    private static FrameInfo findNextFrame(byte[] buf, int len, CodecKind kind) {
        for (int pos = 0; pos <= len - 7; pos++) {
            if ((buf[pos] & 0xFF) != 0xFF) continue;
            FrameInfo f = (kind == CodecKind.ADTS_AAC) ? tryParseAdts(buf, pos, len) : tryParseMp3(buf, pos, len);
            if (f != null) return f;
        }
        return null;
    }

    private static FrameInfo tryParseAdts(byte[] buf, int pos, int len) {
        if (pos + 7 > len) return null;
        int b0 = buf[pos] & 0xFF;
        int b1 = buf[pos + 1] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xF6) != 0xF0) return null;
        int protectionAbsent = b1 & 0x01;
        int hdrLen = (protectionAbsent == 1) ? 7 : 9;
        int b2 = buf[pos + 2] & 0xFF;
        int b3 = buf[pos + 3] & 0xFF;
        int b4 = buf[pos + 4] & 0xFF;
        int b5 = buf[pos + 5] & 0xFF;
        int profileObjType = ((b2 >> 6) & 0x03) + 1;
        int sfIdx = (b2 >> 2) & 0x0F;
        if (sfIdx >= ADTS_SAMPLE_RATES.length) return null;
        int chanCfg = ((b2 & 0x01) << 2) | ((b3 >> 6) & 0x03);
        int frameLen = ((b3 & 0x03) << 11) | (b4 << 3) | ((b5 >> 5) & 0x07);
        if (frameLen <= hdrLen || frameLen > 4096) return null;
        int ch = (chanCfg == 0) ? 2 : (chanCfg >= 7 ? 2 : chanCfg);
        return new FrameInfo(pos, frameLen, hdrLen, CodecKind.ADTS_AAC,
                ADTS_SAMPLE_RATES[sfIdx], ch, profileObjType, sfIdx, chanCfg);
    }

    private static FrameInfo tryParseMp3(byte[] buf, int pos, int len) {
        if (pos + 4 > len) return null;
        int b0 = buf[pos] & 0xFF;
        int b1 = buf[pos + 1] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) return null;
        int versionId = (b1 >> 3) & 0x03;
        int layer = (b1 >> 1) & 0x03;
        if (versionId == 1 || layer != 1) return null; // Only MPEG 1/2/2.5 Layer III
        int b2 = buf[pos + 2] & 0xFF;
        int b3 = buf[pos + 3] & 0xFF;
        int brIdx = (b2 >> 4) & 0x0F;
        int srIdx = (b2 >> 2) & 0x03;
        if (brIdx <= 0 || brIdx >= 15 || srIdx >= 3) return null;
        int padding = (b2 >> 1) & 0x01;
        int bitrateBps = (versionId == 3) ? MP3_V1_BITRATES[brIdx] : MP3_V2_BITRATES[brIdx];
        int sampleRate = MP3_SAMPLE_RATES[versionId][srIdx];
        int coeff = (versionId == 3) ? 144 : 72;
        int frameLen = (coeff * bitrateBps) / sampleRate + padding;
        if (frameLen < 24 || frameLen > 4096) return null;
        int channelMode = (b3 >> 6) & 0x03;
        int channels = (channelMode == 3) ? 1 : 2;
        return new FrameInfo(pos, frameLen, 4, CodecKind.MP3, sampleRate, channels, 0, srIdx, channels);
    }

    private static int fillBuffer(InputStream in, byte[] buf, int off, int target) throws IOException {
        int total = off;
        while (total < target) {
            int n = in.read(buf, total, target - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    @SuppressWarnings("deprecation")
    private static ByteBuffer getInputBufferCompat(MediaCodec codec, int idx) {
        return Build.VERSION.SDK_INT >= 21 ? codec.getInputBuffer(idx) : codec.getInputBuffers()[idx];
    }

    @SuppressWarnings("deprecation")
    private static ByteBuffer getOutputBufferCompat(MediaCodec codec, int idx) {
        return Build.VERSION.SDK_INT >= 21 ? codec.getOutputBuffer(idx) : codec.getOutputBuffers()[idx];
    }

    private static void releaseAudioTrack(AudioTrack track) {
        if (track == null) return;
        try {
            if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause();
            }
            track.flush();
        } catch (Throwable ignored) {}
        try {
            track.release();
        } catch (Throwable ignored) {}
    }

    private static void releaseCodec(MediaCodec codec) {
        if (codec == null) return;
        try {
            codec.stop();
        } catch (Throwable ignored) {}
        try {
            codec.release();
        } catch (Throwable ignored) {}
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void disconnectQuietly(HttpURLConnection conn) {
        if (conn != null) {
            try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }
}
