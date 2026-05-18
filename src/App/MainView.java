package lyrify.gui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.layout.*;

// Owns the navigation bar at the top and the status bar at the bottom.
// Also handles switching the centre panel when the user clicks a nav button —
// it calls root.setCenter() with the appropriate view's node.
public class MainView {

    private final AppState       state;
    private final BorderPane     root;          // the window's root layout
    private final ScanView       scanView;
    private final TrackListView  listView;
    private final TrackDetailView detailView;
    private final SettingsView   settingsView;

    // Keep a reference to each nav button so we can highlight the active one
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

    // -- Navigation bar -------------------------------------------------------

    public Node buildNavBar() {
        // App logo / name on the left
        Label logo = new Label("🎵 Lyrify");
        logo.setStyle(
            "-fx-font-size: 18px; -fx-font-weight: bold;" +
            "-fx-text-fill: white; -fx-padding: 0 24 0 0;"
        );

        // Nav buttons — each switches the centre panel to a different view
        btnScan     = navButton("Scan",     true);   // active by default
        btnTracks   = navButton("Results",  false);
        btnSettings = navButton("Settings", false);

        btnScan.setOnAction(e -> switchTo(scanView.build(), btnScan));

        // Results view shows the track list; clicking a row also opens detail
        btnTracks.setOnAction(e -> switchTo(listView.build(), btnTracks));

        btnSettings.setOnAction(e -> switchTo(settingsView.build(), btnSettings));

        // When a track is selected in the list, auto-navigate to the detail view
        state.selectedTrackProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                // Rebuild detail view with the newly selected track, then show it
                switchTo(detailView.build(), null);
            }
        });

        HBox leftSection  = new HBox(logo, new Separator(), btnScan, btnTracks);
        leftSection.setAlignment(Pos.CENTER_LEFT);
        leftSection.setSpacing(4);

        HBox rightSection = new HBox(btnSettings);
        rightSection.setAlignment(Pos.CENTER_RIGHT);

        // HBox grows to fill the full width — right section is pushed to the edge
        HBox navBar = new HBox();
        navBar.setAlignment(Pos.CENTER_LEFT);
        navBar.setSpacing(0);
        navBar.setPadding(new Insets(0, 16, 0, 16));
        navBar.setStyle("-fx-background-color: " + UIStyles.ACCENT + "; -fx-min-height: 52;");

        HBox.setHgrow(leftSection, Priority.ALWAYS);
        navBar.getChildren().addAll(leftSection, rightSection);

        return navBar;
    }

    // Switches the centre panel to the given node and marks the active button
    private void switchTo(Node view, Button activeBtn) {
        root.setCenter(view);

        // Reset all nav buttons to inactive style, then highlight the active one
        for (Button btn : new Button[]{btnScan, btnTracks, btnSettings}) {
            btn.setStyle(navButtonStyle(false));
        }
        if (activeBtn != null) {
            activeBtn.setStyle(navButtonStyle(true));
        }
    }

    // Creates a nav bar button with the given label and initial active state
    private Button navButton(String label, boolean active) {
        Button btn = new Button(label);
        btn.setStyle(navButtonStyle(active));
        // Remove the default JavaFX focus ring — looks bad on dark navbars
        btn.setFocusTraversable(false);
        return btn;
    }

    // Returns the CSS string for a nav button (active = highlighted, inactive = plain)
    private String navButtonStyle(boolean active) {
        String bg    = active ? "rgba(255,255,255,0.2)" : "transparent";
        String hover = "rgba(255,255,255,0.1)";
        return "-fx-background-color: " + bg + ";" +
               "-fx-text-fill: white;" +
               "-fx-font-size: 13px;" +
               "-fx-background-radius: 5;" +
               "-fx-padding: 8 16 8 16;" +
               "-fx-cursor: hand;" +
               "-fx-border-color: transparent;";
    }

    // -- Status bar -----------------------------------------------------------

    public Node buildStatusBar() {
        // Status message (left side)
        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: " + UIStyles.TEXT_MUTED + ";");
        // Bind directly to AppState so it updates whenever state.statusMessage changes
        statusLabel.textProperty().bind(state.statusMessageProperty());

        // Progress bar (right side) — only visible while a scan is running
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setStyle("-fx-pref-width: 180; -fx-pref-height: 10;");
        progressBar.progressProperty().bind(state.scanProgressProperty());
        // Hide when not scanning so it doesn't sit there at 0% looking broken
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
}
