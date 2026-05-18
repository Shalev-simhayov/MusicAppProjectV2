package lyrify.gui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import lyrify.AiClient;
import lyrify.LyrifyManager;
import lyrify.PipelineResult;
import lyrify.api.*;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// The first screen the user sees.
// Lets the user pick a music folder, configure scan options,
// then start the scan. Results are fed into AppState as they arrive.
public class ScanView {

    private final AppState state;
    private final Stage    ownerStage; // needed to open the directory chooser

    // Background thread pool — we run the scan off the JavaFX thread
    // so the UI stays responsive while files are being processed
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "lyrify-scan");
                t.setDaemon(true); // don't block JVM shutdown
                return t;
            });

    public ScanView(AppState state, Stage ownerStage) {
        this.state      = state;
        this.ownerStage = ownerStage;
    }

    // Builds and returns the full scan screen node.
    // Called every time the user navigates to this screen.
    public Node build() {
        VBox page = new VBox(UIStyles.PAD_LG);
        page.setPadding(new Insets(UIStyles.PAD_LG));
        page.setStyle(UIStyles.CSS_ROOT);

        page.getChildren().addAll(
                buildHeading(),
                buildDirectoryPicker(),
                buildOptions(),
                buildAiStatus(),
                buildScanButton()
        );

        return page;
    }

    // -- Section builders -----------------------------------------------------

    private Node buildHeading() {
        Label title = new Label("Scan Music Library");
        title.setStyle(UIStyles.CSS_HEADING);

        Label sub = new Label("Choose a folder and Lyrify will find and fix metadata for every audio file inside.");
        sub.setStyle(UIStyles.CSS_MUTED);
        sub.setWrapText(true);

        VBox box = new VBox(4, title, sub);
        return box;
    }

    private Node buildDirectoryPicker() {
        VBox card = new VBox(UIStyles.PAD_SM);
        card.setStyle(UIStyles.CSS_CARD);

        Label label = new Label("Music folder");
        label.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        // Text field shows the selected path; user can also type one directly
        TextField pathField = new TextField();
        pathField.setStyle(UIStyles.CSS_FIELD);
        pathField.setPromptText("e.g. /Users/me/Music");
        pathField.setPrefWidth(999); // fill available width
        // Keep AppState in sync when the user types a path manually
        pathField.textProperty().addListener(
                (obs, o, n) -> state.setSelectedDirectory(n)
        );
        // Also update the field when AppState changes (e.g. after picking a folder)
        state.selectedDirectoryProperty().addListener(
                (obs, o, n) -> { if (!pathField.getText().equals(n)) pathField.setText(n); }
        );

        // Browse button opens the OS directory chooser
        Button browseBtn = new Button("Browse…");
        browseBtn.setStyle(UIStyles.CSS_BTN_SECONDARY);
        browseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Music Folder");
            // Pre-open the last used directory if there is one
            if (!state.getSelectedDirectory().isBlank()) {
                File current = new File(state.getSelectedDirectory());
                if (current.isDirectory()) chooser.setInitialDirectory(current);
            }
            File selected = chooser.showDialog(ownerStage);
            if (selected != null) state.setSelectedDirectory(selected.getAbsolutePath());
        });

        HBox row = new HBox(UIStyles.PAD_SM, pathField, browseBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pathField, Priority.ALWAYS);

        card.getChildren().addAll(label, row);
        return card;
    }

    private Node buildOptions() {
        VBox card = new VBox(UIStyles.PAD_SM);
        card.setStyle(UIStyles.CSS_CARD);

        Label label = new Label("Scan options");
        label.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        // Checkboxes bind directly to AppState boolean properties
        CheckBox recursive = new CheckBox("Include subdirectories");
        recursive.selectedProperty().bindBidirectional(state.recursiveProperty());

        CheckBox useCache = new CheckBox("Use cache (skip unchanged files)");
        useCache.selectedProperty().bindBidirectional(state.useCacheProperty());

        CheckBox backup = new CheckBox("Backup existing metadata before writing");
        backup.selectedProperty().bindBidirectional(state.backupBeforeScanProperty());

        card.getChildren().addAll(label, recursive, useCache, backup);
        return card;
    }

    // Shows whether the local Python AI server is reachable
    private Node buildAiStatus() {
        HBox row = new HBox(UIStyles.PAD_SM);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle(UIStyles.CSS_CARD);

        Label titleLabel = new Label("AI Server status:");
        titleLabel.setStyle("-fx-font-weight: bold;");

        Label statusLabel = new Label("Checking…");
        statusLabel.setStyle("-fx-text-fill: " + UIStyles.TEXT_MUTED + ";");

        // Run the health check on a background thread so the UI doesn't freeze
        executor.submit(() -> {
            boolean up = new AiClient().isServerUp();
            // Always update UI on the JavaFX thread (Platform.runLater)
            Platform.runLater(() -> {
                if (up) {
                    statusLabel.setText("● Online — AI fallback available");
                    statusLabel.setStyle("-fx-text-fill: " + UIStyles.SUCCESS + ";");
                } else {
                    statusLabel.setText("● Offline — run ai/server.py to enable AI fallback");
                    statusLabel.setStyle("-fx-text-fill: " + UIStyles.WARNING + ";");
                }
            });
        });

        Button recheck = new Button("Re-check");
        recheck.setStyle(UIStyles.CSS_BTN_SECONDARY);
        recheck.setOnAction(e -> {
            statusLabel.setText("Checking…");
            statusLabel.setStyle("-fx-text-fill: " + UIStyles.TEXT_MUTED + ";");
            executor.submit(() -> {
                boolean up = new AiClient().isServerUp();
                Platform.runLater(() -> {
                    if (up) {
                        statusLabel.setText("● Online — AI fallback available");
                        statusLabel.setStyle("-fx-text-fill: " + UIStyles.SUCCESS + ";");
                    } else {
                        statusLabel.setText("● Offline — run ai/server.py to enable AI fallback");
                        statusLabel.setStyle("-fx-text-fill: " + UIStyles.WARNING + ";");
                    }
                });
            });
        });

        row.getChildren().addAll(titleLabel, statusLabel, recheck);
        return row;
    }

    private Node buildScanButton() {
        Button scanBtn = new Button("Start Scan");
        scanBtn.setStyle(UIStyles.CSS_BTN_PRIMARY);
        scanBtn.setPrefWidth(160);

        // Disable the button while a scan is already running
        scanBtn.disableProperty().bind(state.scanningProperty());

        scanBtn.setOnAction(e -> startScan());

        HBox row = new HBox(scanBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // -- Scan logic -----------------------------------------------------------

    // Builds the LyrifyManager from current AppState settings and runs the scan
    // on a background thread, updating AppState as results arrive.
    private void startScan() {
        String dir = state.getSelectedDirectory();
        if (dir.isBlank()) {
            showError("Please select a music folder first.");
            return;
        }

        // Clear previous results before starting
        state.getResults().clear();
        state.setScanning(true);
        state.setScanProgress(0.0);
        state.setStatusMessage("Building manager…");

        executor.submit(() -> {
            try {
                LyrifyManager manager = buildManager();

                // scanDirectory returns all results once complete — for large
                // libraries you'd want streaming; this is fine for most use cases
                Platform.runLater(() -> state.setStatusMessage("Scanning…"));
                List<PipelineResult> results = manager.processDirectory(
                        dir, state.isRecursive()
                );

                // Add results to ObservableList on the FX thread one at a time
                // so the table view animates as they arrive
                for (int i = 0; i < results.size(); i++) {
                    final int idx = i;
                    final PipelineResult r = results.get(i);
                    Platform.runLater(() -> {
                        state.getResults().add(r);
                        state.setScanProgress((double)(idx + 1) / results.size());
                        state.setStatusMessage(
                                "Processed %d / %d tracks".formatted(idx + 1, results.size())
                        );
                    });
                    // Small pause so the UI renders each row rather than batch-jumping
                    Thread.sleep(20);
                }

                Platform.runLater(() -> {
                    state.setScanning(false);
                    state.setScanProgress(1.0);
                    long ok  = results.stream().filter(PipelineResult::isUsable).count();
                    long bad = results.size() - ok;
                    state.setStatusMessage(
                            "Done — %d updated, %d no match".formatted(ok, bad)
                    );
                });

            } catch (Exception ex) {
                Platform.runLater(() -> {
                    state.setScanning(false);
                    state.setStatusMessage("Error: " + ex.getMessage());
                    showError("Scan failed: " + ex.getMessage());
                });
            }
        });
    }

    // Builds the LyrifyManager using current AppState settings.
    // Every API client is optional — we only add ones whose keys are filled in.
    private LyrifyManager buildManager() {
        ApiClient http = new ApiClient();

        // AcoustID — only needs an API key
        AcoustIdClient acoustId = null;
        if (!state.acoustIdKeyProperty().get().isBlank()) {
            acoustId = new AcoustIdClient(http, state.acoustIdKeyProperty().get());
        }

        // MusicBrainz — no key required, always on
        MusicBrainzClient musicBrainz = new MusicBrainzClient(http);

        // Spotify — needs both client ID and secret
        SpotifyClient spotify = null;
        if (!state.spotifyClientIdProperty().get().isBlank()
                && !state.spotifySecretProperty().get().isBlank()) {
            spotify = new SpotifyClient(http,
                    state.spotifyClientIdProperty().get(),
                    state.spotifySecretProperty().get());
        }

        // Genius — needs a bearer token
        GeniusClient genius = null;
        if (!state.geniusTokenProperty().get().isBlank()) {
            genius = new GeniusClient(http, state.geniusTokenProperty().get());
        }

        ApiAggregator.Builder aggBuilder = new ApiAggregator.Builder()
                .musicBrainz(musicBrainz);
        if (acoustId  != null) aggBuilder.acoustId(acoustId);
        if (spotify   != null) aggBuilder.spotify(spotify);
        if (genius    != null) aggBuilder.genius(genius);

        ApiAggregator aggregator = aggBuilder.build();

        // AI client — always created; isServerUp() is checked inside LyrifyManager
        AiClient aiClient = new AiClient();

        return new LyrifyManager.Builder()
                .apiAggregator(aggregator)
                .aiClient(aiClient)
                .acceptThreshold(state.getAcceptThreshold())
                .reviewThreshold(state.getReviewThreshold())
                .aiThreshold(state.getAiThreshold())
                .build();
    }

    // Shows a simple error dialog on the FX thread
    private void showError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Lyrify");
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
}
