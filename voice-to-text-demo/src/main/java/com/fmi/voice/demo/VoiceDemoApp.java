package com.fmi.voice.demo;

import com.fmi.voice.ModelDownloader;
import com.fmi.voice.VoiceRecognitionException;
import com.fmi.voice.VoiceRecognizer;
import com.fmi.voice.WhisperModel;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletionException;

/**
 * Example GUI: press "Record", speak, press "Stop", and the text appears in the box.
 *
 * <p>Usage:
 * <pre>
 *   java -jar voice-to-text-demo.jar                     # uses/downloads the multilingual "base" model
 *   java -jar voice-to-text-demo.jar BASE_EN             # any WhisperModel name
 *   java -jar voice-to-text-demo.jar /path/to/ggml-x.bin # a model file you already have
 * </pre>
 */
public final class VoiceDemoApp {

    private static final String[][] LANGUAGES = {
            {"Auto-detect", "auto"},
            {"English", "en"},
            {"Português", "pt"},
            {"Español", "es"},
            {"Français", "fr"},
            {"Deutsch", "de"},
            {"Italiano", "it"},
    };

    private static final Color RECORD_COLOR = new Color(0xC6, 0x28, 0x28);

    private final JFrame frame = new JFrame("Voice to Text — Demo");
    private final JButton recordButton = new JButton("● Record");
    private final JButton clearButton = new JButton("Clear");
    private final JComboBox<String> languageBox = new JComboBox<>();
    private final JTextArea textArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("Starting…");
    private final JProgressBar levelBar = new JProgressBar(0, 100);
    private final String modelArg;

    private VoiceRecognizer recognizer;

