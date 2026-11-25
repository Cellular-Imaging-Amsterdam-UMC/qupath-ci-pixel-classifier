package qupath.ext.cipixel;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Dialog;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import qupath.lib.common.GeneralTools;

import java.util.List;

final class WindowStyle {
    private static List<Image> icons;

    private WindowStyle() {}

    static Scene scene(Parent root, double width, double height) {
        root.getStyleClass().add("ci-pixel");
        var scene = new Scene(root, width, height);
        scene.getStylesheets().add(WindowStyle.class.getResource("workspace.css").toExternalForm());
        return scene;
    }

    static void decorate(Stage stage) {
        if (stage.getIcons().contains(icons().getFirst()))
            return;
        stage.getIcons().setAll(icons());
        stage.addEventHandler(WindowEvent.WINDOW_SHOWN, e -> Platform.runLater(() -> {
            if (stage.isShowing() && GeneralTools.isWindows())
                WindowsCaptionIcon.apply(stage.getTitle(), icons().get(0), icons().get(1));
        }));
    }

    static void decorate(Dialog<?> dialog) {
        var pane = dialog.getDialogPane();
        dialog.setOnShowing(e -> {
            if (pane.getScene().getWindow() instanceof Stage stage)
                decorate(stage);
        });
    }

    static ImageView iconView(int size) {
        var mark = new ImageView(icons().get(1));
        mark.setFitWidth(size);
        mark.setFitHeight(size);
        mark.setPreserveRatio(true);
        return mark;
    }

    private static List<Image> icons() {
        if (icons == null)
            icons = List.of(icon(16), icon(32), icon(48), icon(64));
        return icons;
    }

    private static Image icon(int size) {
        var canvas = new Canvas(size, size);
        var graphics = canvas.getGraphicsContext2D();
        graphics.scale(size / 48.0, size / 48.0);
        graphics.setFill(Color.web("#145f58"));
        graphics.fillRoundRect(0, 0, 48, 48, 11, 11);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                graphics.setFill(Color.web(row + column < 2 ? "#baf4dc"
                        : row + column == 2 ? "#63c8aa" : "#368d79"));
                graphics.fillRoundRect(8 + column * 11, 8 + row * 11, 10, 10, 2, 2);
            }
        }
        var snapshot = new SnapshotParameters();
        snapshot.setFill(Color.TRANSPARENT);
        return canvas.snapshot(snapshot, null);
    }
}
