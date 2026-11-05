package com.fmi.voice;

import io.github.givimad.whisperjni.WhisperJNI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WhisperTranscriberTest {

    @Test
    void removesNonSpeechTags() {
        assertEquals("", WhisperTranscriber.cleanUp(" [BLANK_AUDIO]"));
        assertEquals("open the file", WhisperTranscriber.cleanUp(" (music) open  the file [Music]"));
    }

    @Test
    void nativeLibraryLoadsOnThisPlatform() {
        assertDoesNotThrow(() -> WhisperJNI.loadLibrary());
        assertFalse(new WhisperJNI().getSystemInfo().isBlank());
    }

    @Test
    void missingModelGivesClearError() {
        VoiceRecognitionException e = assertThrows(VoiceRecognitionException.class,
                () -> VoiceRecognizer.builder().modelPath(Path.of("does-not-exist.bin")).build());
        assertEquals("Whisper model not found: does-not-exist.bin", e.getMessage());
    }

    /**
     * End-to-end check with a real model and recording. Runs only when both variables are set, e.g.
     * {@code VOICE_TEST_MODEL=~/.fmi-voice/models/ggml-base.bin VOICE_TEST_WAV=hello.wav mvn test}.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "VOICE_TEST_MODEL", matches = ".+")
    @EnabledIfEnvironmentVariable(named = "VOICE_TEST_WAV", matches = ".+")
    void transcribesWavFile() throws Exception {
        try (VoiceRecognizer recognizer = VoiceRecognizer.builder()
                .modelPath(Path.of(System.getenv("VOICE_TEST_MODEL")))
                .build()) {
            String text = recognizer.transcribeFile(Path.of(System.getenv("VOICE_TEST_WAV"))).get(2, TimeUnit.MINUTES);
            System.out.println("Transcription: " + text);
            assertFalse(text.isBlank());
        }
    }
}
