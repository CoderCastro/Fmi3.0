# Voice to Text (offline, push-to-talk) for Java

A small Java library that records from the microphone while the user holds a key or button, then turns
the speech into text **locally**: no internet and no cloud API. It uses
[whisper.cpp](https://github.com/ggerganov/whisper.cpp) through
[whisper-jni](https://github.com/GiviMAD/whisper-jni), which ships the native libraries for:

| OS | Architectures |
|---|---|
| Windows | x64 |
| macOS | Intel (x64), Apple Silicon (arm64) |
| Linux | x64, arm64, armv7 |

```
voice-to-text/        → the library jar you import into your app
voice-to-text-demo/   → example Swing GUI: a Record button and a text box
```

Requires **Java 17+** and Maven 3.8+.

---

## 1. Build

```bash
mvn package
```

This produces:

| File | Use it when |
|---|---|
| `voice-to-text/target/voice-to-text-0.1.0.jar` | Your app uses Maven or Gradle (whisper-jni comes as a dependency) |
| `voice-to-text/target/voice-to-text-0.1.0-all.jar` | You want **one jar** with everything inside (no other dependencies) |
| `voice-to-text-demo/target/voice-to-text-demo-0.1.0.jar` | Running the demo GUI |

## 2. Try the demo

```bash
java -jar voice-to-text-demo/target/voice-to-text-demo-0.1.0.jar
```

1. On first run it offers to download the speech model (`ggml-base.bin`, ~142 MB) to
   `~/.fmi-voice/models`. This happens once; after that everything is offline.
2. Choose the language, press **● Record**, speak, then press **■ Stop**.
3. The text appears in the box.

To use another model, pass its name or a file path:

```bash
java -jar voice-to-text-demo/target/voice-to-text-demo-0.1.0.jar TINY_EN
```

```bash
java -jar voice-to-text-demo/target/voice-to-text-demo-0.1.0.jar /path/to/ggml-small.bin
```

> **macOS:** the first time, the system asks for microphone permission for your terminal or Java.
> If you denied it, re-enable it under System Settings → Privacy & Security → Microphone.

## 3. Use it in your app

### Add the dependency

**Maven** (after `mvn install` in this project):

```xml
<dependency>
    <groupId>com.fmi.voice</groupId>
    <artifactId>voice-to-text</artifactId>
    <version>0.1.0</version>
</dependency>
```

**Gradle** (after `mvn install`, with `mavenLocal()` in your repositories):

```groovy
implementation 'com.fmi.voice:voice-to-text:0.1.0'
```

**Without a build tool:** put `voice-to-text-0.1.0-all.jar` on the classpath.

### Code

```java
import com.fmi.voice.*;

// Once, at startup (loading the model takes 1-2 s, so do it in the background):
Path model = ModelDownloader.ensureModel(WhisperModel.BASE, ModelDownloader.defaultModelDirectory(), null);
VoiceRecognizer recognizer = VoiceRecognizer.builder()
        .modelPath(model)
        .language("en")                 // "pt", "es", ... or "auto"
        .build();

// Key / button pressed:
recognizer.startListening();

// Key / button released:
recognizer.stopAndTranscribe().thenAccept(text ->
        SwingUtilities.invokeLater(() -> myTextField.setText(text)));   // or Platform.runLater for JavaFX

// When the app closes:
recognizer.close();
```

Push-to-talk with a key in Swing:

```java
InputMap im = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
im.put(KeyStroke.getKeyStroke("pressed F2"), "talk-start");
im.put(KeyStroke.getKeyStroke("released F2"), "talk-stop");
rootPane.getActionMap().put("talk-start", new AbstractAction() {
    public void actionPerformed(ActionEvent e) {
        if (!recognizer.isListening()) {                 // ignore key auto-repeat
            try { recognizer.startListening(); } catch (VoiceRecognitionException ex) { /* show error */ }
        }
    }
});
rootPane.getActionMap().put("talk-stop", new AbstractAction() {
    public void actionPerformed(ActionEvent e) {
        if (recognizer.isListening()) {
            recognizer.stopAndTranscribe().thenAccept(text -> SwingUtilities.invokeLater(() -> handle(text)));
        }
    }
});
```

> On Linux (X11), holding a key can send repeated release/press pairs. If push-to-talk stops too early there,
> use a click-to-toggle button as in the demo.

### Main API

| Method | What it does |
|---|---|
| `startListening()` | Opens the mic and starts recording |
| `stopAndTranscribe()` | Stops and returns `CompletableFuture<String>` with the text (`""` if nothing was said) |
| `cancelListening()` | Stops and discards the audio |
| `transcribeFile(Path)` | Transcribes a WAV file (useful for tests) |
| `setLanguage(String)` | `"en"`, `"pt"`, ..., or `"auto"` |
| `setInitialPrompt(String)` | Hint words to improve accuracy, e.g. `"open, save, export, zoom in"` |
| `setLevelListener(Consumer<Float>)` | Microphone level 0..1, for a VU meter |
| `close()` | Frees the mic and the model |

Builder options: `threads`, `trimSilence`, `silenceThreshold` (raise it in noisy rooms), `minSpeechMillis`
and `nativeLogging`.

## Choosing a model

| Model | Size | Speed | Notes |
|---|---|---|---|
| `TINY` / `TINY_EN` | 75 MB | fastest | OK for short, clear commands |
| `BASE` / `BASE_EN` | 142 MB | fast | **Good default** |
| `SMALL` / `SMALL_EN` | 466 MB | ~3× slower than base | Noticeably more accurate |
| `MEDIUM` | 1.5 GB | slow | Best accuracy, needs a strong CPU |

`*_EN` models only understand English but are slightly better at it. The others support ~99 languages.
For short commands, `base` typically takes around a second on a modern laptop CPU.

Ship the model file next to your app or download it on first run with `ModelDownloader`. Don't put it
inside the jar: the native code needs a real file path.

## Tests

```bash
mvn test
```

To also run an end-to-end transcription with a real model and recording:

```bash
VOICE_TEST_MODEL=~/.fmi-voice/models/ggml-base.bin VOICE_TEST_WAV=/path/to/hello.wav mvn test
```
