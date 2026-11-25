# CI Pixel Classifier for QuPath 0.7

Train and reuse QuPath pixel classifiers in a single workspace. It follows QuPath’s Light and Dark themes, remembers the last training setup, and uses QuPath 0.7’s native classifier JSON for both import and export.

[Read the guide and watch the captioned Islet tutorial](https://cellular-imaging-amsterdam-umc.github.io/qupath-ci-pixel-classifier/).

## Install

Use **QuPath 0.7.0**. Download the JAR from [Releases](https://github.com/Cellular-Imaging-Amsterdam-UMC/qupath-ci-pixel-classifier/releases). Copy it into the `extensions` subfolder of the QuPath user directory, restart QuPath, and open **Extensions → CI Pixel Classifier → Train classifier**. In a portable setup, the user directory can point to a separate location outside the QuPath application folder.

## Train, preview and save

1. Open an image. Draw unlocked annotations for at least two different classes. The training panel shows how many annotations it found for each class. Select **Choose project images** to include annotated images from the same project.
2. Pick a classifier, resolution, channels, feature filters and scales. **Model & resolution** shows the effective pixel size for calibrated images. New and reset setups select all ten 2D filters. Saved and imported selections are restored. **Advanced training settings** includes local normalization and scale, sample limits, class weighting, feature normalization, PCA and boundary handling.
3. Select **Show filters** to preview the computed features in a QuPath window. Choose a feature channel in the popup and use **Refresh view** after moving to another region or changing settings.
4. Select **Train classifier**. The training card shows how many sampled pixels each class contributed and its percentage of the training set. The QuPath viewer shows the prediction overlay. Set its region and opacity in **Preview**. Enable **Live Prediction** to update the prediction automatically.
5. Use **Export JSON** for a standalone file, or save the classifier to the open project. Create objects, add measurements, classify detections or export the prediction image using QuPath’s standard tools.

Use the arrow in the title area to collapse or expand the training workspace. The window keeps its width and the button stays in place. **Live Prediction** moves into the header so it stays available while annotating. **Expand** restores the training controls. The Undo arrow beside **Export JSON** uses the active QuPath viewer’s Undo history (**Ctrl+Z**, or **Cmd+Z** on macOS). Changes are remembered in the active QuPath preference profile. Training annotations and classifiers are saved through their usual QuPath workflows. Save or export a trained model if you want to use it in a later session.

## Evaluate and compare

- **Validate** uses the open image and the additional annotated project images selected under **Choose project images**. Each fold fits a fresh model using the other images and evaluates the held-out image. The report offers a confusion matrix, class precision, recall and F1, and metrics per image. Each class must occur on at least two images. For imported fitted preprocessing, choose **Fit new preprocessing** first so each fold learns its own preprocessing.
- **Importance** ranks the configured channel, filter and scale combinations using a separate Random Trees fit. Select a row to preview the corresponding feature channel. Turn off PCA for a ranking of the original filters.
- **Compare with source** adds a draggable vertical line to the QuPath viewer. The left side shows the source. The right side keeps the live prediction overlay at its selected opacity.

## Reuse a saved classifier

Open **Extensions → CI Pixel Classifier → Load classifier**, then choose a project classifier or **Open JSON**. You can preview and apply a compatible model immediately. **Edit** closes the loader and opens the training workspace. QuPath’s standard multiscale pipeline restores the saved calibration, ordered channels, filters, scales, local normalization, output type and the OpenCV parameters available in the file.

- Original JSON records the fitted model. Settings such as the random seed, sample limit and class weighting come from your current setup. For Random Trees, the imported tree count reflects the fitted forest. The original training limit and stopping threshold may be absent.
- Imported fitted normalization and PCA can be reused as-is. Choose **Fit new preprocessing** or edit the feature inputs to fit preprocessing again on the current annotations.
- A supported OpenCV model with a custom feature pipeline can keep that pipeline intact for retraining. Threshold classifiers and other native formats without an editable training setup open for prediction and export when QuPath can load them.
- A classifier still needs compatible channels or stains on the image where you apply it. The viewer reports incompatible inputs.

## Build and test

Use JDK 25. Gradle resolves QuPath 0.7.0 from its published dependencies.

```powershell
.\gradlew.bat clean build
```

The extension JAR is in `build/libs`. The automated tests train a native model, compare its predictions against the classifier loaded by stock QuPath code, and check settings recovery and threshold-classifier round trips.

For an end-to-end Windows desktop test, run the smoke test with the QuPath 0.7.0 console launcher and a lesson image. The optional annotation file provides the lesson’s saved training annotations.

```powershell
.\tools\smoke-test.ps1 -QuPathConsole 'C:\path\QuPath-0.7.0 (console).exe' `
    -Image 'C:\path\qp-lessons\resources\QP-ML-Train-Nuclei-Example\R01.tif' `
    -Annotations 'C:\path\qp-lessons\resources\QP-ML-Train-Nuclei-Example\QP\data\3\data.qpdata'
```

The smoke test imports a classifier created by stock QuPath’s training window, trains and saves a model with the extension, compares prediction pixels through QuPath’s own loader, checks the loader-to-editor handoff and collapse toggle, captures the filter preview in both themes and verifies settings after relaunch. Its project and files are under `build/desktop-smoke-*`.

## References

[QuPath 0.7.0 source](https://github.com/qupath/qupath/tree/v0.7.0) · [QuPath Java API](https://qupath.github.io/javadoc/docs/) · [QuPath extension template](https://github.com/qupath/qupath-extension-template) · [QP Lessons](https://github.com/Cellular-Imaging-Amsterdam-UMC/qp-lessons)

Licensed under GPL-3.0-or-later.
