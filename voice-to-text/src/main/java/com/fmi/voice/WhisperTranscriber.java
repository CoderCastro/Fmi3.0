package com.fmi.voice;

import io.github.givimad.whisperjni.WhisperContext;
import io.github.givimad.whisperjni.WhisperFullParams;
import io.github.givimad.whisperjni.WhisperJNI;
import io.github.givimad.whisperjni.WhisperSamplingStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Low-level wrapper around whisper.cpp. Turns 16 kHz mono float samples into text.
 * Most apps should use {@link VoiceRecognizer} instead.
 *
 * <p>Calls are serialized: one Whisper context can only run one transcription at a time.
 */
public final class WhisperTranscriber implements AutoCloseable {

    /** Whisper ignores input shorter than one second, so short clips are padded with silence. */
    private static final int MIN_SAMPLES = (int) (AudioUtils.WHISPER_SAMPLE_RATE * 1.1);

    /** Tags such as [BLANK_AUDIO], [Music] or (silence) that Whisper emits for non-speech. */
    private static final Pattern NON_SPEECH = Pattern.compile("\\[[^\\]]*\\]|\\([^)]*\\)|\\*[^*]*\\*");

    private static boolean nativeLoaded;

    private final WhisperJNI whisper;
    private final WhisperContext context;
    private final int threads;
    private volatile String language;
    private volatile String initialPrompt;

    /**
     * Loads a ggml model, e.g. {@code ggml-base.en.bin}. This takes a moment, so do it once and reuse it.
     *
     * @param language ISO code such as "en" or "pt", or "auto" to detect it (less reliable on short clips)
     * @param threads  CPU threads; 0 means "pick automatically"
     */
    public WhisperTranscriber(Path modelPath, String language, int threads, boolean nativeLogging)
            throws VoiceRecognitionException {
        if (modelPath == null || !Files.isRegularFile(modelPath)) {
            throw new VoiceRecognitionException("Whisper model not found: " + modelPath);
        }
        loadNative(nativeLogging);
        this.whisper = new WhisperJNI();
        try {
            this.context = whisper.init(modelPath);
        } catch (IOException | RuntimeException e) {
            throw new VoiceRecognitionException("Could not load Whisper model " + modelPath, e);
        }
        if (context == null) {
            throw new VoiceRecognitionException("Could not load Whisper model " + modelPath + " (is it a ggml .bin file?)");
        }
        this.threads = threads > 0 ? threads : Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        setLanguage(language);
    }

    private static synchronized void loadNative(boolean nativeLogging) throws VoiceRecognitionException {
        if (nativeLoaded) {
            return;
        }
        try {
            WhisperJNI.loadLibrary();
            // whisper.cpp prints a lot of internals; only forward them when asked to.
            WhisperJNI.setLibraryLogger(nativeLogging ? System.err::println : null);
            nativeLoaded = true;
        } catch (IOException | UnsatisfiedLinkError e) {
            throw new VoiceRecognitionException("Could not load the native Whisper library for "
                    + System.getProperty("os.name") + " / " + System.getProperty("os.arch"), e);
        }
    }

    /** Whether the loaded model understands languages other than English. */
    public boolean isMultilingual() {
        return whisper.isMultilingual(context);
    }

    /** "en", "pt", "es", ... or "auto". English-only models always use "en". */
    public void setLanguage(String language) {
        this.language = (language == null || language.isBlank()) ? "auto" : language.trim().toLowerCase();
    }

    public String getLanguage() {
        return language;
    }

    /**
     * Optional hint text that biases recognition, e.g. a list of your app's command words:
     * {@code "open, close, save, export, zoom in, zoom out"}.
     */
    public void setInitialPrompt(String initialPrompt) {
        this.initialPrompt = initialPrompt;
    }

    /** Transcribes 16 kHz mono float samples. Blocks until done. Returns "" if nothing was said. */
    public synchronized String transcribe(float[] samples) throws VoiceRecognitionException {
        if (samples == null || samples.length == 0) {
            return "";
        }
        float[] input = AudioUtils.padToLength(samples, MIN_SAMPLES);

        WhisperFullParams params = new WhisperFullParams(WhisperSamplingStrategy.GREEDY);
        params.nThreads = threads;
        params.language = isMultilingual() ? language : "en";
        params.detectLanguage = false;
        params.translate = false;
        params.noContext = true;
        params.suppressBlank = true;
        params.suppressNonSpeechTokens = true;
        params.printProgress = false;
        params.printRealtime = false;
        params.printSpecial = false;
        params.printTimestamps = false;
        if (initialPrompt != null && !initialPrompt.isBlank()) {
            params.initialPrompt = initialPrompt;
        }

        int result = whisper.full(context, params, input, input.length);
        if (result != 0) {
            throw new VoiceRecognitionException("Whisper failed with code " + result);
        }
        StringBuilder text = new StringBuilder();
        int segments = whisper.fullNSegments(context);
        for (int i = 0; i < segments; i++) {
            text.append(whisper.fullGetSegmentText(context, i));
        }
        return cleanUp(text.toString());
    }

    static String cleanUp(String raw) {
        return NON_SPEECH.matcher(raw).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    @Override
    public synchronized void close() {
        context.close();
    }
}
