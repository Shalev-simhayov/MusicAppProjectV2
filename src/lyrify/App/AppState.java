package lyrify.App;

import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import lyrify.FileHub.PipelineResult;

// Holds the live state of the application.
// All views share one AppState instance so they stay in sync —
// when ScanView adds results, TrackListView automatically sees them
// because JavaFX ObservableList notifies listeners on every change.
public final class AppState {

    // -- Currently selected directory path --
    // StringProperty lets the UI bind to it so labels update automatically
    private final StringProperty selectedDirectory = new SimpleStringProperty("");

    // -- Flat list of every PipelineResult from the last scan --
    // ObservableList means TableView refreshes itself whenever we add/remove items
    private final ObservableList<PipelineResult> results =
            FXCollections.observableArrayList();

    // -- The single track the user has clicked on in the table --
    private final ObjectProperty<PipelineResult> selectedTrack =
            new SimpleObjectProperty<>(null);

    // -- Whether a scan is currently running --
    // Used to disable buttons while work is in progress
    private final BooleanProperty scanning = new SimpleBooleanProperty(false);

    // -- Progress of the current scan (0.0 to 1.0) --
    private final DoubleProperty scanProgress = new SimpleDoubleProperty(0.0);

    // -- Human-readable status message shown in the status bar --
    private final StringProperty statusMessage = new SimpleStringProperty("Ready");

    // -- Settings: API keys and thresholds --
    // These are plain StringProperty / DoubleProperty so Settings view
    // can bind its text fields and sliders directly
    private final StringProperty acoustIdKey     = new SimpleStringProperty("");
    private final StringProperty spotifyClientId = new SimpleStringProperty("");
    private final StringProperty spotifySecret   = new SimpleStringProperty("");
    private final StringProperty geniusToken     = new SimpleStringProperty("");

    private final DoubleProperty acceptThreshold = new SimpleDoubleProperty(0.85);
    private final DoubleProperty reviewThreshold = new SimpleDoubleProperty(0.60);
    private final DoubleProperty aiThreshold     = new SimpleDoubleProperty(0.60);

    private final BooleanProperty recursive      = new SimpleBooleanProperty(true);
    private final BooleanProperty useCache       = new SimpleBooleanProperty(true);
    private final BooleanProperty backupBeforeScan = new SimpleBooleanProperty(true);

    // -- Accessors (JavaFX convention: xProperty() + getX() + setX()) --

    public StringProperty selectedDirectoryProperty() { return selectedDirectory; }
    public String getSelectedDirectory()              { return selectedDirectory.get(); }
    public void   setSelectedDirectory(String v)      { selectedDirectory.set(v); }

    public ObservableList<PipelineResult> getResults() { return results; }

    public ObjectProperty<PipelineResult> selectedTrackProperty() { return selectedTrack; }
    public PipelineResult getSelectedTrack()                       { return selectedTrack.get(); }
    public void           setSelectedTrack(PipelineResult v)       { selectedTrack.set(v); }

    public BooleanProperty scanningProperty() { return scanning; }
    public boolean isScanning()               { return scanning.get(); }
    public void    setScanning(boolean v)     { scanning.set(v); }

    public DoubleProperty scanProgressProperty() { return scanProgress; }
    public void setScanProgress(double v)         { scanProgress.set(v); }

    public StringProperty statusMessageProperty() { return statusMessage; }
    public void setStatusMessage(String v)         { statusMessage.set(v); }

    // API key properties
    public StringProperty acoustIdKeyProperty()     { return acoustIdKey; }
    public StringProperty spotifyClientIdProperty() { return spotifyClientId; }
    public StringProperty spotifySecretProperty()   { return spotifySecret; }
    public StringProperty geniusTokenProperty()     { return geniusToken; }

    // Threshold properties
    public DoubleProperty acceptThresholdProperty() { return acceptThreshold; }
    public DoubleProperty reviewThresholdProperty() { return reviewThreshold; }
    public DoubleProperty aiThresholdProperty()     { return aiThreshold; }
    public double getAcceptThreshold()              { return acceptThreshold.get(); }
    public double getReviewThreshold()              { return reviewThreshold.get(); }
    public double getAiThreshold()                  { return aiThreshold.get(); }

    // Option properties
    public BooleanProperty recursiveProperty()        { return recursive; }
    public BooleanProperty useCacheProperty()         { return useCache; }
    public BooleanProperty backupBeforeScanProperty() { return backupBeforeScan; }
    public boolean isRecursive()                      { return recursive.get(); }
    public boolean isUseCache()                       { return useCache.get(); }
    public boolean isBackupBeforeScan()               { return backupBeforeScan.get(); }
}
