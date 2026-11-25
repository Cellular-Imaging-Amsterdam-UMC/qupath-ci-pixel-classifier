package qupath.ext.cipixel;

import javafx.beans.property.SimpleDoubleProperty;
import qupath.lib.classifiers.pixel.PixelClassifier;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.gui.viewer.OverlayOptions;
import qupath.lib.gui.viewer.QuPathViewer;
import qupath.lib.gui.viewer.RegionFilter;
import qupath.lib.gui.viewer.overlays.PathOverlay;
import qupath.lib.gui.viewer.overlays.PixelClassificationOverlay;

final class ViewerPrediction {
    private final OverlayOptions options = new OverlayOptions();
    private final SimpleDoubleProperty divider = new SimpleDoubleProperty(0.5);
    private QuPathViewer viewer;
    private PixelClassificationOverlay overlay;
    private CurtainOverlay curtain;
    private PathOverlay previous;
    private PathOverlay installed;
    private boolean comparison;

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

    void setComparison(boolean enabled) {
        comparison = enabled;
        if (overlay != null && viewer.getCustomPixelLayerOverlay() == installed)
            install();
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
        install();
    }

    private void install() {
        if (comparison && curtain == null)
            curtain = new CurtainOverlay(viewer, overlay, divider);
        if (curtain != null) {
            if (comparison)
                curtain.attach();
            else
                curtain.detach();
        }
        installed = comparison ? curtain : overlay;
        viewer.setCustomPixelLayerOverlay(installed);
    }

    void clear() {
        if (overlay == null)
            return;
        if (curtain != null)
            curtain.detach();
        if (viewer.getCustomPixelLayerOverlay() == installed)
            viewer.setCustomPixelLayerOverlay(previous);
        overlay.stop();
        curtain = null;
        installed = null;
        overlay = null;
        viewer = null;
        previous = null;
    }
}
