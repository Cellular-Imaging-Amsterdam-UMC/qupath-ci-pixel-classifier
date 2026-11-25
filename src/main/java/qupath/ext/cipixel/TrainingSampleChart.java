// SPDX-FileCopyrightText: 2026 CI Pixel Classifier contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package qupath.ext.cipixel;

import javafx.geometry.Pos;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import qupath.lib.objects.classes.PathClass;

import java.util.Map;

import static qupath.ext.cipixel.TrainingEditor.*;

final class TrainingSampleChart extends VBox {
    private final Label heading = note("");
    private final PieChart chart = new PieChart();
    private final VBox legend = new VBox(3);
    private Map<PathClass, Integer> counts = Map.of();

    TrainingSampleChart() {
        setId("training-pixel-distribution");
        setSpacing(4);
        chart.setId("training-pixel-chart");
        chart.setAnimated(false);
        chart.setLegendVisible(false);
        chart.setLabelsVisible(false);
        chart.setMinSize(73, 73);
        chart.setPrefSize(73, 73);
        chart.setMaxSize(73, 73);
        chart.getStyleClass().add("sample-chart");
        var scroller = new ScrollPane(legend);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setMaxHeight(106);
        scroller.getStyleClass().add("sample-legend");
        var content = row(scroller, chart);
        content.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(heading, content);
        update(counts, true);
    }

    void update(Map<PathClass, Integer> samples, boolean current) {
        boolean hasSamples = !samples.isEmpty();
        setVisible(hasSamples);
        setManaged(hasSamples);
        long total = samples.values().stream().mapToLong(Integer::longValue).sum();
        heading.setText(String.format(current ? "%,d sampled training pixels" : "%,d pixels in previous training", total));
        if (samples.equals(counts))
            return;
        counts = Map.copyOf(samples);
        chart.getData().clear();
        legend.getChildren().clear();
        samples.forEach((pathClass, pixels) -> {
            String name = pathClass.toString();
            var datum = new PieChart.Data(name, pixels);
            var color = pathClass.getColor();
            String fill = color == null ? "#339c8b" : "#%06x".formatted(color & 0xffffff);
            var description = String.format("%s · %,d pixels · %.1f%%", name, pixels, pixels * 100.0 / total);
            datum.nodeProperty().addListener((v, old, node) -> {
                if (node != null) {
                    node.setStyle("-fx-pie-color: " + fill + ";");
                    Tooltip.install(node, new Tooltip(description));
                }
            });
            chart.getData().add(datum);

            var nameLabel = note(name);
            nameLabel.setMinWidth(0);
            var count = note(String.format("%,d", pixels));
            count.getStyleClass().add("class-count");
            count.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            var percentage = new Label(String.format("%.1f%%", pixels * 100.0 / total));
            percentage.setId("training-pixel-share");
            percentage.getStyleClass().add("note");
            percentage.setMinWidth(48);
            percentage.setAlignment(Pos.CENTER_RIGHT);
            var dot = new javafx.scene.layout.Region();
            dot.getStyleClass().add("class-swatch");
            dot.setStyle("-fx-background-color: " + fill + ";");
            var values = row(nameLabel, count, percentage);
            var line = new HBox(6, dot, values);
            HBox.setHgrow(values, javafx.scene.layout.Priority.ALWAYS);
            line.setAlignment(Pos.CENTER_LEFT);
            Tooltip.install(line, new Tooltip(description));
            legend.getChildren().add(line);
        });
        legend.setPrefHeight(Math.min(100, counts.size() * 19.0));
    }
}
