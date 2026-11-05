package com.fmi.voice;

import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Push-to-talk speech recognition, fully offline.
 *
 * <pre>{@code
 * VoiceRecognizer recognizer = VoiceRecognizer.builder()
 *         .modelPath(Path.of("models/ggml-base.en.bin"))
 *         .build();
 *
 * // key/button pressed:
 * recognizer.startListening();
 * // key/button released:
 * recognizer.stopAndTranscribe().thenAccept(text -> System.out.println("You said: " + text));
 *
 * // when the app closes:
 * recognizer.close();
 * }</pre>
 *
 * <p>Transcription runs on a background thread, so it is safe to call these methods from the UI thread.
 * Futures complete on that background thread: in Swing, wrap UI updates in {@code SwingUtilities.invokeLater}.
 */
public final class VoiceRecognizer implements AutoCloseable {

    private final WhisperTranscriber transcriber;
    private final AudioRecorder recorder = new AudioRecorder();
    private final ExecutorService worker;
    private final boolean trimSilence;
    private final float silenceThreshold;
    private final int minSpeechMillis;

    private VoiceRecognizer(Builder b) throws VoiceRecognitionException {
        this.transcriber = new WhisperTranscriber(b.modelPath, b.language, b.threads, b.nativeLogging);
        this.transcriber.setInitialPrompt(b.initialPrompt);
        this.trimSilence = b.trimSilence;
        this.silenceThreshold = b.silenceThreshold;
        this.minSpeechMillis = b.minSpeechMillis;
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "voice-transcriber");
            t.setDaemon(true);
            return t;
        });
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Opens the microphone and starts recording. */
    public void startListening() throws VoiceRecognitionException {
        recorder.start();
    }

    public boolean isListening() {
        return recorder.isRecording();
    }

    /**
     * Stops recording and transcribes what was said. The future completes with the text
     * ("" if nothing was said), or exceptionally with a {@link VoiceRecognitionException}.
     */
    public CompletableFuture<String> stopAndTranscribe() {
        float[] audio = recorder.stop();
        return transcribe(audio);
    }

    /** Stops recording without transcribing. */
    public void cancelListening() {
        recorder.cancel();
    }

    /** Transcribes 16 kHz mono float samples in the background. */
    public CompletableFuture<String> transcribe(float[] samples16kMono) {
        float[] audio = samples16kMono;
        if (trimSilence) {
            audio = AudioUtils.trimSilence(audio, AudioUtils.WHISPER_SAMPLE_RATE, silenceThreshold);
        }
        if (audio.length < AudioUtils.WHISPER_SAMPLE_RATE * minSpeechMillis / 1000) {
            return CompletableFuture.completedFuture("");
        }
        float[] finalAudio = audio;
        return CompletableFuture.supplyAsync(() -> {
            try {
                return transcriber.transcribe(finalAudio);
            } catch (VoiceRecognitionException e) {
                throw new CompletionException(e);
            }
        }, worker);
    }

    /** Transcribes an audio file (WAV, AIFF, AU) in the background. Handy for testing without a microphone. */
    public CompletableFuture<String> transcribeFile(Path audioFile) {
        float[] samples;
        try {
            samples = AudioUtils.readAudioFile(audioFile);
        } catch (IOException | UnsupportedAudioFileException e) {
            return CompletableFuture.failedFuture(
                    new VoiceRecognitionException("Could not read audio file " + audioFile, e));
        }
        return transcribe(samples);
    }

    /** Receives the microphone level (0..1) while listening, e.g. for a level meter. Called on a background thread. */
    public void setLevelListener(Consumer<Float> listener) {
        recorder.setLevelListener(listener);
    }

    /** Changes the spoken language ("en", "pt", "es", ... or "auto"). Ignored by English-only models. */
    public void setLanguage(String language) {
        transcriber.setLanguage(language);
    }

    public String getLanguage() {
        return transcriber.getLanguage();
    }

    public boolean isMultilingual() {
        return transcriber.isMultilingual();
    }

    /** Hint text with expected words (e.g. your command names) to improve accuracy. */
    public void setInitialPrompt(String prompt) {
        transcriber.setInitialPrompt(prompt);
    }

    /** Releases the microphone and the model. */
    @Override
    public void close() {
        recorder.close();
        worker.shutdown();
        // Let a running transcription finish before freeing the native model.
        try {
            worker.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        transcriber.close();
    }

    /** Configures a {@link VoiceRecognizer}. Only {@link #modelPath(Path)} is required. */
    public static final class Builder {
        private Path modelPath;
        private String language = "auto";
        private int threads;
        private String initialPrompt;
        private boolean trimSilence = true;
        private float silenceThreshold = 0.01f;
        private int minSpeechMillis = 200;
        private boolean nativeLogging;

        private Builder() {
        }

        /** Path to a ggml Whisper model, e.g. {@code ggml-base.en.bin}. See {@link ModelDownloader}. */
        public Builder modelPath(Path modelPath) {
            this.modelPath = modelPath;
            return this;
        }

        /** "en", "pt", "es", ... or "auto" (default). English-only models always use "en". */
        public Builder language(String language) {
            this.language = language;
            return this;
        }

        /** CPU threads used for transcription. Default: number of cores, up to 8. */
        public Builder threads(int threads) {
            this.threads = threads;
            return this;
        }

        /** Hint text with expected words (e.g. your command names) to improve accuracy. */
        public Builder initialPrompt(String initialPrompt) {
            this.initialPrompt = initialPrompt;
            return this;
        }

        /** Cut silence at the start and end before transcribing. Default: true. */
        public Builder trimSilence(boolean trimSilence) {
            this.trimSilence = trimSilence;
            return this;
        }

        /** Level (0..1 RMS) below which audio counts as silence. Default: 0.01. Raise it in noisy rooms. */
        public Builder silenceThreshold(float silenceThreshold) {
            this.silenceThreshold = silenceThreshold;
            return this;
        }

        /** Recordings with less speech than this return "" without running Whisper. Default: 200 ms. */
        public Builder minSpeechMillis(int minSpeechMillis) {
            this.minSpeechMillis = minSpeechMillis;
            return this;
        }

        /** Print whisper.cpp's internal log to stderr. Default: false. */
        public Builder nativeLogging(boolean nativeLogging) {
            this.nativeLogging = nativeLogging;
            return this;
        }

        /** Loads the model (takes a second or two) and returns a ready recognizer. */
        public VoiceRecognizer build() throws VoiceRecognitionException {
            Objects.requireNonNull(modelPath, "modelPath is required");
            return new VoiceRecognizer(this);
        }
    }
}
