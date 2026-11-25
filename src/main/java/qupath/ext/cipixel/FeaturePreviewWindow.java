package qupath.ext.cipixel;

import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageChannel;
import qupath.lib.regions.RegionRequest;
import qupath.opencv.ops.ImageOps;

import java.awt.image.BufferedImage;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static qupath.ext.cipixel.TrainingEditor.*;

final class FeaturePreviewWindow {
    private static final Logger logger = LoggerFactory.getLogger(FeaturePreviewWindow.class);

    private final Stage stage = new Stage();
    private final ComboBox<Integer> band = new ComboBox<>();
    private final ImageView imageView = new ImageView();
    private final Label status = note("");
    private final Button refresh = new Button("Refresh view");
    private final Supplier<QuPathViewer> viewer;
    private final Supplier<TrainingSettings> settings;
    private final Executor worker;
    private Slice slice;
    private long revision;

    record Slice(int width, int height, int bands, List<String> names, float[] values, RegionRequest request) {}

    FeaturePreviewWindow(Stage owner, Supplier<QuPathViewer> viewer,
                         Supplier<TrainingSettings> settings, Executor worker) {
        this.viewer = viewer;
        this.settings = settings;
        this.worker = worker;
        stage.initOwner(owner);
        stage.setTitle("CI Pixel Classifier · Filter preview");
        stage.setMinWidth(400);
        stage.setMinHeight(325);
        WindowStyle.decorate(stage);
        stage.setOnHidden(e -> {
            revision++;
            clearImage();
            refresh.setDisable(false);
        });

        band.setId("preview-feature-band");
        band.setMaxWidth(Double.MAX_VALUE);
        band.setConverter(new StringConverter<>() {
            @Override public String toString(Integer value) {
                return slice == null || value == null ? "" : slice.names().get(value);
            }

            @Override public Integer fromString(String text) {
                throw new UnsupportedOperationException();
            }
        });
        band.valueProperty().addListener((v, old, next) -> render(next));
        refresh.setTooltip(new javafx.scene.control.Tooltip("Recompute using the current viewer position and training settings."));
        refresh.setOnAction(e -> refresh());
        imageView.setId("preview-feature-image");
        imageView.setPreserveRatio(true);
        var canvas = new StackPane(imageView);
        canvas.getStyleClass().add("filter-canvas");
        imageView.fitWidthProperty().bind(canvas.widthProperty().subtract(16));
        imageView.fitHeightProperty().bind(canvas.heightProperty().subtract(16));
        var header = row(band, refresh);
        header.setAlignment(Pos.CENTER_LEFT);
        var top = WindowStyle.withIcon(field("Feature channel", band, header), 22);
        var root = new BorderPane(canvas, top, null, status, null);
        root.setPadding(new Insets(12));
        BorderPane.setMargin(canvas, new Insets(9, 0, 7, 0));
        status.setId("feature-preview-status");
        stage.setScene(WindowStyle.scene(root, 530, 450));
    }

    void show() {
        if (!stage.isShowing())
            stage.show();
        stage.toFront();
        refresh();
    }

    void hide() {
        stage.hide();
    }

    private void refresh() {
        var current = viewer.get();
        var image = current == null ? null : current.getImageData();
        if (image == null) {
            clearImage();
            status.setText("Open an image to preview its features.");
            return;
        }
        TrainingSettings snapshot;
        try {
            snapshot = settings.get();
        } catch (IllegalArgumentException e) {
            clearImage();
            status.setText(e.getMessage());
            return;
        }
        Integer previous = band.getValue();
        clearImage();
        double centerX = current.getCenterPixelX();
        double centerY = current.getCenterPixelY();
        var plane = current.getImagePlane();
        long requested = ++revision;
        refresh.setDisable(true);
        status.setText("Computing features at the classifier resolution…");
        var task = new Task<Slice>() {
            @Override protected Slice call() throws Exception {
                return sample(snapshot, image, centerX, centerY, plane.getZ(), plane.getT());
            }
        };
        task.setOnSucceeded(e -> {
            if (requested != revision)
                return;
            refresh.setDisable(false);
            var activeViewer = viewer.get();
            if (activeViewer == null || activeViewer.getImageData() != image) {
                status.setText("The image changed. Select Refresh view to preview it.");
                return;
            }
            slice = task.getValue();
            band.getItems().setAll(IntStream.range(0, slice.bands()).boxed().toList());
            band.setDisable(false);
            band.setValue(previous != null && previous < slice.bands() ? previous : 0);
        });
        task.setOnFailed(e -> {
            if (requested != revision)
                return;
            refresh.setDisable(false);
            logger.warn("Unable to compute feature preview", task.getException());
            var message = task.getException().getLocalizedMessage();
            status.setText(message == null ? "Could not compute the feature preview. See the QuPath log." : message);
        });
        worker.execute(task);
    }

