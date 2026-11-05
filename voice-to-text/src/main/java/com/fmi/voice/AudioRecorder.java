package com.fmi.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

/**
 * Records from the default microphone between {@link #start()} and {@link #stop()}.
 *
 * <p>It asks for 16 kHz mono first (what Whisper wants). If the sound card refuses, it falls back to
 * common rates and channel counts and converts afterwards.
 */
public final class AudioRecorder implements AutoCloseable {

    private static final float[] SAMPLE_RATES = {16_000f, 48_000f, 44_100f, 32_000f, 22_050f};
    private static final int[] CHANNELS = {1, 2};

    private final Object lock = new Object();
    private volatile Consumer<Float> levelListener;

    private TargetDataLine line;
    private AudioFormat format;
    private ByteArrayOutputStream buffer;
    private Thread captureThread;
    private volatile boolean recording;

    /** Optional: receives the microphone level (0..1) about every 50 ms while recording. Called on the capture thread. */
    public void setLevelListener(Consumer<Float> listener) {
        this.levelListener = listener;
    }

    public boolean isRecording() {
        return recording;
    }

    /** Opens the microphone and starts buffering audio. */
    public void start() throws VoiceRecognitionException {
        synchronized (lock) {
            if (recording) {
                throw new IllegalStateException("Already recording");
            }
            openLine();
            buffer = new ByteArrayOutputStream();
            line.start();
            recording = true;
            captureThread = new Thread(this::captureLoop, "voice-recorder");
            captureThread.setDaemon(true);
            captureThread.start();
        }
    }

    /** Stops recording and returns the captured audio as 16 kHz mono floats. */
    public float[] stop() {
        synchronized (lock) {
            if (!recording) {
                throw new IllegalStateException("Not recording");
            }
            recording = false;
            line.stop();
            try {
                captureThread.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            line.close();
            line = null;
            byte[] pcm;
            synchronized (buffer) {
                pcm = buffer.toByteArray();
            }
            return AudioUtils.toWhisperInput(pcm, format);
        }
    }

    /** Stops recording and throws the audio away. Does nothing if not recording. */
    public void cancel() {
        synchronized (lock) {
            if (recording) {
                stop();
            }
        }
    }

    @Override
    public void close() {
        cancel();
    }

    private void captureLoop() {
        // ~50 ms chunks, so stop() is responsive and the level meter is smooth.
        int bytesPerChunk = Math.max(format.getFrameSize(),
                (int) (format.getFrameRate() / 20) * format.getFrameSize());
        byte[] chunk = new byte[bytesPerChunk];
        while (recording) {
            int n = line.read(chunk, 0, chunk.length);
            if (n <= 0) {
                continue;
            }
            synchronized (buffer) {
                buffer.write(chunk, 0, n);
            }
            Consumer<Float> listener = levelListener;
            if (listener != null) {
                byte[] copy = n == chunk.length ? chunk : java.util.Arrays.copyOf(chunk, n);
                float[] samples = AudioUtils.pcm16ToMonoFloat(copy, format);
                listener.accept(AudioUtils.rms(samples, 0, samples.length));
            }
        }
    }

    private void openLine() throws VoiceRecognitionException {
        Exception lastError = null;
        for (float rate : SAMPLE_RATES) {
            for (int channels : CHANNELS) {
                AudioFormat candidate = new AudioFormat(rate, 16, channels, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, candidate);
                if (!AudioSystem.isLineSupported(info)) {
                    continue;
                }
                try {
                    TargetDataLine l = (TargetDataLine) AudioSystem.getLine(info);
                    l.open(candidate);
                    line = l;
                    format = candidate;
                    return;
                } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
                    lastError = e;
                }
            }
        }
        throw new VoiceRecognitionException(
                "No usable microphone found. Check that one is connected and that this app may use it.", lastError);
    }
}
