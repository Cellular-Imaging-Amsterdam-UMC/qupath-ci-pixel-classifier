// SPDX-FileCopyrightText: 2026 CI Pixel Classifier contributors
// SPDX-FileCopyrightText: 2018 - 2025 QuPath developers, The University of Edinburgh
// SPDX-License-Identifier: GPL-3.0-or-later
// Upstream reference: QuPath 0.7.0 PixelClassifierPane and the QuPath 0.8 training references.
// Adapted for CI Pixel Classifier on 2026-11-25. See THIRD-PARTY-NOTICES.txt.

package qupath.ext.cipixel;

import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.classifiers.pixel.PixelClassifierMetadata;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata.ChannelType;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.classes.PathClass;
import qupath.opencv.ml.FeaturePreprocessor;
import qupath.opencv.ml.OpenCVClassifiers.OpenCVStatModel;
import qupath.opencv.ml.OpenCVClassifiers.RTreesClassifier;
import qupath.opencv.ml.pixel.PixelClassifiers;
import qupath.opencv.ops.ImageDataOp;
import qupath.opencv.ops.ImageOps;
import qupath.process.gui.commands.ml.PixelClassifierTraining;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class ClassifierTrainer {
    private ClassifierTrainer() {}

    record Result(PixelClassifier classifier, Map<PathClass, Integer> samples) {}
    record FeatureRank(int band, String name, double weight) {}
    record Fit(OpenCVStatModel model, FeaturePreprocessor preprocessor, Map<PathClass, Integer> samples) {}
    record Setup(TrainingSettings settings, PixelCalibration resolution, ImageDataOp features) {
        PixelClassifierTraining sampler() {
            var sampler = new PixelClassifierTraining(features);
            sampler.setResolution(resolution);
            sampler.setBoundaryStrategy(settings.boundaryStrategy());
            return sampler;
        }
    }

    static Setup setup(TrainingSettings settings, ImageData<BufferedImage> active,
                       Collection<ImageData<BufferedImage>> images) {
        var features = settings.featureOp(active);
        if (features.getChannels(active).size() > opencv_core.CV_CN_MAX)
            throw new IllegalArgumentException("Too many features. Reduce the number of channels, filters or scales.");
        for (var image : images) {
            if (!features.supportsImage(image))
                throw new IllegalArgumentException("Incompatible training channels in " + imageName(image) + ".");
        }
        if (settings.maxSamples() < 0)
            throw new IllegalArgumentException("Maximum samples must be zero or greater.");
        if (!Double.isFinite(settings.pcaVariance()) || settings.pcaVariance() < 0 || settings.pcaVariance() > 1)
            throw new IllegalArgumentException("PCA retained variance must be between 0 and 1.");
        return new Setup(settings, settings.resolution(active), features);
    }

    static Result train(TrainingSettings settings, ImageData<BufferedImage> active,
                        Collection<ImageData<BufferedImage>> images) throws IOException {
        var setup = setup(settings, active, images);
        var sampler = setup.sampler();
        try {
            var data = sampler.createTrainingData(images);
            var fit = fit(settings, data, settings.parameters());
            var features = setup.features();
            if (fit.preprocessor().doesSomething())
                features = features.appendOps(ImageOps.ML.preprocessor(fit.preprocessor()));

            var labels = new TreeMap<Integer, PathClass>();
            data.getLabelMap().forEach((label, id) -> labels.put(id, label));
            var metadata = new PixelClassifierMetadata.Builder()
                    .inputResolution(setup.resolution())
                    .inputShape(512, 512)
                    .setChannelType(settings.output())
                    .outputChannels(ServerTools.classificationLabelsToChannels(labels, true))
                    .build();
            return new Result(PixelClassifiers.createClassifier(fit.model(), features, metadata, true), fit.samples());
        } finally {
            sampler.setFeatureOp(null);
        }
    }

    static List<FeatureRank> importance(TrainingSettings settings, ImageData<BufferedImage> active,
                                        Collection<ImageData<BufferedImage>> images) throws IOException {
        if (settings.model() != TrainingSettings.Model.RANDOM_TREES)
            throw new IllegalArgumentException("Select Random trees to calculate filter importance.");
        if (settings.pcaVariance() > 0 || (settings.fittedPreprocessing() != null
                && settings.fittedPreprocessing().has("pca")))
            throw new IllegalArgumentException("Turn off PCA to rank the original filter channels.");
        var setup = setup(settings, active, images);
        var sampler = setup.sampler();
        try {
            var params = new LinkedHashMap<>(settings.parameters());
            params.put("calcImportance", "true");
            var fit = fit(settings, sampler.createTrainingData(images), params);
            var model = (RTreesClassifier) fit.model();
            var names = setup.features().getChannels(active);
            var weights = model.getFeatureImportance();
            if (weights == null || weights.length != names.size())
                throw new IllegalArgumentException("OpenCV did not return a score for each feature channel.");
            var rank = new ArrayList<FeatureRank>();
            for (int i = 0; i < weights.length; i++)
                rank.add(new FeatureRank(i, names.get(i).getName(), weights[i]));
            rank.sort(Comparator.comparingDouble(FeatureRank::weight).reversed());
            return List.copyOf(rank);
        } finally {
            sampler.setFeatureOp(null);
        }
    }

    static Fit fit(TrainingSettings settings, PixelClassifierTraining.ClassifierTrainingData data,
                   Map<String, String> parameters) {
        if (data == null)
            throw new IllegalArgumentException("Draw unlocked training annotations for at least two classes.");
        var model = settings.model().create(parameters);
        if (settings.output() != ChannelType.CLASSIFICATION && !model.supportsProbabilities())
            throw new IllegalArgumentException("This model does not support probability output.");

        opencv_core.setRNGSeed(settings.seed());
        try (var raw = data.getTrainData()) {
            if (settings.maxSamples() > 0 && raw.getNTrainSamples() > settings.maxSamples())
                raw.setTrainTestSplit(settings.maxSamples(), true);
            else
                raw.shuffleTrainTest();
            try (var samples = raw.getTrainSamples(); var targets = raw.getTrainResponses()) {
                var counts = countLabels(data.getLabelMap(), targets);
                if (counts.values().stream().anyMatch(n -> n == 0))
                    throw new IllegalArgumentException("A class has no sampled training pixels. Add regions, use a finer resolution, or raise the sample limit.");
                var preprocessor = settings.fittedPreprocessing() == null
                        ? FeaturePreprocessor.builder().normalize(settings.normalization())
                            .pca(settings.pcaVariance(), settings.normalizePca()).missingValue(0).build(samples, false)
                        : GsonTools.getInstance().fromJson(settings.fittedPreprocessing(), FeaturePreprocessor.class);
                preprocessor.apply(samples, false);
                try (var weights = settings.balance() ? sampleWeights(targets, counts, data.getLabelMap()) : null;
                     var trainData = model.createTrainData(samples, targets, weights, false)) {
                    model.train(trainData);
                }
                if (!model.isTrained())
                    throw new IllegalArgumentException("OpenCV could not train this model with the current settings.");
                return new Fit(model, preprocessor, counts);
            }
        }
    }

    static String imageName(ImageData<BufferedImage> image) {
        return ServerTools.getDisplayableImageName(image.getServer());
    }

    private static Map<PathClass, Integer> countLabels(Map<PathClass, Integer> labels, Mat targets) {
        IntBuffer buffer = targets.createBuffer();
        var counts = new int[labels.size()];
        for (int i = 0; i < targets.rows(); i++)
            counts[buffer.get(i)]++;
        var result = new LinkedHashMap<PathClass, Integer>();
        labels.forEach((label, id) -> result.put(label, counts[id]));
        return result;
    }

    private static Mat sampleWeights(Mat targets, Map<PathClass, Integer> counts, Map<PathClass, Integer> labels) {
        int n = targets.rows();
        var weights = new Mat(n, 1, opencv_core.CV_32FC1);
        IntBuffer buffer = targets.createBuffer();
        var byLabel = new float[counts.size()];
        labels.forEach((label, id) -> byLabel[id] = (float) n / counts.get(label));
        try (FloatIndexer output = weights.createIndexer()) {
            for (int i = 0; i < n; i++)
                output.put(i, byLabel[buffer.get(i)]);
        }
        return weights;
    }
}