    private void clearImage() {
        slice = null;
        band.setValue(null);
        band.getItems().clear();
        band.setDisable(true);
        imageView.setImage(null);
    }

    static Slice sample(TrainingSettings settings, ImageData<BufferedImage> image,
                        double centerX, double centerY, int z, int t) throws Exception {
        var op = settings.featureOp(image);
        try (var server = ImageOps.buildServer(image, op, settings.resolution(image))) {
            double downsample = server.getDownsampleForResolution(0);
            var names = op.getChannels(image).stream().map(ImageChannel::getName).toList();
            if (names.isEmpty() || names.size() > org.bytedeco.opencv.global.opencv_core.CV_CN_MAX)
                throw new IllegalArgumentException("Choose fewer channels, filters or scales for preview.");
            // Limit the sampled area while keeping the classifier's actual pixel resolution.
            int side = Math.min(384, (int) Math.sqrt(4_000_000.0 / names.size()));
            double span = Math.max(1, side * downsample);
            int width = (int) Math.min(server.getWidth(), Math.ceil(span));
            int height = (int) Math.min(server.getHeight(), Math.ceil(span));
            int x = (int) Math.clamp(Math.round(centerX - width * 0.5), 0, server.getWidth() - width);
            int y = (int) Math.clamp(Math.round(centerY - height * 0.5), 0, server.getHeight() - height);
            var request = RegionRequest.createInstance(image.getServer().getPath(), downsample, x, y, width, height, z, t);
            try (var raw = op.apply(image, request); var converted = new Mat()) {
                raw.convertTo(converted, CV_32F);
                if (converted.empty() || converted.channels() != names.size())
                    throw new IllegalArgumentException("The feature pipeline did not return the expected image channels.");
                float[] values = new float[Math.toIntExact(converted.total() * converted.channels())];
                FloatBuffer buffer = converted.createBuffer();
                buffer.get(values);
                return new Slice(converted.cols(), converted.rows(), converted.channels(), names, values, request);
            }
        }
    }

    private void render(Integer selection) {
        if (selection == null || slice == null)
            return;
        int pixels = slice.width() * slice.height();
        var sorted = new float[pixels];
        int finite = 0;
        for (int i = 0; i < pixels; i++) {
            float value = slice.values()[i * slice.bands() + selection];
            if (Float.isFinite(value))
                sorted[finite++] = value;
        }
        Arrays.sort(sorted, 0, finite);
        float low = finite == 0 ? 0 : sorted[(int) ((finite - 1) * 0.02)];
        float high = finite == 0 ? 0 : sorted[(int) ((finite - 1) * 0.98)];
        var argb = new int[pixels];
        for (int i = 0; i < pixels; i++) {
            float value = slice.values()[i * slice.bands() + selection];
            int gray = !Float.isFinite(value) ? 0 : high <= low ? 128
                    : (int) Math.round(255 * Math.clamp((value - (double) low) / (high - (double) low), 0.0, 1.0));
            argb[i] = 0xff000000 | gray << 16 | gray << 8 | gray;
        }
        var preview = new WritableImage(slice.width(), slice.height());
        preview.getPixelWriter().setPixels(0, 0, slice.width(), slice.height(),
                PixelFormat.getIntArgbInstance(), argb, 0, slice.width());
        imageView.setImage(preview);
        status.setText(String.format("%d × %d pixels · z %d · t %d · auto contrast",
                slice.width(), slice.height(), slice.request().getZ() + 1, slice.request().getT() + 1));
    }
}
