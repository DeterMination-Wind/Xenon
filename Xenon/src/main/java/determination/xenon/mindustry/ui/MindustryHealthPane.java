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
package determination.xenon.mindustry.ui;

import com.jfoenix.controls.JFXButton;
import determination.xenon.mindustry.MindustryMemoryPolicy;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.mod.MindustryModStateAudit;
import determination.xenon.mindustry.playtime.MindustryPlaytimeStore;
import determination.xenon.mindustry.playtime.PlaytimeFormat;
import determination.xenon.mindustry.playtime.PlaytimeSummary;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.AdvancedListItem;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.util.DataSizeUnit;
import determination.xenon.util.i18n.I18n;
import determination.xenon.util.platform.SystemInfo;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Per-instance Mindustry health report.
///
/// Shows a summary of the instance itself, its recorded playtime and the
/// compatibility findings produced by
/// [MindustryCompatibility#forInstance(MindustryVersion, Path, Path)]. All
/// disk access happens on the IO scheduler and is applied back on the JavaFX
/// thread.
@NotNullByDefault
public final class MindustryHealthPane extends BorderPane {

    /// Instance descriptor whose build and Java requirements are reported.
    private final MindustryVersion version;

    /// Instance root directory used to resolve the jar and playtime log.
    private final Path versionRoot;

    /// Data directory the next launch actually uses, i.e. the runtime copy
    /// when a save archive is selected. The compatibility scan and the mod
    /// state audit both read this directory.
    private final Path dataDir;

    /// Status label for loading and issue counts.
    private final Label status = new Label();

    /// Vertical stack of section and issue rows.
    private final VBox listBox = new VBox(2);

    /// Scrollable container for [#listBox].
    private final ScrollPane scroll = new ScrollPane(listBox);

    /// Monotonic token of the newest reload request; results of an older
    /// evaluation are dropped so a slow refresh cannot overwrite a newer one.
    private long reloadGeneration;

