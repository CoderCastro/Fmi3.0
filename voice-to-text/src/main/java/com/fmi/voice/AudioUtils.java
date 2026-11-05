package com.fmi.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/** Audio conversion helpers. Whisper expects 16 kHz, mono, float samples in [-1, 1]. */
public final class AudioUtils {

    /** The sample rate Whisper works with. */
    public static final int WHISPER_SAMPLE_RATE = 16_000;

    private AudioUtils() {
    }

    /**
     * Converts raw signed 16-bit PCM bytes (any channel count, any endianness, any rate)
     * into 16 kHz mono float samples ready for Whisper.
     */
    public static float[] toWhisperInput(byte[] pcm, AudioFormat format) {
        float[] mono = pcm16ToMonoFloat(pcm, format);
        return resample(mono, format.getSampleRate(), WHISPER_SAMPLE_RATE);
    }

    /** Decodes signed 16-bit PCM into mono floats, averaging the channels. */
    public static float[] pcm16ToMonoFloat(byte[] pcm, AudioFormat format) {
        if (format.getSampleSizeInBits() != 16 || format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
            throw new IllegalArgumentException("Only signed 16-bit PCM is supported, got " + format);
        }
        int channels = Math.max(1, format.getChannels());
        boolean bigEndian = format.isBigEndian();
        int frameSize = 2 * channels;
        int frames = pcm.length / frameSize;
        float[] out = new float[frames];
        for (int f = 0; f < frames; f++) {
            float sum = 0f;
            for (int c = 0; c < channels; c++) {
                int i = f * frameSize + c * 2;
                int lo = bigEndian ? pcm[i + 1] : pcm[i];
                int hi = bigEndian ? pcm[i] : pcm[i + 1];
                short sample = (short) ((hi << 8) | (lo & 0xFF));
                sum += sample / 32768f;
            }
            out[f] = sum / channels;
        }
        return out;
    }

    /** Linear-interpolation resampler. Good enough for speech. */
    public static float[] resample(float[] in, float fromRate, int toRate) {
        if (in.length == 0 || Math.abs(fromRate - toRate) < 0.5f) {
            return in;
        }
        double ratio = fromRate / toRate;
        int outLen = (int) Math.floor(in.length / ratio);
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            double src = i * ratio;
            int i0 = (int) src;
            int i1 = Math.min(i0 + 1, in.length - 1);
            double frac = src - i0;
            out[i] = (float) (in[i0] * (1 - frac) + in[i1] * frac);
        }
        return out;
    }

    /** Root-mean-square level of {@code samples[from, to)}, between 0 and 1. */
    public static float rms(float[] samples, int from, int to) {
        if (to <= from) {
            return 0f;
        }
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += (double) samples[i] * samples[i];
        }
        return (float) Math.sqrt(sum / (to - from));
    }

    /**
     * Removes leading and trailing silence, keeping a small padding around the speech.
     * Returns an empty array if nothing is louder than {@code threshold}.
     */
    public static float[] trimSilence(float[] samples, int sampleRate, float threshold) {
        int frame = Math.max(1, sampleRate / 50); // 20 ms frames
        int first = -1;
        int last = -1;
        for (int start = 0; start < samples.length; start += frame) {
            int end = Math.min(samples.length, start + frame);
            if (rms(samples, start, end) >= threshold) {
                if (first < 0) {
                    first = start;
                }
                last = end;
            }
        }
        if (first < 0) {
            return new float[0];
        }
        int pad = sampleRate / 5; // 200 ms
        int from = Math.max(0, first - pad);
        int to = Math.min(samples.length, last + pad);
        return Arrays.copyOfRange(samples, from, to);
    }

    /** Pads with silence so the clip is at least {@code minSamples} long. */
    public static float[] padToLength(float[] samples, int minSamples) {
        return samples.length >= minSamples ? samples : Arrays.copyOf(samples, minSamples);
    }

    /** Reads a WAV (or any format Java Sound can decode) as 16 kHz mono floats. */
    public static float[] readAudioFile(Path file) throws IOException, UnsupportedAudioFileException {
        try (AudioInputStream original = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat src = original.getFormat();
            AudioFormat pcm16 = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, src.getSampleRate(), 16,
                    src.getChannels(), src.getChannels() * 2, src.getSampleRate(), false);
            try (AudioInputStream converted = AudioSystem.getAudioInputStream(pcm16, original)) {
                return toWhisperInput(converted.readAllBytes(), pcm16);
            }
        }
    }
}
