/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package determination.xenon.mindustry.install;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXListView;
import determination.xenon.mindustry.MindustryEra;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryVersionDisplay;
import determination.xenon.mindustry.VersionVariant;
import determination.xenon.mindustry.download.GitHubReleaseClient;
import determination.xenon.mindustry.download.MindustryRemoteVersion;
import determination.xenon.mindustry.download.MindustryVersionList;
import determination.xenon.mindustry.download.VersionCache;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.MarkdownDialog;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.RipplerContainer;
import determination.xenon.ui.construct.TwoLineListItem;
import determination.xenon.ui.wizard.Refreshable;
import determination.xenon.ui.wizard.WizardController;
import determination.xenon.ui.wizard.WizardPage;
import determination.xenon.util.DataSizeUnit;
import determination.xenon.util.SettingsMap;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.Nullable;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static determination.xenon.ui.FXUtils.newToggleButton4;
import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/**
 * Step 2 of the Xenon install wizard: pick a concrete release.
 *
 * <p>HMCL-style rich list: every row shows the asset filename as the
 * primary line, build / size / publish date as the subtitle, and tag /
 * channel as inline pill tags — matches the look of HMCL's vanilla
 * version picker so the wizard feels native.</p>
 */
public final class XenonVersionsPage extends VBox implements WizardPage, Refreshable {
    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final WizardController controller;
    private final GitHubReleaseClient client;
    private final JFXListView<MindustryRemoteVersion> list;
    private final Label status;
    private final Label legacyHint;
    private final JFXButton next;
    private VersionVariant variant;

