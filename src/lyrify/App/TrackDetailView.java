package lyrify.App;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import lyrify.FileHub.PipelineResult;
import lyrify.FileInterface.FileInterface;
import lyrify.FileInterface.LyrifyException;
import lyrify.FileInterface.TrackMetadata;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class TrackDetailView {

    private final AppState state;

    private java.util.function.Consumer<PipelineResult> onEditLyrics;

    // Text fields for each editable metadata field
    private TextField fTitle, fArtist, fAlbum, fYear, fGenre, fTrackNum;

    // Callback to navigate back to the list — set by MainView
    private Runnable onNavigateBack;



    public TrackDetailView(AppState state) {
        this.state = state;
    }

    public void setOnNavigateBack(Runnable callback) {
        this.onNavigateBack = callback;
    }

    public void setOnEditLyrics(java.util.function.Consumer<PipelineResult> callback) {
        this.onEditLyrics = callback;
    }

    public Node build() {
        PipelineResult result = state.getSelectedTrack();

        if (result == null) {
            Label msg = new Label("Select a track from the Results tab.");
            msg.setStyle(UIStyles.CSS_MUTED);
            VBox placeholder = new VBox(msg);
            placeholder.setAlignment(Pos.CENTER);
            placeholder.setStyle(UIStyles.CSS_ROOT);
            return placeholder;
        }

        VBox page = new VBox(UIStyles.PAD_MD);
        page.setPadding(new Insets(UIStyles.PAD_LG));
        page.setStyle(UIStyles.CSS_ROOT);

        page.getChildren().addAll(
                buildHeader(result),
                buildMetadataDiff(result),
                buildActionBar(result)
        );

        return page;
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private Node buildHeader(PipelineResult result) {
        String filename = Path.of(result.filepath()).getFileName().toString();

        Label title = new Label(filename);
        title.setStyle(UIStyles.CSS_HEADING);

        Label scoreBadge = new Label(
                UIStyles.scoreLabel(result.finalScore()) +
                        " — %.0f%%".formatted(result.finalScore() * 100)
        );
        scoreBadge.setStyle(UIStyles.scoreBadgeStyle(result.finalScore()));

        Label stageLabel = new Label("via " + result.stage().name());
        stageLabel.setStyle(UIStyles.CSS_MUTED);

        HBox row = new HBox(UIStyles.PAD_SM, title, scoreBadge, stageLabel);
        row.setAlignment(Pos.CENTER_LEFT);

        Label note = new Label(result.note());
        note.setStyle(UIStyles.CSS_MUTED);
        note.setWrapText(true);

        return new VBox(4, row, note);
    }

    // ── Metadata diff table ───────────────────────────────────────────────────

    private Node buildMetadataDiff(PipelineResult result) {
        VBox card = new VBox(0);
        card.setStyle(UIStyles.CSS_CARD);

        card.getChildren().add(buildDiffHeaderRow());

        // Read existing tags from the file
        TrackMetadata existing = null;
        try {
            existing = FileInterface.scanMetadata(Path.of(result.filepath()));
        } catch (LyrifyException ignored) {}

        TrackMetadata proposed = result.metadata();

        // Build the editable text fields
        fTitle    = new TextField(orEmpty(proposed != null ? proposed.title()       : null));
        fArtist   = new TextField(orEmpty(proposed != null ? proposed.artist()      : null));
        fAlbum    = new TextField(orEmpty(proposed != null ? proposed.album()       : null));
        fYear     = new TextField(orEmpty(proposed != null ? proposed.year()        : null));
        fGenre    = new TextField(orEmpty(proposed != null ? proposed.genre()       : null));
        fTrackNum = new TextField(orEmpty(proposed != null ? proposed.trackNumber() : null));

        // Field label, existing value, editable field
        String[][] rows = {
                {"Title",    existing != null ? existing.title()       : null,  proposed != null ? proposed.title()       : null},
                {"Artist",   existing != null ? existing.artist()      : null,  proposed != null ? proposed.artist()      : null},
                {"Album",    existing != null ? existing.album()       : null,  proposed != null ? proposed.album()       : null},
                {"Year",     existing != null ? existing.year()        : null,  proposed != null ? proposed.year()        : null},
                {"Genre",    existing != null ? existing.genre()       : null,  proposed != null ? proposed.genre()       : null},
                {"Track #",  existing != null ? existing.trackNumber() : null,  proposed != null ? proposed.trackNumber() : null},
        };
        TextField[] editFields = {fTitle, fArtist, fAlbum, fYear, fGenre, fTrackNum};

        for (int i = 0; i < rows.length; i++) {
            String fieldName   = rows[i][0];
            String currentVal  = rows[i][1];
            String proposedVal = rows[i][2];
            boolean changed    = !orEmpty(currentVal).equals(orEmpty(proposedVal));
            card.getChildren().add(buildDiffRow(fieldName, currentVal, editFields[i], changed));
        }

        return card;
    }

    private Node buildDiffHeaderRow() {
        Label field    = boldLabel("Field");
        Label current  = boldLabel("Current value");
        Label proposed = boldLabel("Proposed (editable)");

        field.setMinWidth(110);
        field.setPrefWidth(110);
        field.setMaxWidth(110);
        HBox.setHgrow(field, Priority.NEVER);

        HBox row = new HBox(UIStyles.PAD_MD, field, current, proposed);
        row.setPadding(new Insets(8, 12, 8, 12));
        row.setStyle(
                "-fx-background-color: " + UIStyles.ACCENT_LIGHT + ";" +
                        "-fx-border-color: " + UIStyles.BORDER + " transparent " + UIStyles.BORDER + " transparent;"
        );
        HBox.setHgrow(current, Priority.ALWAYS);
        HBox.setHgrow(proposed, Priority.ALWAYS);
        return row;
    }

    private Node buildDiffRow(String fieldName, String currentVal,
                              TextField editField, boolean changed) {
        // Field label — always clearly visible
        Label nameLabel = new Label(fieldName);
        nameLabel.setMinWidth(110);
        nameLabel.setPrefWidth(110);
        nameLabel.setMaxWidth(110);
        nameLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: " + UIStyles.TEXT_PRIMARY + ";");
        HBox.setHgrow(nameLabel, Priority.NEVER);

        // Current value — show "—" instead of blank so the column is never empty
        String display = (currentVal != null && !currentVal.isBlank()) ? currentVal : "—";
        Label currentLabel = new Label(display);
        currentLabel.setPrefWidth(999);
        currentLabel.setStyle(
                "-fx-font-size: 13px; -fx-text-fill: " +
                        (changed ? UIStyles.TEXT_PRIMARY : UIStyles.TEXT_MUTED) + ";"
        );

        editField.setStyle(UIStyles.CSS_FIELD);
        editField.setPrefWidth(999);

        HBox row = new HBox(UIStyles.PAD_MD, nameLabel, currentLabel, editField);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(7, 12, 7, 12));
        row.setStyle(
                "-fx-background-color: " + (changed ? "#FFFDE7" : UIStyles.BG_CARD) + ";" +
                        "-fx-border-color: transparent transparent " + UIStyles.BORDER + " transparent;"
        );

        HBox.setHgrow(currentLabel, Priority.ALWAYS);
        HBox.setHgrow(editField, Priority.ALWAYS);
        return row;
    }

    // ── Action bar ────────────────────────────────────────────────────────────

    private Node buildActionBar(PipelineResult result) {
        Button applyBtn = new Button("✓  Apply Changes");
        applyBtn.setStyle(UIStyles.CSS_BTN_PRIMARY);
        applyBtn.setOnAction(e -> applyChanges(result));

        Button skipBtn = new Button("✕  Skip — Keep Original");
        skipBtn.setStyle(UIStyles.CSS_BTN_DANGER);
        skipBtn.setOnAction(e -> navigateBack());

        Button lyricsBtn = new Button("♪  Edit Lyrics");
        lyricsBtn.setStyle(UIStyles.CSS_BTN_SECONDARY);
        lyricsBtn.setOnAction(e -> {
            if (onEditLyrics != null) onEditLyrics.accept(result);
        });

        // Info label so the user knows what clicking Apply will do
        Label info = new Label("Changes apply only to: " +
                Path.of(result.filepath()).getFileName().toString());
        info.setStyle(UIStyles.CSS_MUTED);
        info.setWrapText(true);

        VBox bar = new VBox(UIStyles.PAD_SM,
                info,
                new HBox(UIStyles.PAD_SM, applyBtn, skipBtn, lyricsBtn));
        bar.setPadding(new Insets(UIStyles.PAD_MD, 0, 0, 0));
        return bar;
    }

    // ── Apply logic ───────────────────────────────────────────────────────────

    private void applyChanges(PipelineResult result) {
        Map<String, String> updates = new LinkedHashMap<>();
        putIfNotBlank(updates, "title",       fTitle.getText());
        putIfNotBlank(updates, "artist",      fArtist.getText());
        putIfNotBlank(updates, "album",       fAlbum.getText());
        putIfNotBlank(updates, "year",        fYear.getText());
        putIfNotBlank(updates, "genre",       fGenre.getText());
        putIfNotBlank(updates, "trackNumber", fTrackNum.getText());

        if (updates.isEmpty()) {
            showInfo("No changes to apply.");
            return;
        }

        try {
            FileInterface.modifyMetadata(Path.of(result.filepath()), updates, null);
            showInfo("Metadata updated for:\n" +
                    Path.of(result.filepath()).getFileName().toString());
            navigateBack();
        } catch (LyrifyException e) {
            showError("Failed to write metadata:\n" + e.getMessage());
        }
    }

    private void navigateBack() {
        state.setSelectedTrack(null);
        if (onNavigateBack != null) onNavigateBack.run();
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static void putIfNotBlank(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value.strip());
    }

    private static String orEmpty(String s) { return s != null ? s : ""; }

    private static Label boldLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        return l;
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