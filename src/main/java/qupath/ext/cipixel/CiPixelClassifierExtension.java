// SPDX-FileCopyrightText: 2026 CI Pixel Classifier contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package qupath.ext.cipixel;

import javafx.scene.control.Alert;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.VBox;
import qupath.lib.common.Version;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

public final class CiPixelClassifierExtension implements QuPathExtension {
    private static final String SOURCE = "https://github.com/Cellular-Imaging-Amsterdam-UMC/qupath-ci-pixel-classifier";
    private ClassifierWorkspace workspace;

    @Override
    public void installExtension(QuPathGUI qupath) {
        if (workspace != null)
            return;
        workspace = new ClassifierWorkspace(qupath);
        var loader = new ClassifierLoader(qupath, workspace);
        var menu = new Menu("CI Pixel Classifier");
        menu.setGraphic(WindowStyle.iconView(16));
        var train = new MenuItem("Train classifier…");
        train.setGraphic(WindowStyle.iconView(16));
        train.setOnAction(e -> {
            loader.hide();
            workspace.show();
        });
        var load = new MenuItem("Load classifier…");
        load.setOnAction(e -> workspace.switchTo(loader::show));
        var license = new MenuItem("License and source…");
        license.setOnAction(e -> showLicense(qupath));
        workspace.setLoader(() -> workspace.switchTo(loader::show));
        menu.getItems().addAll(train, load, license);
        qupath.getMenu("Extensions", true).getItems().add(menu);
    }

    private static void showLicense(QuPathGUI qupath) {
        var dialog = new Alert(Alert.AlertType.INFORMATION);
        dialog.initOwner(qupath.getStage());
        dialog.setTitle("CI Pixel Classifier · License and source");
        dialog.setHeaderText("GNU General Public License, version 3 or later");
        var notice = new Label("""
                Copyright © 2026 CI Pixel Classifier contributors.
                Contains adaptations of QuPath. See the third-party notices for the upstream copyright notices and sources.

                This software is provided without warranty. You may redistribute or modify it under the GNU GPL.
                The license and notices are also included in the extension JAR under META-INF.
                """.stripTrailing());
        notice.setWrapText(true);
        var license = new Hyperlink("Read the full GPL and third-party notices");
        license.setOnAction(e -> QuPathGUI.openInBrowser(SOURCE + "#license"));
        var source = new Hyperlink("Find the source code for each release");
        source.setOnAction(e -> QuPathGUI.openInBrowser(SOURCE + "/releases"));
        dialog.getDialogPane().setContent(new VBox(8, notice, license, source));
        dialog.getDialogPane().setPrefWidth(515);
        WindowStyle.decorate(dialog);
        dialog.show();
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
