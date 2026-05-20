package lyrify.App;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

// JavaFX entry point.
// JavaFX requires a class that extends Application with a start() method —
// this is the equivalent of main() for the UI layer.
// The actual main() at the bottom hands off to JavaFX's launcher.
public class MainApp extends Application {

    // Shared state passed to every view so they all see the same data
    private final AppState state = new AppState();

    @Override
    public void start(Stage primaryStage) {

        // -- Root layout --
        // BorderPane divides the window into 5 zones: top, bottom, left, right, centre.
        // We use: top = toolbar/nav, centre = current screen, bottom = status bar.
        BorderPane root = new BorderPane();
        root.setStyle(UIStyles.CSS_ROOT);

        // -- Build each view, all sharing the same AppState --
        ScanView        scanView    = new ScanView(state, primaryStage);
        TrackListView   listView    = new TrackListView(state);
        TrackDetailView detailView  = new TrackDetailView(state);
        SettingsView    settingsView = new SettingsView(state);

        // -- Navigation bar at the top --
        MainView mainView = new MainView(
                state, root, scanView, listView, detailView, settingsView
        );
        root.setTop(mainView.buildNavBar());

        // -- Start on the Scan screen --
        root.setCenter(scanView.build());

        // -- Status bar at the bottom --
        root.setBottom(mainView.buildStatusBar());

        // -- Scene wraps the layout and sets the window size --
        Scene scene = new Scene(root, 1100, 700);
        primaryStage.setScene(scene);
        primaryStage.setTitle("Lyrify — Music Metadata Corrector");
        primaryStage.setMinWidth(800);
        primaryStage.setMinHeight(550);
        primaryStage.show();
    }

    // JavaFX needs this main() only when launching without a JavaFX-aware launcher.
    // The launch() call hands control to JavaFX which then calls start() above.
    public static void main(String[] args) {
        launch(args);
    }
}
