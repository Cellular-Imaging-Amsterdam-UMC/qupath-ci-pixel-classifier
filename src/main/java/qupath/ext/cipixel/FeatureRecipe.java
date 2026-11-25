package qupath.ext.cipixel;

import com.google.gson.JsonObject;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ColorTransforms;
import qupath.lib.images.servers.ColorTransforms.ColorTransform;
import qupath.lib.io.GsonTools;
import qupath.opencv.ops.ImageDataOp;
import qupath.opencv.ops.ImageOp;
import qupath.opencv.ops.ImageOps;
import qupath.opencv.tools.MultiscaleFeatures.MultiscaleFeature;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

record FeatureRecipe(
        List<JsonObject> channels, List<MultiscaleFeature> features, List<Double> scales,
        LocalNormalization localNormalization, double localScale
) {

    FeatureRecipe {
        channels = List.copyOf(channels);
        features = List.copyOf(features);
        scales = List.copyOf(scales);
    }

    enum LocalNormalization {
        NONE("None"),
        MEAN("Subtract local mean"),
        MEAN_VARIANCE("Local mean and variance"),
        MIN_MAX("Local min and max"),
        MIN_MAX_SMOOTHED("Smoothed local min and max");

        private final String label;

        LocalNormalization(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        ImageOp build(double scale) {
            return switch (this) {
                case NONE -> null;
                case MEAN -> ImageOps.Normalize.localNormalization(scale, 0);
                case MEAN_VARIANCE -> ImageOps.Normalize.localNormalization(scale, scale);
                case MIN_MAX -> ImageOps.Normalize.localNormalizationMinMax((int) Math.ceil(scale), 0);
                case MIN_MAX_SMOOTHED -> ImageOps.Normalize.localNormalizationMinMax((int) Math.ceil(scale), scale);
            };
        }
    }

    static List<MultiscaleFeature> supportedFilters() {
        return Arrays.stream(MultiscaleFeature.values()).filter(MultiscaleFeature::supports2D).toList();
    }

    static FeatureRecipe defaults(ImageData<?> image) {
        return new FeatureRecipe(rawChannels(image), supportedFilters(), List.of(1.0),
                LocalNormalization.NONE, 8.0);
    }

    private static List<JsonObject> rawChannels(ImageData<?> image) {
        if (image == null)
            return List.of();
        return image.getServer().getMetadata().getChannels().stream()
                .map(c -> c.getName()).distinct()
                .map(ColorTransforms::createChannelExtractor).map(FeatureRecipe::encodeChannel).toList();
    }

    static List<JsonObject> availableChannels(ImageData<?> image) {
        var channels = new ArrayList<>(rawChannels(image));
        var stains = image == null ? null : image.getColorDeconvolutionStains();
        if (stains != null) {
            for (int i = 1; i <= 3; i++)
                channels.add(encodeChannel(ColorTransforms.createColorDeconvolvedChannel(stains, i)));
        }
        return channels;
    }

    static JsonObject encodeChannel(ColorTransform channel) {
        return GsonTools.getInstance().toJsonTree(channel, ColorTransform.class).getAsJsonObject();
    }

    static ColorTransform decodeChannel(JsonObject channel) {
        return GsonTools.getInstance().fromJson(channel, ColorTransform.class);
    }

    ImageDataOp build(ImageData<BufferedImage> image) {
        if (channels.isEmpty() || features.isEmpty() || scales.isEmpty())
            throw new IllegalArgumentException("Select at least one channel, feature and scale.");
        if (scales.stream().anyMatch(s -> !Double.isFinite(s) || s <= 0 || s > 128))
            throw new IllegalArgumentException("Feature scales must be greater than 0 and at most 128.");
        if (localNormalization != LocalNormalization.NONE
                && (!Double.isFinite(localScale) || localScale <= 0 || localScale > 128))
            throw new IllegalArgumentException("Local normalization scale must be greater than 0 and at most 128.");

        var transforms = channels.stream().map(FeatureRecipe::decodeChannel).toList();
        if (image != null) {
            var missing = transforms.stream().filter(c -> !c.supportsImage(image.getServer()))
                    .map(ColorTransform::getName).toList();
            if (!missing.isEmpty())
                throw new IllegalArgumentException("Unavailable channels on this image: " + String.join(", ", missing));
        }
        var filters = scales.stream().distinct()
                .map(s -> ImageOps.Filters.features(features, s, s)).toList();
        var featureOp = ImageOps.Core.splitMerge(filters);
        if (localNormalization != LocalNormalization.NONE)
            featureOp = ImageOps.Core.sequential(localNormalization.build(localScale), featureOp);
        return ImageOps.buildImageDataOp(transforms).appendOps(featureOp);
    }

}
