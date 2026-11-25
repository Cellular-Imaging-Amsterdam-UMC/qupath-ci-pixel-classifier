// SPDX-FileCopyrightText: 2026 CI Pixel Classifier contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package qupath.ext.cipixel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.classifiers.Normalization;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.images.servers.ImageServerMetadata.ChannelType;
import qupath.lib.io.GsonTools;
import qupath.opencv.ml.OpenCVClassifiers.OpenCVStatModel;
import qupath.opencv.ml.pixel.PixelClassifiers;
import qupath.opencv.ops.ImageDataOp;
import qupath.opencv.tools.MultiscaleFeatures.MultiscaleFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NativeClassifierIO {
    private static final Logger logger = LoggerFactory.getLogger(NativeClassifierIO.class);
    private static final String SEQUENTIAL = "op.core.sequential";
    private static final String STAT_MODEL = "op.ml.opencv-statmodel";
    private static final String PREPROCESSOR = "op.ml.feature-preprocessor";

    private NativeClassifierIO() {}

    record Imported(PixelClassifier classifier, TrainingSettings settings, String description) {}

    static Imported read(Path path, TrainingSettings current) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            var json = JsonParser.parseReader(reader);
            // QuPath subtype adapters consume the type fields of the tree they deserialize.
            return inspect(GsonTools.getInstance().fromJson(json.deepCopy(), PixelClassifier.class), json, current);
        }
    }

    static Imported inspect(PixelClassifier classifier, TrainingSettings current) {
        return inspect(classifier, GsonTools.getInstance().toJsonTree(classifier, PixelClassifier.class), current);
    }

    private static Imported inspect(PixelClassifier classifier, JsonElement json, TrainingSettings current) {
        if (classifier == null || classifier.getMetadata() == null)
            throw new IllegalArgumentException("The file does not contain a QuPath pixel classifier.");

        // The 0.7 public API exposes metadata, while the saved operation tree carries the feature and model configuration.
        try {
            var pipeline = json.getAsJsonObject().getAsJsonObject("op");
            if (pipeline == null)
                return predictionOnly(classifier);
            var ops = sequentialOps(pipeline.get("op"));
            if (ops.stream().filter(op -> isType(op, STAT_MODEL, "op.opencv-statmodel")).count() != 1)
                return predictionOnly(classifier);
            int modelIndex = 0;
            while (!isType(ops.get(modelIndex), STAT_MODEL, "op.opencv-statmodel"))
                modelIndex++;
            var modelJson = ops.get(modelIndex).getAsJsonObject().getAsJsonObject("model");
            var modelType = modelJson.get("class").getAsString();
            var model = Arrays.stream(TrainingSettings.Model.values())
                    .filter(m -> m.type.getSimpleName().equals(modelType)).findFirst().orElse(null);
            var output = classifier.getMetadata().getOutputType();
            if (model == null || (output != ChannelType.CLASSIFICATION && output != ChannelType.PROBABILITY))
                return predictionOnly(classifier);

            JsonObject preprocessor = null;
            int end = modelIndex;
            if (end > 0 && isType(ops.get(end - 1), PREPROCESSOR, "op.feature-preprocessor")) {
                preprocessor = ops.get(end - 1).getAsJsonObject().getAsJsonObject("preprocessor");
                end--;
            }
            var featurePipeline = pipeline.deepCopy();
            if (end == 0)
                featurePipeline.remove("op");
            else
                featurePipeline.add("op", sequence(ops.subList(0, end)));
            var gson = GsonTools.getInstance();
            // Deserialize and reserialize the prefix to normalize QuPath's legacy operation aliases.
            var featureOp = gson.fromJson(featurePipeline, ImageDataOp.class);
            var canonical = gson.toJsonTree(featureOp, ImageDataOp.class).getAsJsonObject();
            var recipe = editableRecipe(canonical);
            var parameters = modelParameters(model, modelJson);
            var settings = new TrainingSettings(model, parameters, current.downsample(),
                    classifier.getMetadata().getInputResolution(), recipe == null ? current.features() : recipe,
                    recipe == null ? canonical : null, output, current.maxSamples(), current.seed(), current.balance(),
                    Normalization.NONE, 0, true, preprocessor, current.boundary(), current.boundaryClass(),
                    current.boundaryWidth(), current.previewRegion(), current.opacity(), current.autoTrain());
            String description = recipe == null
                    ? "Loaded resolution, model and output. The imported feature pipeline is preserved as a unit."
                    : "Loaded resolution, channels, features, scales, model and output.";
            if (preprocessor != null)
                description += " The fitted preprocessing is available for reuse.";
            return new Imported(classifier, settings, description);
        } catch (RuntimeException e) {
            logger.debug("No editable training recipe in this classifier", e);
            return predictionOnly(classifier);
        }
    }

    private static Imported predictionOnly(PixelClassifier classifier) {
        return new Imported(classifier, null,
                "Loaded for prediction. This classifier has no editable multiscale training setup.");
    }

    private static Map<String, String> modelParameters(TrainingSettings.Model model, JsonObject json) {
        var wrapped = GsonTools.getInstance().fromJson(json, OpenCVStatModel.class);
        var values = new LinkedHashMap<>(wrapped.getParameterList().getKeyValueParameters(false));
        var nativeModel = json.getAsJsonObject("statmodel");
        if (!nativeModel.has("format") && nativeModel.size() == 1)
            nativeModel = nativeModel.entrySet().iterator().next().getValue().getAsJsonObject();

        if (model == TrainingSettings.Model.NEURAL_NETWORK && nativeModel.has("layer_sizes")) {
            // OpenCV includes the input and output layers, while QuPath's editor wants hidden layers only.
            var layers = nativeModel.getAsJsonArray("layer_sizes");
            for (int i = 1; i <= 5; i++)
                values.put("hidden" + i, i < layers.size() - 1 ? layers.get(i).getAsInt() : 0);
        }
        if (model == TrainingSettings.Model.RANDOM_TREES && nativeModel.has("ntrees")) {
            // OpenCV saves the fitted tree count and omits the requested training termination criteria.
            values.put("maxTrees", nativeModel.get("ntrees").getAsInt());
        }

        var allowed = model.create(Map.of()).getParameterList().getKeyValueParameters(false).keySet();
        var result = new LinkedHashMap<String, String>();
        values.forEach((key, value) -> {
            if (allowed.contains(key) && value != null)
                result.put(key, value.toString());
        });
        return result;
    }

    private static FeatureRecipe editableRecipe(JsonObject json) {
        if (!json.has("colorTransforms"))
            return null;
        var pipeline = sequentialOps(json.get("op"));
        if (pipeline.size() < 1 || pipeline.size() > 2)
            return null;
        var filters = pipeline.getLast();
        if (!isType(filters, "op.core.split-merge"))
            return null;
        var filterList = filters.getAsJsonObject().getAsJsonArray("ops");
        var channels = json.getAsJsonArray("colorTransforms").asList().stream().map(JsonElement::getAsJsonObject).toList();
        var scales = new ArrayList<Double>();
        List<MultiscaleFeature> features = List.of();
        for (var filter : filterList) {
            if (!isType(filter, "op.filters.multiscale"))
                return null;
            var specification = filter.getAsJsonObject();
            var next = specification.getAsJsonArray("features").asList().stream()
                    .map(f -> MultiscaleFeature.valueOf(f.getAsString())).toList();
            if (!features.isEmpty() && !features.equals(next))
                return null;
            features = next;
            double scale = specification.get("sigmaX").getAsDouble();
            if (scale != specification.get("sigmaY").getAsDouble())
                return null;
            scales.add(scale);
        }
        var local = FeatureRecipe.LocalNormalization.NONE;
        double localScale = 8;
        if (pipeline.size() == 2) {
            var norm = pipeline.getFirst().getAsJsonObject();
            if (isType(norm, "op.normalize.local")) {
                localScale = norm.get("sigmaMean").getAsDouble();
                double sd = norm.get("sigmaStdDev").getAsDouble();
                if (sd != 0 && sd != localScale)
                    return null;
                local = sd == 0 ? FeatureRecipe.LocalNormalization.MEAN : FeatureRecipe.LocalNormalization.MEAN_VARIANCE;
            } else if (isType(norm, "op.normalize.local-min-max")) {
                double smoothing = norm.get("sigmaSmooth").getAsDouble();
                localScale = smoothing == 0 ? norm.get("radius").getAsInt() : smoothing;
                local = smoothing == 0 ? FeatureRecipe.LocalNormalization.MIN_MAX
                        : FeatureRecipe.LocalNormalization.MIN_MAX_SMOOTHED;
            } else {
                return null;
            }
        }
        var result = new FeatureRecipe(channels, features, scales, local, localScale);
        try {
            var rebuilt = GsonTools.getInstance().toJsonTree(result.build(null), ImageDataOp.class).getAsJsonObject();
            // Flatten the outer sequence on both sides before comparing. Nested tile filters stay intact.
            if (sameFeaturePipeline(json, rebuilt))
                return result;
        } catch (IllegalArgumentException e) {
            logger.debug("Imported operation does not fit the multiscale editor", e);
        }
        return null;
    }

    private static boolean sameFeaturePipeline(JsonObject a, JsonObject b) {
        return a.get("colorTransforms").equals(b.get("colorTransforms")) &&
                sequentialOps(a.get("op")).equals(sequentialOps(b.get("op")));
    }

    private static boolean isType(JsonElement element, String... types) {
        if (element == null || !element.isJsonObject() || !element.getAsJsonObject().has("type"))
            return false;
        return Arrays.asList(types).contains(element.getAsJsonObject().get("type").getAsString());
    }

    private static List<JsonElement> sequentialOps(JsonElement op) {
        if (op == null || op.isJsonNull())
            return List.of();
        if (!isType(op, SEQUENTIAL))
            return List.of(op);
        return op.getAsJsonObject().getAsJsonArray("ops").asList().stream()
                .flatMap(child -> sequentialOps(child).stream()).toList();
    }

    private static JsonElement sequence(List<JsonElement> ops) {
        if (ops.size() == 1)
            return ops.getFirst();
        var array = new JsonArray();
        ops.forEach(array::add);
        var json = new JsonObject();
        json.addProperty("type", SEQUENTIAL);
        json.add("ops", array);
        return json;
    }

    static void write(PixelClassifier classifier, Path output) throws IOException {
        var absolute = output.toAbsolutePath();
        var temporary = Files.createTempFile(absolute.getParent(), ".ci-pixel-", ".json.tmp");
        try {
            PixelClassifiers.writeClassifier(classifier, temporary);
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
