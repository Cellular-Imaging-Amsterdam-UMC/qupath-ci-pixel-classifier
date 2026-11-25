// SPDX-FileCopyrightText: 2026 CI Pixel Classifier contributors
// SPDX-FileCopyrightText: 2018 - 2025 QuPath developers, The University of Edinburgh
// SPDX-License-Identifier: GPL-3.0-or-later
// Upstream reference: QuPath 0.7.0 PixelClassifierPane.
// Adapted for CI Pixel Classifier on 2026-11-25. See THIRD-PARTY-NOTICES.txt.

package qupath.ext.cipixel;

import com.google.gson.JsonObject;
import org.bytedeco.opencv.opencv_ml.ANN_MLP;
import org.bytedeco.opencv.opencv_ml.KNearest;
import org.bytedeco.opencv.opencv_ml.LogisticRegression;
import org.bytedeco.opencv.opencv_ml.RTrees;
import org.bytedeco.opencv.opencv_ml.StatModel;
import qupath.lib.classifiers.Normalization;
import qupath.lib.gui.viewer.RegionFilter.StandardRegionFilters;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata.ChannelType;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.io.GsonTools;
import qupath.lib.objects.classes.PathClass;
import qupath.lib.plugins.parameters.ParameterList;
import qupath.opencv.ml.OpenCVClassifiers;
import qupath.opencv.ml.OpenCVClassifiers.OpenCVStatModel;
import qupath.opencv.ops.ImageDataOp;
import qupath.process.gui.commands.ml.BoundaryStrategy;

import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Map;

record TrainingSettings(
        Model model, Map<String, String> parameters, double downsample, PixelCalibration fixedResolution,
        FeatureRecipe features, JsonObject importedPipeline, ChannelType output,
        int maxSamples, int seed, boolean balance, Normalization normalization, double pcaVariance,
        boolean normalizePca, JsonObject fittedPreprocessing, Boundary boundary, PathClass boundaryClass,
        double boundaryWidth, StandardRegionFilters previewRegion, double opacity, boolean autoTrain
) {
    TrainingSettings {
        parameters = Map.copyOf(parameters);
    }

    enum Model {
        RANDOM_TREES("Random trees", RTrees.class, true),
        NEURAL_NETWORK("Neural network", ANN_MLP.class, true),
        K_NEAREST("K-nearest neighbors", KNearest.class, false),
        LOGISTIC_REGRESSION("Logistic regression", LogisticRegression.class, false);

        final Class<? extends StatModel> type;
        final boolean probabilities;
        private final String label;

        Model(String label, Class<? extends StatModel> type, boolean probabilities) {
            this.label = label;
            this.type = type;
            this.probabilities = probabilities;
        }

        OpenCVStatModel create(Map<String, String> parameters) {
            var result = OpenCVClassifiers.createStatModel(type);
            if (!parameters.isEmpty())
                ParameterList.updateParameterList(result.getParameterList(), parameters, Locale.ROOT);
            return result;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum Boundary {
        IGNORE("Keep annotation interiors"),
        DERIVED("Train a separate boundary for each class"),
        ONE_CLASS("Use one class for all boundaries");

        private final String label;

        Boundary(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static TrainingSettings defaults(ImageData<?> image) {
        return new TrainingSettings(Model.RANDOM_TREES, Map.of(), 4, null, FeatureRecipe.defaults(image), null,
                ChannelType.CLASSIFICATION, 100_000, 100, false, Normalization.NONE, 0, true, null,
                Boundary.IGNORE, null, 1, StandardRegionFilters.IMAGE, 0.55, false);
    }

    PixelCalibration resolution(ImageData<?> image) {
        if (fixedResolution != null)
            return fixedResolution;
        if (!Double.isFinite(downsample) || downsample < 1 || downsample > 1024)
            throw new IllegalArgumentException("Downsample must be between 1 and 1024.");
        return image.getServer().getPixelCalibration().createScaledInstance(downsample, downsample, 1);
    }

    ImageDataOp featureOp(ImageData<BufferedImage> image) {
        var op = importedPipeline == null ? features.build(image)
                : GsonTools.getInstance().fromJson(importedPipeline.deepCopy(), ImageDataOp.class);
        if (!op.supportsImage(image))
            throw new IllegalArgumentException("The feature pipeline is incompatible with this image's channels.");
        return op;
    }

    BoundaryStrategy boundaryStrategy() {
        if (boundary != Boundary.IGNORE && (!Double.isFinite(boundaryWidth) || boundaryWidth <= 0))
            throw new IllegalArgumentException("Boundary thickness must be greater than zero.");
        return switch (boundary) {
            case IGNORE -> BoundaryStrategy.getSkipBoundaryStrategy();
            case DERIVED -> BoundaryStrategy.getDerivedBoundaryStrategy(boundaryWidth);
            case ONE_CLASS -> BoundaryStrategy.getClassifyBoundaryStrategy(
                    java.util.Objects.requireNonNull(boundaryClass, "Choose a boundary class."), boundaryWidth);
        };
    }
}
