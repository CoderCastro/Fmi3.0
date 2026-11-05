package com.fmi.voice;

/** Thrown when the microphone, the model or the speech engine fails. */
public class VoiceRecognitionException extends Exception {

    public VoiceRecognitionException(String message) {
        super(message);
    }

    public VoiceRecognitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
