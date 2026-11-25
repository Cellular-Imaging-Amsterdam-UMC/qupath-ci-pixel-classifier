package qupath.ext.cipixel;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import qupath.lib.classifiers.Normalization;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata.ChannelType;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.images.servers.WrappedBufferedImageServer;
import qupath.lib.objects.PathObjects;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.regions.ImagePlane;
import qupath.lib.regions.RegionRequest;
import qupath.lib.roi.ROIs;
import qupath.lib.scripting.QP;
import qupath.opencv.ml.pixel.PixelClassifiers;
import qupath.opencv.tools.MultiscaleFeatures.MultiscaleFeature;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClassifierCompatibilityTest {
    @TempDir Path directory;

    @BeforeAll
    static void initializeQuPath() {
        QP.getProject(); // Registers the same image operation and model adapters as the QuPath desktop.
    }

    @Test
    void newRecipesPreviewAllTenTwoDimensionalFilters() throws Exception {
        try (var image = testImage()) {
            var settings = TrainingSettings.defaults(image);
            assertEquals(10, settings.features().features().size());
            var preview = FeaturePreviewWindow.sample(settings, image, 64, 64, 0, 0);
            assertEquals(10, preview.bands());
            assertEquals(10, preview.names().size());
            assertEquals(32, preview.width());
            assertEquals(32, preview.height());
            assertEquals(10 * 32 * 32, preview.values().length);
            assertTrue(preview.names().stream().anyMatch(name -> name.contains("Gaussian")));
        }
    }

    @Test
    void trainsExportsAndReloadsWithTheNativeQuPathReader() throws Exception {
        try (var image = testImage()) {
            var settings = probabilitySettings(image);
            var result = ClassifierTrainer.train(settings, image, List.of(image));
            assertEquals(2, result.samples().size());
            assertEquals(ChannelType.PROBABILITY, result.classifier().getMetadata().getOutputType());
            var path = directory.resolve("extension-trained.json");
            NativeClassifierIO.write(result.classifier(), path);
            var nativeClassifier = QP.loadPixelClassifier(path.toString());
            assertEquals(ChannelType.PROBABILITY, nativeClassifier.getMetadata().getOutputType());
            assertArrayEquals(prediction(result.classifier(), image), prediction(nativeClassifier, image));

            var imported = NativeClassifierIO.read(path, TrainingSettings.defaults(image));
            assertNotNull(imported.settings(), imported.description());
            assertEquals(settings.features(), imported.settings().features(), imported.description());
            assertNull(imported.settings().importedPipeline());
            assertEquals(settings.resolution(image), imported.settings().fixedResolution());
            assertEquals("17", imported.settings().parameters().get("maxTrees"));
            assertNotNull(imported.settings().fittedPreprocessing());
            var retrained = ClassifierTrainer.train(imported.settings(), image, List.of(image));
            assertEquals(ChannelType.PROBABILITY, retrained.classifier().getMetadata().getOutputType());
            assertEquals(2, retrained.samples().size());
        }
    }

    @Test
    void opensAndExportsNativeThresholdClassifiersWithoutInventingTrainingSettings() throws Exception {
        try (var image = testImage()) {
            var op = qupath.opencv.ops.ImageOps.buildImageDataOp().appendOps(
                    qupath.opencv.ops.ImageOps.Threshold.threshold(100),
                    qupath.opencv.ops.ImageOps.Core.ensureType(qupath.lib.images.servers.PixelType.UINT8));
            var nativeClassifier = PixelClassifiers.createClassifier(op, PixelCalibration.getDefaultInstance(),
                    Map.of(0, PathClass.fromString("Low"), 1, PathClass.fromString("High")));
            var original = directory.resolve("native-threshold.json");
            PixelClassifiers.writeClassifier(nativeClassifier, original);
            var imported = NativeClassifierIO.read(original, TrainingSettings.defaults(image));
            assertNull(imported.settings());
            assertTrue(imported.classifier().supportsImage(image));

            var exported = directory.resolve("export-threshold.json");
            NativeClassifierIO.write(imported.classifier(), exported);
            assertEquals(JsonParser.parseString(Files.readString(original)),
                    JsonParser.parseString(Files.readString(exported)));
            assertArrayEquals(prediction(nativeClassifier, image),
                    prediction(PixelClassifiers.readClassifier(exported), image));
        }
    }

    @Test
    void reportsIncompatibleSavedChannelByName() throws Exception {
        try (var image = testImage()) {
            var missing = FeatureRecipe.encodeChannel(
                    qupath.lib.images.servers.ColorTransforms.createChannelExtractor("Missing stain"));
            var features = new FeatureRecipe(List.of(missing), List.of(MultiscaleFeature.GAUSSIAN), List.of(1.0),
                    FeatureRecipe.LocalNormalization.NONE, 8);
            var exception = assertThrows(IllegalArgumentException.class, () -> features.build(image));
            assertTrue(exception.getMessage().contains("Missing stain"));
        }
    }

    @ParameterizedTest
    @EnumSource(TrainingSettings.Model.class)
    void eachAvailableAlgorithmProducesNativeClassLabels(TrainingSettings.Model model) throws Exception {
        try (var image = testImage()) {
            var defaults = TrainingSettings.defaults(image);
            var settings = new TrainingSettings(model, Map.of(), 2, null,
                    defaults.features(), null, ChannelType.CLASSIFICATION, 1000, 42, false, Normalization.NONE,
                    0, true, null, TrainingSettings.Boundary.IGNORE, null, 1, defaults.previewRegion(), 0.55, false);
            var result = ClassifierTrainer.train(settings, image, List.of(image));
            var path = directory.resolve("class-labels.json");
            NativeClassifierIO.write(result.classifier(), path);
            var imported = NativeClassifierIO.read(path, defaults);
            assertNotNull(imported.settings(), imported.description());
            assertEquals(model, imported.settings().model());
            assertEquals(ChannelType.CLASSIFICATION, imported.settings().output());
            assertArrayEquals(prediction(result.classifier(), image), prediction(imported.classifier(), image));
        }
    }

    @Test
    void imageValidationHoldsOutEachSourceAndReportsClassMetrics() throws Exception {
        try (var first = testImage("First validation image", 0);
             var second = testImage("Second validation image", 5)) {
            var settings = TrainingSettings.defaults(first);
            var report = ImageValidation.validate(settings, first, List.of(first, second), message -> {});
            assertEquals(2, report.classes().size());
            assertEquals(2, report.folds().size());
            assertNotEquals(report.folds().getFirst().image(), report.folds().getLast().image());
            for (var fold : report.folds()) {
                var stats = new AnalysisWindows.Metrics(fold.matrix());
                assertTrue(stats.annotated(0) > 0 && stats.annotated(1) > 0);
                assertTrue(stats.accuracy() >= 0.9);
                assertTrue(stats.f1(0) >= 0.9 && stats.f1(1) >= 0.9);
            }
            var duplicate = assertThrows(IllegalArgumentException.class,
                    () -> ImageValidation.validate(settings, first, List.of(first, first), message -> {}));
            assertTrue(duplicate.getMessage().contains("selected twice"));
            var high = second.getHierarchy().getAnnotationObjects().stream()
                    .filter(a -> a.getPathClass() == PathClass.fromString("High")).findFirst().orElseThrow();
            high.setLocked(true);
            var missing = assertThrows(IllegalArgumentException.class,
                    () -> ImageValidation.validate(settings, first, List.of(first, second), message -> {}));
            assertTrue(missing.getMessage().contains("Missing coverage for [High]"));
        }
    }

    @Test
    void randomTreesRanksEachOriginalFilterWithItsPreviewBand() throws Exception {
        try (var image = testImage()) {
            var settings = TrainingSettings.defaults(image);
            var names = settings.featureOp(image).getChannels(image);
            var ranks = ClassifierTrainer.importance(settings, image, List.of(image));
            assertEquals(10, ranks.size());
            assertEquals(10, ranks.stream().map(ClassifierTrainer.FeatureRank::band).distinct().count());
            for (int i = 0; i < ranks.size(); i++) {
                var rank = ranks.get(i);
                assertEquals(names.get(rank.band()).getName(), rank.name());
                assertTrue(Double.isFinite(rank.weight()));
                if (i > 0)
                    assertTrue(ranks.get(i - 1).weight() >= rank.weight());
            }
        }
    }

    private static TrainingSettings probabilitySettings(ImageData<BufferedImage> image) {
        var defaultSettings = TrainingSettings.defaults(image);
        var features = new FeatureRecipe(defaultSettings.features().channels(),
                List.of(MultiscaleFeature.GAUSSIAN, MultiscaleFeature.LAPLACIAN), List.of(0.5, 2.0),
                FeatureRecipe.LocalNormalization.MEAN_VARIANCE, 4);
        return new TrainingSettings(defaultSettings.model(), Map.of("maxTrees", "17"), 2, null,
                features, null, ChannelType.PROBABILITY, 0, 42, true, Normalization.MEAN_VARIANCE,
                0.85, true, null, TrainingSettings.Boundary.IGNORE, null, 1,
                defaultSettings.previewRegion(), 0.37, false);
    }

    private static ImageData<BufferedImage> testImage() {
        return testImage("Test grayscale", 0);
    }

    private static ImageData<BufferedImage> testImage(String name, int shift) {
        var pixels = new BufferedImage(128, 128, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                int bias = x < 64 ? 20 : 185;
                pixels.getRaster().setSample(x, y, 0, bias + (x * x + 3 * y + shift) % 63);
            }
        }
        var image = new ImageData<>(new WrappedBufferedImageServer(name, pixels));
        var plane = ImagePlane.getDefaultPlane();
        image.getHierarchy().addObjects(List.of(
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(12, 12, 32, 64, plane), PathClass.fromString("Low")),
                PathObjects.createAnnotationObject(ROIs.createRectangleROI(82, 12, 32, 64, plane), PathClass.fromString("High"))));
        return image;
    }

    private static int[] prediction(PixelClassifier classifier, ImageData<BufferedImage> image) throws Exception {
        var request = RegionRequest.createInstance(image.getServer().getPath(), 2, 0, 0, 128, 128);
        var raster = classifier.applyClassification(image, request).getRaster();
        return raster.getPixels(0, 0, raster.getWidth(), raster.getHeight(), (int[]) null);
    }
}
