package qupath.ext.cipixel;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.viewer.RegionFilter.StandardRegionFilters;
import qupath.process.gui.commands.ml.PixelClassifierUI;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static qupath.ext.cipixel.TrainingEditor.*;

final class ClassifierLoader {
    private record Selection(NativeClassifierIO.Imported imported, String source, String projectName) {}

    private static final Logger logger = LoggerFactory.getLogger(ClassifierLoader.class);
    private final QuPathGUI qupath;
    private final ClassifierWorkspace trainer;
    private final ViewerPrediction prediction = new ViewerPrediction();
    private final SimpleObjectProperty<PixelClassifier> active = new SimpleObjectProperty<>();
    private final SimpleObjectProperty<PixelClassifier> applicable = new SimpleObjectProperty<>();
    private final SimpleStringProperty savedName = new SimpleStringProperty();
    private final ChangeListener<Object> imageChanged = (v, o, n) -> refreshPrediction();
    private final ChangeListener<Object> projectChanged = (v, o, n) -> populateProjectNames();
    private final java.util.concurrent.Executor worker = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "ci-pixel-classifier-loader");
        thread.setDaemon(true);
        return thread;
    });

    private Stage stage;
    private ComboBox<String> projects;
    private Label status;
    private Button edit;
    private Button browse;
    private Button reload;
    private Slider opacity;
    private CheckBox show;
    private Selection selection;
    private File previousFolder;
    private long revision;
    private boolean busy;

    ClassifierLoader(QuPathGUI qupath, ClassifierWorkspace trainer) {
        this.qupath = qupath;
        this.trainer = trainer;
    }

    void show() {
        if (stage == null)
            create();
        if (stage.isShowing()) {
            stage.toFront();
            return;
        }
        stage.show();
        qupath.projectProperty().addListener(projectChanged);
        qupath.imageDataProperty().addListener(imageChanged);
        qupath.viewerProperty().addListener(imageChanged);
        populateProjectNames();
    }

    void hide() {
        if (stage != null)
            stage.hide();
    }

    private void create() {
        stage = new Stage();
        stage.initOwner(qupath.getStage());
        stage.setTitle("CI Pixel Classifier · Load classifier");
        stage.setMinWidth(415);
        WindowStyle.decorate(stage);

        projects = new ComboBox<>();
        projects.setMaxWidth(Double.MAX_VALUE);
        projects.setPromptText("Choose a saved classifier");
        projects.setId("load-project-classifier");
        projects.setOnAction(e -> {
            if (projects.getValue() != null)
                openProject(projects.getValue());
        });
        reload = new Button("Refresh");
        reload.setOnAction(e -> populateProjectNames());
        browse = new Button("Open JSON…");
        browse.setOnAction(e -> openFile());

        show = new CheckBox("Show prediction in viewer");
        show.setSelected(true);
        show.selectedProperty().addListener((v, o, n) -> refreshPrediction());
        var region = combo(StandardRegionFilters.values());
        region.setValue(StandardRegionFilters.IMAGE);
        region.valueProperty().addListener((v, o, n) -> prediction.setRegion(n));
        prediction.setRegion(region.getValue());
        opacity = new Slider(0, 1, .55);
        opacity.valueProperty().addListener((v, o, n) -> prediction.setOpacity(n.doubleValue()));
        var opacityLabel = note("");
        opacityLabel.textProperty().bind(opacity.valueProperty().multiply(100).asString("%.0f%%"));

        var actions = PixelClassifierUI.createPixelClassifierButtons(qupath.imageDataProperty(), applicable, savedName);
        if (actions instanceof GridPane grid) {
            grid.setHgap(5);
            grid.setVgap(5);
        }
        var projectSave = PixelClassifierUI.createSavePixelClassifierPane(qupath.projectProperty(), active, savedName);
        status = note("");
        status.setId("load-classifier-status");
        status.setMinWidth(0);
        status.setMinHeight(54);
        var statusTooltip = new Tooltip();
        statusTooltip.textProperty().bind(status.textProperty());
        status.setTooltip(statusTooltip);
        edit = new Button("Edit…");
        edit.setId("edit-loaded-classifier");
        edit.setMinWidth(Region.USE_PREF_SIZE);
        edit.getStyleClass().add("primary");
        edit.setOnAction(e -> {
            var selected = selection;
            var projectName = savedName.get();
            hide();
            trainer.openForEditing(selected.imported(), selected.source(), projectName);
        });
        var footer = row(status, edit);
        footer.setAlignment(Pos.CENTER_LEFT);
        var sourceCard = card("Load classifier", field("Project classifier", projects, row(projects, reload, browse)));
        var root = new VBox(10, sourceCard, card("Preview", show, field("Prediction region", region),
                        field("Opacity", opacity, row(opacity, opacityLabel))),
                card("Apply classifier", actions, projectSave), footer);
        root.setPadding(new Insets(10));
        stage.setScene(WindowStyle.scene(root, 495, -1));
        stage.setOnHidden(e -> {
            revision++;
            qupath.projectProperty().removeListener(projectChanged);
            qupath.imageDataProperty().removeListener(imageChanged);
            qupath.viewerProperty().removeListener(imageChanged);
            prediction.clear();
            selection = null;
            active.set(null);
            applicable.set(null);
            busy = false;
        });
    }

    private void populateProjectNames() {
        revision++;
        busy = false;
        selection = null;
        projects.getSelectionModel().clearSelection();
        projects.getItems().clear();
        var project = qupath.getProject();
        if (project != null) {
            try {
                projects.getItems().setAll(project.getPixelClassifiers().getNames().stream().sorted().toList());
            } catch (IOException e) {
                reportError(e);
                return;
            }
        }
        refreshPrediction();
    }

    private void openProject(String name) {
        var project = qupath.getProject();
        if (project == null)
            return;
        var settings = trainer.settingsForImport();
        load(() -> new Selection(NativeClassifierIO.inspect(project.getPixelClassifiers().get(name),
                settings), name, name));
    }

    private void openFile() {
        var chooser = new FileChooser();
        chooser.setTitle("Open QuPath pixel classifier");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("QuPath pixel classifier", "*.json"));
        if (previousFolder != null && previousFolder.isDirectory())
            chooser.setInitialDirectory(previousFolder);
        File file = chooser.showOpenDialog(stage);
        if (file == null)
            return;
        previousFolder = file.getParentFile();
        projects.getSelectionModel().clearSelection();
        var settings = trainer.settingsForImport();
        load(() -> new Selection(NativeClassifierIO.read(file.toPath(), settings), file.getName(), ""));
    }

    private void load(Callable<Selection> operation) {
        long requested = ++revision;
        selection = null;
        busy = true;
        refreshPrediction();
        var task = new Task<Selection>() {
            @Override protected Selection call() throws Exception {
                return operation.call();
            }
        };
        task.setOnSucceeded(e -> {
            if (revision != requested)
                return;
            selection = task.getValue();
            busy = false;
            refreshPrediction();
            savedName.set(selection.projectName());
        });
        task.setOnFailed(e -> {
            if (revision == requested)
                reportError(task.getException());
        });
        worker.execute(task);
    }

    private void reportError(Throwable error) {
        logger.warn("Unable to load pixel classifier", error);
        busy = false;
        refreshPrediction();
        status.setText(error.getLocalizedMessage());
    }

    private void refreshPrediction() {
        var classifier = selection == null ? null : selection.imported().classifier();
        var image = qupath.getImageData();
        boolean compatible = classifier != null && image != null && classifier.supportsImage(image);
        active.set(classifier);
        applicable.set(compatible ? classifier : null);
        prediction.show(stage.isShowing() && show.isSelected() && compatible ? qupath.getViewer() : null,
                classifier, opacity.getValue());
        show.setDisable(!compatible);
        projects.setDisable(busy || projects.getItems().isEmpty());
        reload.setDisable(busy || qupath.getProject() == null);
        browse.setDisable(busy);
        edit.setDisable(classifier == null || busy);
        if (busy)
            status.setText("Reading classifier…");
        else if (selection == null)
            status.setText(projects.getItems().isEmpty() ? "Open a classifier JSON file." : "Choose a project classifier or open JSON.");
        else if (image == null)
            status.setText(selection.source() + "\nOpen an image to preview the classifier.");
        else if (!compatible)
            status.setText(selection.source() + "\nThe image channels are incompatible.");
        else
            status.setText(selection.source() + "\n" + selection.imported().description());
    }
}
