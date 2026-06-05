package lyrify.App;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import lyrify.FileHub.PipelineResult;
import lyrify.FileInterface.FileInterface;
import lyrify.FileInterface.LrcLine;
import lyrify.FileInterface.LyrifyException;
import lyrify.FileInterface.TrackMetadata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// LRC lyrics editor — shown when the user clicks "Edit Lyrics" in TrackDetailView.
// Lets the user view and edit the timestamped lyric lines for a track.
// Saves back to the .lrc file on disk when the user clicks Save.
public class LrcEditorView {

    private final AppState      state;
    private final PipelineResult result;
    private Runnable            onBack;      // callback to return to detail view

    // The main text area where the user edits raw LRC content
    private TextArea lrcTextArea;

    public LrcEditorView(AppState state, PipelineResult result) {
        this.state  = state;
        this.result = result;
    }

    public void setOnBack(Runnable callback) {
        this.onBack = callback;
    }

    public Node build() {
        VBox page = new VBox(UIStyles.PAD_MD);
        page.setPadding(new Insets(UIStyles.PAD_LG));
        page.setStyle(UIStyles.CSS_ROOT);
        VBox.setVgrow(page, Priority.ALWAYS);

        page.getChildren().addAll(
                buildHeader(),
                buildFormatHelp(),
                buildEditor(),
                buildActionBar()
        );

        return page;
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private Node buildHeader() {
        String filename = Path.of(result.filepath()).getFileName().toString();

        Label title = new Label("Edit Lyrics — " + filename);
        title.setStyle(UIStyles.CSS_HEADING);
        title.setWrapText(true);

        Label sub = new Label(
                "Edit the LRC file below. Each line should be in the format [mm:ss.xx]Lyric text. " +
                        "The file will be saved alongside the audio file."
        );
        sub.setStyle(UIStyles.CSS_MUTED);
        sub.setWrapText(true);

        return new VBox(4, title, sub);
    }

    // ── Format help ───────────────────────────────────────────────────────────

    private Node buildFormatHelp() {
        Label help = new Label(
                "Format:  [01:23.45]Line of lyrics here\n" +
                        "Header:  [ti:Title]  [ar:Artist]  [al:Album]  [by:Tool]"
        );
        help.setStyle(
                "-fx-font-family: 'Courier New'; -fx-font-size: 11px;" +
                        "-fx-text-fill: " + UIStyles.TEXT_MUTED + ";" +
                        "-fx-background-color: " + UIStyles.ACCENT_LIGHT + ";" +
                        "-fx-padding: 8; -fx-background-radius: 4;"
        );
        help.setWrapText(true);
        return help;
    }

    // ── Editor ────────────────────────────────────────────────────────────────

    private Node buildEditor() {
        lrcTextArea = new TextArea();
        lrcTextArea.setStyle(
                "-fx-font-family: 'Courier New'; -fx-font-size: 12px;" +
                        "-fx-background-color: " + UIStyles.BG_CARD + ";" +
                        "-fx-border-color: " + UIStyles.BORDER + ";" +
                        "-fx-border-radius: 4;"
        );
        lrcTextArea.setWrapText(false);
        VBox.setVgrow(lrcTextArea, Priority.ALWAYS);

        // Load existing LRC content if the file exists
        Path lrcPath = getLrcPath();
        if (lrcPath != null && Files.exists(lrcPath)) {
            try {
                String content = Files.readString(lrcPath, StandardCharsets.UTF_8);
                lrcTextArea.setText(content);
            } catch (IOException e) {
                lrcTextArea.setText("# Could not read LRC file: " + e.getMessage());
            }
        } else {
            // No LRC file yet — generate a template from the track's lyrics if available
            lrcTextArea.setText(generateTemplate());
        }

        return lrcTextArea;
    }

    // ── Action bar ────────────────────────────────────────────────────────────

    private Node buildActionBar() {
        Button saveBtn = new Button("💾  Save LRC File");
        saveBtn.setStyle(UIStyles.CSS_BTN_PRIMARY);
        saveBtn.setOnAction(e -> saveLrc());

        Button backBtn = new Button("← Back to Track Details");
        backBtn.setStyle(UIStyles.CSS_BTN_SECONDARY);
        backBtn.setOnAction(e -> { if (onBack != null) onBack.run(); });



        HBox bar = new HBox(UIStyles.PAD_SM, saveBtn, backBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(UIStyles.PAD_SM, 0, 0, 0));
        return bar;
    }

    // ── Save logic ────────────────────────────────────────────────────────────

    private void saveLrc() {
        String content = lrcTextArea.getText();
        if (content == null || content.isBlank()) {
            showError("LRC content is empty — nothing to save.");
            return;
        }

        // Parse and validate before saving
        List<LrcLine> lines = parseLrcLines(content);

        Path lrcPath = getLrcPath();
        if (lrcPath == null) {
            showError("Could not determine LRC file path.");
            return;
        }

        try {
            // Read existing metadata for the header
            TrackMetadata meta = null;
            try {
                meta = FileInterface.scanMetadata(Path.of(result.filepath()));
            } catch (LyrifyException ignored) {}

            FileInterface.createLrcFile(Path.of(result.filepath()), lines, meta, lrcPath);
            showInfo("LRC file saved:\n" + lrcPath.getFileName().toString());
        } catch (LyrifyException e) {
            showError("Failed to save LRC file:\n" + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Returns the path where the .lrc file should live (same dir, same base name)
    private Path getLrcPath() {
        try {
            Path audioPath = Path.of(result.filepath());
            String name = audioPath.getFileName().toString();
            String base = name.contains(".")
                    ? name.substring(0, name.lastIndexOf('.'))
                    : name;
            return audioPath.getParent().resolve(base + ".lrc");
        } catch (Exception e) {
            return null;
        }
    }

    // Generates a template LRC from the track's lyrics with evenly spaced timestamps
    private String generateTemplate() {
        TrackMetadata meta = result.metadata();
        if (meta == null || meta.lyrics() == null || meta.lyrics().isBlank()) {
            return "# No lyrics available for this track.\n" +
                    "# Add lyrics below in the format:\n" +
                    "# [mm:ss.xx]Lyric text here\n";
        }

        double totalDur = meta.durationSeconds() != null ? meta.durationSeconds() : 180.0;
        String[] lines  = meta.lyrics().split("\n");
        double step     = totalDur / Math.max(lines.length, 1);

        StringBuilder sb = new StringBuilder();
        // LRC header
        if (meta.title()  != null) sb.append("[ti:").append(meta.title()).append("]\n");
        if (meta.artist() != null) sb.append("[ar:").append(meta.artist()).append("]\n");
        if (meta.album()  != null) sb.append("[al:").append(meta.album()).append("]\n");
        sb.append("[by:Lyrify]\n\n");

        // Timestamped lines
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isBlank()) continue;
            int totalMs  = (int)(i * step * 1000);
            int mins     = totalMs / 60000;
            int secs     = (totalMs % 60000) / 1000;
            int centis   = (totalMs % 1000) / 10;
            sb.append(String.format("[%02d:%02d.%02d]%s%n", mins, secs, centis, line));
        }

        return sb.toString();
    }

    // Parse LRC text back into LrcLine objects (skips header lines and comments)
    private List<LrcLine> parseLrcLines(String content) {
        List<LrcLine> result = new ArrayList<>();
        for (String line : content.lines().toList()) {
            line = line.strip();
            if (line.startsWith("#") || line.isBlank()) continue;
            // Match [mm:ss.xx]text
            if (line.matches("\\[\\d{2}:\\d{2}\\.\\d{2}].*")) {
                try {
                    int mins   = Integer.parseInt(line.substring(1, 3));
                    double sec = Double.parseDouble(line.substring(4, 9));
                    int ms     = (int)(mins * 60_000 + sec * 1000);
                    String text = line.substring(10).strip();
                    if (!text.isBlank()) result.add(new LrcLine(ms, text));
                } catch (Exception ignored) {}
            }
            // Skip header lines like [ti:...] [ar:...] etc.
        }
        return result;
    }

    private void showInfo(String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle("Lyrify"); a.setHeaderText(null); a.setContentText(msg);
        a.showAndWait();
    }

    private void showError(String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle("Lyrify"); a.setHeaderText(null); a.setContentText(msg);
        a.showAndWait();
    }
}