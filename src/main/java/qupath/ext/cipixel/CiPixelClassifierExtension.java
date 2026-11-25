package qupath.ext.cipixel;

import javafx.scene.control.MenuItem;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

public final class CiPixelClassifierExtension implements QuPathExtension {
    private ClassifierWorkspace workspace;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (workspace != null)
            return;
        workspace = new ClassifierWorkspace(qupath);
        var item = new MenuItem("CI Pixel Classifier…");
        item.setOnAction(e -> workspace.show());
        qupath.getMenu("Extensions", true).getItems().add(item);
    }

    @Override
    public String getName() {
        return "CI Pixel Classifier";
    }

    @Override
    public String getDescription() {
        return "Train, preview, import and export native QuPath pixel classifiers.";
    }

    @Override
    public Version getQuPathVersion() {
        return Version.parse("0.7.0");
    }
}
