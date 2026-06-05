package lyrify.App;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import lyrify.FileHub.PipelineResult;

import java.nio.file.Path;

public class TrackListView {

    private final AppState state;
    private Node cachedNode = null;
    private final Label totalVal   = new Label("0");
    private final Label updatedVal = new Label("0");
    private final Label reviewVal  = new Label("0");
    private final Label failedVal  = new Label("0");

    public TrackListView(AppState state) {
        this.state = state;

        // Register listener once here — not inside build() which may be called multiple times
        state.getResults().addListener((javafx.collections.ListChangeListener<PipelineResult>) ignored -> {
            long total   = state.getResults().size();
            long updated = state.getResults().stream().filter(PipelineResult::written).count();
            long review  = state.getResults().stream().filter(PipelineResult::needsReview).count();
            long failed  = state.getResults().stream()
                    .filter(r -> r.stage() == PipelineResult.Stage.NONE).count();

            System.out.println("[Summary] total=" + total + " updated=" + updated +
                    " review=" + review + " failed=" + failed);

            totalVal.setText(String.valueOf(total));
            updatedVal.setText(String.valueOf(updated));
            reviewVal.setText(String.valueOf(review));
            failedVal.setText(String.valueOf(failed));
        });
    }

    public Node build() {
        if (cachedNode != null) return cachedNode;

        VBox page = new VBox(UIStyles.PAD_MD);
        page.setPadding(new Insets(UIStyles.PAD_LG));
        page.setStyle(UIStyles.CSS_ROOT);

        Node table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        page.getChildren().addAll(
                buildHeading(),
                buildSummaryBar(),
                table
        );

        cachedNode = page;
        return cachedNode;
    }

    private Node buildHeading() {
        Label title = new Label("Scan Results");
        title.setStyle(UIStyles.CSS_HEADING);

        Label sub = new Label("Click any row to review and edit metadata.");
        sub.setStyle(UIStyles.CSS_MUTED);

        return new VBox(4, title, sub);
    }

    private Node buildSummaryBar() {
        HBox bar = new HBox(UIStyles.PAD_MD);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle(UIStyles.CSS_CARD);

        bar.getChildren().addAll(
                summaryChip("Total",        totalVal,   UIStyles.ACCENT),
                summaryChip("Updated",      updatedVal, UIStyles.SUCCESS),
                summaryChip("Needs Review", reviewVal,  UIStyles.WARNING),
                summaryChip("No Match",     failedVal,  UIStyles.DANGER)
        );
        return bar;
    }

    // Creates a small labelled counter chip using a pre-built value label
    private Node summaryChip(String label, Label valueLabel, String color) {
        valueLabel.setStyle(
                "-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: " + color + ";"
        );
        Label textLabel = new Label(label);
        textLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: " + UIStyles.TEXT_MUTED + ";");

        VBox chip = new VBox(2, valueLabel, textLabel);
        chip.setAlignment(Pos.CENTER);
        chip.setPadding(new Insets(8, 16, 8, 16));
        chip.setStyle(
                "-fx-background-color: " + color + "11;" +
                        "-fx-background-radius: 8;"
        );
        return chip;
    }

    @SuppressWarnings("unchecked")
    private Node buildTable() {
        TableView<PipelineResult> table = new TableView<>();
        table.setStyle("-fx-background-color: " + UIStyles.BG_CARD + ";");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No results yet — run a scan first."));
        table.setItems(state.getResults());

        // -- File column --
        TableColumn<PipelineResult, String> fileCol = new TableColumn<>("File");
        fileCol.setPrefWidth(220);
        fileCol.setCellValueFactory(row ->
                new javafx.beans.property.SimpleStringProperty(
                        Path.of(row.getValue().filepath()).getFileName().toString()
                )
        );

        // -- Title column --
        TableColumn<PipelineResult, String> titleCol = new TableColumn<>("Title");
        titleCol.setPrefWidth(200);
        titleCol.setCellValueFactory(row ->
                new javafx.beans.property.SimpleStringProperty(
                        row.getValue().metadata() != null && row.getValue().metadata().title() != null
                                ? row.getValue().metadata().title()
                                : "—"
                )
        );

        // -- Artist column --
        TableColumn<PipelineResult, String> artistCol = new TableColumn<>("Artist");
        artistCol.setPrefWidth(160);
        artistCol.setCellValueFactory(row ->
                new javafx.beans.property.SimpleStringProperty(
                        row.getValue().metadata() != null && row.getValue().metadata().artist() != null
                                ? row.getValue().metadata().artist()
                                : "—"
                )
        );

        // -- Source column --
        TableColumn<PipelineResult, String> sourceCol = new TableColumn<>("Source");
        sourceCol.setPrefWidth(80);
        sourceCol.setCellValueFactory(row ->
                new javafx.beans.property.SimpleStringProperty(
                        row.getValue().stage().name()
                )
        );

        // -- Score column with colour badge --
        TableColumn<PipelineResult, Double> scoreCol = new TableColumn<>("Score");
        scoreCol.setPrefWidth(90);
        scoreCol.setCellValueFactory(row ->
                new javafx.beans.property.SimpleObjectProperty<>(row.getValue().finalScore())
        );
        scoreCol.setCellFactory(ignored -> new TableCell<>() {
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

        // -- Status column --
        TableColumn<PipelineResult, String> statusCol = new TableColumn<>("Status");
        statusCol.setPrefWidth(120);
        statusCol.setCellValueFactory(row -> {
            PipelineResult r = row.getValue();
            String status;
            if (r.stage() == PipelineResult.Stage.CACHED) status = "Cached";
            else if (r.needsReview())                      status = "Needs Review";
            else if (r.written())                          status = "Updated";
            else if (r.stage() == PipelineResult.Stage.NONE) status = "No Match";
            else                                           status = "Pending";
            return new javafx.beans.property.SimpleStringProperty(status);
        });
        statusCol.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) { setText(null); return; }
                setText(status);
                String color = switch (status) {
                    case "Updated"      -> UIStyles.SUCCESS;
                    case "Needs Review" -> UIStyles.WARNING;
                    case "No Match"     -> UIStyles.DANGER;
                    case "Cached"       -> UIStyles.ACCENT;
                    default             -> UIStyles.TEXT_MUTED;
                };
                setStyle("-fx-text-fill: " + color + "; -fx-font-weight: bold;");
            }
        });

        table.getColumns().addAll(fileCol, titleCol, artistCol, sourceCol, scoreCol, statusCol);

        // Clicking a row opens the detail view
        table.getSelectionModel().selectedItemProperty().addListener(
                (ignored, ignoredOldVal, selected) -> {
                    if (selected != null) state.setSelectedTrack(selected);
                }
        );

        return table;
    }
}