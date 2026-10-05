package com.seekgodtakeall.karaokestudio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;

public final class OfflineAudioEngine {
    public interface Listener {
        void onProgress(String stage, int percent);
    }

    public static final class CancelToken {
        volatile boolean cancelled = false;
        public void cancel() { cancelled = true; }
    }

    private enum Mode { KARAOKE, MASTER }

    private OfflineAudioEngine() {}

    public static File createKaraoke(Context context, Uri input, Listener listener, CancelToken token) throws Exception {
        return process(context, input, listener, token, Mode.KARAOKE);
    }

    public static File master(Context context, Uri input, Listener listener, CancelToken token) throws Exception {
        return process(context, input, listener, token, Mode.MASTER);
    }

    private static File process(Context context, Uri input, Listener listener, CancelToken token, Mode mode) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        RandomAccessFile wav = null;
        File out = null;

        try {
            extractor.setDataSource(context, input, null);
            int audioTrack = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    inputFormat = f;
                    break;
                }
            }
            if (audioTrack < 0 || inputFormat == null) {
                throw new IllegalArgumentException("No audio track was found in this file.");
            }

            extractor.selectTrack(audioTrack);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION) : -1L;
            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;

            if (mode == Mode.KARAOKE && channels < 2) {
                throw new IllegalArgumentException("Offline vocal reduction currently needs a stereo audio file.");
            }

            MediaFormat decoderFormat = new MediaFormat(inputFormat);
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                decoderFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            }

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(decoderFormat, null, null, 0);
            decoder.start();

            File base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (base == null) base = context.getFilesDir();
            File dir = new File(base, "KaraokeStudio");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Could not create the offline output folder.");
            }
            String prefix = mode == Mode.KARAOKE ? "KaraokeStudio-Offline-" : "KaraokeStudio-Master-";
            out = new File(dir, prefix + System.currentTimeMillis() + ".wav");
            wav = new RandomAccessFile(out, "rw");
            wav.setLength(0);
            for (int i = 0; i < 44; i++) wav.write(0);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            int outputChannels = mode == Mode.KARAOKE ? 2 : Math.max(1, Math.min(2, channels));
            long dataBytes = 0;
            int lastPercent = -1;

            if (listener != null) listener.onProgress(mode == Mode.KARAOKE ? "Preparing offline karaoke" : "Preparing offline master", 0);

            while (!outputDone) {
                if (token != null && token.cancelled) throw new CancellationException("Cancelled");

                if (!inputDone) {
                    int inIndex = decoder.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer in = decoder.getInputBuffer(inIndex);
                        if (in != null) {
                            in.clear();
                            int size = extractor.readSampleData(in, 0);
                            if (size < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                long pts = extractor.getSampleTime();
                                decoder.queueInputBuffer(inIndex, 0, size, pts, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outIndex = decoder.dequeueOutputBuffer(info, 10000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = decoder.getOutputFormat();
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    if (mode == Mode.KARAOKE && channels < 2) {
                        throw new IllegalArgumentException("Offline vocal reduction currently needs stereo PCM audio.");
                    }
                    outputChannels = mode == Mode.KARAOKE ? 2 : Math.max(1, Math.min(2, channels));
                } else if (outIndex >= 0) {
                    ByteBuffer buffer = decoder.getOutputBuffer(outIndex);
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        int written = writeProcessed(buffer, pcmEncoding, channels, outputChannels, mode, wav);
                        dataBytes += written;
                    }

                    if (durationUs > 0 && info.presentationTimeUs >= 0) {
                        int percent = (int) Math.max(0, Math.min(99, (info.presentationTimeUs * 100L) / durationUs));
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            if (listener != null) {
                                listener.onProgress(mode == Mode.KARAOKE ? "Reducing lead vocal on device" : "Mastering on device", percent);
                            }
                        }
                    }

                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outIndex, false);
                }
            }

            writeWavHeader(wav, sampleRate, outputChannels, dataBytes);
            if (listener != null) listener.onProgress("Finishing WAV", 100);
            return out;
        } catch (Exception e) {
            if (out != null && out.exists()) out.delete();
            throw e;
        } finally {
            if (wav != null) try { wav.close(); } catch (Exception ignored) {}
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                try { decoder.release(); } catch (Exception ignored) {}
            }
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    private static int writeProcessed(ByteBuffer src, int pcmEncoding, int channels, int outputChannels, Mode mode, RandomAccessFile out) throws Exception {
        if (channels <= 0) throw new IllegalArgumentException("Invalid audio channel count.");

        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            src.order(ByteOrder.LITTLE_ENDIAN);
            int totalFloats = src.remaining() / 4;
            int frames = totalFloats / channels;
            byte[] result = new byte[frames * outputChannels * 2];
            int pos = 0;
            for (int f = 0; f < frames; f++) {
                float left = src.getFloat();
                float right = channels > 1 ? src.getFloat() : left;
                for (int c = 2; c < channels; c++) src.getFloat();
                if (mode == Mode.KARAOKE) {
                    float[] lr = karaoke(left, right);
                    pos = put16(result, pos, lr[0]);
                    pos = put16(result, pos, lr[1]);
                } else {
                    pos = put16(result, pos, masterSample(left));
                    if (outputChannels > 1) pos = put16(result, pos, masterSample(right));
                }
            }
            out.write(result, 0, pos);
            return pos;
        }

        if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT && pcmEncoding != 0) {
            throw new IllegalArgumentException("This device returned an unsupported PCM format.");
        }

        src.order(ByteOrder.LITTLE_ENDIAN);
        int totalShorts = src.remaining() / 2;
        int frames = totalShorts / channels;
        byte[] result = new byte[frames * outputChannels * 2];
        int pos = 0;
        for (int f = 0; f < frames; f++) {
            float left = src.getShort() / 32768f;
            float right = channels > 1 ? src.getShort() / 32768f : left;
            for (int c = 2; c < channels; c++) src.getShort();

            if (mode == Mode.KARAOKE) {
                float[] lr = karaoke(left, right);
                pos = put16(result, pos, lr[0]);
                pos = put16(result, pos, lr[1]);
            } else {
                pos = put16(result, pos, masterSample(left));
                if (outputChannels > 1) pos = put16(result, pos, masterSample(right));
            }
        }
        out.write(result, 0, pos);
        return pos;
    }

    private static float[] karaoke(float left, float right) {
        // Mid/side vocal reduction: most lead vocals are mixed near the stereo centre.
        // Keep the stereo side information while strongly attenuating the centre.
        float mid = (left + right) * 0.5f;
        float side = (left - right) * 0.5f;
        mid *= 0.18f;
        float outL = (mid + side) * 1.18f;
        float outR = (mid - side) * 1.18f;
        return new float[]{softClip(outL), softClip(outR)};
    }

    private static float masterSample(float x) {
        float sign = x < 0 ? -1f : 1f;
        float a = Math.abs(x);
        final float threshold = 0.58f;
        if (a > threshold) a = threshold + (a - threshold) / 3.0f;
        a *= 1.28f;
        return softClip(sign * a);
    }

    private static float softClip(float x) {
        if (x > 1.2f) x = 1.2f;
        if (x < -1.2f) x = -1.2f;
        return x / (1f + 0.20f * Math.abs(x));
    }

    private static int put16(byte[] dst, int pos, float value) {
        value = Math.max(-1f, Math.min(1f, value));
        short s = (short) Math.round(value * 32767f);
        dst[pos++] = (byte) (s & 0xff);
        dst[pos++] = (byte) ((s >> 8) & 0xff);
        return pos;
    }

    private static void writeWavHeader(RandomAccessFile f, int sampleRate, int channels, long dataBytes) throws Exception {
        int bits = 16;
        long byteRate = (long) sampleRate * channels * bits / 8;
        int blockAlign = channels * bits / 8;

        f.seek(0);
        f.writeBytes("RIFF");
        writeLE32(f, 36 + dataBytes);
        f.writeBytes("WAVE");
        f.writeBytes("fmt ");
        writeLE32(f, 16);
        writeLE16(f, 1);
        writeLE16(f, channels);
        writeLE32(f, sampleRate);
        writeLE32(f, byteRate);
        writeLE16(f, blockAlign);
        writeLE16(f, bits);
        f.writeBytes("data");
        writeLE32(f, dataBytes);
    }

    private static void writeLE16(RandomAccessFile f, long v) throws Exception {
        f.write((int) (v & 0xff));
        f.write((int) ((v >> 8) & 0xff));
    }

    private static void writeLE32(RandomAccessFile f, long v) throws Exception {
        f.write((int) (v & 0xff));
        f.write((int) ((v >> 8) & 0xff));
        f.write((int) ((v >> 16) & 0xff));
        f.write((int) ((v >> 24) & 0xff));
    }
}
