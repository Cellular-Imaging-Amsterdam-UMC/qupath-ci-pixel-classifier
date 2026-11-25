// SPDX-FileCopyrightText: 2026 CI Pixel Classifier contributors
// SPDX-FileCopyrightText: 2018 - 2025 QuPath developers, The University of Edinburgh
// SPDX-License-Identifier: GPL-3.0-or-later
// Upstream reference: QuPath 0.7.0 PixelClassifierPane.
// Adapted for CI Pixel Classifier on 2026-11-25. See THIRD-PARTY-NOTICES.txt.

package qupath.ext.cipixel;

import com.google.gson.JsonObject;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.controlsfx.control.CheckComboBox;
import qupath.lib.classifiers.Normalization;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import qupath.lib.gui.dialogs.ParameterPanelFX;
import qupath.lib.images.ImageData;
import qupath.lib.images.servers.ImageServerMetadata.ChannelType;
import qupath.lib.images.servers.PixelCalibration;
import qupath.lib.objects.classes.PathClass;
import qupath.opencv.tools.MultiscaleFeatures.MultiscaleFeature;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class TrainingEditor {
    private final ComboBox<TrainingSettings.Model> model = combo(TrainingSettings.Model.values());
    private final ComboBox<ChannelType> output = combo(ChannelType.CLASSIFICATION, ChannelType.PROBABILITY);
    private final TextField downsample = input("downsample");
    private final Label resolutionHint = note("");
    private final Label missingChannels = note("");
    private final CheckComboBox<JsonObject> channels = new CheckComboBox<>();
    private final CheckComboBox<MultiscaleFeature> features = new CheckComboBox<>();
    private final TextField scales = input("feature-scales");
    private final ComboBox<FeatureRecipe.LocalNormalization> local = combo(FeatureRecipe.LocalNormalization.values());
    private final TextField localScale = input("local-scale");
    private final TextField maxSamples = input("max-samples");
    private final TextField seed = input("sample-seed");
    private final CheckBox balance = new CheckBox("Balance class weights");
    private final ComboBox<Normalization> normalization = combo(Normalization.values());
    private final TextField pca = input("pca-variance");
    private final CheckBox normalizePca = new CheckBox("Normalize PCA output");
    private final ComboBox<TrainingSettings.Boundary> boundary = combo(TrainingSettings.Boundary.values());
    private final ComboBox<PathClass> boundaryClass = new ComboBox<>();
    private final TextField boundaryWidth = input("boundary-width");
    private final Label fittedHint = note("");
    private final VBox featureFields;
    private final VBox importedFields;
    private final HBox localFields;
    private final VBox customPreprocessing;
    private final VBox fittedFields;
    private final Runnable onChange;
    private final VBox content;
    private Map<String, String> parameters = Map.of();
    private PixelCalibration fixedResolution;
    private JsonObject importedPipeline;
    private JsonObject fitted;
    private ImageData<BufferedImage> image;
    private boolean updating;

    TrainingEditor(Runnable onChange, Runnable showFeatures, Runnable showImportance) {
        this.onChange = onChange;
        model.setId("model");
        output.setId("output-type");
        channels.setId("feature-channels");
        features.setId("feature-types");
        features.setTitle("Selected");
        features.setShowCheckedCount(true);
        output.setMinWidth(115);
        boundaryClass.setMaxWidth(Double.MAX_VALUE);
        output.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(ChannelType type) {
                return type == ChannelType.PROBABILITY ? "Probabilities" : "Class labels";
            }

            @Override public ChannelType fromString(String label) {
                throw new UnsupportedOperationException();
            }
        });

        var editParameters = new Button("Parameters…");
        editParameters.setMinWidth(Region.USE_PREF_SIZE);
        editParameters.setOnAction(e -> editParameters());
        var resolutionPresets = new MenuButton("Presets");
        resolutionPresets.setMinWidth(Region.USE_PREF_SIZE);
        addChoice(resolutionPresets, "Full resolution · 1×", () -> setDownsample("1"));
        addChoice(resolutionPresets, "Fine · 2×", () -> setDownsample("2"));
        addChoice(resolutionPresets, "Balanced · 4×", () -> setDownsample("4"));
        addChoice(resolutionPresets, "Coarse · 8×", () -> setDownsample("8"));
        addChoice(resolutionPresets, "Very coarse · 16×", () -> setDownsample("16"));
        addChoice(resolutionPresets, "Overview · 32×", () -> setDownsample("32"));
        var modelCard = card("Model & resolution",
                field("Classifier", model, row(model, editParameters)),
                columns(field("Downsample", downsample, row(downsample, resolutionPresets)),
                        field("Output", output)), resolutionHint);

        channels.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(JsonObject channel) {
                return channel == null ? "" : FeatureRecipe.decodeChannel(channel).getName();
            }

            @Override public JsonObject fromString(String label) {
                throw new UnsupportedOperationException();
            }
        });
        channels.setMaxWidth(Double.MAX_VALUE);
        features.setMaxWidth(Double.MAX_VALUE);
        var useImageChannels = new Button("Use image channels");
        useImageChannels.setMinWidth(Region.USE_PREF_SIZE);
        useImageChannels.getStyleClass().add("small-action");
        useImageChannels.setOnAction(e -> {
            updating = true;
            setChannels(FeatureRecipe.defaults(image).channels());
            updating = false;
            inputChanged();
        });
        var scalePresets = new MenuButton("Presets");
        addChoice(scalePresets, "Single scale · 1", () -> scales.setText("1"));
        addChoice(scalePresets, "Fine detail · 0.5, 1, 2", () -> scales.setText("0.5, 1, 2"));
        addChoice(scalePresets, "Multiscale · 1, 2, 4, 8", () -> scales.setText("1, 2, 4, 8"));
        addChoice(scalePresets, "Broad structures · 4, 8, 16", () -> scales.setText("4, 8, 16"));
        var localScaleField = field("Local scale", localScale);
        localScaleField.disableProperty().bind(local.valueProperty().isEqualTo(FeatureRecipe.LocalNormalization.NONE));
        localFields = columns(field("Local normalization", local), localScaleField);
        featureFields = new VBox(6,
                field("Channels", channels, row(channels, useImageChannels)), missingChannels,
                field("Feature filters", features),
                field("Scales in classifier pixels", scales, row(scales, scalePresets)));
        var replacePipeline = new Button("Use editable features");
        replacePipeline.setOnAction(e -> {
            importedPipeline = null;
            updateModes();
            inputChanged();
        });
        importedFields = new VBox(9, note("Training will reuse the imported feature pipeline exactly."),
                replacePipeline);
        var featuresCard = card("Features", featureFields, importedFields);
        var preview = new Button("Show filters…");
        preview.setId("show-filters");
        preview.setOnAction(e -> showFeatures.run());
        var importance = new Button("Importance…");
        importance.setId("filter-importance");
        importance.setOnAction(e -> showImportance.run());
        importance.setTooltip(new Tooltip("Calculate channel, filter and scale importance using Random trees."));
        var featureHeader = row(featuresCard.getChildren().removeFirst(), preview, importance);
        featureHeader.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        featuresCard.getChildren().addFirst(featureHeader);

        var clearFitted = new Button("Fit new preprocessing");
        clearFitted.setOnAction(e -> {
            fitted = null;
            updateModes();
            onChange.run();
        });
        fittedFields = new VBox(8, fittedHint, clearFitted);
        customPreprocessing = new VBox(8,
                columns(field("Feature normalization", normalization), field("PCA retained variance", pca)),
                normalizePca, note("Set PCA retained variance to 0 to turn PCA off."));
        var boundaryClassField = field("Class for all boundaries", boundaryClass);
        boundaryClassField.disableProperty().bind(boundary.valueProperty().isNotEqualTo(TrainingSettings.Boundary.ONE_CLASS));
        var boundaryWidthField = field("Thickness in source pixels", boundaryWidth);
        boundaryWidthField.disableProperty().bind(boundary.valueProperty().isEqualTo(TrainingSettings.Boundary.IGNORE));
        var advanced = new TitledPane("Advanced training settings", new VBox(8,
                localFields,
                columns(field("Maximum samples", maxSamples), field("Random seed", seed)), balance,
                note("Set maximum samples to 0 to use every training pixel."),
                customPreprocessing, fittedFields, field("Annotation boundaries", boundary),
                columns(boundaryWidthField, boundaryClassField)));
        advanced.setId("advanced-training");
        advanced.setExpanded(false);
        advanced.setAnimated(false);

        content = new VBox(8, modelCard, featuresCard, advanced);
        content.setFillWidth(true);

        model.valueProperty().addListener((v, old, next) -> {
            if (updating || next == null)
                return;
            parameters = Map.of();
            updating = true;
            configureOutput(output.getValue());
            updating = false;
            onChange.run();
        });
        downsample.textProperty().addListener((v, old, next) -> {
            if (updating)
                return;
            fixedResolution = null;
            inputChanged();
        });
        channels.getCheckModel().getCheckedItems().addListener((ListChangeListener<JsonObject>) e -> inputChanged());
        features.getCheckModel().getCheckedItems().addListener((ListChangeListener<MultiscaleFeature>) e -> inputChanged());
        scales.textProperty().addListener((v, old, next) -> inputChanged());
        local.valueProperty().addListener((v, old, next) -> inputChanged());
        localScale.textProperty().addListener((v, old, next) -> inputChanged());
        for (var box : List.of(output, normalization, boundary, boundaryClass))
            box.valueProperty().addListener((v, old, next) -> changed());
        for (var field : List.of(maxSamples, seed, pca, boundaryWidth))
            field.textProperty().addListener((v, old, next) -> changed());
        balance.selectedProperty().addListener((v, old, next) -> changed());
        normalizePca.selectedProperty().addListener((v, old, next) -> changed());
    }

    VBox node() {
        return content;
    }

    void setImage(ImageData<BufferedImage> data, Collection<PathClass> classes) {
        updating = true;
        var selected = List.copyOf(channels.getCheckModel().getCheckedItems());
        if (channels.getItems().isEmpty() && selected.isEmpty())
            selected = FeatureRecipe.defaults(data).channels();
        image = data;
        if (fixedResolution != null)
            downsample.setText(format(importedDownsample(parseDouble(downsample.getText(), "Downsample"))));
        setChannels(selected);
        var selectedBoundary = boundaryClass.getValue();
        boundaryClass.getItems().setAll(classes.stream().filter(Objects::nonNull).toList());
        if (selectedBoundary != null) {
            if (!boundaryClass.getItems().contains(selectedBoundary))
                boundaryClass.getItems().add(selectedBoundary);
            boundaryClass.setValue(selectedBoundary);
        }
        updateModes();
        updating = false;
    }

    void apply(TrainingSettings settings) {
        updating = true;
        parameters = settings.parameters();
        model.setValue(settings.model());
        configureOutput(settings.output());
        fixedResolution = settings.fixedResolution();
        downsample.setText(format(importedDownsample(settings.downsample())));
        var recipe = settings.features();
        setChannels(recipe.channels());
        var allFeatures = new LinkedHashSet<>(recipe.features());
        allFeatures.addAll(FeatureRecipe.supportedFilters());
        features.getItems().setAll(allFeatures);
        features.getCheckModel().clearChecks();
        recipe.features().forEach(features.getCheckModel()::check);
        scales.setText(String.join(", ", recipe.scales().stream().map(TrainingEditor::format).toList()));
        local.setValue(recipe.localNormalization());
        localScale.setText(format(recipe.localScale()));
        importedPipeline = settings.importedPipeline();
        maxSamples.setText(Integer.toString(settings.maxSamples()));
        seed.setText(Integer.toString(settings.seed()));
        balance.setSelected(settings.balance());
        normalization.setValue(settings.normalization());
        pca.setText(format(settings.pcaVariance()));
        normalizePca.setSelected(settings.normalizePca());
        fitted = settings.fittedPreprocessing();
        boundary.setValue(settings.boundary());
        var pathClass = settings.boundaryClass();
        if (pathClass != null && !boundaryClass.getItems().contains(pathClass))
            boundaryClass.getItems().add(pathClass);
        boundaryClass.setValue(pathClass);
        boundaryWidth.setText(format(settings.boundaryWidth()));
        updateModes();
        updating = false;
    }

    TrainingSettings read(qupath.lib.gui.viewer.RegionFilter.StandardRegionFilters region, double opacity,
                          boolean autoTrain) {
        var parsedScales = Arrays.stream(scales.getText().strip().split("[,;\\s]+"))
                .map(s -> parseDouble(s, "Feature scales")).distinct().toList();
        var recipe = new FeatureRecipe(channels.getCheckModel().getCheckedItems(),
                features.getCheckModel().getCheckedItems(), parsedScales, local.getValue(),
                parseDouble(localScale.getText(), "Local scale"));
        return new TrainingSettings(model.getValue(), parameters, parseDouble(downsample.getText(), "Downsample"),
                fixedResolution, recipe, importedPipeline, output.getValue(),
                parseInt(maxSamples.getText(), "Maximum samples"), parseInt(seed.getText(), "Random seed"),
                balance.isSelected(), normalization.getValue(), parseDouble(pca.getText(), "PCA retained variance"),
                normalizePca.isSelected(), fitted, boundary.getValue(), boundaryClass.getValue(),
                parseDouble(boundaryWidth.getText(), "Boundary thickness"), region, opacity, autoTrain);
    }

    private static double parseDouble(String text, String label) {
        try {
            double result = Double.parseDouble(text.strip());
            if (Double.isFinite(result))
                return result;
        } catch (NumberFormatException ignored) {
            // Report the field name below.
        }
        throw new IllegalArgumentException(label + " requires a number.");
    }

    private static int parseInt(String text, String label) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " requires a whole number.");
        }
    }

    private void editParameters() {
        var params = model.getValue().create(parameters).getParameterList();
        var dialog = new Dialog<ButtonType>();
        dialog.initOwner(content.getScene().getWindow());
        dialog.setTitle("Classifier parameters");
        dialog.getDialogPane().setContent(new ParameterPanelFX(params).getPane());
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        WindowStyle.decorate(dialog);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            var copy = new java.util.LinkedHashMap<String, String>();
            params.getKeyValueParameters(false).forEach((key, value) -> copy.put(key, value.toString()));
            parameters = Map.copyOf(copy);
            onChange.run();
        }
    }

    private void setChannels(List<JsonObject> selected) {
        var choices = new LinkedHashSet<>(selected);
        choices.addAll(FeatureRecipe.availableChannels(image));
        channels.getItems().setAll(choices);
        channels.getCheckModel().clearChecks();
        selected.forEach(channels.getCheckModel()::check);
        updateSelectionTitles();
    }

    private void configureOutput(ChannelType requested) {
        boolean probabilities = model.getValue().probabilities;
        if (probabilities)
            output.getItems().setAll(ChannelType.CLASSIFICATION, ChannelType.PROBABILITY);
        else
            output.getItems().setAll(ChannelType.CLASSIFICATION);
        output.setValue(probabilities && requested == ChannelType.PROBABILITY ? requested : ChannelType.CLASSIFICATION);
        output.setDisable(!probabilities);
    }

    private void setDownsample(String value) {
        if (Objects.equals(downsample.getText(), value) && fixedResolution != null) {
            fixedResolution = null;
            inputChanged();
        } else {
            downsample.setText(value);
        }
    }

    private void inputChanged() {
        if (updating)
            return;
        if (fitted != null) {
            fitted = null;
            fittedHint.setText("");
        }
        changed();
    }

    private void changed() {
        if (updating)
            return;
        updateModes();
        onChange.run();
    }

    private void updateModes() {
        setVisible(featureFields, importedPipeline == null);
        setVisible(importedFields, importedPipeline != null);
        setVisible(localFields, importedPipeline == null);
        setVisible(customPreprocessing, fitted == null);
        setVisible(fittedFields, fitted != null);
        fittedHint.setText("Training will reuse the fitted preprocessing from the imported classifier.");
        updateSelectionTitles();
        if (fixedResolution != null) {
            var value = formatResolution(fixedResolution.getAveragedPixelSize().doubleValue());
            var units = fixedResolution.hasPixelSizeMicrons() ? " µm/px" : "×";
            resolutionHint.setText("Using saved resolution (" + value + units + "). Edit downsample to change it.");
        } else if (image == null) {
            resolutionHint.setText("Downsample is relative to the open image.");
        } else {
            try {
                var cal = image.getServer().getPixelCalibration();
                double scale = parseDouble(downsample.getText(), "Downsample");
                resolutionHint.setText(cal.hasPixelSizeMicrons()
                        ? formatResolution(cal.getAveragedPixelSizeMicrons() * scale) + " µm per classifier pixel"
                        : "One classifier pixel spans " + format(scale) + " source pixels.");
            } catch (IllegalArgumentException e) {
                resolutionHint.setText(e.getMessage());
            }
        }
    }

    private double importedDownsample(double fallback) {
        if (fixedResolution == null)
            return fallback;
        double size = fixedResolution.getAveragedPixelSize().doubleValue();
        if (!fixedResolution.hasPixelSizeMicrons())
            return size;
        if (image != null && image.getServer().getPixelCalibration().hasPixelSizeMicrons())
            return size / image.getServer().getPixelCalibration().getAveragedPixelSizeMicrons();
        return fallback;
    }

    private void updateSelectionTitles() {
        var selected = channels.getCheckModel().getCheckedItems();
        var names = selected.stream().map(FeatureRecipe::decodeChannel).map(c -> c.getName()).toList();
        channels.setTooltip(names.isEmpty() ? null : new Tooltip(String.join(", ", names)));
        var missing = image == null ? List.<String>of() : selected.stream().map(FeatureRecipe::decodeChannel)
                .filter(c -> !c.supportsImage(image.getServer())).map(c -> c.getName()).toList();
        missingChannels.setText("Unavailable on this image: " + String.join(", ", missing));
        setVisible(missingChannels, !missing.isEmpty());
        var filters = features.getCheckModel().getCheckedItems();
        features.setTooltip(filters.isEmpty() ? null : new Tooltip(String.join(", ", filters.stream().map(Object::toString).toList())));
    }

    static <T> ComboBox<T> combo(T[] values) {
        var control = new ComboBox<T>();
        control.getItems().setAll(values);
        control.setMaxWidth(Double.MAX_VALUE);
        return control;
    }

    static <T> ComboBox<T> combo(T first, T second) {
        var control = new ComboBox<T>();
        control.getItems().addAll(List.of(first, second));
        control.setMaxWidth(Double.MAX_VALUE);
        return control;
    }

    static TextField input(String id) {
        var field = new TextField();
        field.setId(id);
        field.setMinWidth(64);
        return field;
    }

    static HBox row(Node grow, Node... others) {
        var result = new HBox(8, grow);
        result.getChildren().addAll(others);
        HBox.setHgrow(grow, Priority.ALWAYS);
        return result;
    }

    static HBox columns(Node first, Node second) {
        var result = new HBox(12, first, second);
        HBox.setHgrow(first, Priority.ALWAYS);
        HBox.setHgrow(second, Priority.ALWAYS);
        return result;
    }

    static Label note(String text) {
        var label = new Label(text);
        label.getStyleClass().add("note");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    static VBox field(String name, Node control) {
        return field(name, control, control);
    }

    static VBox field(String name, Node control, Node content) {
        var label = new Label(name);
        label.getStyleClass().add("field-label");
        label.setLabelFor(control);
        var result = new VBox(4, label, content);
        result.setMaxWidth(Double.MAX_VALUE);
        return result;
    }

    static VBox card(String title, Node... body) {
        var heading = new Label(title);
        heading.getStyleClass().add("card-heading");
        var result = new VBox(9, heading);
        result.getStyleClass().add("card");
        result.getChildren().addAll(body);
        return result;
    }

    static void addChoice(MenuButton control, String label, Runnable action) {
        var item = new MenuItem(label);
        item.setOnAction(e -> action.run());
        control.getItems().add(item);
    }

    private static void setVisible(Node node, boolean show) {
        node.setVisible(show);
        node.setManaged(show);
    }

    private static String formatResolution(double value) {
        return java.math.BigDecimal.valueOf(value).round(new java.math.MathContext(5))
                .stripTrailingZeros().toPlainString();
    }

    private static String format(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
