package lyrify.App;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import lyrify.FileHub.AiClient;
import lyrify.FileHub.LyrifyManager;
import lyrify.APIinterface.*;
import lyrify.FileHub.PipelineResult;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScanView {

    private final AppState state;
    private final Stage    ownerStage;
    private Node cachedNode = null;

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "lyrify-scan");
                t.setDaemon(true);
                return t;
            });

    public ScanView(AppState state, Stage ownerStage) {
        this.state      = state;
        this.ownerStage = ownerStage;
    }

    // Build the scan screen once and cache it so navigating away and back
    // doesn't reset the directory field.
    public Node build() {
        if (cachedNode != null) return cachedNode;

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

        cachedNode = page;
        return cachedNode;
    }

    // ── Section builders ──────────────────────────────────────────────────────

    private Node buildHeading() {
        Label title = new Label("Scan Music Library");
        title.setStyle(UIStyles.CSS_HEADING);

        Label sub = new Label("Choose a folder and Lyrify will find and fix metadata for every audio file inside.");
        sub.setStyle(UIStyles.CSS_MUTED);
        sub.setWrapText(true);

        return new VBox(4, title, sub);
    }

    private Node buildDirectoryPicker() {
        VBox card = new VBox(UIStyles.PAD_SM);
        card.setStyle(UIStyles.CSS_CARD);

        Label label = new Label("Music folder");
        label.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        TextField pathField = new TextField();
        pathField.setStyle(UIStyles.CSS_FIELD);
        pathField.setPromptText("e.g. C:\\Users\\Me\\Music");
        pathField.setPrefWidth(999);

        // Keep AppState in sync when the user types a path manually
        pathField.textProperty().addListener(
                (obs, o, n) -> state.setSelectedDirectory(n)
        );
        // Update the field when AppState changes (e.g. after Browse)
        state.selectedDirectoryProperty().addListener(
                (obs, o, n) -> { if (!pathField.getText().equals(n)) pathField.setText(n); }
        );

        Button browseBtn = new Button("Browse…");
        browseBtn.setStyle(UIStyles.CSS_BTN_SECONDARY);
        browseBtn.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Select Music Folder");
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

        CheckBox recursive = new CheckBox("Include subdirectories");
        recursive.selectedProperty().bindBidirectional(state.recursiveProperty());

        CheckBox useCache = new CheckBox("Use cache (skip unchanged files)");
        useCache.selectedProperty().bindBidirectional(state.useCacheProperty());

        CheckBox backup = new CheckBox("Backup existing metadata before writing");
        backup.selectedProperty().bindBidirectional(state.backupBeforeScanProperty());

        card.getChildren().addAll(label, recursive, useCache, backup);
        return card;
    }

    private Node buildAiStatus() {
        HBox row = new HBox(UIStyles.PAD_SM);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle(UIStyles.CSS_CARD);

        Label titleLabel = new Label("AI Server status:");
        titleLabel.setStyle("-fx-font-weight: bold;");

        Label statusLabel = new Label("Checking…");
        statusLabel.setStyle("-fx-text-fill: " + UIStyles.TEXT_MUTED + ";");

        executor.submit(() -> {
            boolean up = new AiClient().isServerUp();
            Platform.runLater(() -> updateAiStatus(statusLabel, up));
        });

        Button recheck = new Button("Re-check");
        recheck.setStyle(UIStyles.CSS_BTN_SECONDARY);
        recheck.setOnAction(e -> {
            statusLabel.setText("Checking…");
            statusLabel.setStyle("-fx-text-fill: " + UIStyles.TEXT_MUTED + ";");
            executor.submit(() -> {
                boolean up = new AiClient().isServerUp();
                Platform.runLater(() -> updateAiStatus(statusLabel, up));
            });
        });

        row.getChildren().addAll(titleLabel, statusLabel, recheck);
        return row;
    }

    private void updateAiStatus(Label statusLabel, boolean up) {
        if (up) {
            statusLabel.setText("● Online — AI fallback available");
            statusLabel.setStyle("-fx-text-fill: " + UIStyles.SUCCESS + ";");
        } else {
            statusLabel.setText("● Offline — run ai/server.py to enable AI fallback");
            statusLabel.setStyle("-fx-text-fill: " + UIStyles.WARNING + ";");
        }
    }

    private Node buildScanButton() {
        Button scanBtn = new Button("Start Scan");
        scanBtn.setStyle(UIStyles.CSS_BTN_PRIMARY);
        scanBtn.setPrefWidth(160);
        scanBtn.disableProperty().bind(state.scanningProperty());
        scanBtn.setOnAction(e -> startScan());

        HBox row = new HBox(scanBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // ── Scan logic ────────────────────────────────────────────────────────────

    private void startScan() {
        String dir = state.getSelectedDirectory();
        if (dir.isBlank()) {
            showError("Please select a music folder first.");
            return;
        }

        // Read all AppState values on the FX thread BEFORE going to the background thread
        String  acoustIdKey  = state.acoustIdKeyProperty().get();
        String  geniusToken  = state.geniusTokenProperty().get();
        boolean isRecursive  = state.isRecursive();
        boolean useCache    = state.isUseCache();
        double  acceptT      = state.getAcceptThreshold();
        double  reviewT      = state.getReviewThreshold();
        double  aiT          = state.getAiThreshold();
        boolean doBackup = state.isBackupBeforeScan();

        state.getResults().clear();
        state.setScanning(true);
        state.setScanProgress(0.0);
        state.setStatusMessage("Building manager…");

        executor.submit(() -> {
            try {
                LyrifyManager manager = buildManager(
                        acoustIdKey, geniusToken,
                        acceptT, reviewT, aiT
                );

                Platform.runLater(() -> state.setStatusMessage("Scanning…"));

                List<PipelineResult> results = manager.processDirectory(dir, isRecursive, useCache, doBackup);

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

    private LyrifyManager buildManager(String acoustIdKey, String geniusToken,
                                       double acceptThreshold, double reviewThreshold,
                                       double aiThreshold) {
        ApiClient http = new ApiClient();

        AcoustIdClient acoustId = null;
        if (!acoustIdKey.isBlank()) {
            acoustId = new AcoustIdClient(http, acoustIdKey);
        }

        MusicBrainzClient musicBrainz = new MusicBrainzClient(http);

        GeniusClient genius = null;
        if (!geniusToken.isBlank()) {
            genius = new GeniusClient(http, geniusToken);
        }

        ApiAggregator.Builder aggBuilder = new ApiAggregator.Builder()
                .musicBrainz(musicBrainz);
        if (acoustId != null) aggBuilder.acoustId(acoustId);
        if (genius   != null) aggBuilder.genius(genius);

        ApiAggregator aggregator = aggBuilder.build();
        AiClient aiClient = new AiClient();

        return new LyrifyManager.Builder()
                .apiAggregator(aggregator)
                .aiClient(aiClient)
                .acceptThreshold(acceptThreshold)
                .reviewThreshold(reviewThreshold)
                .aiThreshold(aiThreshold)
                .build();
    }

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