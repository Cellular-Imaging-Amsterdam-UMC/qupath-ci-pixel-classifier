package qupath.ext.cipixel;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.geometry.Pos;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

final class WindowStyle {
    private static Image icon;

    private WindowStyle() {}

    static Scene scene(Parent root, double width, double height) {
        root.getStyleClass().add("ci-pixel");
        var scene = new Scene(root, width, height);
        scene.getStylesheets().add(WindowStyle.class.getResource("workspace.css").toExternalForm());
        return scene;
    }

    static void decorate(Stage stage) {
        if (!stage.getIcons().contains(icon()))
            stage.getIcons().add(icon());
    }

    static void decorate(Dialog<?> dialog) {
        var pane = dialog.getDialogPane();
        var header = pane.getHeader();
        if (header == null) {
            String text = pane.getHeaderText();
            var label = new Label(text == null || text.isBlank() ? dialog.getTitle() : text);
            label.setWrapText(true);
            header = label;
        }
        var heading = withIcon(header, 22);
        heading.setPadding(new Insets(8, 10, 5, 10));
        pane.setHeader(heading);
        dialog.setOnShowing(e -> {
            var window = pane.getScene().getWindow();
            if (window instanceof Stage stage)
                decorate(stage);
        });
    }

    static HBox withIcon(Node content, int size) {
        var mark = new ImageView(icon());
        mark.setId("ci-window-icon");
        mark.setFitWidth(size);
        mark.setFitHeight(size);
        mark.setPreserveRatio(true);
        var line = new HBox(8, mark, content);
        line.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(content, Priority.ALWAYS);
        return line;
    }

    private static Image icon() {
        if (icon != null)
            return icon;
        var canvas = new Canvas(48, 48);
        var graphics = canvas.getGraphicsContext2D();
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
        icon = canvas.snapshot(snapshot, null);
        return icon;
    }
}
