package qupath.ext.cipixel;

import javafx.scene.control.Menu;
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
        var loader = new ClassifierLoader(qupath, workspace);
        var menu = new Menu("CI Pixel Classifier");
        var train = new MenuItem("Train classifier…");
        train.setOnAction(e -> {
            loader.hide();
            workspace.show();
        });
        var load = new MenuItem("Load classifier…");
        load.setOnAction(e -> workspace.switchTo(loader::show));
        workspace.setLoader(() -> workspace.switchTo(loader::show));
        menu.getItems().addAll(train, load);
        qupath.getMenu("Extensions", true).getItems().add(menu);
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
