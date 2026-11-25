package qupath.ext.cipixel;

import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.RegionFilter;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.gui.viewer.overlays.PixelClassificationOverlay;

final class ViewerPrediction {
    private final OverlayOptions options = new OverlayOptions();
    private QuPathViewer viewer;
    private PixelClassificationOverlay overlay;
    private PathOverlay previous;

    ViewerPrediction() {
        options.setShowPixelClassification(true);
    }

    void setRegion(RegionFilter filter) {
        if (filter != null)
            options.setPixelClassificationRegionFilter(filter);
        if (viewer != null)
            viewer.repaint();
    }

    void setOpacity(double opacity) {
        if (overlay != null) {
            overlay.setOpacity(opacity);
            viewer.repaint();
        }
    }

    void show(QuPathViewer target, PixelClassifier classifier, double opacity) {
        clear();
        if (target == null || classifier == null || target.getImageData() == null
                || !classifier.supportsImage(target.getImageData()))
            return;
        viewer = target;
        previous = viewer.getCustomPixelLayerOverlay();
        overlay = PixelClassificationOverlay.create(options, classifier,
                Math.max(1, Math.min(8, PathPrefs.numCommandThreadsProperty().get())));
        overlay.setOpacity(opacity);
        overlay.setLivePrediction(true);
        viewer.setCustomPixelLayerOverlay(overlay);
    }

    void clear() {
        if (overlay == null)
            return;
        if (viewer.getCustomPixelLayerOverlay() == overlay)
            viewer.setCustomPixelLayerOverlay(previous);
        overlay.stop();
        overlay = null;
        viewer = null;
        previous = null;
    }
}
