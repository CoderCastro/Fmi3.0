package com.fmi.voice;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioUtilsTest {

    @Test
    void decodesLittleEndianMono() {
        AudioFormat fmt = new AudioFormat(16_000f, 16, 1, true, false);
        byte[] pcm = {0x00, 0x40, 0x00, (byte) 0xC0}; // +16384, -16384
        assertArrayEquals(new float[]{0.5f, -0.5f}, AudioUtils.pcm16ToMonoFloat(pcm, fmt), 1e-6f);
    }

    @Test
    void decodesBigEndianStereoByAveragingChannels() {
        AudioFormat fmt = new AudioFormat(16_000f, 16, 2, true, true);
        byte[] pcm = {0x40, 0x00, 0x00, 0x00}; // L=+16384, R=0
        assertArrayEquals(new float[]{0.25f}, AudioUtils.pcm16ToMonoFloat(pcm, fmt), 1e-6f);
    }

    @Test
    void resamples48kTo16k() {
        float[] in = new float[48_000];
        assertEquals(16_000, AudioUtils.resample(in, 48_000f, 16_000).length);
        float[] same = new float[10];
        assertTrue(same == AudioUtils.resample(same, 16_000f, 16_000));
    }

    @Test
    void trimsSilenceAroundSpeech() {
        int sr = 16_000;
        float[] s = new float[sr * 3];
        for (int i = sr; i < 2 * sr; i++) {           // 1 s of "speech" in the middle
            s[i] = (float) Math.sin(i * 0.1) * 0.3f;
        }
        float[] trimmed = AudioUtils.trimSilence(s, sr, 0.01f);
        assertTrue(trimmed.length >= sr && trimmed.length <= sr + 2 * sr / 5 + sr / 50,
                "length was " + trimmed.length);
        assertEquals(0, AudioUtils.trimSilence(new float[sr], sr, 0.01f).length);
    }

    @Test
    void readsWavFileAndConvertsTo16kMono() throws Exception {
        AudioFormat fmt = new AudioFormat(44_100f, 16, 2, true, false);
        byte[] pcm = new byte[44_100 * 4]; // 1 second of stereo silence
        Path wav = Files.createTempFile("voice-test", ".wav");
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), fmt, 44_100)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wav.toFile());
            float[] samples = AudioUtils.readAudioFile(wav);
            assertEquals(16_000, samples.length, 1);
        } finally {
            Files.deleteIfExists(wav);
        }
    }
}
