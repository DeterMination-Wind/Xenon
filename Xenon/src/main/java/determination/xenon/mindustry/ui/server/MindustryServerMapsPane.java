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
package determination.xenon.mindustry.ui.server;

import com.jfoenix.controls.JFXButton;
import determination.xenon.mindustry.server.ServerInstance;
import determination.xenon.mindustry.server.ServerInstanceManager;
import determination.xenon.mindustry.server.ServerMapPool;
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
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/**
 * Map-pool browser for one server. Lists every {@code .msav} archive in
 * the server's {@code <dataDir>/maps/} folder, with an Import button to
 * copy a local file in and per-row Delete.
 *
 * <p>The Mindustry server picks maps for rotation by reading this folder
 * directly, so the only operations the UI exposes are import/delete; the
 * actual rotation order is decided by the server config.</p>
 */
public final class MindustryServerMapsPane extends BorderPane {

    private final ServerInstance inst;
    private final ServerInstanceManager manager;
    private final ServerMapPool pool;
    private final Label status = new Label();
    private final VBox listBox = new VBox(2);

    public MindustryServerMapsPane(ServerInstance inst, ServerInstanceManager manager) {
        this.inst = inst;
        this.manager = manager;
        this.pool = new ServerMapPool(inst, manager);
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.mindustry.server.tab.maps"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.mindustry.server.maps.hint", pool.getMapsDir().toString()));
        hint.setWrapText(true);

        JFXButton refresh = FXUtils.newRaisedButton(i18n("button.refresh"));
        refresh.setOnAction(e -> reload());
        JFXButton importBtn = FXUtils.newRaisedButton(i18n("xenon.mindustry.server.maps.import"));
        importBtn.setOnAction(e -> chooseAndImport());
        JFXButton openFolder = FXUtils.newRaisedButton(i18n("xenon.mindustry.server.open_folder"));
        openFolder.setOnAction(e -> FXUtils.openFolder(pool.getMapsDir()));

        HBox toolbar = new HBox(8, refresh, importBtn, openFolder, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, title, hint, toolbar);
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
        status.setText(i18n("xenon.mindustry.server.maps.loading"));
        listBox.getChildren().clear();
        Schedulers.io().execute(() -> {
            List<ServerMapPool.MapEntry> entries = pool.list();
            Platform.runLater(() -> populate(entries));
        });
    }

    private void populate(List<ServerMapPool.MapEntry> entries) {
        listBox.getChildren().clear();
        status.setText(i18n("xenon.mindustry.server.maps.count", entries.size()));
        if (entries.isEmpty()) {
            Label empty = new Label(i18n("xenon.mindustry.server.maps.empty"));
            empty.setPadding(new Insets(8));
            listBox.getChildren().add(empty);
            return;
        }
        DateFormat df = DateFormat.getDateInstance(DateFormat.SHORT);
        for (ServerMapPool.MapEntry entry : entries) {
            AdvancedListItem item = new AdvancedListItem();
            item.setLeftIcon(SVG.PUBLIC);
            item.setTitle(entry.name());
            String size = humanSize(entry.size());
            String date = df.format(Date.from(entry.lastModified()));
            item.setSubtitle(size + "  ·  " + date);

            JFXButton del = FXUtils.newRaisedButton(i18n("button.delete"));
            del.setOnAction(e -> Schedulers.io().execute(() -> {
                try {
                    pool.delete(entry.file());
                    Platform.runLater(this::reload);
                } catch (IOException ex) {
                    LOG.warning("Failed to delete map " + entry.file(), ex);
                    Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                            i18n("message.error"), MessageDialogPane.MessageType.ERROR));
                }
            }));
            item.setRightGraphic(del);
            listBox.getChildren().add(item);
        }
    }

    private void chooseAndImport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("xenon.mindustry.server.maps.import"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Mindustry map (*.msav)", "*.msav"));
        File f = FXUtils.showOpenDialog(chooser, Controllers.getStage());
        if (f == null) return;
        Schedulers.io().execute(() -> {
            try {
                pool.importMap(f.toPath());
                Platform.runLater(this::reload);
            } catch (IOException ex) {
                LOG.warning("Failed to import map " + f, ex);
                Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
