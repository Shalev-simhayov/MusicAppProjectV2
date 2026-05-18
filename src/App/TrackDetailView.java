package lyrify.gui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import lyrify.FileInterface;
import lyrify.LyrifyException;
import lyrify.PipelineResult;
import lyrify.TrackMetadata;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

// Detail screen for a single track.
// Shows the existing metadata vs the proposed metadata side by side,
// with colour-coded rows for changed fields.
// The user can approve (write to file), reject, or edit fields manually.
public class TrackDetailView {

    private final AppState state;

    // Text fields for each editable metadata field — kept as instance
    // fields so we can read their values when the user clicks Apply
    private TextField fTitle, fArtist, fAlbum, fYear, fGenre, fTrackNum;

    public TrackDetailView(AppState state) {
        this.state = state;
    }

    public Node build() {
        PipelineResult result = state.getSelectedTrack();

        // If nothing is selected (e.g. navigated here directly), show a placeholder
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

    // -- Section builders -----------------------------------------------------

    // Shows filename + confidence badge at the top
    private Node buildHeader(PipelineResult result) {
        String filename = Path.of(result.filepath()).getFileName().toString();

        Label title = new Label(filename);
        title.setStyle(UIStyles.CSS_HEADING);

        // Score badge
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

    // Two-column comparison table: field | current value | proposed value
    // Changed fields are highlighted; unchanged ones are shown in muted grey
    private Node buildMetadataDiff(PipelineResult result) {
        VBox card = new VBox(0);
        card.setStyle(UIStyles.CSS_CARD);

        // Header row
        card.getChildren().add(buildDiffHeaderRow());

        // Existing tags (what's currently in the file)
        TrackMetadata existing = null;
        try {
            existing = FileInterface.scanMetadata(Path.of(result.filepath()));
        } catch (LyrifyException e) {
            // If we can't read the file, just show empty existing values
        }

        TrackMetadata proposed = result.metadata();

        // Build editable rows for each metadata field
        // LinkedHashMap preserves insertion order so rows appear in a sensible sequence
        Map<String, String[]> fields = new LinkedHashMap<>();
        fields.put("Title",        new String[]{
                existing != null ? existing.title()       : null,
                proposed != null ? proposed.title()       : null });
        fields.put("Artist",       new String[]{
                existing != null ? existing.artist()      : null,
                proposed != null ? proposed.artist()      : null });
        fields.put("Album",        new String[]{
                existing != null ? existing.album()       : null,
                proposed != null ? proposed.album()       : null });
        fields.put("Year",         new String[]{
                existing != null ? existing.year()        : null,
                proposed != null ? proposed.year()        : null });
        fields.put("Genre",        new String[]{
                existing != null ? existing.genre()       : null,
                proposed != null ? proposed.genre()       : null });
        fields.put("Track #",      new String[]{
                existing != null ? existing.trackNumber() : null,
                proposed != null ? proposed.trackNumber() : null });

        // Store editable text fields so buildActionBar can read them
        fTitle    = new TextField(orEmpty(proposed != null ? proposed.title()       : null));
        fArtist   = new TextField(orEmpty(proposed != null ? proposed.artist()      : null));
        fAlbum    = new TextField(orEmpty(proposed != null ? proposed.album()       : null));
        fYear     = new TextField(orEmpty(proposed != null ? proposed.year()        : null));
        fGenre    = new TextField(orEmpty(proposed != null ? proposed.genre()       : null));
        fTrackNum = new TextField(orEmpty(proposed != null ? proposed.trackNumber() : null));

        TextField[] editFields = {fTitle, fArtist, fAlbum, fYear, fGenre, fTrackNum};
        int i = 0;
        for (var entry : fields.entrySet()) {
            String currentVal  = entry.getValue()[0];
            String proposedVal = entry.getValue()[1];
            boolean changed    = !orEmpty(currentVal).equals(orEmpty(proposedVal));
            card.getChildren().add(
                    buildDiffRow(entry.getKey(), currentVal, editFields[i], changed)
            );
            i++;
        }

        return card;
    }

    // Column header row
    private Node buildDiffHeaderRow() {
        Label field    = boldLabel("Field");
        Label current  = boldLabel("Current");
        Label proposed = boldLabel("Proposed (editable)");

        HBox row = new HBox();
        HBox.setHgrow(field,    Priority.NEVER);
        HBox.setHgrow(current,  Priority.ALWAYS);
        HBox.setHgrow(proposed, Priority.ALWAYS);
        field.setPrefWidth(110);
        current.setPrefWidth(999);
        proposed.setPrefWidth(999);

        row.getChildren().addAll(field, current, proposed);
        row.setPadding(new Insets(8, 12, 8, 12));
        row.setStyle(
            "-fx-background-color: " + UIStyles.ACCENT_LIGHT + ";" +
            "-fx-border-color: " + UIStyles.BORDER + " transparent " + UIStyles.BORDER + " transparent;"
        );
        return row;
    }

    // One row in the diff table: field label | current value | editable proposed field
    private Node buildDiffRow(String fieldName, String currentVal,
                               TextField editField, boolean changed) {
        Label nameLabel    = new Label(fieldName);
        nameLabel.setPrefWidth(110);
        nameLabel.setStyle("-fx-text-fill: " + UIStyles.TEXT_MUTED + "; -fx-font-size: 12px;");

        Label currentLabel = new Label(orEmpty(currentVal));
        currentLabel.setPrefWidth(999);
        currentLabel.setStyle(
            "-fx-font-size: 13px; -fx-text-fill: " +
            (changed ? UIStyles.TEXT_PRIMARY : UIStyles.TEXT_MUTED) + ";"
        );

        editField.setStyle(UIStyles.CSS_FIELD);
        editField.setPrefWidth(999);
        HBox.setHgrow(editField, Priority.ALWAYS);

        HBox row = new HBox(UIStyles.PAD_SM, nameLabel, currentLabel, editField);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(7, 12, 7, 12));

        // Highlight changed rows in a pale yellow to draw attention
        String bg = changed ? "#FFFDE7" : UIStyles.BG_CARD;
        row.setStyle(
            "-fx-background-color: " + bg + ";" +
            "-fx-border-color: transparent transparent " + UIStyles.BORDER + " transparent;"
        );

        HBox.setHgrow(currentLabel, Priority.ALWAYS);
        return row;
    }

    // Apply / Reject buttons
    private Node buildActionBar(PipelineResult result) {
        Button applyBtn = new Button("✓  Apply Changes");
        applyBtn.setStyle(UIStyles.CSS_BTN_PRIMARY);
        applyBtn.setOnAction(e -> applyChanges(result));

        Button rejectBtn = new Button("✕  Skip This Track");
        rejectBtn.setStyle(UIStyles.CSS_BTN_DANGER);
        rejectBtn.setOnAction(e -> {
            // Just go back to the list without writing anything
            state.setSelectedTrack(null);
        });

        HBox bar = new HBox(UIStyles.PAD_SM, applyBtn, rejectBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(UIStyles.PAD_MD, 0, 0, 0));
        return bar;
    }

    // -- Apply logic ----------------------------------------------------------

    // Reads the editable text fields and writes them back to the audio file
    private void applyChanges(PipelineResult result) {
        Map<String, String> updates = new LinkedHashMap<>();

        // Only include fields that are non-blank — don't overwrite a good tag with empty
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
            showInfo("Metadata updated for:\n" + Path.of(result.filepath()).getFileName());
            // Go back to the results list after a successful write
            state.setSelectedTrack(null);
        } catch (LyrifyException e) {
            showError("Failed to write metadata:\n" + e.getMessage());
        }
    }

    // -- Utilities ------------------------------------------------------------

    private static void putIfNotBlank(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value.strip());
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }

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
