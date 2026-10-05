package com.seekgodtakeall.karaokestudio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Environment;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.concurrent.CancellationException;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

public final class NeuralKaraokeEngine {
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int SEGMENT = 343980;
    private static final int OVERLAP = SEGMENT / 4;
    private static final int STRIDE = SEGMENT - OVERLAP;
    private static final int VOCALS_ROW = 3;

    private static final String MODEL_NAME = "htdemucs_ft_vocals_fp16weights.onnx";
    private static final String MODEL_URL =
            "https://huggingface.co/StemSplitio/htdemucs-ft-vocals-onnx/resolve/main/htdemucs_ft_vocals_fp16weights.onnx";
    private static final String MODEL_SHA256 =
            "0cbe651f535415c9d26a7bb614f7d322dd5a080fa0298f2e50f478030a994dce";

    public interface Listener {
        void onProgress(String stage, int percent);
    }

    public static final class CancelToken {
        volatile boolean cancelled = false;
        public void cancel() { cancelled = true; }
        void check() {
            if (cancelled) throw new CancellationException("Cancelled");
        }
    }

    private NeuralKaraokeEngine() {}

    private static File modelDir(Context context) {
        File dir = new File(context.getFilesDir(), "ai-models");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File modelFile(Context context) {
        return new File(modelDir(context), MODEL_NAME);
    }

    private static File verifiedMarker(Context context) {
        return new File(modelDir(context), MODEL_NAME + ".verified");
    }

    public static boolean isModelInstalled(Context context) {
        File model = modelFile(context);
        File marker = verifiedMarker(context);
        return model.isFile() && model.length() > 150L * 1024L * 1024L && marker.isFile();
    }

    public static void downloadModel(Context context, Listener listener, CancelToken token) throws Exception {
        File model = modelFile(context);
        File part = new File(model.getAbsolutePath() + ".part");
        File marker = verifiedMarker(context);

        if (isModelInstalled(context)) {
            if (listener != null) listener.onProgress("Professional AI model ready", 100);
            return;
        }

        if (part.exists()) part.delete();
        if (marker.exists()) marker.delete();

        if (listener != null) listener.onProgress("Downloading professional AI model", 0);
        HttpURLConnection connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "KaraokeStudioAndroid/3.1");

        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new Exception("AI model download failed with HTTP " + code + ".");
        }

        long total = connection.getContentLengthLong();
        long received = 0;
        byte[] buffer = new byte[256 * 1024];

