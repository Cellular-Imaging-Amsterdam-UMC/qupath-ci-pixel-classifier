// SPDX-FileCopyrightText: 2026 CI Pixel Classifier contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package qupath.ext.cipixel;

import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.io.GsonTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.prefs.Preferences;

final class SettingsStore {
    private static final Logger logger = LoggerFactory.getLogger(SettingsStore.class);
    // Preferences values are capped at 8 KiB. Fitted PCA matrices can exceed this.
    private static final int PART_LENGTH = 3000;

    @SuppressWarnings("deprecation")
    private Preferences node() {
        return PathPrefs.getUserPreferences().node("ci-pixel-classifier-v1");
    }

    TrainingSettings load() {
        try {
            var node = node();
            int active = node.getInt("active", 0);
            var data = node.node("slot" + active);
            int count = data.getInt("parts", 0);
            if (count < 1)
                return null;
            var text = new StringBuilder();
            for (int i = 0; i < count; i++)
                text.append(Objects.requireNonNull(data.get("part" + i, null)));
            return GsonTools.getInstance().fromJson(text.toString(), TrainingSettings.class);
        } catch (RuntimeException e) {
            logger.warn("Could not restore CI Pixel Classifier settings", e);
            return null;
        }
    }

    void save(TrainingSettings settings) {
        try {
            var parent = node();
            int next = 1 - parent.getInt("active", 0);
            var data = parent.node("slot" + next);
            data.clear();
            String text = GsonTools.getInstance().toJson(settings);
            int parts = 0;
            for (int start = 0; start < text.length(); start += PART_LENGTH)
                data.put("part" + parts++, text.substring(start, Math.min(text.length(), start + PART_LENGTH)));
            data.putInt("parts", parts);
            data.flush();
            parent.putInt("active", next);
            parent.flush();
        } catch (Exception e) {
            logger.warn("Could not save CI Pixel Classifier settings", e);
        }
    }
}
