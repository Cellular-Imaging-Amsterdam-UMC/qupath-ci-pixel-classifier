// SPDX-FileCopyrightText: 2026 CI Pixel Classifier contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// Upstream reference: QuPath 0.8 CurtainsCommand.
// Adapted for CI Pixel Classifier on 2026-11-25. See THIRD-PARTY-NOTICES.txt.

package qupath.ext.cipixel;

import javafx.beans.property.DoubleProperty;
import javafx.beans.InvalidationListener;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.gui.viewer.overlays.PixelClassificationOverlay;
import qupath.lib.images.ImageData;
import qupath.lib.regions.ImageRegion;

import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;

final class CurtainOverlay implements PathOverlay {
    private static final double HANDLE_WIDTH = 24;
    private final QuPathViewer viewer;
    private final PixelClassificationOverlay prediction;
    private final DoubleProperty position;
    private final StackPane handle = new StackPane();
    private final InvalidationListener placement = ignored -> reposition();

    CurtainOverlay(QuPathViewer viewer, PixelClassificationOverlay prediction, DoubleProperty position) {
        this.viewer = viewer;
        this.prediction = prediction;
        this.position = position;

        var line = new Region();
        line.setMaxWidth(2);
        line.setStyle("-fx-background-color: white; -fx-effect: dropshadow(gaussian, black, 3, 0.6, 0, 0);");
        var grip = new Label("↔");
        grip.setStyle("-fx-background-color: #232929; -fx-text-fill: white; -fx-padding: 3 5; "
                + "-fx-background-radius: 4; -fx-border-radius: 4; -fx-border-color: white;");
        handle.getChildren().addAll(line, grip);
        handle.setId("ci-prediction-curtain");
        handle.setManaged(false);
        handle.setPickOnBounds(true);
        handle.setCursor(Cursor.H_RESIZE);
        handle.setAccessibleText("Drag to compare the source on the left with the prediction on the right.");
        Tooltip.install(handle, new Tooltip("Drag to compare source and prediction"));
        handle.setOnMousePressed(e -> {
            if (e.isPrimaryButtonDown())
                e.consume();
        });
        handle.setOnMouseDragged(e -> {
            if (e.isPrimaryButtonDown()) {
                var view = viewer.getView();
                double x = view.sceneToLocal(e.getSceneX(), e.getSceneY()).getX();
                if (view.getWidth() > 0)
                    position.set(Math.clamp(x / view.getWidth(), 0.02, 0.98));
                e.consume();
            }
        });
        handle.setOnMouseReleased(e -> e.consume());
        handle.setOnMouseClicked(e -> e.consume());
    }

    void attach() {
        var view = viewer.getView();
        if (view.getChildren().contains(handle))
            return;
        view.getChildren().add(handle);
        view.widthProperty().addListener(placement);
        view.heightProperty().addListener(placement);
        position.addListener(placement);
        reposition();
    }

    void detach() {
        var view = viewer.getView();
        view.getChildren().remove(handle);
        view.widthProperty().removeListener(placement);
        view.heightProperty().removeListener(placement);
        position.removeListener(placement);
    }

    private double split() {
        return position.get() * viewer.getView().getWidth();
    }

    private void reposition() {
        handle.resizeRelocate(split() - HANDLE_WIDTH / 2, 0, HANDLE_WIDTH, viewer.getView().getHeight());
        viewer.repaint();
    }

    @Override
    public void paintOverlay(Graphics2D graphics, ImageRegion region, double downsample, ImageData<BufferedImage> image,
                             boolean paintCompletely) {
        var view = viewer.getView();
        var clip = new Path2D.Double();
        var topLeft = viewer.componentPointToImagePoint(split(), 0, null, false);
        clip.moveTo(topLeft.getX(), topLeft.getY());
        for (var point : new double[][] {
                {view.getWidth(), 0}, {view.getWidth(), view.getHeight()}, {split(), view.getHeight()}
        }) {
            var transformed = viewer.componentPointToImagePoint(point[0], point[1], null, false);
            clip.lineTo(transformed.getX(), transformed.getY());
        }
        clip.closePath();

        var clipped = (Graphics2D) graphics.create();
        try {
            clipped.clip(clip);
            prediction.paintOverlay(clipped, region, downsample, image, paintCompletely);
        } finally {
            clipped.dispose();
        }
    }

    @Override
    public String getLocationString(ImageData<BufferedImage> image, double x, double y, int z, int t) {
        return viewer.imagePointToComponentPoint(new Point2D.Double(x, y), null, false).getX() >= split()
                ? prediction.getLocationString(image, x, y, z, t) : null;
    }
}
