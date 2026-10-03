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
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.playtime.MindustryPlaytimeStore;
import determination.xenon.mindustry.playtime.PlaytimeSummary;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.AdvancedListItem;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.util.i18n.I18n;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNullByDefault;

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

    /// Mindustry data directory whose `mods/` folder is evaluated.
    private final Path dataDir;

    /// Status label for loading and issue counts.
    private final Label status = new Label();

    /// Vertical stack of section and issue rows.
    private final VBox listBox = new VBox(2);

    /// Scrollable container for [#listBox].
    private final ScrollPane scroll = new ScrollPane(listBox);

    /// Creates a health report pane for one Mindustry instance.
    ///
    /// @param version the Mindustry instance descriptor
    /// @param versionRoot instance root directory resolved for `version`
    /// @param dataDir Mindustry data directory the instance launches with
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
        status.setText(i18n("xenon.mindustry.health.loading"));
        listBox.getChildren().clear();
        Schedulers.io().execute(() -> {
            try {
                PlaytimeSummary playtime = new MindustryPlaytimeStore(versionRoot).readSummary();
                List<MindustryCompatibility.Issue> issues =
                        MindustryCompatibility.forInstance(version, versionRoot, dataDir);
                Platform.runLater(() -> populate(playtime, issues));
            } catch (RuntimeException ex) {
                LOG.warning("Failed to evaluate Mindustry instance health for " + version.getId(), ex);
                Platform.runLater(() -> showError(ex));
            }
        });
    }

    /// Fills the row stack from a completed evaluation.
    private void populate(PlaytimeSummary playtime, List<MindustryCompatibility.Issue> issues) {
        listBox.getChildren().clear();
        listBox.getChildren().add(infoItem());
        listBox.getChildren().add(playtimeItem(playtime));
        appendCompatItems(issues);
        status.setText(issues.isEmpty()
                ? i18n("xenon.mindustry.health.clean")
                : i18n("xenon.mindustry.health.count", issues.size()));
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
        return i18n("xenon.mindustry.health.playtime.total", formatDuration(playtime.totalActiveMillis()))
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
            item.setLeftIcon(switch (issue.severity()) {
                case ERROR -> SVG.ERROR;
                case WARNING -> SVG.WARNING;
                case INFO -> SVG.INFO;
            });
            item.setTitle(MindustryCompatibility.severityLabel(issue.severity()) + "：" + issue.message());
            listBox.getChildren().add(item);
        }
    }

    /// Formats a duration as hours + minutes, or minutes when under one hour.
    private static String formatDuration(long millis) {
        long hours = millis / 3_600_000;
        long minutes = (millis % 3_600_000) / 60_000;
        if (hours > 0) {
            return i18n("xenon.mindustry.playtime.duration.hm", hours, minutes);
        }
        return i18n("xenon.mindustry.playtime.duration.m", minutes);
    }

    /// Reports an evaluation failure with an error dialog.
    private void showError(Throwable ex) {
        Controllers.dialog(ex.getMessage(), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
    }
}
