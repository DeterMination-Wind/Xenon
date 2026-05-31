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
import com.jfoenix.controls.JFXComboBox;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.XenonGameRepository;
import determination.xenon.mindustry.server.ServerInstance;
import determination.xenon.mindustry.server.ServerInstanceManager;
import determination.xenon.mindustry.server.ServerModsBridge;
import determination.xenon.mindustry.ui.MindustryModListPane;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.MessageDialogPane;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.io.IOException;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Server-scoped mods page with client-to-server sync affordance.
///
/// The shared `MindustryModListPane` remains the main surface for local
/// install/enable/disable/delete, while this wrapper adds the dedicated
/// server action that matters most for MVP: copy a chosen client's mod
/// archive set into the server's `mods/` directory.
public final class MindustryServerModsPane extends BorderPane {

    private final ServerInstance instance;
    private final ServerInstanceManager manager;
    private final JFXComboBox<MindustryVersion> clientBox = new JFXComboBox<>();
    private final MindustryModListPane modListPane;

    public MindustryServerModsPane(ServerInstance instance, ServerInstanceManager manager) {
        this.instance = instance;
        this.manager = manager;
        ServerModsBridge.forServer(instance, manager);
        java.nio.file.Path dataDir = instance.resolveDataDir(manager.getServerRoot(instance.getId()));
        this.modListPane = new MindustryModListPane(dataDir);
        setPadding(new Insets(12));

        Label label = new Label(i18n("xenon.mindustry.server.mods.sync_from_client"));
        JFXButton syncButton = FXUtils.newRaisedButton(i18n("xenon.mindustry.server.mods.sync"));
        syncButton.setOnAction(e -> syncSelectedClient());
        HBox toolbar = new HBox(8, label, clientBox, syncButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(clientBox, Priority.ALWAYS);

        setTop(toolbar);
        BorderPane.setMargin(toolbar, new Insets(0, 0, 8, 0));
        setCenter(modListPane);

        reloadClientVersions();
    }

    private void reloadClientVersions() {
        XenonGameRepository repository = MindustryImportFlow.repository();
        repository.refresh();
        clientBox.getItems().setAll(repository.all());
        if (!clientBox.getItems().isEmpty()) {
            clientBox.getSelectionModel().select(0);
        }
        clientBox.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(MindustryVersion item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getVariant().getDisplayName() + ")");
            }
        });
        clientBox.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(MindustryVersion item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getVariant().getDisplayName() + ")");
            }
        });
    }

    private void syncSelectedClient() {
        MindustryVersion selected = clientBox.getValue();
        if (selected == null) {
            Controllers.dialog(i18n("xenon.mindustry.server.mods.choose_client_first"),
                    i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
            return;
        }
        XenonGameRepository repository = MindustryImportFlow.repository();
        java.nio.file.Path versionRoot = repository.getVersionRoot(selected.getId());
        Schedulers.io().execute(() -> {
            try {
                ServerModsBridge.syncFromClient(instance, manager, selected, versionRoot);
                Platform.runLater(() -> Controllers.showToast(i18n("message.success")));
            } catch (IOException ex) {
                LOG.warning("Failed to sync mods from client " + selected.getId()
                        + " to server " + instance.getId(), ex);
                Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }
}
