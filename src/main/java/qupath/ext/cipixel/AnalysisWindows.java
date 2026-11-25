// SPDX-FileCopyrightText: 2026 CI Pixel Classifier contributors
// SPDX-License-Identifier: GPL-3.0-or-later
// Upstream reference: QuPath 0.8 CrossValidationTask and FeatureImportanceTask.
// Adapted for CI Pixel Classifier on 2026-11-25. See THIRD-PARTY-NOTICES.txt.

package qupath.ext.cipixel;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import static qupath.ext.cipixel.TrainingEditor.*;

final class AnalysisWindows {
    private AnalysisWindows() {}

    static Stage showImportance(Stage owner, List<ClassifierTrainer.FeatureRank> ranks, IntConsumer preview) {
        var table = new TableView<ClassifierTrainer.FeatureRank>();
        table.setId("feature-importance-table");
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(column("Channel · filter · scale", ClassifierTrainer.FeatureRank::name, 368));
        table.getColumns().add(column("Importance", rank -> String.format("%.4f", rank.weight()), 110));
        table.getItems().setAll(ranks);
        table.setRowFactory(t -> {
            var row = new TableRow<ClassifierTrainer.FeatureRank>();
            row.setOnMouseClicked(e -> {
                if (!row.isEmpty() && e.getButton() == MouseButton.PRIMARY)
                    preview.accept(row.getItem().band());
            });
            return row;
        });
        var open = new Button("Show selected filter");
        open.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        open.setOnAction(e -> preview.accept(table.getSelectionModel().getSelectedItem().band()));
        var description = note("Scores come from a separate Random Trees fit. Select a row to preview that filter.");
        return show(owner, "Filter importance", new BorderPane(table, description, null, open, null), 570, 425);
    }

    static Stage showValidation(Stage owner, ImageValidation.Report report) {
        var matrix = new TableView<List<String>>();
        matrix.setId("validation-matrix");
        matrix.getColumns().add(column("Annotated class", r -> r.getFirst(), 130));
        for (int i = 0; i < report.classes().size(); i++) {
            int index = i + 1;
            var name = report.classes().get(i).toString();
            matrix.getColumns().add(column(name, r -> r.get(index), Math.clamp(name.length() * 8 + 22, 105, 245)));
        }

        var scores = stringTable("Class", "Pixels", "Precision", "Recall", "F1");
        var folds = stringTable("Held-out image", "Pixels", "Accuracy", "Macro F1");
        var totals = new long[report.classes().size()][report.classes().size()];
        report.folds().forEach(fold -> {
            var summary = new Metrics(fold.matrix());
            folds.getItems().add(List.of(fold.image(), number(summary.total()),
                    percent(summary.accuracy()), percent(summary.macroF1())));
            for (int row = 0; row < totals.length; row++)
                for (int col = 0; col < totals.length; col++)
                    totals[row][col] += fold.matrix()[row][col];
        });

        var filter = new ComboBox<Integer>();
        filter.getItems().setAll(IntStream.rangeClosed(0, report.folds().size()).boxed().toList());
        filter.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Integer index) {
                if (index == null)
                    return "";
                return index == 0 ? "All held-out images" : report.folds().get(index - 1).image();
            }

            @Override public Integer fromString(String label) {
                throw new UnsupportedOperationException();
            }
        });
        filter.setMaxWidth(Double.MAX_VALUE);
        var summaryLabel = note("");
        filter.valueProperty().addListener((v, old, next) -> {
            if (next == null)
                return;
            var counts = next == 0 ? totals : report.folds().get(next - 1).matrix();
            var stats = new Metrics(counts);
            summaryLabel.setText(number(stats.total()) + " pixels · Accuracy " + percent(stats.accuracy())
                    + " · Macro F1 " + percent(stats.macroF1()));
            matrix.getItems().clear();
            scores.getItems().clear();
            for (int i = 0; i < counts.length; i++) {
                var label = report.classes().get(i).toString();
                var values = new ArrayList<String>();
                values.add(label);
                for (long count : counts[i])
                    values.add(number(count));
                matrix.getItems().add(values);
                scores.getItems().add(List.of(label, number(stats.annotated(i)), percent(stats.precision(i)),
                        percent(stats.recall(i)), percent(stats.f1(i))));
            }
        });
        filter.setValue(0);
        var tabs = new TabPane(new Tab("Confusion matrix", matrix), new Tab("Class scores", scores),
                new Tab("By image", folds));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        var header = new VBox(6, field("Validation image", filter), summaryLabel,
                note("Rows show annotated classes. Columns show predicted classes."));
        return show(owner, "Image validation", new BorderPane(tabs, header, null, null, null), 695, 474);
    }

    static record Metrics(long[][] matrix) {
        long total() {
            return IntStream.range(0, matrix.length).mapToLong(this::annotated).sum();
        }

        long annotated(int i) {
            return java.util.Arrays.stream(matrix[i]).sum();
        }

        private long predicted(int i) {
            return java.util.Arrays.stream(matrix).mapToLong(row -> row[i]).sum();
        }

        double accuracy() {
            return divide(IntStream.range(0, matrix.length).mapToLong(i -> matrix[i][i]).sum(), total());
        }

        double precision(int i) {
            return divide(matrix[i][i], predicted(i));
        }

        double recall(int i) {
            return divide(matrix[i][i], annotated(i));
        }

        double f1(int i) {
            return divide(2.0 * matrix[i][i], annotated(i) + predicted(i));
        }

        double macroF1() {
            return IntStream.range(0, matrix.length).mapToDouble(this::f1).filter(Double::isFinite)
                    .average().orElse(Double.NaN);
        }

        private static double divide(double a, long b) {
            return b == 0 ? Double.NaN : a / b;
        }
    }

    private static TableView<List<String>> stringTable(String... headers) {
        var table = new TableView<List<String>>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        for (int i = 0; i < headers.length; i++) {
            int index = i;
            table.getColumns().add(column(headers[i], r -> r.get(index), i == 0 ? 170 : 85));
        }
        return table;
    }

    private static <T> TableColumn<T, String> column(String title, Function<T, String> value, double width) {
        var column = new TableColumn<T, String>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        column.setSortable(false);
        return column;
    }

    private static Stage show(Stage owner, String title, BorderPane pane, double width, double height) {
        pane.setPadding(new Insets(10));
        BorderPane.setMargin(pane.getCenter(), new Insets(8, 0, 8, 0));
        var stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("CI Pixel Classifier · " + title);
        WindowStyle.decorate(stage);
        stage.setScene(WindowStyle.scene(pane, width, height));
        stage.show();
        return stage;
    }

    private static String number(long n) {
        return String.format("%,d", n);
    }

    private static String percent(double p) {
        return Double.isFinite(p) ? String.format("%.1f%%", 100 * p) : "—";
    }
}
