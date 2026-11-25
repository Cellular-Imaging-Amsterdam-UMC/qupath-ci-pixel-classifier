package qupath.ext.cipixel;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.dialogs.ProjectDialogs;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.RegionFilter.StandardRegionFilters;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.gui.viewer.overlays.PixelClassificationOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.objects.PathObject;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.objects.hierarchy.PathObjectHierarchy;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyEvent;
import qupath.lib.objects.hierarchy.events.PathObjectHierarchyListener;
import qupath.lib.projects.ProjectImageEntry;
import qupath.process.gui.commands.ml.PixelClassifierUI;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static qupath.ext.cipixel.TrainingEditor.*;

final class ClassifierWorkspace {
    private static final Logger logger = LoggerFactory.getLogger(ClassifierWorkspace.class);
    private static final String TITLE = "CI Pixel Classifier";

    private final QuPathGUI qupath;
    private final SettingsStore store = new SettingsStore();
    private final SimpleObjectProperty<ImageData<BufferedImage>> image = new SimpleObjectProperty<>();
    private final SimpleObjectProperty<PixelClassifier> active = new SimpleObjectProperty<>();
    private final SimpleObjectProperty<PixelClassifier> applicable = new SimpleObjectProperty<>();
    private final SimpleStringProperty savedName = new SimpleStringProperty();
    private final PauseTransition saveDelay = new PauseTransition(Duration.millis(450));
    private final PauseTransition trainDelay = new PauseTransition(Duration.millis(800));
    private final List<ProjectImageEntry<BufferedImage>> trainingEntries = new ArrayList<>();
    private final PathObjectHierarchyListener hierarchyListener = this::hierarchyChanged;
    private final ChangeListener<ImageData<BufferedImage>> imageListener = (v, old, next) -> setImage(next);
    private final ChangeListener<QuPathViewer> viewerListener = (v, old, next) -> refreshOverlay();
    private final ChangeListener<Object> projectListener = (v, old, next) -> projectChanged();
    private final java.util.concurrent.Executor worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "ci-pixel-classifier");
        thread.setDaemon(true);
        return thread;
    });

    private Stage stage;
    private TrainingEditor editor;
    private Label imageLabel;
    private Label status;
    private Label modelStatus;
    private Label trainingPixels;
    private Label projectImagesLabel;
    private VBox classRows;
    private Button train;
    private Button export;
    private CheckBox showOverlay;
    private CheckBox autoTrain;
    private Slider opacity;
    private javafx.scene.control.ComboBox<StandardRegionFilters> previewRegion;
    private ProgressIndicator progress;
    private MenuItem loadProject;
    private PathObjectHierarchy hierarchy;
    private TrainingSettings lastValid;
    private Map<PathClass, Integer> sampleCounts = Map.of();
    private boolean applyingSettings;
    private boolean busy;
    private boolean trainedFromAnnotations;
    private boolean pendingTrain;
    private long trainingRevision;
    private long classifierRevision = -1;
    private String modelSource;
    private String activityStatus;
    private File lastDirectory;
    private PixelClassificationOverlay overlay;
    private final OverlayOptions previewOptions = new OverlayOptions();
    private QuPathViewer overlayViewer;
    private PathOverlay previousOverlay;

    ClassifierWorkspace(QuPathGUI qupath) {
        this.qupath = qupath;
        previewOptions.setShowPixelClassification(true);
    }

    void show() {
        if (stage == null)
            createStage();
        if (stage.isShowing()) {
            stage.toFront();
            return;
        }
        stage.show();
        qupath.imageDataProperty().addListener(imageListener);
        qupath.viewerProperty().addListener(viewerListener);
        qupath.projectProperty().addListener(projectListener);
        setImage(qupath.getImageData());
    }

    private void createStage() {
        stage = new Stage();
        stage.initOwner(qupath.getStage());
        stage.setTitle(TITLE);
        stage.setMinWidth(810);
        stage.setMinHeight(585);

        var title = new Label(TITLE);
        title.getStyleClass().add("workspace-title");
        imageLabel = note("");
        imageLabel.setWrapText(false);
        imageLabel.setMinWidth(0);
        var load = new MenuButton("Load classifier");
        addChoice(load, "Import JSON…", this::loadFile);
        loadProject = new MenuItem("Open from project…");
        loadProject.setOnAction(e -> loadProject());
        load.getItems().add(loadProject);
        var reset = new MenuButton("Settings");
        addChoice(reset, "Reset training settings", () -> {
            applySettings(TrainingSettings.defaults(image.get()));
            trainingChanged();
        });
        var header = row(new VBox(3, title, imageLabel), load, reset);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("workspace-header");

        editor = new TrainingEditor(this::trainingChanged);
        var left = editor.node();
        left.setMinWidth(391);
        left.setPrefWidth(472);
        left.getChildren().add(saveCard());
        var right = new VBox(12, trainingCard(), previewCard());
        right.setMinWidth(322);
        right.setPrefWidth(353);
        var body = new HBox(14, left, right);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        body.getStyleClass().add("workspace-body");
        var scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("workspace-scroll");

        progress = new ProgressIndicator();
        progress.setPrefSize(23, 23);
        progress.setMaxSize(23, 23);
        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());
        status = note("");
        status.setId("workspace-status");
        status.setMinWidth(0);
        train = new Button("Train classifier");
        train.setId("train");
        train.getStyleClass().add("primary");
        train.setOnAction(e -> train());
        export = new Button("Export JSON…");
        export.setId("export-classifier");
        export.setOnAction(e -> exportFile());
        var footer = row(status, progress, export, train);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("workspace-footer");

        var root = new BorderPane(scroll, header, null, footer, null);
        root.getStyleClass().add("ci-pixel");
        double screenHeight = Screen.getPrimary().getVisualBounds().getHeight();
        var scene = new Scene(root, 890, Math.max(560, Math.min(880, screenHeight - 70)));
        scene.getStylesheets().add(ClassifierWorkspace.class.getResource("workspace.css").toExternalForm());
        stage.setScene(scene);
        stage.setOnHidden(e -> hide());
        load.disableProperty().bind(progress.visibleProperty());
        reset.disableProperty().bind(progress.visibleProperty());

        saveDelay.setOnFinished(e -> store.save(lastValid));
        trainDelay.setOnFinished(e -> requestAutoTraining());
        image.set(qupath.getImageData());
        editor.setImage(image.get(), qupath.getAvailablePathClasses());
        var settings = store.load();
        try {
            applySettings(settings == null ? TrainingSettings.defaults(image.get()) : settings);
        } catch (RuntimeException e) {
            logger.warn("Could not apply restored settings", e);
            applySettings(TrainingSettings.defaults(image.get()));
        }
    }

    private VBox trainingCard() {
        var instructions = note("Draw and classify unlocked annotations in the viewer. Use at least two classes.");
        classRows = new VBox(6);
        classRows.setId("training-classes");
        trainingPixels = note("");
        var more = new Button("Choose project images…");
        more.disableProperty().bind(qupath.projectProperty().isNull());
        more.setOnAction(e -> chooseTrainingImages());
        projectImagesLabel = note("");
        return card("Training data", instructions, classRows, new Separator(), projectImagesLabel, more, trainingPixels);
    }

    private VBox previewCard() {
        showOverlay = new CheckBox("Show prediction in viewer");
        showOverlay.setId("show-prediction");
        showOverlay.setSelected(true);
        showOverlay.selectedProperty().addListener((v, old, next) -> refreshOverlay());
        autoTrain = new CheckBox("Retrain when settings or annotations change");
        autoTrain.setId("auto-train");
        autoTrain.setWrapText(true);
        autoTrain.selectedProperty().addListener((v, old, next) -> {
            if (applyingSettings)
                return;
            remember();
            if (next && (active.get() == null || classifierRevision != trainingRevision))
                scheduleAutoTraining();
            if (!next) {
                trainDelay.stop();
                pendingTrain = false;
            }
        });
        previewRegion = combo(StandardRegionFilters.values());
        previewRegion.setId("preview-region");
        previewRegion.valueProperty().addListener((v, old, next) -> {
            if (next != null)
                previewOptions.setPixelClassificationRegionFilter(next);
            if (!applyingSettings) {
                remember();
                if (overlayViewer != null)
                    overlayViewer.repaint();
            }
        });
        opacity = new Slider(0, 1, 0.55);
        opacity.setId("preview-opacity");
        var percent = note("");
        percent.setMinWidth(37);
        percent.textProperty().bind(opacity.valueProperty().multiply(100).asString("%.0f%%"));
        opacity.valueProperty().addListener((v, old, next) -> {
            if (overlay != null) {
                overlay.setOpacity(next.doubleValue());
                overlayViewer.repaint();
            }
            if (!applyingSettings)
                remember();
        });
        modelStatus = note("");
        modelStatus.setId("model-status");
        modelStatus.getStyleClass().add("model-status");
        return card("Preview", modelStatus, showOverlay, autoTrain,
                field("Prediction region", previewRegion), field("Opacity", opacity, row(opacity, percent)));
    }

    private VBox saveCard() {
        var saveProject = PixelClassifierUI.createSavePixelClassifierPane(qupath.projectProperty(), active, savedName);
        saveProject.setId("project-save");
        var apply = PixelClassifierUI.createPixelClassifierButtons(image, applicable, savedName);
        if (apply instanceof javafx.scene.layout.GridPane grid) {
            grid.setHgap(5);
            grid.setVgap(5);
        }
        return card("Save to project & apply", saveProject, new Separator(), apply);
    }

    private void applySettings(TrainingSettings settings) {
        applyingSettings = true;
        try {
            editor.apply(settings);
            previewRegion.setValue(settings.previewRegion());
            opacity.setValue(settings.opacity());
            autoTrain.setSelected(settings.autoTrain());
            lastValid = settings;
        } finally {
            applyingSettings = false;
        }
    }

    private TrainingSettings readSettings() {
        return editor.read(previewRegion.getValue(), opacity.getValue(), autoTrain.isSelected());
    }

    private void remember() {
        try {
            lastValid = readSettings();
            saveDelay.playFromStart();
        } catch (IllegalArgumentException e) {
            // Leave the last complete settings in place while a numeric field is being edited.
        }
    }

    private void trainingChanged() {
        trainingRevision++;
        activityStatus = null;
        remember();
        updateState();
        scheduleAutoTraining();
    }

    private void scheduleAutoTraining() {
        if (stage.isShowing() && autoTrain.isSelected() && image.get() != null)
            trainDelay.playFromStart();
    }

    private void requestAutoTraining() {
        if (!autoTrain.isSelected() || image.get() == null ||
                (trainingEntries.isEmpty() && annotationCounts().size() < 2))
            return;
        if (busy)
            pendingTrain = true;
        else
            train();
    }

    private void projectChanged() {
        trainingEntries.clear();
        savedName.set("");
        editor.setImage(image.get(), qupath.getAvailablePathClasses());
        trainingChanged();
        updateTrainingCounts();
    }

    private void setImage(ImageData<BufferedImage> next) {
        if (hierarchy != null)
            hierarchy.removeListener(hierarchyListener);
        boolean imageChanged = image.get() != next;
        if (imageChanged && trainedFromAnnotations) {
            // The trained model stays available to apply to another compatible image.
            trainingRevision++;
        }
        image.set(next);
        hierarchy = next == null ? null : next.getHierarchy();
        if (hierarchy != null)
            hierarchy.addListener(hierarchyListener);
        editor.setImage(next, qupath.getAvailablePathClasses());
        activityStatus = null;
        imageLabel.setText(next == null ? "No image open" : qupath.getDisplayedImageName(next));
        imageLabel.setTooltip(new Tooltip(imageLabel.getText()));
        remember();
        updateTrainingCounts();
        updateState();
        refreshOverlay();
        if (active.get() == null || (imageChanged && trainedFromAnnotations))
            scheduleAutoTraining();
    }

    private void hierarchyChanged(PathObjectHierarchyEvent event) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> hierarchyChanged(event));
            return;
        }
        if (!stage.isShowing() || hierarchy != event.getHierarchy() || event.isChanging() ||
                event.isObjectMeasurementEvent())
            return;
        if (!event.isStructureChangeEvent() && event.getChangedObjects().stream().noneMatch(PathObject::isAnnotation))
            return;
        updateTrainingCounts();
        trainingChanged();
    }

    private Map<PathClass, Integer> annotationCounts() {
        var result = new TreeMap<PathClass, Integer>(java.util.Comparator.comparing(PathClass::toString));
        if (hierarchy != null) {
            for (var annotation : hierarchy.getAnnotationObjects()) {
                var label = annotation.getPathClass();
                if (!annotation.isLocked() && label != null && label != PathClass.StandardPathClasses.REGION &&
                        annotation.hasROI() && !annotation.getROI().isEmpty())
                    result.merge(label, 1, Integer::sum);
            }
        }
        return result;
    }

    private void updateTrainingCounts() {
        var counts = annotationCounts();
        classRows.getChildren().clear();
        counts.entrySet().stream().limit(6).forEach(entry -> {
            var label = new Label(entry.getKey().toString());
            label.setMinWidth(0);
            var count = new Label(entry.getValue().toString());
            count.getStyleClass().add("class-count");
            var swatch = new Region();
            swatch.getStyleClass().add("class-swatch");
            var color = entry.getKey().getColor();
            if (color != null)
                swatch.setStyle("-fx-background-color: #%06x;".formatted(color & 0xffffff));
            var line = new HBox(7, swatch, label, new Region(), count);
            HBox.setHgrow(line.getChildren().get(2), Priority.ALWAYS);
            line.setAlignment(Pos.CENTER_LEFT);
            line.getStyleClass().add("class-row");
            classRows.getChildren().add(line);
        });
        if (counts.isEmpty())
            classRows.getChildren().add(note("No classified training annotations on this image."));
        else if (counts.size() > 6)
            classRows.getChildren().add(note("+" + (counts.size() - 6) + " more classes"));
        projectImagesLabel.setText(trainingEntries.isEmpty() ? "Training uses the open image."
                : "Training also uses " + trainingEntries.size() + " selected project image(s).");
        updateSampleSummary();
    }

    private void updateSampleSummary() {
        boolean show = !sampleCounts.isEmpty() && classifierRevision == trainingRevision;
        trainingPixels.setVisible(show);
        trainingPixels.setManaged(show);
        if (show) {
            int total = sampleCounts.values().stream().mapToInt(Integer::intValue).sum();
            trainingPixels.setText(String.format("%,d training pixels across %d classes.", total, sampleCounts.size()));
            trainingPixels.setTooltip(new Tooltip(String.join("\n", sampleCounts.entrySet().stream()
                    .map(e -> String.format("%s  •  %,d pixels", e.getKey(), e.getValue())).toList())));
        }
    }

    private void chooseTrainingImages() {
        var project = qupath.getProject();
        if (project == null)
            return;
        var choices = ProjectDialogs.createImageChoicePane(qupath, project.getImageList(), trainingEntries,
                "The open image is already included.");
        if (Dialogs.builder().title("Training images").content(choices).resizable()
                .buttons(ButtonType.APPLY, ButtonType.CANCEL).showAndWait().orElse(ButtonType.CANCEL) == ButtonType.APPLY) {
            trainingEntries.clear();
            trainingEntries.addAll(choices.getTargetItems());
            trainingChanged();
            updateTrainingCounts();
        }
    }

    private void train() {
        var currentImage = image.get();
        if (currentImage == null || busy)
            return;
        trainDelay.stop();
        pendingTrain = false;
        TrainingSettings settings;
        try {
            settings = readSettings();
            settings.resolution(currentImage);
            var features = settings.featureOp(currentImage);
            int featureCount = features.getChannels(currentImage).size();
            if (featureCount > org.bytedeco.opencv.global.opencv_core.CV_CN_MAX)
                throw new IllegalArgumentException("Too many features. Reduce the number of channels, filters or scales.");
        } catch (RuntimeException e) {
            activityStatus = e.getMessage();
            updateState();
            return;
        }

        inputChangesSaved(settings);
        var entries = List.copyOf(trainingEntries);
        var project = qupath.getProject();
        var open = new java.util.HashMap<ProjectImageEntry<BufferedImage>, ImageData<BufferedImage>>();
        if (project != null) {
            for (var viewer : qupath.getAllViewers()) {
                var data = viewer.getImageData();
                if (data != null) {
                    var entry = project.getEntry(data);
                    if (entry != null)
                        open.put(entry, data);
                }
            }
        }
        long revision = trainingRevision;
        run("Training classifier…", () -> {
            var images = new LinkedHashSet<ImageData<BufferedImage>>();
            var opened = new ArrayList<ImageData<BufferedImage>>();
            images.add(currentImage);
            try {
                for (var entry : entries) {
                    var data = open.get(entry);
                    if (data == null) {
                        data = entry.readImageData();
                        opened.add(data);
                    }
                    images.add(data);
                }
                return ClassifierTrainer.train(settings, currentImage, images);
            } finally {
                for (var data : opened) {
                    try {
                        data.close();
                    } catch (Exception e) {
                        logger.warn("Could not close a training image", e);
                    }
                }
            }
        }, result -> {
            if (trainingRevision != revision || image.get() != currentImage) {
                activityStatus = "Training inputs changed. Train again to use the current settings.";
                return;
            }
            trainedFromAnnotations = true;
            classifierRevision = revision;
            sampleCounts = result.samples();
            modelSource = "Trained from annotations";
            active.set(result.classifier());
            savedName.set("");
            activityStatus = "Training complete.";
            updateSampleSummary();
            refreshOverlay();
        });
    }

    private void inputChangesSaved(TrainingSettings settings) {
        lastValid = settings;
        saveDelay.playFromStart();
    }

    private void loadFile() {
        var chooser = chooser("Open QuPath pixel classifier");
        var file = chooser.showOpenDialog(stage);
        if (file == null)
            return;
        lastDirectory = file.getParentFile();
        var settings = lastValid;
        run("Reading classifier…", () -> NativeClassifierIO.read(file.toPath(), settings),
                result -> acceptImport(result, file.getName(), ""));
    }

    private void loadProject() {
        var project = qupath.getProject();
        if (project == null)
            return;
        try {
            var resources = project.getPixelClassifiers();
            var names = resources.getNames().stream().sorted().toList();
            if (names.isEmpty()) {
                activityStatus = "This project has no saved pixel classifiers.";
                updateState();
                return;
            }
            var dialog = new ChoiceDialog<>(names.getFirst(), names);
            dialog.initOwner(stage);
            dialog.setTitle("Open project classifier");
            dialog.setHeaderText("Choose a saved pixel classifier");
            dialog.setContentText("Classifier");
            dialog.showAndWait().ifPresent(name -> {
                var settings = lastValid;
                run("Reading classifier…", () -> NativeClassifierIO.inspect(resources.get(name), settings),
                        result -> acceptImport(result, name, qupath.getProject() == project ? name : ""));
            });
        } catch (IOException e) {
            reportError(e);
            updateState();
        }
    }

    private void acceptImport(NativeClassifierIO.Imported result, String source, String projectName) {
        trainDelay.stop();
        pendingTrain = false;
        if (result.settings() != null) {
            applySettings(result.settings());
            inputChangesSaved(result.settings());
        }
        trainedFromAnnotations = false;
        classifierRevision = ++trainingRevision;
        modelSource = result.settings() == null ? source + " (prediction only)" : source;
        sampleCounts = Map.of();
        active.set(result.classifier());
        savedName.set(projectName);
        activityStatus = result.description();
        updateSampleSummary();
        refreshOverlay();
    }

    private void exportFile() {
        var classifier = active.get();
        if (classifier == null)
            return;
        var chooser = chooser("Export QuPath pixel classifier");
        var name = savedName.get();
        chooser.setInitialFileName(name == null || name.isBlank() ? "pixel-classifier.json" : name + ".json");
        var file = chooser.showSaveDialog(stage);
        if (file == null)
            return;
        lastDirectory = file.getParentFile();
        var output = file.toPath();
        if (!file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
            output = output.resolveSibling(file.getName() + ".json");
            if (java.nio.file.Files.exists(output) && !Dialogs.showConfirmDialog(TITLE, "Replace " + output.getFileName() + "?"))
                return;
        }
        var path = output;
        run("Exporting classifier…", () -> {
            NativeClassifierIO.write(classifier, path);
            return path;
        }, saved -> activityStatus = "Saved " + saved.getFileName() + ".");
    }

    private FileChooser chooser(String title) {
        var chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("QuPath pixel classifier", "*.json"));
        if (lastDirectory != null && lastDirectory.isDirectory())
            chooser.setInitialDirectory(lastDirectory);
        return chooser;
    }

    private <T> void run(String message, Callable<T> operation, Consumer<T> success) {
        busy = true;
        activityStatus = message;
        updateState();
        var task = new Task<T>() {
            @Override protected T call() throws Exception {
                return operation.call();
            }
        };
        task.setOnSucceeded(e -> {
            try {
                success.accept(task.getValue());
            } catch (RuntimeException exception) {
                reportError(exception);
            } finally {
                operationFinished();
            }
        });
        task.setOnFailed(e -> {
            reportError(task.getException());
            operationFinished();
        });
        worker.execute(task);
    }

    private void operationFinished() {
        busy = false;
        updateState();
        if (pendingTrain && stage.isShowing()) {
            pendingTrain = false;
            requestAutoTraining();
        }
    }

    private void reportError(Throwable e) {
        logger.warn("CI Pixel Classifier operation failed", e);
        activityStatus = e.getLocalizedMessage();
        if (activityStatus == null || activityStatus.isBlank())
            activityStatus = "Operation failed. Details are available in the QuPath log.";
    }

    private void updateState() {
        var classifier = active.get();
        boolean compatible = classifier != null && image.get() != null && classifier.supportsImage(image.get());
        applicable.set(compatible ? classifier : null);
        train.setDisable(image.get() == null || busy);
        export.setDisable(classifier == null || busy);
        loadProject.setDisable(qupath.getProject() == null);
        showOverlay.setDisable(!compatible);
        progress.setVisible(busy);
        var label = classifier == null ? "No classifier loaded or trained." : modelSource;
        if (classifier != null && image.get() != null && !compatible)
            label += "\nThe image channels are incompatible.";
        else if (classifier != null && classifierRevision != trainingRevision)
            label += "\nPreview and export use the previous model.";
        modelStatus.setText(label);
        updateSampleSummary();
        if (activityStatus != null)
            status.setText(activityStatus);
        else if (image.get() == null)
            status.setText("Open an image to train or preview.");
        else if (classifier != null && classifierRevision != trainingRevision)
            status.setText("Training inputs changed. Train to update the model.");
        else if (annotationCounts().size() < 2 && trainingEntries.isEmpty())
            status.setText("Assign unlocked training annotations to at least two classes.");
        else
            status.setText("Ready to train.");
    }

    private void refreshOverlay() {
        clearOverlay();
        var classifier = active.get();
        var data = image.get();
        var viewer = qupath.getViewer();
        if (!stage.isShowing() || !showOverlay.isSelected() || classifier == null || data == null || viewer == null ||
                viewer.getImageData() != data || !classifier.supportsImage(data))
            return;
        overlayViewer = viewer;
        previousOverlay = viewer.getCustomPixelLayerOverlay();
        overlay = PixelClassificationOverlay.create(previewOptions, classifier,
                Math.max(1, Math.min(8, PathPrefs.numCommandThreadsProperty().get())));
        overlay.setOpacity(opacity.getValue());
        overlay.setLivePrediction(true);
        viewer.setCustomPixelLayerOverlay(overlay);
    }

    private void clearOverlay() {
        if (overlay == null)
            return;
        if (overlayViewer.getCustomPixelLayerOverlay() == overlay)
            overlayViewer.setCustomPixelLayerOverlay(previousOverlay);
        overlay.stop();
        overlay = null;
        overlayViewer = null;
        previousOverlay = null;
    }

    private void hide() {
        qupath.imageDataProperty().removeListener(imageListener);
        qupath.viewerProperty().removeListener(viewerListener);
        qupath.projectProperty().removeListener(projectListener);
        if (hierarchy != null)
            hierarchy.removeListener(hierarchyListener);
        hierarchy = null;
        trainDelay.stop();
        pendingTrain = false;
        saveDelay.stop();
        if (lastValid != null)
            store.save(lastValid);
        clearOverlay();
    }
}
