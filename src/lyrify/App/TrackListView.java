package lyrify.App;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import lyrify.FileHub.PipelineResult;

import java.nio.file.Path;

// Shows all scanned tracks in a sortable table.
// Clicking a row sets AppState.selectedTrack, which causes MainView
// to automatically navigate to TrackDetailView.
public class TrackListView {

    private final AppState state;

    public TrackListView(AppState state) {
        this.state = state;
    }

    public Node build() {
        VBox page = new VBox(UIStyles.PAD_MD);
        page.setPadding(new Insets(UIStyles.PAD_LG));
        page.setStyle(UIStyles.CSS_ROOT);

        page.getChildren().addAll(
                buildHeading(),
                buildSummaryBar(),
                buildTable()
        );

        VBox.setVgrow(buildTable(), Priority.ALWAYS);
        return page;
    }

    private Node buildHeading() {
        Label title = new Label("Scan Results");
        title.setStyle(UIStyles.CSS_HEADING);

        Label sub = new Label("Click any row to review and edit metadata.");
        sub.setStyle(UIStyles.CSS_MUTED);

        return new VBox(4, title, sub);
    }

    // Three summary counters: total, updated, needs review
    private Node buildSummaryBar() {
        HBox bar = new HBox(UIStyles.PAD_MD);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle(UIStyles.CSS_CARD);

        Label totalLbl   = summaryChip("Total",       "0", UIStyles.ACCENT);
        Label updatedLbl = summaryChip("Updated",     "0", UIStyles.SUCCESS);
        Label reviewLbl  = summaryChip("Needs Review","0", UIStyles.WARNING);
        Label failedLbl  = summaryChip("No Match",    "0", UIStyles.DANGER);

        // Recalculate counters whenever the results list changes
        state.getResults().addListener((javafx.collections.ListChangeListener<PipelineResult>) c -> {
            long total   = state.getResults().size();
            long updated = state.getResults().stream().filter(r -> r.written()).count();
            long review  = state.getResults().stream().filter(r -> r.needsReview()).count();
            long failed  = state.getResults().stream()
                    .filter(r -> r.stage() == PipelineResult.Stage.NONE).count();

            // These lambdas run on the FX thread because list changes come from Platform.runLater
            ((Label)totalLbl.getUserData()).setText(String.valueOf(total));
            ((Label)updatedLbl.getUserData()).setText(String.valueOf(updated));
            ((Label)reviewLbl.getUserData()).setText(String.valueOf(review));
            ((Label)failedLbl.getUserData()).setText(String.valueOf(failed));
        });

        bar.getChildren().addAll(totalLbl, updatedLbl, reviewLbl, failedLbl);
        return bar;
    }

    // Creates a small labelled counter chip
    private Label summaryChip(String label, String value, String color) {
        Label valueLabel = new Label(value);
        valueLabel.setStyle(
            "-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: " + color + ";"
        );
        Label textLabel = new Label(label);
        textLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + UIStyles.TEXT_MUTED + ";");

        VBox chip = new VBox(2, valueLabel, textLabel);
        chip.setAlignment(Pos.CENTER);
        chip.setPadding(new Insets(8, 16, 8, 16));
        chip.setStyle(
            "-fx-background-color: " + color + "11;" + // 11 = ~7% alpha
            "-fx-background-radius: 8;"
        );

        // Store the value label reference in userData so the listener above can update it
        Label wrapper = new Label();
        wrapper.setGraphic(chip);
        wrapper.setUserData(valueLabel);
        return wrapper;
    }

    @SuppressWarnings("unchecked")
    private Node buildTable() {
        TableView<PipelineResult> table = new TableView<>();
        table.setStyle("-fx-background-color: " + UIStyles.BG_CARD + ";");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        // Placeholder shown when there are no results yet
        table.setPlaceholder(new Label("No results yet — run a scan first."));

        // Bind the table's items directly to the AppState observable list.
        // The table automatically refreshes when items are added/removed.
        table.setItems(state.getResults());

        // -- Column: filename --
        TableColumn<PipelineResult, String> fileCol = new TableColumn<>("File");
        fileCol.setPrefWidth(220);
        // Extract just the filename (not the full path) for readability
        fileCol.setCellValueFactory(row ->
                new javafx.beans.property.SimpleStringProperty(
                        Path.of(row.getValue().filepath()).getFileName().toString()
                )
        );

        // -- Column: title --
        TableColumn<PipelineResult, String> titleCol = new TableColumn<>("Title");
        titleCol.setPrefWidth(200);
        titleCol.setCellValueFactory(row ->
                new javafx.beans.property.SimpleStringProperty(
                        row.getValue().metadata() != null
                                ? row.getValue().metadata().title()
                                : "—"
                )
        );

        // -- Column: artist --
        TableColumn<PipelineResult, String> artistCol = new TableColumn<>("Artist");
        artistCol.setPrefWidth(160);
        artistCol.setCellValueFactory(row ->
                new javafx.beans.property.SimpleStringProperty(
                        row.getValue().metadata() != null
                                ? row.getValue().metadata().artist()
                                : "—"
                )
        );

        // -- Column: source (API/AI/NONE) --
        TableColumn<PipelineResult, String> sourceCol = new TableColumn<>("Source");
        sourceCol.setPrefWidth(80);
        sourceCol.setCellValueFactory(row ->
                new javafx.beans.property.SimpleStringProperty(
                        row.getValue().stage().name()
                )
        );

        // -- Column: score with colour badge --
        TableColumn<PipelineResult, Double> scoreCol = new TableColumn<>("Score");
        scoreCol.setPrefWidth(90);
        scoreCol.setCellValueFactory(row ->
                new javafx.beans.property.SimpleObjectProperty<>(row.getValue().finalScore())
        );
        // Custom cell renderer draws a coloured badge instead of a plain number
        scoreCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Double score, boolean empty) {
                super.updateItem(score, empty);
                if (empty || score == null) {
                    setText(null); setGraphic(null);
                } else {
                    Label badge = new Label("%.0f%%".formatted(score * 100));
                    badge.setStyle(UIStyles.scoreBadgeStyle(score));
                    setGraphic(badge);
                    setText(null);
                }
            }
        });

        // -- Column: status --
        TableColumn<PipelineResult, String> statusCol = new TableColumn<>("Status");
        statusCol.setPrefWidth(120);
        statusCol.setCellValueFactory(row ->
                new javafx.beans.property.SimpleStringProperty(
                        row.getValue().needsReview() ? "Needs Review"
                      : row.getValue().written()     ? "Updated"
                      : row.getValue().stage() == PipelineResult.Stage.NONE ? "No Match"
                      : "Pending"
                )
        );
        // Colour-code the status text
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) { setText(null); return; }
                setText(status);
                String color = switch (status) {
                    case "Updated"      -> UIStyles.SUCCESS;
                    case "Needs Review" -> UIStyles.WARNING;
                    case "No Match"     -> UIStyles.DANGER;
                    default             -> UIStyles.TEXT_MUTED;
                };
                setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
            }
        });

        table.getColumns().addAll(fileCol, titleCol, artistCol, sourceCol, scoreCol, statusCol);

        // When the user clicks a row, update AppState — MainView will open detail view
        table.getSelectionModel().selectedItemProperty().addListener(
                (obs, old, selected) -> {
                    if (selected != null) state.setSelectedTrack(selected);
                }
        );

        VBox.setVgrow(table, Priority.ALWAYS);
        return table;
    }
}