    public XenonVersionsPage(WizardController controller, GitHubReleaseClient client) {
        this.controller = controller;
        this.client = client;
        setSpacing(10);
        setPadding(new Insets(16));
        setFillWidth(true);

        Label title = new Label(i18n("xenon.install.versions.title"));
        title.getStyleClass().add("title");

        status = new Label();
        status.setWrapText(true);

        legacyHint = new Label();
        legacyHint.setWrapText(true);
        legacyHint.setManaged(false);
        legacyHint.setVisible(false);

        list = new JFXListView<>();
        list.getStyleClass().add("no-padding");
        list.setCellFactory(lv -> new VersionCell());
        VBox.setVgrow(list, Priority.ALWAYS);
        list.setPrefHeight(420);

        next = FXUtils.newRaisedButton(i18n("wizard.next"));
        next.setDefaultButton(true);
        next.setDisable(true);
        next.setOnAction(e -> commit());
        list.getSelectionModel().selectedItemProperty()
                .addListener((obs, o, n) -> {
                    next.setDisable(n == null);
                    updateLegacyHint(n);
                });
        // Double-click to commit, mirroring HMCL.
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
                commit();
            }
        });

        getChildren().addAll(title, status, list, legacyHint, next);
    }

    /// Shows a compatibility warning when a pre-v7 vanilla build is selected.
    ///
    /// @param item the selected remote row, or `null` after a refresh
    private void updateLegacyHint(@Nullable MindustryRemoteVersion item) {
        boolean legacy = item != null
                && MindustryEra.of(item.getVariant(), item.getBuild()).isLegacy();
        legacyHint.setManaged(legacy);
        legacyHint.setVisible(legacy);
        if (legacy) {
            legacyHint.setText(i18n("xenon.install.versions.legacy_warning", item.getBuild()));
        }
    }

    private void commit() {
        MindustryRemoteVersion sel = list.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        controller.getSettings().put(WizardKeys.REMOTE_VERSION, sel);
        controller.onNext();
    }

    @Override
    public void onNavigate(SettingsMap settings) {
        VersionVariant chosen = settings.get(WizardKeys.VARIANT);
        if (chosen == null) {
            controller.onPrev(true);
            return;
        }
        if (variant == chosen && !list.getItems().isEmpty()) return;
        variant = chosen;
        refresh();
    }

    @Override
    public void refresh() {
        VersionVariant target = variant;
        next.setDisable(true);
        updateLegacyHint(null);

        // Cache-first: prime the picker with whatever we last fetched so
        // the UI never shows a blank list, then kick off the async
        // network refresh that overwrites both the list and the cache.
        List<MindustryRemoteVersion> cached = VersionCache.load(target, MindustryImportFlow.cachesDirectory());
        if (!cached.isEmpty()) {
            list.getItems().setAll(cached);
            status.setText(i18n("xenon.install.versions.count", cached.size())
                    + "  ·  " + i18n("xenon.install.versions.refreshing"));
        } else {
            list.getItems().clear();
            status.setText(i18n("xenon.install.versions.loading"));
        }

        Schedulers.io().execute(() -> {
            try {
                MindustryVersionList vl = VersionListFactory.listFor(target, client);
                List<MindustryRemoteVersion> versions = vl.refresh();
                // Persist before publishing to UI so a subsequent UI
                // navigation always reads a self-consistent cache.
                VersionCache.save(target, MindustryImportFlow.cachesDirectory(), versions);
                Platform.runLater(() -> {
                    if (variant != target) return; // user navigated away
                    list.getItems().setAll(versions);
                    status.setText(versions.isEmpty()
                            ? i18n("xenon.install.versions.empty")
                            : i18n("xenon.install.versions.count", versions.size()));
                });
            } catch (Throwable ex) {
                LOG.warning("Failed to fetch versions for " + target, ex);
                Platform.runLater(() -> {
                    if (variant != target) return;
                    // Cache-served fallback: keep whatever we already showed,
                    // surface the error subordinately so the user knows the
                    // list is stale rather than authoritative.
                    if (!list.getItems().isEmpty()) {
                        status.setText(i18n("xenon.install.versions.count", list.getItems().size())
                                + "  ·  " + i18n("xenon.install.versions.failed")
                                + " " + ex.getMessage());
                    } else {
                        // First offline start: fall back to the snapshot
                        // bundled with the launcher instead of an empty list.
                        List<MindustryRemoteVersion> bundled = VersionCache.loadBundled(target);
                        if (!bundled.isEmpty()) {
                            list.getItems().setAll(bundled);
                            status.setText(i18n("xenon.install.versions.offline_snapshot", bundled.size()));
                        } else {
                            status.setText(i18n("xenon.install.versions.failed") + " " + ex.getMessage());
                        }
                    }
                });
            }
        });
    }

    @Override
    public String getTitle() {
        return i18n("xenon.install.versions.title");
    }

    /// HMCL-style rich row: title (filename or tag) + subtitle + tags + arrow.
    private final class VersionCell extends ListCell<MindustryRemoteVersion> {
        private final TwoLineListItem twoLine = new TwoLineListItem();
        private final StackPane pane = new StackPane();
        private final JFXButton notesButton;

        VersionCell() {
            HBox hbox = new HBox(12);
            HBox.setHgrow(twoLine, Priority.ALWAYS);
            hbox.setAlignment(Pos.CENTER_LEFT);

            HBox actions = new HBox(8);
            actions.setAlignment(Pos.CENTER_RIGHT);

            // Release-notes button: only shown for rows whose feed carried a body.
            notesButton = newToggleButton4(SVG.FEEDBACK);
            notesButton.setOnAction(e -> {
                MindustryRemoteVersion item = getItem();
                if (item == null || item.getReleaseNotes().isBlank()) {
                    return;
                }
                Controllers.dialog(new MarkdownDialog(
                        item.getTagName().isBlank() ? item.getDisplayVersion() : item.getTagName(),
                        item.getReleaseNotes()));
            });
            notesButton.setManaged(false);
            notesButton.setVisible(false);

            JFXButton actionBtn = newToggleButton4(SVG.ARROW_FORWARD);
            actionBtn.setOnAction(e -> {
                MindustryRemoteVersion item = getItem();
                if (item != null) {
                    list.getSelectionModel().select(item);
                    commit();
                }
            });
            actions.getChildren().addAll(notesButton, actionBtn);

            hbox.getChildren().setAll(twoLine, actions);
            pane.getStyleClass().add("md-list-cell");
            StackPane.setMargin(hbox, new Insets(10, 16, 10, 16));
            pane.getChildren().setAll(new RipplerContainer(hbox));
        }

        @Override
        protected void updateItem(MindustryRemoteVersion item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            setGraphic(pane);

            // MindustryX tags carry a short user-facing version (X##/B##);
            // other variants keep the asset file as the actionable label.
            String fileName = item.getFileName();
            twoLine.setTitle(item.getVariant() == VersionVariant.MINDUSTRY_X
                    || fileName.isBlank()
                    || "MDTbbs desktop archive".equals(fileName)
                    ? item.getDisplayVersion()
                    : fileName);

            // Subtitle = "tag · size · published".
            StringBuilder sub = new StringBuilder();
            if (!item.getTagName().isEmpty()) sub.append(item.getTagName());
            if (item.getSize() > 0) {
                if (sub.length() > 0) sub.append("  ·  ");
                sub.append(formatSize(item.getSize()));
            }
            if (item.getPublishedAt() != null) {
                if (sub.length() > 0) sub.append("  ·  ");
                sub.append(DATE.format(item.getPublishedAt()));
            }
            twoLine.setSubtitle(sub.length() == 0 ? null : sub.toString());

            twoLine.getTags().clear();
            boolean hasNotes = !item.getReleaseNotes().isBlank();
            notesButton.setManaged(hasNotes);
            notesButton.setVisible(hasNotes);
            String buildLabel = MindustryVersionDisplay.buildLabel(item.getVariant(),
                    item.getBuild(), item.getBuildType(), item.getTagName(), item.getFileName());
            if (!buildLabel.isEmpty()) {
                twoLine.addTag(buildLabel);
            }
            // Era + runtime chips make the huge version range scannable; they
            // only apply to vanilla builds because other variants count
            // differently.
            MindustryEra era = MindustryEra.of(item.getVariant(), item.getBuild());
            if (era != MindustryEra.UNKNOWN) {
                twoLine.addTag(i18n(era.i18nKey()));
                twoLine.addTag(i18n(MindustryEra.requiresJava8(item.getBuild())
                        ? "xenon.mindustry.era.java8"
                        : "xenon.mindustry.era.java17"));
            }
            if (!item.getBuildType().isEmpty()) {
                twoLine.addTag(localizeChannel(item.getBuildType()));
            }
        }
    }

    /**
     * Localised label for the channel token stored in
     * {@link MindustryRemoteVersion#getBuildType()}. MindustryX writes
     * {@code X} / {@code B} (release / preview); the other variants use
     * {@code stable} / {@code be}. Unknown tokens fall through verbatim.
     */
    private static String localizeChannel(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        switch (raw) {
            case "X": return i18n("xenon.mindustry.channel.release");
            case "B": return i18n("xenon.mindustry.channel.preview");
            case "stable": return i18n("xenon.mindustry.channel.stable");
            case "be": return i18n("xenon.mindustry.channel.be");
            default: return raw;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return DataSizeUnit.GIGABYTES.formatBytes(bytes);
        }
        if (bytes >= 1024L * 1024) {
            return DataSizeUnit.MEGABYTES.formatBytes(bytes);
        }
        if (bytes >= 1024L) {
            return DataSizeUnit.KILOBYTES.formatBytes(bytes);
        }
        return bytes + " B";
    }
}
