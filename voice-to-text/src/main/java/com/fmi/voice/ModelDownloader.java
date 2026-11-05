package com.fmi.voice;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/** Downloads Whisper models from the official whisper.cpp repository on Hugging Face. */
public final class ModelDownloader {

    /** Receives download progress. {@code totalBytes} is -1 if the server does not say. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    private ModelDownloader() {
    }

    /** Default folder for models: {@code ~/.fmi-voice/models}. */
    public static Path defaultModelDirectory() {
        return Path.of(System.getProperty("user.home"), ".fmi-voice", "models");
    }

    /** Returns the model file if it is already in {@code directory}, otherwise downloads it first. */
    public static Path ensureModel(WhisperModel model, Path directory, ProgressListener listener)
            throws VoiceRecognitionException {
        Path target = directory.resolve(model.fileName());
        if (Files.isRegularFile(target)) {
            return target;
        }
        return download(model, directory, listener);
    }

    /** Downloads {@code model} into {@code directory} (to a .part file first, so a failed download leaves no broken model). */
    public static Path download(WhisperModel model, Path directory, ProgressListener listener)
            throws VoiceRecognitionException {
        Path target = directory.resolve(model.fileName());
        Path partial = directory.resolve(model.fileName() + ".part");
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(model.downloadUrl())).GET().build();
        try {
            Files.createDirectories(directory);
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw new VoiceRecognitionException("Download of " + model.fileName() + " failed: HTTP "
                        + response.statusCode() + " from " + model.downloadUrl());
            }
            long total = response.headers().firstValueAsLong("content-length").orElse(-1L);
            try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(partial)) {
                byte[] buf = new byte[1 << 16];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (listener != null) {
                        listener.onProgress(done, total);
                    }
                }
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            throw new VoiceRecognitionException("Download of " + model.fileName() + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VoiceRecognitionException("Download of " + model.fileName() + " was interrupted", e);
        } finally {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }
}
