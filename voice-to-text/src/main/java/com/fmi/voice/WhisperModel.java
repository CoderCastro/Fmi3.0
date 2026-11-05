package com.fmi.voice;

/**
 * The standard whisper.cpp (ggml) models. Bigger models are more accurate but slower and need more RAM.
 *
 * <p>Models ending in {@code _EN} only understand English but are a bit more accurate for English.
 * The others are multilingual (Portuguese, Spanish, French, ...).
 */
public enum WhisperModel {
    TINY_EN("ggml-tiny.en.bin", 75, true),
    TINY("ggml-tiny.bin", 75, false),
    BASE_EN("ggml-base.en.bin", 142, true),
    BASE("ggml-base.bin", 142, false),
    SMALL_EN("ggml-small.en.bin", 466, true),
    SMALL("ggml-small.bin", 466, false),
    MEDIUM("ggml-medium.bin", 1500, false);

    /** Where whisper.cpp publishes its converted models. */
    public static final String DOWNLOAD_BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    private final String fileName;
    private final int approxSizeMb;
    private final boolean englishOnly;

    WhisperModel(String fileName, int approxSizeMb, boolean englishOnly) {
        this.fileName = fileName;
        this.approxSizeMb = approxSizeMb;
        this.englishOnly = englishOnly;
    }

    public String fileName() {
        return fileName;
    }

    public int approxSizeMb() {
        return approxSizeMb;
    }

    public boolean isEnglishOnly() {
        return englishOnly;
    }

    public String downloadUrl() {
        return DOWNLOAD_BASE_URL + fileName;
    }
}