    /// Creates a health report pane for one Mindustry instance.
    ///
    /// @param version the Mindustry instance descriptor
    /// @param versionRoot instance root directory resolved for `version`
    /// @param dataDir data directory the next launch actually uses, i.e. the
    ///     runtime copy when a save archive is selected
    public MindustryHealthPane(MindustryVersion version, Path versionRoot, Path dataDir) {
        this.version = version;
        this.versionRoot = versionRoot;
        this.dataDir = dataDir;
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.mindustry.health.title"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.mindustry.health.hint", dataDir.toString()));
        hint.setWrapText(true);

        JFXButton refresh = FXUtils.newRaisedButton(i18n("button.refresh"));
        refresh.setOnAction(e -> reload());

        HBox toolbar = new HBox(8, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, title, hint, toolbar, status);
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        FXUtils.smoothScrolling(scroll);
        setCenter(scroll);

        reload();
    }

    /// Recomputes playtime and compatibility data on the IO scheduler.
    private void reload() {
        long generation = ++reloadGeneration;
        status.setText(i18n("xenon.mindustry.health.loading"));
        listBox.getChildren().clear();
        Schedulers.io().execute(() -> {
            try {
                PlaytimeSummary playtime = new MindustryPlaytimeStore(versionRoot).readSummary();
                List<MindustryCompatibility.Issue> issues =
                        MindustryCompatibility.forInstance(version, versionRoot, dataDir);
                List<MindustryModStateAudit.Mismatch> mismatches =
                        MindustryModStateAudit.mismatches(dataDir);
                List<String> folderMods = MindustryModStateAudit.folderMods(dataDir);
                Platform.runLater(() -> {
                    if (generation == reloadGeneration) {
                        populate(playtime, issues, mismatches, folderMods);
                    }
                });
            } catch (RuntimeException ex) {
                LOG.warning("Failed to evaluate Mindustry instance health for " + version.getId(), ex);
                Platform.runLater(() -> {
                    if (generation == reloadGeneration) {
                        showError(ex);
                    }
                });
            }
        });
    }

    /// Fills the row stack from a completed evaluation.
    private void populate(PlaytimeSummary playtime, List<MindustryCompatibility.Issue> issues,
                          List<MindustryModStateAudit.Mismatch> mismatches,
                          List<String> folderMods) {
        listBox.getChildren().clear();
        listBox.getChildren().add(infoItem());
        listBox.getChildren().add(memoryItem());
        listBox.getChildren().add(playtimeItem(playtime));
        appendCompatItems(issues);
        appendModStateItems(mismatches, folderMods);
        status.setText(issues.isEmpty() && mismatches.isEmpty() && folderMods.isEmpty()
                ? i18n("xenon.mindustry.health.clean")
                : i18n("xenon.mindustry.health.count", issues.size() + mismatches.size() + folderMods.size()));
    }

    /// Appends the mod-signal consistency section.
    ///
    /// Lists archives whose `.disabled` suffix and `mod-*-enabled` settings key
    /// disagree - the usual aftermath of external renames or crash recovery -
    /// with one-click repairs in either direction, plus folder mods that can
    /// only be toggled through settings keys.
    private void appendModStateItems(List<MindustryModStateAudit.Mismatch> mismatches,
                                     List<String> folderMods) {
        AdvancedListItem section = new AdvancedListItem();
        section.setTitle(i18n("xenon.mindustry.health.section.modstate"));
        listBox.getChildren().add(section);

        if (mismatches.isEmpty() && folderMods.isEmpty()) {
            AdvancedListItem clean = new AdvancedListItem();
            clean.setTitle(i18n("xenon.mindustry.health.modstate.clean"));
            listBox.getChildren().add(clean);
            return;
        }
        for (MindustryModStateAudit.Mismatch mismatch : mismatches) {
            AdvancedListItem item = new AdvancedListItem();
            item.setWrapText(true);
            item.setLeftIcon(SVG.WARNING);
            item.setTitle(i18n("xenon.mindustry.health.modstate.mismatch",
                    mismatch.fileName(),
                    stateLabel(mismatch.suffixEnabled()),
                    stateLabel(mismatch.keyEnabled())));
            JFXButton takeFile = FXUtils.newRaisedButton(
                    i18n("xenon.mindustry.health.modstate.sync.file"));
            takeFile.setOnAction(e -> syncMismatch(mismatch, true));
            JFXButton takeGame = FXUtils.newRaisedButton(
                    i18n("xenon.mindustry.health.modstate.sync.game"));
            takeGame.setOnAction(e -> syncMismatch(mismatch, false));
            item.setRightGraphic(new HBox(4, takeFile, takeGame));
            listBox.getChildren().add(item);
        }
        for (String folder : folderMods) {
            AdvancedListItem item = new AdvancedListItem();
            item.setWrapText(true);
            item.setLeftIcon(SVG.INFO);
            item.setTitle(i18n("xenon.mindustry.health.modstate.folder", folder));
            listBox.getChildren().add(item);
        }
    }

    /// Runs one mismatch repair and reloads the report.
    private void syncMismatch(MindustryModStateAudit.Mismatch mismatch, boolean fileWins) {
        Schedulers.io().execute(() -> {
            try {
                MindustryModStateAudit.sync(dataDir, mismatch, fileWins);
                Platform.runLater(() -> {
                    Controllers.showToast(i18n("xenon.mindustry.health.modstate.synced"));
                    reload();
                });
            } catch (IOException | RuntimeException ex) {
                Platform.runLater(() -> showError(ex));
            }
        });
    }

    /// Localized enabled/disabled label for the mismatch summary.
    private static String stateLabel(boolean enabled) {
        return i18n(enabled
                ? "xenon.mindustry.health.modstate.enabled"
                : "xenon.mindustry.health.modstate.disabled");
    }

    /// Builds the row describing the heap the next launch will request.
    ///
    /// Reports the mod-size-aware automatic suggestion, or states that an
    /// explicit heap limit from the instance's JVM arguments wins.
    private AdvancedListItem memoryItem() {
        AdvancedListItem item = new AdvancedListItem();
        item.setTitle(i18n("xenon.mindustry.health.section.memory"));
        if (MindustryMemoryPolicy.hasExplicitHeapLimit(
                determination.xenon.mindustry.LaunchOptions.tokenize(version.getJvmArgs()))) {
            item.setSubtitle(i18n("xenon.mindustry.health.memory.manual"));
            return item;
        }
        long totalMb = SystemInfo.getTotalMemorySize() / (1024L * 1024L);
        long availableMb = SystemInfo.getPhysicalMemoryStatus().getAvailable() / (1024L * 1024L);
        long modsBytes = MindustryMemoryPolicy.measureEnabledModsBytes(dataDir);
        long suggested = MindustryMemoryPolicy.suggestHeapMb(totalMb, availableMb, modsBytes);
        item.setSubtitle(i18n("xenon.mindustry.health.memory.auto",
                suggested, DataSizeUnit.format(modsBytes)));
        return item;
    }

    /// Builds the row describing the instance itself.
    private AdvancedListItem infoItem() {
        AdvancedListItem item = new AdvancedListItem();
        item.setTitle(i18n("xenon.mindustry.health.section.info"));
        item.setSubtitle(i18n("xenon.mindustry.health.info.instance",
                version.getName(), version.getBuild(), version.getJavaReq()));
        return item;
    }

    /// Builds the row summarizing recorded playtime.
    private AdvancedListItem playtimeItem(PlaytimeSummary playtime) {
        AdvancedListItem item = new AdvancedListItem();
        item.setTitle(i18n("xenon.mindustry.health.section.playtime"));
        item.setSubtitle(playtimeSubtitle(playtime));
        return item;
    }

    /// Renders the playtime summary, falling back to "none" before the first
    /// recorded session.
    private static String playtimeSubtitle(PlaytimeSummary playtime) {
        if (playtime.lastLaunchEpochMillis() <= 0) {
            return i18n("xenon.mindustry.health.playtime.none");
        }
        return i18n("xenon.mindustry.health.playtime.total", PlaytimeFormat.duration(playtime.totalActiveMillis()))
                + " · " + i18n("xenon.mindustry.health.playtime.last",
                        I18n.formatDateTime(Instant.ofEpochMilli(playtime.lastLaunchEpochMillis())))
                + " · " + i18n("xenon.mindustry.health.playtime.sessions", playtime.sessions());
    }

    /// Appends the compatibility section header followed by one row per issue.
    ///
    /// A single "clean" row replaces the issue rows when nothing was found.
    private void appendCompatItems(List<MindustryCompatibility.Issue> issues) {
        AdvancedListItem section = new AdvancedListItem();
        section.setTitle(i18n("xenon.mindustry.health.section.compat"));
        listBox.getChildren().add(section);

        if (issues.isEmpty()) {
            AdvancedListItem clean = new AdvancedListItem();
            clean.setTitle(i18n("xenon.mindustry.health.clean"));
            listBox.getChildren().add(clean);
            return;
        }
        for (MindustryCompatibility.Issue issue : issues) {
            AdvancedListItem item = new AdvancedListItem();
            item.setWrapText(true);
            item.setLeftIcon(switch (issue.severity()) {
                case ERROR -> SVG.ERROR;
                case WARNING -> SVG.WARNING;
                case INFO -> SVG.INFO;
            });
            item.setTitle(i18n("xenon.mindustry.health.issue.line",
                    MindustryCompatibility.severityLabel(issue.severity()), issue.message()));
            listBox.getChildren().add(item);
        }
    }

    /// Reports an evaluation failure with an error dialog.
    private void showError(Throwable ex) {
        Controllers.dialog(ex.getMessage(), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
    }
}
