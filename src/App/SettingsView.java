package lyrify.gui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

// Settings screen — lets the user enter API keys and tune score thresholds.
// All fields bind directly to AppState properties so changes take effect
// immediately without a Save button (the manager is rebuilt on each scan).
public class SettingsView {

    private final AppState state;

    public SettingsView(AppState state) {
        this.state = state;
    }

    public Node build() {
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: " + UIStyles.BG_MAIN + ";");

        VBox page = new VBox(UIStyles.PAD_LG);
        page.setPadding(new Insets(UIStyles.PAD_LG));
        page.setStyle(UIStyles.CSS_ROOT);

        page.getChildren().addAll(
                buildHeading(),
                buildApiKeysSection(),
                buildThresholdsSection(),
                buildScanOptionsSection()
        );

        scroll.setContent(page);
        return scroll;
    }

    // -- Section builders -----------------------------------------------------

    private Node buildHeading() {
        Label title = new Label("Settings");
        title.setStyle(UIStyles.CSS_HEADING);

        Label sub = new Label("API keys and thresholds are used on the next scan. Nothing is saved to disk yet — add a config file in a future update.");
        sub.setStyle(UIStyles.CSS_MUTED);
        sub.setWrapText(true);

        return new VBox(4, title, sub);
    }

    private Node buildApiKeysSection() {
        VBox card = new VBox(UIStyles.PAD_MD);
        card.setStyle(UIStyles.CSS_CARD);

        Label heading = new Label("API Keys");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: " + UIStyles.ACCENT + ";");

        // MusicBrainz doesn't need a key — always enabled
        Label mbNote = new Label("MusicBrainz — no key required, always enabled.");
        mbNote.setStyle(UIStyles.CSS_MUTED);

        // AcoustID key
        Node acoustIdRow = apiKeyRow(
                "AcoustID API Key",
                "Register free at acoustid.org",
                state.acoustIdKeyProperty(),
                false // not a password field — keys are long strings not secrets
        );

        // Spotify — two fields
        Node spotifyIdRow = apiKeyRow(
                "Spotify Client ID",
                "From developer.spotify.com/dashboard",
                state.spotifyClientIdProperty(),
                false
        );
        Node spotifySecretRow = apiKeyRow(
                "Spotify Client Secret",
                "Keep this private",
                state.spotifySecretProperty(),
                true // hide like a password
        );

        // Genius token
        Node geniusRow = apiKeyRow(
                "Genius Access Token",
                "From genius.com/api-clients",
                state.geniusTokenProperty(),
                true
        );

        card.getChildren().addAll(
                heading, mbNote,
                new Separator(),
                acoustIdRow, spotifyIdRow, spotifySecretRow, geniusRow
        );
        return card;
    }

    private Node buildThresholdsSection() {
        VBox card = new VBox(UIStyles.PAD_MD);
        card.setStyle(UIStyles.CSS_CARD);

        Label heading = new Label("Score Thresholds");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: " + UIStyles.ACCENT + ";");

        Label desc = new Label(
            "Accept: auto-write above this score.\n" +
            "Review: ask for confirmation between Review and Accept thresholds.\n" +
            "AI Trigger: send to Demucs+Whisper below this score."
        );
        desc.setStyle(UIStyles.CSS_MUTED);
        desc.setWrapText(true);

        Node acceptRow = thresholdRow("Accept Threshold",   state.acceptThresholdProperty(), UIStyles.SUCCESS);
        Node reviewRow = thresholdRow("Review Threshold",   state.reviewThresholdProperty(), UIStyles.WARNING);
        Node aiRow     = thresholdRow("AI Trigger",         state.aiThresholdProperty(),     UIStyles.DANGER);

        card.getChildren().addAll(heading, desc, acceptRow, reviewRow, aiRow);
        return card;
    }

    private Node buildScanOptionsSection() {
        VBox card = new VBox(UIStyles.PAD_SM);
        card.setStyle(UIStyles.CSS_CARD);

        Label heading = new Label("Scan Options");
        heading.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: " + UIStyles.ACCENT + ";");

        CheckBox recursive = new CheckBox("Scan subdirectories recursively");
        recursive.selectedProperty().bindBidirectional(state.recursiveProperty());

        CheckBox cache = new CheckBox("Use file cache (skip unchanged files on re-scan)");
        cache.selectedProperty().bindBidirectional(state.useCacheProperty());

        CheckBox backup = new CheckBox("Backup original metadata before any writes");
        backup.selectedProperty().bindBidirectional(state.backupBeforeScanProperty());

        card.getChildren().addAll(heading, recursive, cache, backup);
        return card;
    }

    // -- Row builders ---------------------------------------------------------

    // One labelled API key input row
    private Node apiKeyRow(String label, String hint, javafx.beans.property.StringProperty prop,
                            boolean isPassword) {
        Label nameLabel = new Label(label);
        nameLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");
        nameLabel.setPrefWidth(180);

        // Use a PasswordField to hide secrets; plain TextField otherwise
        if (isPassword) {
            PasswordField field = new PasswordField();
            field.setStyle(UIStyles.CSS_FIELD);
            field.setPromptText(hint);
            field.setPrefWidth(999);
            field.textProperty().bindBidirectional(prop);
            HBox row = new HBox(UIStyles.PAD_SM, nameLabel, field);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(field, Priority.ALWAYS);
            return row;
        } else {
            TextField field = new TextField();
            field.setStyle(UIStyles.CSS_FIELD);
            field.setPromptText(hint);
            field.setPrefWidth(999);
            field.textProperty().bindBidirectional(prop);
            HBox row = new HBox(UIStyles.PAD_SM, nameLabel, field);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(field, Priority.ALWAYS);
            return row;
        }
    }

    // One threshold slider row with a live percentage label
    private Node thresholdRow(String label, javafx.beans.property.DoubleProperty prop, String color) {
        Label nameLabel = new Label(label);
        nameLabel.setStyle("-fx-font-size: 13px;");
        nameLabel.setPrefWidth(160);

        // Slider range 0–100 (we store as 0.0–1.0 in AppState, so multiply/divide by 100)
        Slider slider = new Slider(0, 100, prop.get() * 100);
        slider.setPrefWidth(300);
        slider.setBlockIncrement(5); // arrow keys move by 5%

        // Value label shows e.g. "85%" next to the slider
        Label valueLabel = new Label("%.0f%%".formatted(prop.get() * 100));
        valueLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + color + "; -fx-min-width: 40;");

        // Keep slider ↔ AppState in sync
        slider.valueProperty().addListener((obs, o, n) -> {
            prop.set(n.doubleValue() / 100.0);
            valueLabel.setText("%.0f%%".formatted(n.doubleValue()));
        });
        prop.addListener((obs, o, n) -> {
            if (Math.abs(slider.getValue() - n.doubleValue() * 100) > 0.5)
                slider.setValue(n.doubleValue() * 100);
        });

        HBox row = new HBox(UIStyles.PAD_SM, nameLabel, slider, valueLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }
}
