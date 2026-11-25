package qupath.ext.cipixel;

import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.classifiers.pixel.PixelClassifierMetadata;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ServerTools;
import qupath.lib.objects.classes.PathClass;
import qupath.opencv.ml.FeaturePreprocessor;
import qupath.opencv.ml.pixel.PixelClassifiers;
import qupath.opencv.ops.ImageOps;
import qupath.process.gui.commands.ml.PixelClassifierTraining;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

final class ClassifierTrainer {
    private ClassifierTrainer() {}

    record Result(PixelClassifier classifier, Map<PathClass, Integer> samples) {}

    static Result train(TrainingSettings settings, ImageData<BufferedImage> active,
                        Collection<ImageData<BufferedImage>> images) throws IOException {
        var featureOp = settings.featureOp(active);
        var resolution = settings.resolution(active);
        for (var image : images) {
            if (!featureOp.supportsImage(image))
                throw new IllegalArgumentException("Incompatible training channels in " +
                        ServerTools.getDisplayableImageName(image.getServer()) + ".");
        }
        var training = new PixelClassifierTraining(featureOp);
        training.setResolution(resolution);
        training.setBoundaryStrategy(settings.boundaryStrategy());
        var model = settings.model().create(settings.parameters());
        if (settings.maxSamples() < 0)
            throw new IllegalArgumentException("Maximum samples must be zero or greater.");
        if (!Double.isFinite(settings.pcaVariance()) || settings.pcaVariance() < 0 || settings.pcaVariance() > 1)
            throw new IllegalArgumentException("PCA retained variance must be between 0 and 1.");
        if (settings.output() != qupath.lib.images.servers.ImageServerMetadata.ChannelType.CLASSIFICATION
                && !model.supportsProbabilities())
            throw new IllegalArgumentException("This model does not support probability output.");

        try {
            var data = training.createTrainingData(images);
            if (data == null)
                throw new IllegalArgumentException("Draw unlocked training annotations for at least two classes.");
            opencv_core.setRNGSeed(settings.seed());
            try (var raw = data.getTrainData()) {
                if (settings.maxSamples() > 0 && raw.getNTrainSamples() > settings.maxSamples())
                    raw.setTrainTestSplit(settings.maxSamples(), true);
                else
                    raw.shuffleTrainTest();

                try (var samples = raw.getTrainSamples();
                     var targets = raw.getTrainResponses()) {
                    var counts = countLabels(data.getLabelMap(), targets);
                    if (counts.values().stream().anyMatch(n -> n == 0))
                        throw new IllegalArgumentException("A class has no sampled pixels. Add training regions, use a finer resolution, or raise the sample limit.");
                    var preprocessor = settings.fittedPreprocessing() == null
                            ? FeaturePreprocessor.builder().normalize(settings.normalization())
                                .pca(settings.pcaVariance(), settings.normalizePca()).missingValue(0).build(samples, false)
                            : qupath.lib.io.GsonTools.getInstance().fromJson(settings.fittedPreprocessing(), FeaturePreprocessor.class);
                    preprocessor.apply(samples, false);
                    try (var weights = settings.balance() ? sampleWeights(targets, counts, data.getLabelMap()) : null;
                         var trainData = model.createTrainData(samples, targets, weights, false)) {
                        model.train(trainData);
                    }
                    if (!model.isTrained())
                        throw new IllegalArgumentException("OpenCV could not train this model with the current settings.");
                    if (preprocessor.doesSomething())
                        featureOp = featureOp.appendOps(ImageOps.ML.preprocessor(preprocessor));

                    var labels = new TreeMap<Integer, PathClass>();
                    data.getLabelMap().forEach((label, id) -> labels.put(id, label));
                    var metadata = new PixelClassifierMetadata.Builder()
                            .inputResolution(resolution)
                            .inputShape(512, 512)
                            .setChannelType(settings.output())
                            .outputChannels(ServerTools.classificationLabelsToChannels(labels, true))
                            .build();
                    var classifier = PixelClassifiers.createClassifier(model, featureOp, metadata, true);
                    return new Result(classifier, counts);
                }
            }
        } finally {
            training.setFeatureOp(null);
        }
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
