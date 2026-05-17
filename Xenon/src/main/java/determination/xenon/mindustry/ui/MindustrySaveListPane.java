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
import determination.xenon.mindustry.save.SaveBackupService;
import determination.xenon.mindustry.save.SaveSummary;
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

/** Per-instance Mindustry save manager — replaces HMCL's WorldListPage for Mindustry instances. */
public final class MindustrySaveListPane extends BorderPane {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Path savesDir;
    private final SaveBackupService service;
    private final Label status = new Label();
    private final VBox listBox = new VBox(2);

    public MindustrySaveListPane(Path dataDir) {
        this.savesDir = dataDir.resolve("saves");
        this.service = new SaveBackupService(savesDir);
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.mindustry.save.title"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.mindustry.save.hint", savesDir.toString()));
        hint.setWrapText(true);

        JFXButton refresh = FXUtils.newRaisedButton(i18n("button.refresh"));
        refresh.setOnAction(e -> reload());
        JFXButton openFolder = FXUtils.newRaisedButton(i18n("folder.saves"));
        openFolder.setOnAction(e -> FXUtils.openFolder(savesDir));

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
        status.setText(i18n("xenon.mindustry.save.loading"));
        listBox.getChildren().clear();
        Schedulers.io().execute(() -> {
            try {
                List<SaveSummary> saves = service.list();
                Platform.runLater(() -> populate(saves));
            } catch (IOException ex) {
                LOG.warning("Failed to scan saves", ex);
                Platform.runLater(() -> status.setText(
                        i18n("xenon.mindustry.save.failed") + " " + ex.getMessage()));
            }
        });
    }

    private void populate(List<SaveSummary> saves) {
        listBox.getChildren().clear();
        status.setText(i18n("xenon.mindustry.save.count", saves.size()));
        if (saves.isEmpty()) {
            Label empty = new Label(i18n("xenon.mindustry.save.empty"));
            empty.setPadding(new Insets(8));
            listBox.getChildren().add(empty);
            return;
        }
        for (SaveSummary save : saves) {
            listBox.getChildren().add(buildRow(save));
        }
    }

    private AdvancedListItem buildRow(SaveSummary save) {
        AdvancedListItem item = new AdvancedListItem();
        item.setLeftIcon(SVG.PUBLIC);
        String fname = save.file().getFileName().toString();
        String map = save.mapName().isEmpty() ? "—" : save.mapName();
        item.setTitle(fname + "  ·  " + map);

        StringBuilder subtitle = new StringBuilder();
        if (save.wave() > 0) subtitle.append(i18n("xenon.mindustry.save.wave", save.wave()));
        if (save.build() > 0) {
            if (subtitle.length() > 0) subtitle.append("  ·  ");
            subtitle.append("build ").append(save.build());
        }
        if (subtitle.length() > 0) subtitle.append("  ·  ");
        subtitle.append(STAMP.format(save.lastModified()));
        item.setSubtitle(subtitle.toString());

        HBox actions = new HBox(4);
        JFXButton backup = FXUtils.newRaisedButton(i18n("xenon.mindustry.save.backup"));
        backup.setOnAction(e -> Schedulers.io().execute(() -> {
            try {
                Path b = service.backup(save);
                Platform.runLater(() ->
                        Controllers.showToast(i18n("xenon.mindustry.save.backup.done", b.getFileName().toString())));
            } catch (IOException ex) {
                LOG.warning("Failed to backup " + save.file(), ex);
                Platform.runLater(() -> showError(ex));
            }
        }));
        JFXButton delete = FXUtils.newRaisedButton(i18n("button.delete"));
        delete.setOnAction(e -> Schedulers.io().execute(() -> {
            try {
                service.delete(save.file());
                Platform.runLater(this::reload);
            } catch (IOException ex) {
                Platform.runLater(() -> showError(ex));
            }
        }));
        actions.getChildren().setAll(backup, delete);
        item.setRightGraphic(actions);
        return item;
    }

    private void showError(Throwable ex) {
        Controllers.dialog(ex.getMessage(), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
    }
}
