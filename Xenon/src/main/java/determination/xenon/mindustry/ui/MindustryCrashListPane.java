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
import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXTextField;
import determination.xenon.Metadata;
import determination.xenon.mindustry.VersionVariant;
import determination.xenon.mindustry.crash.CrashReport;
import determination.xenon.mindustry.crash.IssueTemplateBuilder;
import determination.xenon.mindustry.crash.MindustryCrashAnalyzer;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.AdvancedListItem;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.ui.construct.RipplerContainer;
import determination.xenon.ui.construct.TwoLineListItem;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Per-instance Mindustry crash report viewer.
@NotNullByDefault
public final class MindustryCrashListPane extends BorderPane {

    /// Display format for crash report timestamps.
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /// Mindustry data directory for the selected instance.
    private final Path dataDir;

    /// Variant used to choose the upstream feedback target.
    private final VersionVariant variant;

    /// Status label for loading and visible item counts.
    private final Label status = new Label();

    /// Search box filtering the virtualized crash list.
    private final JFXTextField search = new JFXTextField();

    /// Virtualized list of crash reports.
    private final JFXListView<CrashReport> list = new JFXListView<>();

    /// Last fully scanned report list before search filtering.
    private @Unmodifiable List<CrashReport> allReports = List.of();

    /// Creates a crash report pane for one Mindustry data directory.
    public MindustryCrashListPane(Path dataDir, @Nullable VersionVariant variant) {
        this.dataDir = dataDir;
        this.variant = variant == null ? VersionVariant.CUSTOM : variant;
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.mindustry.crash.title"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.mindustry.crash.hint",
                Metadata.QQ_GROUP, Metadata.GROUPS_URL));
        hint.setWrapText(true);

        JFXButton refresh = FXUtils.newRaisedButton(i18n("button.refresh"));
        refresh.setOnAction(e -> reload());
        JFXButton openFolder = FXUtils.newRaisedButton(i18n("folder.logs"));
        openFolder.setOnAction(e -> FXUtils.openFolder(dataDir.resolve("crashes")));

        search.setPromptText(i18n("xenon.mindustry.crash.search"));
        search.textProperty().addListener((obs, old, value) -> rebuildList());
        HBox.setHgrow(search, Priority.ALWAYS);

        HBox toolbar = new HBox(8, refresh, openFolder);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, title, hint, toolbar, search, status);
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);

        list.getStyleClass().addAll("no-padding", "no-horizontal-scrollbar");
        list.setItems(FXCollections.observableArrayList());
        list.setCellFactory(lv -> new CrashCell());
        list.setFixedCellSize(68);
        list.setPlaceholder(new Label(i18n("xenon.mindustry.crash.empty")));
        setCenter(list);

        reload();
    }

    /// Reloads crash report metadata from disk on the IO scheduler.
    private void reload() {
        status.setText(i18n("xenon.mindustry.crash.loading"));
        list.getItems().clear();
        Schedulers.io().execute(() -> {
            try {
                List<CrashReport> reports = MindustryCrashAnalyzer.scan(dataDir);
                Platform.runLater(() -> populate(reports));
            } catch (IOException ex) {
                LOG.warning("Failed to scan crashes", ex);
                Platform.runLater(() -> status.setText(
                        i18n("xenon.mindustry.crash.failed") + " " + ex.getMessage()));
            }
        });
    }

    /// Stores a completed scan and applies the current search filter.
    private void populate(List<CrashReport> reports) {
        allReports = List.copyOf(reports);
        rebuildList();
    }

    /// Rebuilds visible rows from the last scan and current search text.
    private void rebuildList() {
        String q = search.getText() == null
                ? ""
                : search.getText().trim().toLowerCase(Locale.ROOT);
        List<CrashReport> visible = new ArrayList<>();
        for (CrashReport report : allReports) {
            if (matches(report, q)) {
                visible.add(report);
            }
        }
        list.getItems().setAll(visible);
        status.setText(i18n("xenon.mindustry.crash.count", visible.size()));
    }

    /// Builds a single crash report row for the virtual list.
    private Node buildRow(CrashReport r) {
        BorderPane row = new BorderPane();
        row.setMinWidth(0);
        row.setMaxWidth(Double.MAX_VALUE);

        Node icon = SVG.SCRIPT.createIcon(AdvancedListItem.LEFT_ICON_SIZE);
        icon.setMouseTransparent(true);
        StackPane iconBox = new StackPane(icon);
        iconBox.setMinSize(AdvancedListItem.LEFT_GRAPHIC_SIZE, AdvancedListItem.LEFT_GRAPHIC_SIZE);
        iconBox.setPrefSize(AdvancedListItem.LEFT_GRAPHIC_SIZE, AdvancedListItem.LEFT_GRAPHIC_SIZE);
        iconBox.setMaxSize(AdvancedListItem.LEFT_GRAPHIC_SIZE, AdvancedListItem.LEFT_GRAPHIC_SIZE);
        BorderPane.setAlignment(iconBox, Pos.CENTER);
        BorderPane.setMargin(iconBox, AdvancedListItem.LEFT_ICON_MARGIN);
        row.setLeft(iconBox);

        TwoLineListItem text = new TwoLineListItem();
        text.setMinWidth(0);
        text.setMaxWidth(Double.MAX_VALUE);
        configureLineLabel(text.getTitleLabel());
        configureLineLabel(text.getSubtitleLabel());

        @Nullable Path file = r.getFile();
        String fname = file == null ? "(in-memory)" : file.getFileName().toString();
        text.setTitle("[" + r.getCategory() + "] " + fname);
        StringBuilder subtitle = new StringBuilder();
        if (r.getWhen() != null) subtitle.append(STAMP.format(r.getWhen())).append("  ·  ");
        subtitle.append(truncate(r.getSummary(), 140));
        text.setSubtitle(subtitle.toString());
        row.setCenter(text);

        HBox actions = new HBox(4);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        actions.setMaxWidth(Region.USE_PREF_SIZE);
        JFXButton askAi = FXUtils.newRaisedButton(i18n("xenon.mindustry.crash.ask_ai"));
        askAi.setOnAction(e -> MindustryCrashAiAssistant.ask(r, variant));
        JFXButton view = FXUtils.newRaisedButton(i18n("xenon.mindustry.crash.view"));
        view.setOnAction(e -> showFullText(r));
        JFXButton copy = FXUtils.newRaisedButton(i18n("xenon.mindustry.crash.copy"));
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(r.getFullText());
            Clipboard.getSystemClipboard().setContent(cc);
            Controllers.showToast(i18n("xenon.mindustry.crash.copy.done"));
        });
        JFXButton report = FXUtils.newRaisedButton(i18n("xenon.mindustry.crash.report"));
        report.setOnAction(e -> {
            @Nullable String url = IssueTemplateBuilder.buildIssueUrl(r, variant);
            if (url == null) {
                showQqHint();
            } else {
                FXUtils.openLink(url);
            }
        });
        actions.getChildren().setAll(askAi, view, copy, report);
        BorderPane.setAlignment(actions, Pos.CENTER_RIGHT);
        BorderPane.setMargin(actions, new Insets(0, 0, 0, 12));
        row.setRight(actions);

        StackPane pane = new StackPane(new RipplerContainer(row));
        pane.getStyleClass().add("md-list-cell");
        pane.setMinWidth(0);
        pane.setMaxWidth(Double.MAX_VALUE);
        StackPane.setMargin(row, new Insets(10, 16, 10, 16));
        return pane;
    }

    /// Configures one line of row text to shrink and ellipsize within the visible row width.
    private static void configureLineLabel(Label label) {
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox.setHgrow(label, Priority.ALWAYS);
    }

    /// Shows a truncated preview of the full crash text.
    private void showFullText(CrashReport r) {
        // MessageDialogPane truncates long bodies sensibly; for full text users
        // hit "Copy" instead.
        String body = r.getFullText();
        if (body.length() > 4000) {
            body = body.substring(0, 4000) + "\n\n... (" + (r.getFullText().length() - 4000)
                    + " more chars; use Copy to get the full text)";
        }
        Controllers.dialog(body, r.getSummary(), MessageDialogPane.MessageType.ERROR);
    }

    /// Shows manual feedback destinations for variants without a known repository.
    private void showQqHint() {
        Controllers.dialog(
                i18n("xenon.mindustry.crash.qq.hint", Metadata.QQ_GROUP, Metadata.GROUPS_URL),
                i18n("xenon.mindustry.crash.report"),
                MessageDialogPane.MessageType.INFO);
    }

    /// Collapses and truncates a possibly missing string for row subtitles.
    private static String truncate(@Nullable String s, int max) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        if (one.length() <= max) return one;
        return one.substring(0, max - 1) + "…";
    }

    /// Returns whether a crash report matches the current lowercase query.
    private static boolean matches(CrashReport report, String q) {
        if (q.isEmpty()) return true;
        if (contains(report.getSummary(), q)) return true;
        if (contains(report.getRootClass(), q)) return true;
        if (contains(report.getCategory().name(), q)) return true;
        @Nullable Path file = report.getFile();
        return file != null && contains(file.getFileName().toString(), q);
    }

    /// Performs a null-tolerant lowercase substring check.
    private static boolean contains(@Nullable String text, String q) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(q);
    }

    /// Recyclable virtual-list cell for crash reports.
    private final class CrashCell extends ListCell<CrashReport> {
        /// Creates a crash cell whose width is bound to the list viewport.
        CrashCell() {
            setText(null);
            FXUtils.limitCellWidth(list, this);
        }

        /// Prevents long row content from increasing the virtual list viewport width.
        @Override
        protected double computePrefWidth(double height) {
            return 0;
        }

        /// Updates the visible row graphic for the current crash report.
        @Override
        protected void updateItem(@Nullable CrashReport report, boolean empty) {
            super.updateItem(report, empty);
            setText(null);
            setGraphic(empty || report == null ? null : buildRow(report));
        }
    }
}
