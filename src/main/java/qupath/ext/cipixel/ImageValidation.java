// SPDX-FileCopyrightText: 2026 CI Pixel Classifier contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// Upstream reference: QuPath 0.8 CrossValidation and CrossValidationTask.
// Adapted for CI Pixel Classifier on 2026-11-25. See THIRD-PARTY-NOTICES.txt.

package qupath.ext.cipixel;

import org.bytedeco.opencv.opencv_core.Mat;
import qupath.lib.images.ImageData;
import qupath.lib.objects.classes.PathClass;
import qupath.process.gui.commands.ml.BoundaryStrategy;
import qupath.process.gui.commands.ml.PixelClassifierTraining.ClassifierTrainingData;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

final class ImageValidation {
    private ImageValidation() {}

    record Fold(String image, long[][] matrix) {}
    record Report(List<PathClass> classes, List<Fold> folds) {}

    static Report validate(TrainingSettings settings, ImageData<BufferedImage> reference,
                           List<ImageData<BufferedImage>> images, Consumer<String> progress) throws IOException {
        if (images.size() < 2)
            throw new IllegalArgumentException("Select at least two distinct annotated images for image-level validation.");
        if (settings.fittedPreprocessing() != null)
            throw new IllegalArgumentException("Choose Fit new preprocessing before validation so each fold fits its own preprocessing.");

        var strategy = settings.boundaryStrategy();
        var seenSources = new HashSet<Object>();
        var classFrequency = new HashMap<PathClass, Integer>();
        for (var image : images) {
            var uris = image.getServer().getURIs();
            var sources = uris.isEmpty() ? List.of(image.getServer().getPath()) : uris;
            if (sources.stream().anyMatch(seenSources::contains))
                throw new IllegalArgumentException("Validation requires separate source images. A source is selected twice.");
            seenSources.addAll(sources);
            var classes = classesOn(image, strategy);
            if (classes.isEmpty())
                throw new IllegalArgumentException("Add classified, unlocked annotations to " + ClassifierTrainer.imageName(image) + ".");
            classes.forEach(label -> classFrequency.merge(label, 1, Integer::sum));
        }

        var classes = classFrequency.keySet().stream().sorted(Comparator.comparing(PathClass::toString)).toList();
        if (classes.size() < 2)
            throw new IllegalArgumentException("Validation requires at least two training classes.");
        var missing = classes.stream().filter(c -> classFrequency.get(c) < 2).toList();
        if (!missing.isEmpty())
            throw new IllegalArgumentException("Annotate each class on at least two images. Missing coverage for " + missing + ".");

        var labels = new LinkedHashMap<PathClass, Integer>();
        for (int i = 0; i < classes.size(); i++)
            labels.put(classes.get(i), i);

        var setup = ClassifierTrainer.setup(settings, reference, images);
        var sampler = setup.sampler();
        var folds = new ArrayList<Fold>();
        try {
            for (int i = 0; i < images.size(); i++) {
                var heldOut = images.get(i);
                var name = ClassifierTrainer.imageName(heldOut);
                progress.accept("Validating image " + (i + 1) + "/" + images.size() + " · " + name);
                var training = new ArrayList<>(images);
                training.remove(i);
                try {
                    var trainData = sampler.createTrainingDataForLabelMap(training, labels);
                    var fit = ClassifierTrainer.fit(settings, trainData, settings.parameters());
                    var testData = sampler.createTrainingDataForLabelMap(List.of(heldOut), labels);
                    if (testData == null)
                        throw new IllegalArgumentException("The held-out annotations yielded no pixels at the chosen resolution.");
                    folds.add(new Fold(name, evaluate(fit, testData, classes.size())));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Holding out " + name + ": " + e.getMessage(), e);
                }
            }
            return new Report(classes, List.copyOf(folds));
        } finally {
            sampler.setFeatureOp(null);
        }
    }

    private static Set<PathClass> classesOn(ImageData<BufferedImage> image, BoundaryStrategy strategy) {
        var labels = new TreeSet<PathClass>(Comparator.comparing(PathClass::toString));
        for (var annotation : image.getHierarchy().getAnnotationObjects()) {
            var label = annotation.getPathClass();
            var roi = annotation.getROI();
            if (label == null || label == PathClass.StandardPathClasses.REGION || annotation.isLocked()
                    || roi == null || roi.isEmpty())
                continue;
            labels.add(label);
            var boundary = roi.isArea() ? strategy.getBoundaryClass(label) : null;
            if (boundary != null)
                labels.add(boundary);
        }
        return labels;
    }

    private static long[][] evaluate(ClassifierTrainer.Fit fit, ClassifierTrainingData test, int classes) {
        var matrix = new long[classes][classes];
        try (var data = test.getTrainData(); var features = data.getTrainSamples(); var responses = data.getTrainResponses()) {
            java.nio.IntBuffer expected = responses.createBuffer();
            for (int start = 0; start < features.rows(); start += 32_768) {
                int end = Math.min(features.rows(), start + 32_768);
                try (var rows = features.rowRange(start, end); var samples = rows.clone(); var predictions = new Mat()) {
                    fit.preprocessor().apply(samples, false);
                    fit.model().predict(samples, predictions, null);
                    java.nio.IntBuffer actual = predictions.createBuffer();
                    for (int i = start; i < end; i++) {
                        int prediction = actual.get(i - start);
                        if (prediction < 0 || prediction >= classes)
                            throw new IllegalArgumentException("The classifier returned an invalid validation label.");
                        matrix[expected.get(i)][prediction]++;
                    }
                }
            }
        }
        return matrix;
    }
}
