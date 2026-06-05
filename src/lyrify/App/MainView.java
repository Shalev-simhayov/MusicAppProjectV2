package lyrify.App;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.layout.*;

public class MainView {

    private final AppState        state;
    private final BorderPane      root;
    private final ScanView        scanView;
    private final TrackListView   listView;
    private final TrackDetailView detailView;
    private final SettingsView    settingsView;

    private Button btnScan, btnTracks, btnSettings;

    public MainView(AppState state, BorderPane root,
                    ScanView scanView, TrackListView listView,
                    TrackDetailView detailView, SettingsView settingsView) {
        this.state        = state;
        this.root         = root;
        this.scanView     = scanView;
        this.listView     = listView;
        this.detailView   = detailView;
        this.settingsView = settingsView;
    }

    // ── Navigation bar ────────────────────────────────────────────────────────

    public Node buildNavBar() {
        Label logo = new Label("🎵 Lyrify");
        logo.setStyle(
                "-fx-font-size: 18px; -fx-font-weight: bold;" +
                        "-fx-text-fill: white; -fx-padding: 0 24 0 0;"
        );

        btnScan     = navButton("Scan",     true);
        btnTracks   = navButton("Results",  false);
        btnSettings = navButton("Settings", false);

        btnScan.setOnAction(e     -> switchTo(scanView.build(),     btnScan));
        btnTracks.setOnAction(e   -> switchTo(listView.build(),     btnTracks));
        btnSettings.setOnAction(e -> switchTo(settingsView.build(), btnSettings));

        // Wire up TrackDetailView callbacks BEFORE attaching the listener
        detailView.setOnNavigateBack(() ->
                switchTo(listView.build(), btnTracks)
        );

        detailView.setOnEditLyrics(track -> {
            LrcEditorView editor = new LrcEditorView(state, track);
            editor.setOnBack(() -> switchTo(detailView.build(), null));
            switchTo(editor.build(), null);
        });

        // When a track is selected, navigate to the detail view
        state.selectedTrackProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                switchTo(detailView.build(), null);
            }
        });

        HBox leftSection = new HBox(logo, new Separator(), btnScan, btnTracks);
        leftSection.setAlignment(Pos.CENTER_LEFT);
        leftSection.setSpacing(4);

        HBox rightSection = new HBox(btnSettings);
        rightSection.setAlignment(Pos.CENTER_RIGHT);

        HBox navBar = new HBox();
        navBar.setAlignment(Pos.CENTER_LEFT);
        navBar.setSpacing(0);
        navBar.setPadding(new Insets(0, 16, 0, 16));
        navBar.setStyle("-fx-background-color: " + UIStyles.ACCENT + "; -fx-min-height: 52;");
        HBox.setHgrow(leftSection, Priority.ALWAYS);
        navBar.getChildren().addAll(leftSection, rightSection);

        return navBar;
    }

    // ── Status bar ────────────────────────────────────────────────────────────

    public Node buildStatusBar() {
        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + UIStyles.TEXT_MUTED + ";");
        statusLabel.textProperty().bind(state.statusMessageProperty());

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setStyle("-fx-pref-width: 180; -fx-pref-height: 10;");
        progressBar.progressProperty().bind(state.scanProgressProperty());
        progressBar.visibleProperty().bind(state.scanningProperty());

        HBox statusBar = new HBox(statusLabel, progressBar);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setSpacing(16);
        statusBar.setPadding(new Insets(6, 16, 6, 16));
        statusBar.setStyle(
                "-fx-background-color: " + UIStyles.BG_CARD + ";" +
                        "-fx-border-color: " + UIStyles.BORDER + " transparent transparent transparent;"
        );
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        return statusBar;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void switchTo(Node view, Button activeBtn) {
        root.setCenter(view);
        for (Button btn : new Button[]{btnScan, btnTracks, btnSettings}) {
            btn.setStyle(navButtonStyle(false));
        }
        if (activeBtn != null) activeBtn.setStyle(navButtonStyle(true));
    }

    private Button navButton(String label, boolean active) {
        Button btn = new Button(label);
        btn.setStyle(navButtonStyle(active));
        btn.setFocusTraversable(false);
        return btn;
    }

    private String navButtonStyle(boolean active) {
        String bg = active ? "rgba(255,255,255,0.2)" : "transparent";
        return "-fx-background-color: " + bg + ";" +
                "-fx-text-fill: white;" +
                "-fx-font-size: 13px;" +
                "-fx-background-radius: 5;" +
                "-fx-padding: 8 16 8 16;" +
                "-fx-cursor: hand;" +
                "-fx-border-color: transparent;";
    }
}