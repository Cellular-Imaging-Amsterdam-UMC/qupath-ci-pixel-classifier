// SPDX-FileCopyrightText: 2026 CI Pixel Classifier contributors
// SPDX-FileCopyrightText: 2014 - 2016 The Queen's University of Belfast, Northern Ireland
// SPDX-FileCopyrightText: 2018 - 2025 QuPath developers, The University of Edinburgh
// SPDX-License-Identifier: GPL-3.0-or-later
// Upstream reference: QuPath 0.7.0 PixelClassifierPane and ObjectClassifierCommand.
// Adapted for CI Pixel Classifier on 2026-11-25. See THIRD-PARTY-NOTICES.txt.

package qupath.ext.cipixel;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Alert;
import javafx.scene.control.Dialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Polyline;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.common.GeneralTools;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.tools.IconFactory;
import qupath.lib.gui.dialogs.ProjectDialogs;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.RegionFilter.StandardRegionFilters;
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
    private static final int MIN_EXPANDED_HEIGHT = 490;
    private static final String LIVE_PREDICTION_LABEL = "Live Prediction";

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
    private TrainingSampleChart trainingPixels;
    private Label projectImagesLabel;
    private VBox classRows;
    private Button train;
    private Button validate;
    private CheckBox comparePrediction;
    private Button export;
    private CheckBox showOverlay;
    private CheckBox autoTrain;
    private VBox autoTrainSlot;
    private Slider opacity;
    private javafx.scene.control.ComboBox<StandardRegionFilters> previewRegion;
    private ProgressIndicator progress;
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
    private Runnable openLoader;
    private FeaturePreviewWindow featurePreview;
    private final ViewerPrediction prediction = new ViewerPrediction();
    private ToggleButton compact;
    private Polyline collapseGlyph;
    private double expandedHeight;
    private boolean expandedMaximized;

    ClassifierWorkspace(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    void setLoader(Runnable openLoader) {
        this.openLoader = openLoader;
    }

    void switchTo(Runnable next) {
        if (busy)
            return;
        if (stage != null)
            stage.hide();
        next.run();
    }

    TrainingSettings settingsForImport() {
        if (lastValid != null)
            return lastValid;
        var remembered = store.load();
        return remembered != null ? remembered : TrainingSettings.defaults(qupath.getImageData());
    }

    void openForEditing(NativeClassifierIO.Imported result, String source, String projectName) {
        show();
        compact.setSelected(false);
        acceptImport(result, source, projectName);
        updateState();
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
        WindowStyle.decorate(stage);
        stage.setMinWidth(685);
        stage.setMinHeight(MIN_EXPANDED_HEIGHT);

        var title = new Label(TITLE);
        title.getStyleClass().add("workspace-title");
        imageLabel = note("");
        imageLabel.setWrapText(false);
        imageLabel.setMinWidth(0);
        var load = new Button("Load…");
        load.setOnAction(e -> openLoader.run());
        compact = new ToggleButton();
        compact.setId("training-layout-toggle");
        compact.setMinWidth(34);
        compact.setPrefWidth(34);
        compact.setMaxWidth(34);
        collapseGlyph = new Polyline(0, 5, 5, 0, 10, 5);
        collapseGlyph.getStyleClass().add("window-toggle-glyph");
        compact.setGraphic(collapseGlyph);
        updateCollapseButton(false);
        var reset = new MenuButton("Settings");
        addChoice(reset, "Reset training settings", () -> {
            applySettings(TrainingSettings.defaults(image.get()));
            trainingChanged();
        });
        var header = row(new VBox(2, title, imageLabel), load, reset, compact);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("workspace-header");

        editor = new TrainingEditor(this::trainingChanged, () -> showFeatures(null), this::calculateImportance);
        var left = editor.node();
        left.setMinWidth(345);
        left.setPrefWidth(390);
        left.getChildren().add(saveCard());
        var right = new VBox(8, trainingCard(), previewCard());
        right.setMinWidth(286);
        right.setPrefWidth(320);
        var body = new HBox(9, left, right);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        body.getStyleClass().add("workspace-body");
        var scroll = new ScrollPane(body);
        scroll.setId("training-body");
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
        var undo = createUndoButton();
        var footer = row(status, progress, undo, export, train);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("workspace-footer");

        var root = new BorderPane(scroll, header, null, footer, null);
        double screenHeight = Screen.getPrimary().getVisualBounds().getHeight();
        var scene = WindowStyle.scene(root, 745, Math.max(480, Math.min(735, screenHeight - 70)));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN), () -> {
            if (!(scene.getFocusOwner() instanceof TextInputControl))
                undo.fire();
        });
        compact.selectedProperty().addListener((v, old, collapsed) -> {
            setCollapsed(root, scroll, footer, collapsed);
            moveAutoTrain(header, collapsed);
        });
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
        trainingPixels = new TrainingSampleChart();
        var more = new Button("Choose project images…");
        more.disableProperty().bind(qupath.projectProperty().isNull());
        more.setOnAction(e -> chooseTrainingImages());
        validate = new Button("Validate…");
        validate.setId("validate-by-image");
        validate.setTooltip(new Tooltip("Hold out one annotated image at a time. Choose at least two images."));
        validate.setOnAction(e -> validateImages());
        projectImagesLabel = note("");
        return card("Training data", instructions, classRows, new Separator(), projectImagesLabel,
                new HBox(6, more, validate), trainingPixels);
    }

    private VBox previewCard() {
        showOverlay = new CheckBox("Show prediction in viewer");
        showOverlay.setId("show-prediction");
        showOverlay.setSelected(true);
        showOverlay.selectedProperty().addListener((v, old, next) -> {
            comparePrediction.setDisable(!next || applicable.get() == null);
            refreshOverlay();
        });
        autoTrain = new CheckBox(LIVE_PREDICTION_LABEL);
        autoTrain.setAccessibleText(LIVE_PREDICTION_LABEL);
        autoTrain.setTooltip(new Tooltip("Retrain when settings or annotations change"));
        autoTrainSlot = new VBox(autoTrain);
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
            prediction.setRegion(next);
            if (!applyingSettings)
                remember();
        });
        opacity = new Slider(0, 1, 0.55);
        opacity.setId("preview-opacity");
        var percent = note("");
        percent.setMinWidth(37);
        percent.textProperty().bind(opacity.valueProperty().multiply(100).asString("%.0f%%"));
        opacity.valueProperty().addListener((v, old, next) -> {
            prediction.setOpacity(next.doubleValue());
            if (!applyingSettings)
                remember();
        });
        modelStatus = note("");
        modelStatus.setId("model-status");
        modelStatus.getStyleClass().add("model-status");
        comparePrediction = new CheckBox("Compare with source");
        comparePrediction.setId("compare-prediction");
        comparePrediction.setTooltip(new Tooltip("Drag the line in the viewer to compare the source and prediction."));
        comparePrediction.selectedProperty().addListener((v, old, next) -> prediction.setComparison(next));
        return card("Preview", modelStatus, showOverlay, comparePrediction, autoTrainSlot,
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
        if (imageChanged && featurePreview != null)
            featurePreview.hide();
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
        trainingPixels.update(sampleCounts, classifierRevision == trainingRevision);
    }

    private void chooseTrainingImages() {
        var project = qupath.getProject();
        if (project == null)
            return;
        var choices = ProjectDialogs.createImageChoicePane(qupath, project.getImageList(), trainingEntries,
                "The open image is already included.");
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(stage);
        dialog.setTitle("Training images");
        dialog.setResizable(true);
        dialog.getDialogPane().setContent(choices);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.APPLY, ButtonType.CANCEL);
        WindowStyle.decorate(dialog);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.APPLY) {
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
        long revision = trainingRevision;
        runWithTrainingImages("Training classifier…", currentImage,
                images -> ClassifierTrainer.train(settings, currentImage, images), result -> {
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

    private void validateImages() {
        var current = image.get();
        if (current == null || busy)
            return;
        var settings = analysisSettings();
        if (settings == null)
            return;
        long revision = trainingRevision;
        runWithTrainingImages("Validating images…", current,
                images -> ImageValidation.validate(settings, current, images, message -> Platform.runLater(() -> {
                    if (busy && revision == trainingRevision) {
                        activityStatus = message;
                        status.setText(message);
                    }
                })), result -> {
                    if (!acceptAnalysis(revision, current))
                        return;
                    activityStatus = "Image validation complete.";
                    AnalysisWindows.showValidation(stage, result);
                });
    }

    private void calculateImportance() {
        var current = image.get();
        if (current == null || busy)
            return;
        var settings = analysisSettings();
        if (settings == null)
            return;
        long revision = trainingRevision;
        runWithTrainingImages("Calculating filter importance…", current,
                images -> ClassifierTrainer.importance(settings, current, images), result -> {
                    if (!acceptAnalysis(revision, current))
                        return;
                    activityStatus = "Filter importance calculated.";
                    AnalysisWindows.showImportance(stage, result, band -> {
                        if (acceptAnalysis(revision, current))
                            showFeatures(band);
                        else
                            updateState();
                    });
                });
    }

    private TrainingSettings analysisSettings() {
        try {
            return readSettings();
        } catch (IllegalArgumentException e) {
            activityStatus = e.getMessage();
            updateState();
            return null;
        }
    }

    private boolean acceptAnalysis(long revision, ImageData<BufferedImage> current) {
        if (revision == trainingRevision && image.get() == current && stage.isShowing())
            return true;
        activityStatus = "Training inputs changed. Run the analysis again for the current settings.";
        return false;
    }

    @FunctionalInterface
    private interface TrainingImageOperation<T> {
        T apply(List<ImageData<BufferedImage>> images) throws IOException;
    }

    private <T> void runWithTrainingImages(String message, ImageData<BufferedImage> current,
                                           TrainingImageOperation<T> operation, Consumer<T> completed) {
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
        run(message, () -> {
            var images = new LinkedHashSet<ImageData<BufferedImage>>();
            var opened = new ArrayList<ImageData<BufferedImage>>();
            images.add(current);
            try {
                for (var entry : entries) {
                    var data = open.get(entry);
                    if (data == null) {
                        data = entry.readImageData();
                        opened.add(data);
                    }
                    images.add(data);
                }
                return operation.apply(List.copyOf(images));
            } finally {
                for (var data : opened) {
                    try {
                        data.close();
                    } catch (Exception e) {
                        logger.warn("Could not close a training image", e);
                    }
                }
            }
        }, completed);
    }

    private void inputChangesSaved(TrainingSettings settings) {
        lastValid = settings;
        saveDelay.playFromStart();
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
            if (java.nio.file.Files.exists(output)) {
                var confirmation = new Alert(Alert.AlertType.CONFIRMATION, "Replace " + output.getFileName() + "?");
                confirmation.initOwner(stage);
                confirmation.setTitle(TITLE);
                confirmation.setHeaderText(null);
                WindowStyle.decorate(confirmation);
                if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK)
                    return;
            }
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
        validate.setDisable(image.get() == null || busy);
        comparePrediction.setDisable(!compatible || !showOverlay.isSelected());
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
        var viewer = qupath.getViewer();
        boolean show = stage.isShowing() && showOverlay.isSelected() && viewer != null
                && viewer.getImageData() == image.get();
        prediction.show(show ? viewer : null, active.get(), opacity.getValue());
    }

    private void showFeatures(Integer preferredBand) {
        if (featurePreview == null)
            featurePreview = new FeaturePreviewWindow(stage, qupath::getViewer, this::readSettings, worker);
        featurePreview.show(preferredBand);
    }

    private Button createUndoButton() {
        var manager = qupath.getUndoRedoManager();
        var undo = new Button();
        undo.setId("undo-annotation");
        undo.setAccessibleText("Undo");
        undo.setGraphic(IconFactory.createNode(14, 14, IconFactory.PathIcons.UNDO));
        String shortcut = GeneralTools.isMac() ? "Cmd+Z" : "Ctrl+Z";
        undo.setTooltip(new Tooltip("Undo (" + shortcut + ")"));
        undo.disableProperty().bind(manager.canUndo().not().or(image.isNull()));
        undo.setOnAction(e -> manager.undoOnce());
        return undo;
    }

    private void moveAutoTrain(HBox header, boolean collapsed) {
        if (collapsed) {
            autoTrainSlot.getChildren().clear();
            autoTrain.setWrapText(false);
            header.getChildren().add(1, autoTrain);
        } else {
            header.getChildren().remove(autoTrain);
            autoTrain.setWrapText(true);
            autoTrainSlot.getChildren().setAll(autoTrain);
        }
    }

    private void updateCollapseButton(boolean collapsed) {
        var label = collapsed ? "Expand training window" : "Collapse training window";
        compact.setAccessibleText(label);
        compact.setTooltip(new Tooltip(label));
        collapseGlyph.setRotate(collapsed ? 180 : 0);
    }

    private void setCollapsed(BorderPane root, ScrollPane scroll, HBox footer, boolean collapsed) {
        updateCollapseButton(collapsed);
        if (collapsed) {
            if (featurePreview != null)
                featurePreview.hide();
            expandedHeight = stage.getHeight();
            expandedMaximized = stage.isMaximized();
            double chrome = Math.max(0, expandedHeight - stage.getScene().getHeight());
            double height = Math.ceil(chrome + root.getTop().getLayoutBounds().getHeight());
            if (expandedMaximized) {
                double x = stage.getX();
                double y = stage.getY();
                double width = stage.getWidth();
                stage.setMaximized(false);
                stage.setX(x);
                stage.setY(y);
                stage.setWidth(width);
            }
            root.setCenter(null);
            root.setBottom(null);
            stage.setMinHeight(height);
            stage.setMaxHeight(height);
            stage.setHeight(height);
        } else {
            stage.setMaxHeight(Double.MAX_VALUE);
            stage.setMinHeight(MIN_EXPANDED_HEIGHT);
            root.setCenter(scroll);
            root.setBottom(footer);
            stage.setHeight(expandedHeight);
            if (expandedMaximized)
                stage.setMaximized(true);
        }
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
        if (featurePreview != null)
            featurePreview.hide();
        prediction.clear();
    }
}