        try (java.io.InputStream in = connection.getInputStream();
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(part), 256 * 1024)) {
            int n;
            int lastPercent = -1;
            while ((n = in.read(buffer)) >= 0) {
                token.check();
                out.write(buffer, 0, n);
                received += n;
                if (total > 0) {
                    int percent = (int) Math.min(99, (received * 100L) / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        if (listener != null) listener.onProgress("Downloading professional AI model", percent);
                    }
                }
            }
        } finally {
            connection.disconnect();
        }

        token.check();
        if (listener != null) listener.onProgress("Verifying AI model", 99);

        String sha = sha256(part);
        if (!MODEL_SHA256.equalsIgnoreCase(sha)) {
            part.delete();
            throw new Exception("The downloaded AI model failed integrity verification. Please retry the download.");
        }

        if (model.exists() && !model.delete()) {
            throw new Exception("Could not replace the previous AI model.");
        }
        if (!part.renameTo(model)) {
            copyFile(part, model);
            part.delete();
        }
        try (FileOutputStream out = new FileOutputStream(marker)) {
            out.write(MODEL_SHA256.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        if (listener != null) listener.onProgress("Professional AI model ready", 100);
    }

    public static File createKaraoke(Context context, Uri input, Listener listener, CancelToken token) throws Exception {
        if (!isModelInstalled(context)) {
            throw new Exception("The professional AI model is not installed yet.");
        }

        File workDir = new File(context.getCacheDir(), "neural-karaoke");
        if (!workDir.exists() && !workDir.mkdirs()) {
            throw new Exception("Could not create local processing storage.");
        }

        File rawMix = new File(workDir, "mix-" + System.currentTimeMillis() + ".f32");
        File result = null;
        try {
            if (listener != null) listener.onProgress("Decoding and resampling audio", 0);
            long totalFrames = decodeToStereo44100(context, input, rawMix, listener, token);
            if (totalFrames <= 0) throw new Exception("The selected audio file decoded to an empty track.");

            File base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (base == null) base = context.getFilesDir();
            File outDir = new File(base, "KaraokeStudio");
            if (!outDir.exists() && !outDir.mkdirs()) {
                throw new Exception("Could not create the local output folder.");
            }

            result = new File(outDir, "KaraokeStudio-AI-" + System.currentTimeMillis() + ".wav");
            separateVocals(context, rawMix, totalFrames, result, listener, token);
            return result;
        } catch (Exception e) {
            if (result != null && result.exists()) result.delete();
            throw e;
        } finally {
            rawMix.delete();
        }
    }

    private static void separateVocals(
            Context context, File rawMix, long totalFrames, File output,
            Listener listener, CancelToken token) throws Exception {

        token.check();
        if (listener != null) listener.onProgress("Loading HT-Demucs vocal model", 8);

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
        opts.setMemoryPatternOptimization(false);
        opts.setCPUArenaAllocator(false);
        opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        opts.setInterOpNumThreads(1);
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        opts.setIntraOpNumThreads(threads);

        float[] window = transitionWindow();
        float[][] vocalAcc = new float[][]{new float[SEGMENT], new float[SEGMENT]};
        float[] weight = new float[SEGMENT];

        ByteBuffer directBytes = ByteBuffer.allocateDirect(2 * SEGMENT * 4).order(ByteOrder.nativeOrder());
        FloatBuffer inputBuffer = directBytes.asFloatBuffer();
        byte[] sourceBytes = new byte[SEGMENT * 8];

        RandomAccessFile mix = null;
        RandomAccessFile wav = null;
        OrtSession session = null;

        try {
            session = env.createSession(modelFile(context).getAbsolutePath(), opts);
            mix = new RandomAccessFile(rawMix, "r");
            wav = new RandomAccessFile(output, "rw");
            wav.setLength(0);
            for (int i = 0; i < 44; i++) wav.write(0);

            long dataBytes = 0;
            long base = 0;
            int chunks = (int) Math.max(1, (totalFrames + STRIDE - 1) / STRIDE);

            for (int chunkIndex = 0; chunkIndex < chunks; chunkIndex++) {
                token.check();
                long start = (long) chunkIndex * STRIDE;
                int chunkLen = (int) Math.min((long) SEGMENT, Math.max(0, totalFrames - start));
                if (chunkLen <= 0) break;

                int bytesNeeded = chunkLen * 8;
                mix.seek(start * 8L);
                int off = 0;
                while (off < bytesNeeded) {
                    int n = mix.read(sourceBytes, off, bytesNeeded - off);
                    if (n < 0) break;
                    off += n;
                }
                if (off < bytesNeeded) throw new Exception("Unexpected end of decoded audio.");

                inputBuffer.clear();
                for (int i = 0; i < 2 * SEGMENT; i++) inputBuffer.put(i, 0f);
                ByteBuffer src = ByteBuffer.wrap(sourceBytes, 0, bytesNeeded).order(ByteOrder.LITTLE_ENDIAN);
                for (int k = 0; k < chunkLen; k++) {
                    float l = src.getFloat();
                    float r = src.getFloat();
                    inputBuffer.put(k, l);
                    inputBuffer.put(SEGMENT + k, r);
                }
                inputBuffer.position(0);

                try (OnnxTensor tensor = OnnxTensor.createTensor(
                             env, inputBuffer, new long[]{1, 2, SEGMENT});
                     OrtSession.Result ortResult = session.run(
                             Collections.singletonMap("mix", tensor))) {

                    OnnxTensor stems = (OnnxTensor) ortResult.get(0);
                    FloatBuffer out = stems.getFloatBuffer();
                    if (out == null) throw new Exception("AI model returned an unsupported tensor.");

                    int leftOffset = (VOCALS_ROW * 2) * SEGMENT;
                    int rightOffset = leftOffset + SEGMENT;
                    for (int k = 0; k < chunkLen; k++) {
                        float w = window[k];
                        vocalAcc[0][k] += out.get(leftOffset + k) * w;
                        vocalAcc[1][k] += out.get(rightOffset + k) * w;
                        weight[k] += w;
                    }
                } catch (OutOfMemoryError oom) {
                    throw new Exception(
                            "This phone does not have enough free memory for the professional AI model. " +
                            "Close other apps and retry. The model needs substantial RAM while separating vocals.");
                }

                boolean last = chunkIndex == chunks - 1;
                long flushEnd = last ? totalFrames : Math.min(totalFrames, (long) (chunkIndex + 1) * STRIDE);
                int flushCount = (int) Math.max(0, flushEnd - base);

                byte[] pcm = new byte[flushCount * 4];
                int p = 0;
                for (int k = 0; k < flushCount; k++) {
                    float w = weight[k] < 1e-8f ? 1e-8f : weight[k];
                    float vocalL = vocalAcc[0][k] / w;
                    float vocalR = vocalAcc[1][k] / w;
                    float mixL = inputBuffer.get(k);
                    float mixR = inputBuffer.get(SEGMENT + k);

                    // Professional karaoke output = original mix minus the AI-isolated vocal stem.
                    float instL = softLimit(mixL - vocalL);
                    float instR = softLimit(mixR - vocalR);
                    p = put16(pcm, p, instL);
                    p = put16(pcm, p, instR);
                }
                wav.write(pcm);
                dataBytes += pcm.length;

                if (!last) {
                    int keep = SEGMENT - flushCount;
                    for (int c = 0; c < 2; c++) {
                        System.arraycopy(vocalAcc[c], flushCount, vocalAcc[c], 0, keep);
                        java.util.Arrays.fill(vocalAcc[c], keep, SEGMENT, 0f);
                    }
                    System.arraycopy(weight, flushCount, weight, 0, keep);
                    java.util.Arrays.fill(weight, keep, SEGMENT, 0f);
                    base += flushCount;
                }

                int percent = 10 + (int) Math.round(((chunkIndex + 1) * 88.0) / chunks);
                if (listener != null) listener.onProgress("AI separating lead vocals", Math.min(98, percent));
            }

            writeWavHeader(wav, SAMPLE_RATE, 2, dataBytes);
            if (listener != null) listener.onProgress("Professional karaoke ready", 100);
        } finally {
            if (wav != null) try { wav.close(); } catch (Exception ignored) {}
            if (mix != null) try { mix.close(); } catch (Exception ignored) {}
            if (session != null) try { session.close(); } catch (Exception ignored) {}
            try { opts.close(); } catch (Exception ignored) {}
        }
    }

    private static long decodeToStereo44100(
            Context context, Uri input, File output,
            Listener listener, CancelToken token) throws Exception {

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        FloatStereoWriter writer = null;

        try {
            extractor.setDataSource(context, input, null);
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = f;
                    break;
                }
            }
            if (track < 0 || format == null) throw new Exception("No audio track was found.");

            extractor.selectTrack(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : -1L;

            MediaFormat decoderFormat = new MediaFormat(format);
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                decoderFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            }

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(decoderFormat, null, null, 0);
            decoder.start();

            writer = new FloatStereoWriter(output);
            Resampler resampler = null;
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : SAMPLE_RATE;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            int encoding = AudioFormat.ENCODING_PCM_16BIT;

            boolean inputDone = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int lastPercent = -1;

            while (!outputDone) {
                token.check();

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
                                decoder.queueInputBuffer(inIndex, 0, size, extractor.getSampleTime(), 0);
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
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    resampler = new Resampler(sampleRate, SAMPLE_RATE, writer);
                } else if (outIndex >= 0) {
                    ByteBuffer buffer = decoder.getOutputBuffer(outIndex);
                    if (buffer != null && info.size > 0) {
                        if (resampler == null) resampler = new Resampler(sampleRate, SAMPLE_RATE, writer);
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        buffer.order(ByteOrder.LITTLE_ENDIAN);

                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            int frames = (buffer.remaining() / 4) / Math.max(1, channels);
                            for (int f = 0; f < frames; f++) {
                                float l = buffer.getFloat();
                                float r = channels > 1 ? buffer.getFloat() : l;
                                for (int c = 2; c < channels; c++) buffer.getFloat();
                                resampler.feed(l, r);
                            }
                        } else {
                            int frames = (buffer.remaining() / 2) / Math.max(1, channels);
                            for (int f = 0; f < frames; f++) {
                                float l = buffer.getShort() / 32768f;
                                float r = channels > 1 ? buffer.getShort() / 32768f : l;
                                for (int c = 2; c < channels; c++) buffer.getShort();
                                resampler.feed(l, r);
                            }
                        }
                    }

                    if (durationUs > 0 && info.presentationTimeUs >= 0) {
                        int percent = (int) Math.min(7, (info.presentationTimeUs * 7L) / durationUs);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            if (listener != null) listener.onProgress("Decoding and resampling audio", percent);
                        }
                    }

                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decoder.releaseOutputBuffer(outIndex, false);
                }
            }

            writer.flush();
            return writer.frames();
        } finally {
            if (writer != null) try { writer.close(); } catch (Exception ignored) {}
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                try { decoder.release(); } catch (Exception ignored) {}
            }
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    private static final class Resampler {
        private final double step;
        private final FloatStereoWriter writer;
        private boolean havePrevious = false;
        private float previousL;
        private float previousR;
        private long sourceIndex = 0;
        private double nextOutputPosition = 0.0;

        Resampler(int sourceRate, int targetRate, FloatStereoWriter writer) {
            this.step = sourceRate / (double) targetRate;
            this.writer = writer;
        }

        void feed(float left, float right) throws Exception {
            if (!havePrevious) {
                previousL = left;
                previousR = right;
                havePrevious = true;
                writer.write(left, right);
                nextOutputPosition = step;
                sourceIndex = 0;
                return;
            }

            long currentIndex = sourceIndex + 1;
            while (nextOutputPosition <= currentIndex + 1e-9) {
                double frac = nextOutputPosition - sourceIndex;
                if (frac < 0) frac = 0;
                if (frac > 1) frac = 1;
                float outL = (float) (previousL + (left - previousL) * frac);
                float outR = (float) (previousR + (right - previousR) * frac);
                writer.write(outL, outR);
                nextOutputPosition += step;
            }
            previousL = left;
            previousR = right;
            sourceIndex = currentIndex;
        }
    }

    private static final class FloatStereoWriter implements AutoCloseable {
        private final BufferedOutputStream out;
        private final byte[] buffer = new byte[64 * 1024];
        private int pos = 0;
        private long frames = 0;

        FloatStereoWriter(File file) throws Exception {
            out = new BufferedOutputStream(new FileOutputStream(file), 256 * 1024);
        }

        void write(float l, float r) throws Exception {
            putFloat(l);
            putFloat(r);
            frames++;
        }

        long frames() { return frames; }

        private void putFloat(float v) throws Exception {
            if (pos + 4 > buffer.length) flushBuffer();
            int bits = Float.floatToIntBits(v);
            buffer[pos++] = (byte) (bits & 0xff);
            buffer[pos++] = (byte) ((bits >>> 8) & 0xff);
            buffer[pos++] = (byte) ((bits >>> 16) & 0xff);
            buffer[pos++] = (byte) ((bits >>> 24) & 0xff);
        }

        void flush() throws Exception {
            flushBuffer();
            out.flush();
        }

        private void flushBuffer() throws Exception {
            if (pos > 0) {
                out.write(buffer, 0, pos);
                pos = 0;
            }
        }

        @Override public void close() throws Exception {
            flush();
            out.close();
        }
    }

    private static float[] transitionWindow() {
        float[] window = new float[SEGMENT];
        java.util.Arrays.fill(window, 1f);
        for (int i = 0; i < OVERLAP; i++) {
            float v = i / (float) (OVERLAP - 1);
            window[i] = v;
            window[SEGMENT - 1 - i] = v;
        }
        return window;
    }

    private static float softLimit(float x) {
        if (x > 1.5f) x = 1.5f;
        if (x < -1.5f) x = -1.5f;
        return x / (1f + 0.08f * Math.abs(x));
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

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[256 * 1024];
            int n;
            while ((n = in.read(b)) >= 0) md.update(b, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static void copyFile(File source, File target) throws Exception {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] b = new byte[256 * 1024];
            int n;
            while ((n = in.read(b)) >= 0) out.write(b, 0, n);
        }
    }
}