    private VoiceDemoApp(String modelArg) {
        this.modelArg = modelArg;
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // default look and feel is fine
        }
        String modelArg = args.length > 0 ? args[0] : null;
        SwingUtilities.invokeLater(() -> new VoiceDemoApp(modelArg).show());
    }

    private void show() {
        buildUi();
        frame.setVisible(true);
        loadModelInBackground();
    }

    private void buildUi() {
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setFont(textArea.getFont().deriveFont(Font.PLAIN, 18f));
        textArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        JScrollPane scroll = new JScrollPane(textArea);

        for (String[] lang : LANGUAGES) {
            languageBox.addItem(lang[0]);
        }
        languageBox.setSelectedIndex(defaultLanguageIndex());
        languageBox.addActionListener(e -> {
            if (recognizer != null) {
                recognizer.setLanguage(selectedLanguageCode());
            }
        });

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        JPanel langPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        langPanel.add(new JLabel("Language:"));
        langPanel.add(languageBox);
        top.add(langPanel, BorderLayout.WEST);
        top.add(statusLabel, BorderLayout.CENTER);

        recordButton.setFont(recordButton.getFont().deriveFont(Font.BOLD, 16f));
        recordButton.setPreferredSize(new Dimension(160, 44));
        recordButton.setEnabled(false);
        recordButton.addActionListener(e -> toggleRecording());

        clearButton.addActionListener(e -> textArea.setText(""));

        levelBar.setPreferredSize(new Dimension(160, 14));
        levelBar.setToolTipText("Microphone level");

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
        bottom.add(recordButton);
        bottom.add(levelBar);
        bottom.add(clearButton);

        frame.setLayout(new BorderLayout());
        frame.add(top, BorderLayout.NORTH);
        frame.add(scroll, BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.setSize(720, 460);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (recognizer != null) {
                    recognizer.close();
                }
                System.exit(0);
            }
        });
    }

    // ---------------------------------------------------------------- model loading

    private void loadModelInBackground() {
        Path modelPath = resolveModelPath();
        if (modelPath == null) {
            return; // user declined the download
        }
        new SwingWorker<VoiceRecognizer, Integer>() {
            @Override
            protected VoiceRecognizer doInBackground() throws Exception {
                Path path = modelPath;
                if (!Files.isRegularFile(path)) {
                    WhisperModel model = modelFromArg();
                    setStatus("Downloading " + model.fileName() + "…");
                    path = ModelDownloader.download(model, path.getParent(), (done, total) -> {
                        if (total > 0) {
                            publish((int) (done * 100 / total));
                        }
                    });
                }
                setStatus("Loading model " + path.getFileName() + "…");
                return VoiceRecognizer.builder()
                        .modelPath(path)
                        .language(selectedLanguageCode())
                        .build();
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                int pct = chunks.get(chunks.size() - 1);
                levelBar.setValue(pct);
                statusLabel.setText("Downloading model… " + pct + "%");
            }

            @Override
            protected void done() {
                levelBar.setValue(0);
                try {
                    recognizer = get();
                    recognizer.setLevelListener(level ->
                            SwingUtilities.invokeLater(() -> levelBar.setValue(Math.min(100, (int) (level * 400)))));
                    if (!recognizer.isMultilingual()) {
                        languageBox.setSelectedIndex(1);
                        languageBox.setEnabled(false);
                        languageBox.setToolTipText("This model only understands English");
                    }
                    recordButton.setEnabled(true);
                    statusLabel.setText("Ready. Press Record and speak.");
                } catch (Exception e) {
                    showError("Could not start speech recognition", rootCause(e));
                    statusLabel.setText("Not available — see error.");
                }
            }
        }.execute();
    }

    /** Returns where the model is (or will be downloaded to), or null if the user cancelled. */
    private Path resolveModelPath() {
        if (modelArg != null && (modelArg.endsWith(".bin") || modelArg.contains("/") || modelArg.contains("\\"))) {
            Path p = Path.of(modelArg);
            if (!Files.isRegularFile(p)) {
                showError("Model not found", new IllegalArgumentException(p.toAbsolutePath().toString()));
                return null;
            }
            return p;
        }
        WhisperModel model = modelFromArg();
        Path path = ModelDownloader.defaultModelDirectory().resolve(model.fileName());
        if (!Files.isRegularFile(path)) {
            int answer = JOptionPane.showConfirmDialog(frame,
                    "The speech model \"" + model.fileName() + "\" (~" + model.approxSizeMb() + " MB) is needed.\n"
                            + "It is downloaded once to:\n" + path.getParent()
                            + "\n\nAfter that everything runs offline. Download now?",
                    "Download speech model", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) {
                statusLabel.setText("No model — restart to download it, or pass a model path as argument.");
                return null;
            }
        }
        return path;
    }

    private WhisperModel modelFromArg() {
        if (modelArg == null) {
            return WhisperModel.BASE;
        }
        try {
            return WhisperModel.valueOf(modelArg.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return WhisperModel.BASE;
        }
    }

    // ---------------------------------------------------------------- recording

    private void toggleRecording() {
        if (recognizer == null) {
            return;
        }
        if (!recognizer.isListening()) {
            try {
                recognizer.startListening();
                recordButton.setText("■ Stop");
                recordButton.setForeground(RECORD_COLOR);
                statusLabel.setText("Listening… press Stop when you are done.");
            } catch (VoiceRecognitionException e) {
                showError("Could not open the microphone", e);
            }
            return;
        }

        recordButton.setEnabled(false);
        recordButton.setText("● Record");
        recordButton.setForeground(UIManager.getColor("Button.foreground"));
        levelBar.setValue(0);
        statusLabel.setText("Transcribing…");
        long started = System.nanoTime();

        recognizer.stopAndTranscribe().whenComplete((text, error) -> SwingUtilities.invokeLater(() -> {
            recordButton.setEnabled(true);
            if (error != null) {
                statusLabel.setText("Error.");
                showError("Transcription failed", rootCause(error));
                return;
            }
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (text.isEmpty()) {
                statusLabel.setText("Didn't catch anything — try again, a bit louder.");
                return;
            }
            if (!textArea.getText().isEmpty()) {
                textArea.append("\n");
            }
            textArea.append(text);
            statusLabel.setText("Done in " + ms + " ms. Press Record to speak again.");
        }));
    }

    // ---------------------------------------------------------------- helpers

    private String selectedLanguageCode() {
        return LANGUAGES[Math.max(0, languageBox.getSelectedIndex())][1];
    }

    private static int defaultLanguageIndex() {
        String sys = Locale.getDefault().getLanguage();
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i][1].equals(sys)) {
                return i;
            }
        }
        return 1; // English
    }

    private void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text));
    }

    private void showError(String title, Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : e.toString();
        if (e.getCause() != null && e.getCause().getMessage() != null) {
            message += "\n\nCause: " + e.getCause().getMessage();
        }
        JOptionPane.showMessageDialog(frame, message, title, JOptionPane.ERROR_MESSAGE);
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
