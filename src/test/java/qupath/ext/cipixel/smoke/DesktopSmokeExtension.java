package qupath.ext.cipixel.smoke;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ObjectProperty;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TitledPane;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.QuPathStyleManager;
import qupath.lib.gui.viewer.RegionFilter.StandardRegionFilters;
import qupath.lib.gui.viewer.overlays.PixelClassificationOverlay;
import qupath.lib.io.PathIO;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.projects.Projects;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.scripting.QP;
import qupath.process.gui.commands.ml.PixelClassifierPane;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Invoked by tools/smoke-test.ps1 in the packaged Windows QuPath 0.7.0 runtime.
 * This extension is excluded from the production jar.
 */
public final class DesktopSmokeExtension implements QuPathExtension {
    private final Path output = Path.of(System.getProperty("ci.pixel.smoke.output"));
    private QuPathGUI qupath;
    private Stage workspace;

    @Override
    public void installExtension(QuPathGUI gui) {
        qupath = gui;
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(50);
        var timer = new Timeline();
        timer.getKeyFrames().add(new KeyFrame(Duration.millis(250), e -> {
            if (qupath.getImageData() != null && qupath.getMenu("Extensions", false).getItems().stream()
                    .anyMatch(item -> "CI Pixel Classifier".equals(item.getText()))) {
                timer.stop();
                Platform.runLater(() -> safely(this::test));
            } else if (System.nanoTime() > deadline) {
                timer.stop();
                fail(new AssertionError("QuPath did not open the image or install the extension."));
            }
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private void test() throws Exception {
        if (Boolean.getBoolean("ci.pixel.smoke.restore")) {
            showWorkspace();
            require(text("downsample").equals("3"), "Downsample did not survive a process restart");
            require(text("feature-scales").equals("0.5, 2, 4"), "Feature scales did not survive a restart");
            require(((Slider) workspace.getScene().lookup("#preview-opacity")).getValue() == 0.37,
                    "Opacity did not survive a restart");
            require(((ComboBox<?>) workspace.getScene().lookup("#preview-region")).getValue()
                    == StandardRegionFilters.ANY_ANNOTATIONS, "Preview region did not survive a restart");
            finish("restored");
            return;
        }

        var image = qupath.getImageData();
        String annotations = System.getProperty("ci.pixel.smoke.annotations", "");
        if (!annotations.isBlank())
            image.getHierarchy().setHierarchy(PathIO.readHierarchy(Path.of(annotations)));
        var labels = image.getHierarchy().getAnnotationObjects().stream()
                .filter(a -> !a.isLocked() && a.getPathClass() != null && a.getPathClass() != PathClass.StandardPathClasses.REGION)
                .map(a -> a.getPathClass()).distinct().count();
        if (labels < 2) {
            var plane = ImagePlane.getDefaultPlane();
            image.getHierarchy().addObjects(List.of(
                    PathObjects.createAnnotationObject(ROIs.createRectangleROI(35, 35, 90, 90, plane),
                            PathClass.fromString("Training sample A")),
                    PathObjects.createAnnotationObject(ROIs.createRectangleROI(160, 160, 90, 90, plane),
                            PathClass.fromString("Training sample B"))));
        }

        var projectDir = Files.createDirectories(output.resolve("project"));
        var project = Projects.createProject(projectDir.toFile(), BufferedImage.class);
        image.setChanged(false);
        qupath.setProject(project);
        if (qupath.getImageData() != image)
            qupath.getViewer().setImageData(image);

        // Produce the fixture through the actual v0.7.0 training window.
        var nativePane = new PixelClassifierPane(qupath);
        var stageField = PixelClassifierPane.class.getDeclaredField("stage");
        stageField.setAccessible(true);
        var nativeStage = (Stage) stageField.get(nativePane);
        nativeStage.getScene().getRoot().applyCss();
        var live = nativeStage.getScene().getRoot().lookupAll(".toggle-button").stream()
                .filter(ToggleButton.class::isInstance).map(ToggleButton.class::cast)
                .filter(b -> b.getText().equals("Live prediction")).findFirst().orElseThrow();
        live.fire();
        var classifierField = PixelClassifierPane.class.getDeclaredField("currentClassifier");
        classifierField.setAccessible(true);
        var classifier = (PixelClassifier) ((ObjectProperty<?>) classifierField.get(nativePane)).getValue();
        require(classifier != null, "Stock QuPath classifier did not train");
        project.getPixelClassifiers().put("stock-qupath-classifier", classifier);
        nativeStage.fireEvent(new javafx.stage.WindowEvent(nativeStage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
        if (nativeStage.isShowing())
            nativeStage.hide();

        setTheme("Dark");
        showWorkspace();
        require(!workspace.getIcons().isEmpty(), "Training window is missing its icon");
        var features = (org.controlsfx.control.CheckComboBox<?>) workspace.getScene().lookup("#feature-types");
        require(features.getCheckModel().getCheckedItems().size() == 10,
                "A new workspace did not enable the ten 2D filters");
        var advanced = (TitledPane) workspace.getScene().lookup("#advanced-training");
        advanced.setExpanded(true);
        workspace.getScene().getRoot().applyCss();
        require(advanced.lookup("#local-scale") != null, "Local scale is not in advanced training settings");
        advanced.setExpanded(false);

        var toggle = (ToggleButton) workspace.getScene().lookup("#training-layout-toggle");
        double width = workspace.getWidth();
        double height = workspace.getHeight();
        toggle.fire();
        await(() -> workspace.getHeight() < 120, () -> {
            require(workspace.getScene().lookup("#training-body") == null, "Collapsed window still shows the editor");
            require(workspace.getWidth() < 400, "Collapsed window did not shrink");
            snapshot("workspace-collapsed.png");
            toggle.fire();
            await(() -> Math.abs(workspace.getHeight() - height) < 2, () -> {
                require(Math.abs(workspace.getWidth() - width) < 2, "Expanded window did not restore the width");
                openFromLoader();
            });
        });
    }

    private void openFromLoader() {
        extensionItem("Load classifier…").fire();
        var loader = visibleStage("CI Pixel Classifier · Load classifier");
        require(!workspace.isShowing(), "Training window stayed visible behind the loader");
        require(!loader.getIcons().isEmpty(), "Loader is missing its icon");
        loader.getScene().getRoot().applyCss();
        var projectClassifiers = (ComboBox<?>) loader.getScene().lookup("#load-project-classifier");
        projectClassifiers.getSelectionModel().select(0);
        var loadStatus = (Label) loader.getScene().lookup("#load-classifier-status");
        await(() -> loadStatus.getText().contains("Loaded resolution"), () -> {
            require(qupath.getViewer().getCustomPixelLayerOverlay() instanceof PixelClassificationOverlay,
                    "Loaded classifier has no viewer preview");
            snapshot(loader, "loader-dark.png");
            ((Button) loader.getScene().lookup("#edit-loaded-classifier")).fire();
            require(!loader.isShowing() && workspace.isShowing(), "Edit did not switch to the training window");
            require(status().contains("channels, features, scales"), status());
            require(text("feature-scales").equals("1"), "Native Gaussian scale was not recovered");
            var features = (org.controlsfx.control.CheckComboBox<?>) workspace.getScene().lookup("#feature-types");
            require(features.getCheckModel().getCheckedItems().size() == 1, "Import did not preserve the saved filter selection");
            features.applyCss();
            require(features.lookupAll(".list-cell").stream().filter(Labeled.class::isInstance)
                            .map(Labeled.class::cast).anyMatch(cell -> cell.getText() != null && cell.getText().contains("1/10")),
                    "The filter selector did not show the imported selection count");
            features.getCheckModel().check(1);
            verifyFeaturePreview();
        });
    }

    private void verifyFeaturePreview() {
        ((Button) workspace.getScene().lookup("#show-filters")).fire();
        var preview = visibleStage("CI Pixel Classifier · Filter preview");
        require(!preview.getIcons().isEmpty(), "Filter preview is missing its icon");
        var imageView = (ImageView) preview.getScene().lookup("#preview-feature-image");
        await(() -> imageView.getImage() != null, () -> {
            require(imageView.getImage().getWidth() > 0, "Feature preview returned an empty image");
            var bands = (ComboBox<?>) preview.getScene().lookup("#preview-feature-band");
            require(bands.getItems().size() == 2, "The filter preview did not expose both feature channels");
            var first = imageView.getImage();
            bands.getSelectionModel().selectLast();
            require(imageView.getImage() != first, "Selecting another feature did not update the preview");
            snapshot(preview, "filters-dark.png");
            setTheme("Light");
            await(() -> true, () -> {
                snapshot(preview, "filters-light.png");
                preview.hide();
                setTheme("Dark");
                ((TextField) workspace.getScene().lookup("#downsample")).setText("3");
                ((TextField) workspace.getScene().lookup("#feature-scales")).setText("0.5, 2, 4");
                ((Slider) workspace.getScene().lookup("#preview-opacity")).setValue(0.37);
                @SuppressWarnings("unchecked")
                var region = (ComboBox<StandardRegionFilters>) workspace.getScene().lookup("#preview-region");
                region.setValue(StandardRegionFilters.ANY_ANNOTATIONS);
                ((Button) workspace.getScene().lookup("#train")).fire();
                await(() -> status().equals("Training complete."), this::verifyTrained);
            });
        });
    }

    private void verifyTrained() throws Exception {
        var shares = workspace.getScene().getRoot().lookupAll("#training-pixel-share");
        require(shares.size() >= 2, "Missing class pixel proportions");
        double totalShare = shares.stream().map(Label.class::cast).map(Label::getText)
                .map(s -> s.replace(',', '.').replace("%", "")).mapToDouble(Double::parseDouble).sum();
        require(Math.abs(totalShare - 100) <= 0.3, "Class pixel proportions do not sum to 100%");
        var viewer = qupath.getViewer();
        require(viewer.getCustomPixelLayerOverlay() instanceof PixelClassificationOverlay, "Missing trained overlay");
        var overlay = (PixelClassificationOverlay) viewer.getCustomPixelLayerOverlay();
        var predicted = overlay.getPixelClassificationServer(qupath.getImageData());
        require(predicted != null, "Missing prediction server");
        var request = RegionRequest.createInstance(predicted.getPath(), predicted.getDownsampleForResolution(0),
                0, 0, 256, 256);
        var pixels = predicted.readRegion(request);
        require(pixels != null && pixels.getWidth() >= 80, "Unexpected prediction tile");

        var preview = (CheckBox) workspace.getScene().lookup("#show-prediction");
        preview.fire();
        require(viewer.getCustomPixelLayerOverlay() != overlay, "Preview toggle did not remove the viewer overlay");
        preview.fire();
        require(viewer.getCustomPixelLayerOverlay() instanceof PixelClassificationOverlay, "Preview toggle did not restore the viewer overlay");

        var savePane = (Parent) workspace.getScene().lookup("#project-save");
        ((TextField) savePane.lookup(".text-field")).setText("extension-classifier");
        ((Button) savePane.lookup(".button")).fire();
        var saved = QP.loadPixelClassifier("extension-classifier");
        var rawRequest = RegionRequest.createInstance(qupath.getImageData().getServer().getPath(),
                request.getDownsample(), request.getX(), request.getY(), request.getWidth(), request.getHeight());
        var nativePixels = saved.applyClassification(qupath.getImageData(), rawRequest);
        require(Arrays.equals(samples(pixels), samples(nativePixels)), "Stock QuPath prediction differs after project save");

        snapshot("workspace-dark.png");
        setTheme("Light");
        await(() -> true, () -> {
            snapshot("workspace-light.png");
            finish("trained");
        });
    }

    private static int[] samples(BufferedImage image) {
        return image.getRaster().getPixels(0, 0, image.getWidth(), image.getHeight(), (int[]) null);
    }

    private MenuItem extensionItem(String text) {
        var menu = (Menu) qupath.getMenu("Extensions", false).getItems().stream()
                .filter(item -> "CI Pixel Classifier".equals(item.getText())).findFirst().orElseThrow();
        return menu.getItems().stream().filter(item -> text.equals(item.getText())).findFirst().orElseThrow();
    }

    private static Stage visibleStage(String title) {
        return Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                .filter(s -> s.getTitle().equals(title)).findFirst().orElseThrow();
    }

    private void showWorkspace() {
        extensionItem("Train classifier…").fire();
        workspace = visibleStage("CI Pixel Classifier");
        workspace.getScene().getRoot().applyCss();
        workspace.getScene().getRoot().layout();
    }

    private void snapshot(String name) throws Exception {
        snapshot(workspace, name);
    }

    private void snapshot(Stage window, String name) throws Exception {
        var root = window.getScene().getRoot();
        root.applyCss();
        root.layout();
        ImageIO.write(SwingFXUtils.fromFXImage(window.getScene().snapshot(null), null), "png", output.resolve(name).toFile());
    }

    private static void setTheme(String mode) {
        var style = QuPathStyleManager.availableStylesProperty().stream()
                .filter(s -> s.getName().contains(mode)).findFirst().orElseThrow();
        QuPathStyleManager.selectedStyleProperty().set(style);
    }

    private String text(String id) {
        return ((TextField) workspace.getScene().lookup("#" + id)).getText();
    }

    private String status() {
        return ((Label) workspace.getScene().lookup("#workspace-status")).getText();
    }

    private void await(java.util.function.BooleanSupplier condition, Checked completion) {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
        var timer = new Timeline();
        timer.getKeyFrames().add(new KeyFrame(Duration.millis(250), e -> safely(() -> {
            if (condition.getAsBoolean()) {
                timer.stop();
                Platform.runLater(() -> safely(completion));
            } else if (System.nanoTime() > deadline) {
                timer.stop();
                throw new AssertionError("Unexpected workspace status: " + status());
            }
        })));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private void finish(String name) throws Exception {
        workspace.hide(); // Flush final preferences.
        Files.writeString(output.resolve(name + ".ok"), "PASS");
        qupath.getImageData().setChanged(false);
        var mainStage = qupath.getStage();
        mainStage.fireEvent(new javafx.stage.WindowEvent(mainStage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
    }

    private void safely(Checked action) {
        try {
            action.run();
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void fail(Throwable error) {
        error.printStackTrace();
        try (var writer = new java.io.PrintWriter(Files.newBufferedWriter(output.resolve("error.txt")))) {
            error.printStackTrace(writer);
        } catch (Exception ignored) {
            // The launcher log also includes the stack trace.
        }
        Platform.exit();
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }

    @Override public String getName() { return "CI desktop smoke test"; }
    @Override public String getDescription() { return "Exercises the extension in packaged QuPath."; }
    @Override public Version getQuPathVersion() { return Version.parse("0.7.0"); }
}
