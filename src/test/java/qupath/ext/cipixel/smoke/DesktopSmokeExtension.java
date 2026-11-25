package qupath.ext.cipixel.smoke;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.beans.property.ObjectProperty;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
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
import javafx.scene.control.TableView;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
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
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerProvider;
import qupath.lib.projects.ProjectImageEntry;
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
    private ProjectImageEntry<BufferedImage> validationEntry;

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

        var validationImage = System.getProperty("ci.pixel.smoke.validation-image");
        if (validationImage != null) {
            var server = ImageServerProvider.buildServer(validationImage, BufferedImage.class);
            try (var heldOut = new ImageData<>(server, image.getImageType())) {
                var annotationsCopy = image.getHierarchy().getAnnotationObjects().stream()
                        .filter(a -> !a.isLocked())
                        .map(a -> PathObjects.createAnnotationObject(a.getROI(), a.getPathClass())).toList();
                heldOut.getHierarchy().addObjects(annotationsCopy);
                validationEntry = project.addImage(server.getBuilder());
                validationEntry.setImageName(Path.of(validationImage).getFileName().toString());
                validationEntry.saveImageData(heldOut);
                project.syncChanges();
            }
        }

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
        requireWindowIcon(workspace);
        var features = (org.controlsfx.control.CheckComboBox<?>) workspace.getScene().lookup("#feature-types");
        require(features.getCheckModel().getCheckedItems().size() == 10,
                "A new workspace did not enable the ten 2D filters");
        var advanced = (TitledPane) workspace.getScene().lookup("#advanced-training");
        advanced.setExpanded(true);
        workspace.getScene().getRoot().applyCss();
        require(advanced.lookup("#local-scale") != null, "Local scale is not in advanced training settings");
        advanced.setExpanded(false);

        verifyDialogIcon();
        verifyUndo();
        var toggle = (ToggleButton) workspace.getScene().lookup("#training-layout-toggle");
        require(toggle.getGraphic() != null && (toggle.getText() == null || toggle.getText().isEmpty()),
                "Collapse and expand should use an icon-only button");
        double width = workspace.getWidth();
        double height = workspace.getHeight();
        double x = workspace.getX();
        double y = workspace.getY();
        var buttonBounds = toggle.localToScreen(toggle.getBoundsInLocal());
        var autoRetrain = (CheckBox) workspace.getScene().lookup("#auto-train");
        require("Live Prediction".equals(autoRetrain.getText()), "Missing expanded Live Prediction label");
        var previewSlot = autoRetrain.getParent();
        toggle.fire();
        await(() -> workspace.getHeight() < 120, () -> {
            require(workspace.getScene().lookup("#training-body") == null, "Collapsed window still shows the editor");
            requireGeometry(x, y, width, toggle, buttonBounds);
            require(workspace.getScene().lookup("#auto-train") == autoRetrain
                            && autoRetrain.getParent().getStyleClass().contains("workspace-header"),
                    "The retrain checkbox should move to the collapsed header");
            require("Live Prediction".equals(autoRetrain.getText()), "Missing collapsed Live Prediction label");
            autoRetrain.fire();
            snapshot("workspace-collapsed.png");
            captureTitle(workspace, "workspace-title.png");
            toggle.fire();
            await(() -> Math.abs(workspace.getHeight() - height) < 2, () -> {
                requireGeometry(x, y, width, toggle, buttonBounds);
                require(autoRetrain.getParent() == previewSlot && autoRetrain.isSelected(),
                        "The retrain checkbox should return to Preview with its value preserved");
                autoRetrain.fire();
                openFromLoader();
            });
        });
    }

    private void requireGeometry(double x, double y, double width, ToggleButton toggle, javafx.geometry.Bounds before) {
        require(Math.abs(workspace.getWidth() - width) < 1, "Collapse or expand changed the window width");
        require(Math.abs(workspace.getX() - x) < 1 && Math.abs(workspace.getY() - y) < 1,
                "Collapse or expand moved the window");
        var after = toggle.localToScreen(toggle.getBoundsInLocal());
        require(Math.abs(before.getMinX() - after.getMinX()) < 1 && Math.abs(before.getMinY() - after.getMinY()) < 1,
                "Collapse or expand moved the toggle on screen");
    }

    private void verifyDialogIcon() {
        var parameters = workspace.getScene().getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> "Parameters…".equals(b.getText())).findFirst().orElseThrow();
        var checked = new java.util.concurrent.atomic.AtomicBoolean();
        Platform.runLater(() -> safely(() -> {
            var window = visibleStage("Classifier parameters");
            var pane = (DialogPane) window.getScene().lookup(".dialog-pane");
            try {
                requireWindowIcon(window);
                snapshot(window, "parameters-dark.png");
                checked.set(true);
            } finally {
                ((Button) pane.lookupButton(ButtonType.CANCEL)).fire();
            }
        }));
        parameters.fire();
        require(checked.get(), "Parameters dialog was not verified");
    }

    private void verifyUndo() {
        var undo = (Button) workspace.getScene().lookup("#undo-annotation");
        require(undo.getGraphic() != null, "Undo arrow is missing");
        require(undo.getTooltip().getText().contains("Ctrl+Z"), "Undo tooltip does not mention Ctrl+Z");
        var hierarchy = qupath.getImageData().getHierarchy();
        int original = hierarchy.getAnnotationObjects().size();
        var roi = ROIs.createRectangleROI(15, 15, 10, 10, ImagePlane.getDefaultPlane());
        var label = PathClass.fromString("Undo test");
        hierarchy.addObject(PathObjects.createAnnotationObject(roi, label));
        require(hierarchy.getAnnotationObjects().size() == original + 1 && !undo.isDisabled(),
                "Native Undo did not enable after annotating");
        undo.fire();
        require(hierarchy.getAnnotationObjects().size() == original && qupath.getUndoRedoManager().canRedo().get(),
                "Undo did not restore QuPath's prior annotation hierarchy");

        hierarchy.addObject(PathObjects.createAnnotationObject(roi, label));
        var downsample = (TextField) workspace.getScene().lookup("#downsample");
        String startValue = downsample.getText();
        downsample.requestFocus();
        downsample.replaceText(0, downsample.getLength(), "8");
        Event.fireEvent(downsample, undoKey());
        require(downsample.getText().equals(startValue) && hierarchy.getAnnotationObjects().size() == original + 1,
                "Ctrl+Z while editing a field should use the field's text Undo");

        undo.requestFocus();
        Event.fireEvent(undo, undoKey());
        require(hierarchy.getAnnotationObjects().size() == original,
                "Ctrl+Z did not undo the annotation in the training window");
    }

    private static KeyEvent undoKey() {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.Z, false, true, false, false);
    }

    private static void requireWindowIcon(Stage window) {
        require(window.getIcons().stream().anyMatch(icon -> icon.getWidth() == 16),
                "Missing native title icon in " + window.getTitle());
    }

    private void captureTitle(Stage window, String file) throws Exception {
        var sceneStart = window.getScene().getRoot().localToScreen(0, 0);
        double height = sceneStart.getY() - window.getY();
        require(height > 12, "Missing decorated title bar");
        var screenshot = new javafx.scene.robot.Robot().getScreenCapture(null, window.getX(), window.getY(),
                Math.min(330, window.getWidth()), height, true);
        ImageIO.write(SwingFXUtils.fromFXImage(screenshot, null), "png", output.resolve(file).toFile());
        var pixels = screenshot.getPixelReader();
        int teal = 0;
        for (int y = 0; y < screenshot.getHeight(); y++) {
            for (int x = 0; x < Math.min(35, screenshot.getWidth()); x++) {
                int rgb = pixels.getArgb(x, y);
                int red = rgb >> 16 & 255;
                int green = rgb >> 8 & 255;
                int blue = rgb & 255;
                if (Math.abs(red - 20) <= 15 && Math.abs(green - 95) <= 15 && Math.abs(blue - 88) <= 15)
                    teal++;
            }
        }
        require(teal >= 10, "Extension icon is missing from the actual title bar of " + window.getTitle());
    }

    private void openFromLoader() {
        extensionItem("Load classifier…").fire();
        var loader = visibleStage("CI Pixel Classifier · Load classifier");
        require(!workspace.isShowing(), "Training window stayed visible behind the loader");
        requireWindowIcon(loader);
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
        requireWindowIcon(preview);
        var imageView = (ImageView) preview.getScene().lookup("#preview-feature-image");
        await(() -> imageView.getImage() != null, () -> {
            require(imageView.getImage().getWidth() > 0, "Feature preview returned an empty image");
            var bands = (ComboBox<?>) preview.getScene().lookup("#preview-feature-band");
            require(bands.getItems().size() == 2, "The filter preview did not expose both feature channels");
            var first = imageView.getImage();
            bands.getSelectionModel().selectLast();
            require(imageView.getImage() != first, "Selecting another feature did not update the preview");
            snapshot(preview, "filters-dark.png");
            captureTitle(preview, "filters-title.png");
            setTheme("Light");
            await(() -> true, () -> {
                snapshot(preview, "filters-light.png");
                preview.hide();
                setTheme("Dark");
                ((ComboBox<?>) workspace.getScene().lookup("#model")).getSelectionModel().selectFirst();
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
        var liveClassifier = ((qupath.lib.classifiers.pixel.PixelClassificationImageServer) predicted).getClassifier();
        var livePixels = liveClassifier.applyClassification(qupath.getImageData(), rawRequest);
        var nativePixels = saved.applyClassification(qupath.getImageData(), rawRequest);
        require(Arrays.equals(samples(livePixels), samples(nativePixels)), "Stock QuPath prediction differs after project save");

        snapshot("workspace-dark.png");
        setTheme("Light");
        await(() -> true, () -> {
            snapshot("workspace-light.png");
            verifyCurtain();
        });
    }

    private void verifyCurtain() {
        var compare = (CheckBox) workspace.getScene().lookup("#compare-prediction");
        var visible = (CheckBox) workspace.getScene().lookup("#show-prediction");
        @SuppressWarnings("unchecked")
        var region = (ComboBox<StandardRegionFilters>) workspace.getScene().lookup("#preview-region");
        var previousRegion = region.getValue();
        var viewer = qupath.getViewer();
        region.setValue(StandardRegionFilters.EVERYWHERE);
        visible.fire();
        await(() -> true, () -> {
            var server = viewer.getImageData().getServer();
            int width = server.getWidth();
            int height = server.getHeight();
            var raw = viewer.getView().snapshot(null, null);
            int rawLeft = greenSamples(raw, comparisonRegion(viewer, width * .15, width * .45, height));
            int rawRight = greenSamples(raw, comparisonRegion(viewer, width * .72, width * .9, height));
            visible.fire();
            var fullOverlay = viewer.getCustomPixelLayerOverlay();
            compare.fire();
            var handle = viewer.getView().lookup("#ci-prediction-curtain");
            require(handle != null && viewer.getCustomPixelLayerOverlay() != fullOverlay,
                    "Comparison did not install a cutaway overlay and its handle");
            double oldX = handle.getLayoutX();
            var target = viewer.imagePointToComponentPoint(new java.awt.geom.Point2D.Double(width * .62, height * .5), null, false);
            var point = viewer.getView().localToScene(target.getX(), target.getY());
            Event.fireEvent(handle, new MouseEvent(MouseEvent.MOUSE_DRAGGED, point.getX(), point.getY(),
                    point.getX(), point.getY(), MouseButton.PRIMARY, 1, false, false, false, false,
                    true, false, false, false, false, false, new PickResult(handle, point.getX(), point.getY())));
            require(handle.getLayoutX() > oldX, "Dragging did not move the comparison handle");

            await(() -> {
                var comparison = viewer.getView().snapshot(null, null);
                int left = greenSamples(comparison, comparisonRegion(viewer, width * .15, width * .45, height));
                int right = greenSamples(comparison, comparisonRegion(viewer, width * .72, width * .9, height));
                return right - rawRight >= 300 && Math.abs(left - rawLeft) < 80;
            }, () -> {
                var comparison = viewer.getView().snapshot(null, null);
                var bounds = comparisonRegion(viewer, 0, width, height, 0, 1);
                var trimmed = new javafx.scene.image.WritableImage(comparison.getPixelReader(),
                        bounds[0], bounds[1], bounds[2] - bounds[0], bounds[3] - bounds[1]);
                ImageIO.write(SwingFXUtils.fromFXImage(trimmed, null), "png", output.resolve("comparison.png").toFile());
                compare.fire();
                require(viewer.getView().lookup("#ci-prediction-curtain") == null
                                && viewer.getCustomPixelLayerOverlay() == fullOverlay,
                        "Disabling comparison did not restore the full overlay");
                region.setValue(previousRegion);
                verifyImportance();
            });
        });
    }

    private static int[] comparisonRegion(qupath.lib.gui.viewer.QuPathViewer viewer,
                                           double left, double right, int height) {
        return comparisonRegion(viewer, left, right, height, .25, .75);
    }

    private static int[] comparisonRegion(qupath.lib.gui.viewer.QuPathViewer viewer,
                                           double left, double right, int height, double top, double bottom) {
        var a = viewer.imagePointToComponentPoint(new java.awt.geom.Point2D.Double(left, height * top), null, false);
        var b = viewer.imagePointToComponentPoint(new java.awt.geom.Point2D.Double(right, height * bottom), null, false);
        return new int[] {(int) Math.max(0, Math.ceil(a.getX())), (int) Math.max(0, Math.ceil(a.getY())),
                (int) Math.min(viewer.getView().getWidth(), Math.floor(b.getX())),
                (int) Math.min(viewer.getView().getHeight(), Math.floor(b.getY()))};
    }

    private static int greenSamples(javafx.scene.image.Image image, int[] area) {
        var pixels = image.getPixelReader();
        int count = 0;
        for (int y = area[1]; y < area[3]; y += 3) {
            for (int x = area[0]; x < area[2]; x += 3) {
                int rgb = pixels.getArgb(x, y);
                int red = rgb >> 16 & 255;
                int green = rgb >> 8 & 255;
                int blue = rgb & 255;
                if (green > red + 30 && green > blue + 30)
                    count++;
            }
        }
        return count;
    }

    private void verifyImportance() {
        ((Button) workspace.getScene().lookup("#filter-importance")).fire();
        await(() -> status().equals("Filter importance calculated."), () -> {
            var window = visibleStage("CI Pixel Classifier · Filter importance");
            var table = (TableView<?>) window.getScene().lookup("#feature-importance-table");
            require(table.getItems().size() == 6, "Expected one score for each of two filters at three scales");
            snapshot(window, "importance.png");
            table.getSelectionModel().selectFirst();
            window.getScene().getRoot().lookupAll(".button").stream()
                    .filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(b -> "Show selected filter".equals(b.getText())).findFirst().orElseThrow().fire();
            var preview = visibleStage("CI Pixel Classifier · Filter preview");
            await(() -> ((ImageView) preview.getScene().lookup("#preview-feature-image")).getImage() != null, () -> {
                preview.hide();
                window.hide();
                if (validationEntry == null)
                    finish("trained");
                else
                    verifyImageValidation();
            });
        });
    }

    private void verifyImageValidation() {
        var choose = workspace.getScene().getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(b -> "Choose project images…".equals(b.getText())).findFirst().orElseThrow();
        Platform.runLater(() -> safely(() -> {
            var dialog = visibleStage("Training images");
            var pane = (DialogPane) dialog.getScene().lookup(".dialog-pane");
            try {
                @SuppressWarnings("unchecked")
                var picker = (org.controlsfx.control.ListSelectionView<ProjectImageEntry<BufferedImage>>) pane.getContent();
                picker.getSourceItems().remove(validationEntry);
                picker.getTargetItems().add(validationEntry);
            } finally {
                ((Button) pane.lookupButton(ButtonType.APPLY)).fire();
            }
        }));
        choose.fire();
        ((Button) workspace.getScene().lookup("#validate-by-image")).fire();
        await(() -> status().equals("Image validation complete."), () -> {
            var window = visibleStage("CI Pixel Classifier · Image validation");
            var table = (TableView<?>) window.getScene().lookup("#validation-matrix");
            require(table.getItems().size() >= 2, "Validation report has no class confusion matrix");
            snapshot(window, "validation.png");
            window.hide();
            finish("trained");
        });
    }

    private static int[] samples(BufferedImage image) {
        return image.getRaster().getPixels(0, 0, image.getWidth(), image.getHeight(), (int[]) null);
    }

    private MenuItem extensionItem(String text) {
        var menu = (Menu) qupath.getMenu("Extensions", false).getItems().stream()
                .filter(item -> "CI Pixel Classifier".equals(item.getText())).findFirst().orElseThrow();
        require(menu.getGraphic() instanceof ImageView, "Extension menu is missing its icon");
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
