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
import determination.xenon.mindustry.scriptagent.ScriptAgentModule;
import determination.xenon.mindustry.scriptagent.ScriptAgentModuleManager;
import determination.xenon.mindustry.server.ServerInstance;
import determination.xenon.mindustry.server.ServerInstanceManager;
import determination.xenon.mindustry.server.ServerProcess;
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
import java.util.List;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// ScriptAgent management page for one server instance.
///
/// This pane covers the MVP flow: install latest ScriptAgent, inspect
/// discovered `.kts` modules, enable/disable them on disk, and issue
/// `sa scan/load/hotReload` commands when the server is running.
public final class MindustryServerScriptAgentPane extends BorderPane {

    private final ServerInstance instance;
    private final ServerInstanceManager manager;
    private final ScriptAgentModuleManager modules;
    private final Label status = new Label();
    private final VBox listBox = new VBox(2);

    public MindustryServerScriptAgentPane(ServerInstance instance, ServerInstanceManager manager) {
        this.instance = instance;
        this.manager = manager;
        this.modules = new ScriptAgentModuleManager(instance, manager);
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.scriptagent.title"));
        title.getStyleClass().add("title");
        Label hint = new Label(modules.scriptsDir().toString());
        hint.setWrapText(true);

        JFXButton refresh = FXUtils.newRaisedButton(i18n("button.refresh"));
        refresh.setOnAction(e -> reload());
        JFXButton install = FXUtils.newRaisedButton(i18n("download"));
        install.setOnAction(e -> installLatest());
        JFXButton openFolder = FXUtils.newRaisedButton(i18n("xenon.mindustry.server.open_folder"));
        openFolder.setOnAction(e -> FXUtils.openFolder(modules.scriptsDir()));
        HBox toolbar = new HBox(8, refresh, install, openFolder, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, title, hint, toolbar);
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);

        ScrollPane scroll = new ScrollPane(listBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        FXUtils.smoothScrolling(scroll);
        setCenter(scroll);

        reload();
    }

    private void reload() {
        status.setText(i18n("button.refresh"));
        listBox.getChildren().clear();
        Schedulers.io().execute(() -> {
            try {
                List<ScriptAgentModule> entries = modules.list();
                Platform.runLater(() -> populate(entries));
            } catch (IOException ex) {
                LOG.warning("Failed to load ScriptAgent modules for " + instance.getId(), ex);
                Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    private void populate(List<ScriptAgentModule> entries) {
        listBox.getChildren().clear();
        status.setText(entries.isEmpty() ? i18n("xenon.server.scriptagent") : entries.size() + " modules");
        if (entries.isEmpty()) {
            Label empty = new Label(i18n("xenon.server.scriptagent.empty"));
            empty.setPadding(new Insets(8));
            listBox.getChildren().add(empty);
            return;
        }
        for (ScriptAgentModule entry : entries) {
            listBox.getChildren().add(buildRow(entry));
        }
    }

    private AdvancedListItem buildRow(ScriptAgentModule entry) {
        AdvancedListItem item = new AdvancedListItem();
        item.setLeftIcon(SVG.SCRIPT);
        item.setTitle(entry.getModuleName() + (entry.isEnabled() ? "" : "  [disabled]"));
        item.setSubtitle(entry.getDescription().isBlank() ? entry.getFile().toString() : entry.getDescription());

        HBox actions = new HBox(4);
        JFXButton toggle = FXUtils.newRaisedButton(entry.isEnabled()
                ? i18n("xenon.server.scriptagent.disable")
                : i18n("xenon.server.scriptagent.enable"));
        toggle.setOnAction(e -> mutateModule(entry, entry.isEnabled()));
        JFXButton scan = FXUtils.newRaisedButton(i18n("xenon.server.scriptagent.scan"));
        scan.setOnAction(e -> sendRuntimeCommand(proc -> modules.scan(proc)));
        JFXButton load = FXUtils.newRaisedButton(i18n("xenon.server.scriptagent.load"));
        load.setOnAction(e -> sendRuntimeCommand(proc -> modules.load(proc, entry.getModuleName())));
        JFXButton hotReload = FXUtils.newRaisedButton(i18n("xenon.server.scriptagent.hot_reload"));
        hotReload.setOnAction(e -> sendRuntimeCommand(proc -> modules.reloadHot(proc, entry.getModuleName())));
        actions.getChildren().setAll(toggle, scan, load, hotReload);
        item.setRightGraphic(actions);
        return item;
    }

    private void mutateModule(ScriptAgentModule entry, boolean currentlyEnabled) {
        Schedulers.io().execute(() -> {
            try {
                if (currentlyEnabled) {
                    modules.disable(entry);
                } else {
                    modules.enable(entry);
                }
                Platform.runLater(this::reload);
            } catch (IOException ex) {
                LOG.warning("Failed to toggle ScriptAgent module " + entry.getModuleName(), ex);
                Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    private void installLatest() {
        Schedulers.io().execute(() -> {
            try {
                MindustryServerServices.getInstance().scriptAgentInstaller().installLatest(instance.getId());
                Platform.runLater(() -> {
                    Controllers.showToast(i18n("message.success"));
                    reload();
                });
            } catch (IOException ex) {
                LOG.warning("Failed to install ScriptAgent for " + instance.getId(), ex);
                Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    private void sendRuntimeCommand(CommandAction action) {
        ServerProcess process = manager.getRunningProcesses().get(instance.getId());
        if (process == null || !process.isAlive()) {
            Controllers.dialog(i18n("xenon.server.scriptagent.start_first"),
                    i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                action.run(process);
                Platform.runLater(() -> Controllers.showToast(i18n("message.success")));
            } catch (IOException ex) {
                LOG.warning("Failed to send ScriptAgent command for " + instance.getId(), ex);
                Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    @FunctionalInterface
    private interface CommandAction {
        void run(ServerProcess process) throws IOException;
    }
}
