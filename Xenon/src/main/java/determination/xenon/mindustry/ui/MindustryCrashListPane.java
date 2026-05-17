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
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.List;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/** Per-instance Mindustry crash report viewer. */
public final class MindustryCrashListPane extends BorderPane {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Path dataDir;
    private final VersionVariant variant;
    private final Label status = new Label();
    private final VBox listBox = new VBox(2);

    public MindustryCrashListPane(Path dataDir, VersionVariant variant) {
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

        HBox toolbar = new HBox(8, refresh, openFolder);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, title, hint, toolbar, status);
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);

        ScrollPane scroll = new ScrollPane(listBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        FXUtils.smoothScrolling(scroll);
        setCenter(scroll);

        reload();
    }

    private void reload() {
        status.setText(i18n("xenon.mindustry.crash.loading"));
        listBox.getChildren().clear();
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

    private void populate(List<CrashReport> reports) {
        listBox.getChildren().clear();
        status.setText(i18n("xenon.mindustry.crash.count", reports.size()));
        if (reports.isEmpty()) {
            Label empty = new Label(i18n("xenon.mindustry.crash.empty"));
            empty.setPadding(new Insets(8));
            listBox.getChildren().add(empty);
            return;
        }
        for (CrashReport r : reports) {
            listBox.getChildren().add(buildRow(r));
        }
    }

    private AdvancedListItem buildRow(CrashReport r) {
        AdvancedListItem item = new AdvancedListItem();
        item.setLeftIcon(SVG.SCRIPT);
        String fname = r.getFile() == null ? "(in-memory)" : r.getFile().getFileName().toString();
        item.setTitle("[" + r.getCategory() + "] " + fname);
        StringBuilder subtitle = new StringBuilder();
        if (r.getWhen() != null) subtitle.append(STAMP.format(r.getWhen())).append("  ·  ");
        subtitle.append(truncate(r.getSummary(), 140));
        item.setSubtitle(subtitle.toString());

        HBox actions = new HBox(4);
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
            String url = IssueTemplateBuilder.buildIssueUrl(r, variant);
            if (url == null) {
                showQqHint();
            } else {
                FXUtils.openLink(url);
            }
        });
        actions.getChildren().setAll(view, copy, report);
        item.setRightGraphic(actions);
        return item;
    }

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

    private void showQqHint() {
        Controllers.dialog(
                i18n("xenon.mindustry.crash.qq.hint", Metadata.QQ_GROUP, Metadata.GROUPS_URL),
                i18n("xenon.mindustry.crash.report"),
                MessageDialogPane.MessageType.INFO);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        if (one.length() <= max) return one;
        return one.substring(0, max - 1) + "…";
    }
}
