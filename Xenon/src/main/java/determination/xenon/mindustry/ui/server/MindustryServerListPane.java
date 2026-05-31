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
import determination.xenon.mindustry.VersionVariant;
import determination.xenon.mindustry.download.MindustryRemoteVersion;
import determination.xenon.mindustry.server.ServerInstance;
import determination.xenon.mindustry.server.ServerInstanceManager;
import determination.xenon.mindustry.server.ServerProcess;
import determination.xenon.mindustry.server.ServerRuntimeHandle;
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
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/**
 * Server-instance browser: one row per Mindustry dedicated-server installed
 * under {@code <config>/servers/<id>}. Single click opens the detail page.
 *
 * <p>Status badges read live from {@link ServerInstanceManager#getRunningProcesses()},
 * which is the same map the supervised runner mutates, so the UI always
 * reflects whether a given server is up. We poll once at refresh time
 * because the manager doesn't currently emit state-change events; if that
 * changes later we should rebind to the observable instead.</p>
 */
public final class MindustryServerListPane extends BorderPane {

    private final MindustryServerServices services = MindustryServerServices.getInstance();
    private final ServerInstanceManager manager;
    private final Label status = new Label();
    private final VBox listBox = new VBox(2);

    public MindustryServerListPane() {
        this.manager = services.manager();
        setPadding(new Insets(12));

        Path serversRoot = manager.getServersRoot();

        Label title = new Label(i18n("xenon.mindustry.server.title"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.mindustry.server.hint", serversRoot.toString()));
        hint.setWrapText(true);

        JFXButton refresh = FXUtils.newRaisedButton(i18n("button.refresh"));
        refresh.setOnAction(e -> reload());

        JFXButton create = FXUtils.newRaisedButton(i18n("xenon.mindustry.server.create"));
        create.setOnAction(e -> openCreateDialog());

        JFXButton install = FXUtils.newRaisedButton(i18n("download"));
        install.setOnAction(e -> openInstallDialog());

        JFXButton importJar = FXUtils.newRaisedButton(i18n("xenon.mindustry.import.choose"));
        importJar.setOnAction(e -> openLocalJarDialog());

        JFXButton openFolder = FXUtils.newRaisedButton(i18n("xenon.mindustry.server.open_folder"));
        openFolder.setOnAction(e -> FXUtils.openFolder(serversRoot));

        HBox toolbar = new HBox(8, refresh, create, install, importJar, openFolder);
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
        status.setText(i18n("xenon.mindustry.server.loading"));
        listBox.getChildren().clear();
        Schedulers.io().execute(() -> {
            manager.refresh();
            Collection<ServerInstance> all = manager.all();
            Platform.runLater(() -> populate(all));
        });
    }

    private void populate(Collection<ServerInstance> instances) {
        listBox.getChildren().clear();
        status.setText(i18n("xenon.mindustry.server.count", instances.size()));
        if (instances.isEmpty()) {
            Label empty = new Label(i18n("xenon.mindustry.server.empty"));
            empty.setPadding(new Insets(8));
            listBox.getChildren().add(empty);
            return;
        }
        for (ServerInstance inst : instances) {
            listBox.getChildren().add(buildRow(inst));
        }
    }

    private AdvancedListItem buildRow(ServerInstance inst) {
        AdvancedListItem item = new AdvancedListItem();
        item.setLeftIcon(SVG.PUBLIC);

        ServerProcess running = manager.getRunningProcesses().get(inst.getId());
        boolean isRunning = running != null && running.isAlive();
        String runtimeLabel = runtimeSummary(inst, isRunning);

        String title = inst.getName();
        if (!runtimeLabel.isBlank()) title += "  [" + runtimeLabel + "]";
        item.setTitle(title);

        StringBuilder subtitle = new StringBuilder();
        subtitle.append(inst.getVariant().getDisplayName()).append("  ·  ");
        subtitle.append(i18n("xenon.mindustry.server.subtitle.port", inst.getPort()));
        if (inst.getBuild() > 0) {
            subtitle.append("  ·  ").append("build ").append(inst.getBuild());
        } else if (!inst.getUpstreamTag().isBlank()) {
            subtitle.append("  ·  ").append(inst.getUpstreamTag());
        }
        if (!inst.getLastStatusMessage().isBlank() && !isRunning) {
            subtitle.append("  ·  ").append(inst.getLastStatusMessage());
        }
        if (inst.getJarPath() != null && !inst.getJarPath().isBlank()) {
            subtitle.append("  —  ").append(inst.getJarPath());
        }
        item.setSubtitle(subtitle.toString());

        // Tap the row → open detail. We intentionally don't put a separate
        // "open" button on the right so the row affordance stays one
        // single-click target.
        item.setOnAction(e -> Controllers.navigate(new MindustryServerDetailPage(inst, manager)));

        HBox actions = new HBox(4);
        JFXButton toggle = FXUtils.newRaisedButton(
                isRunning ? i18n("xenon.mindustry.server.console.stop")
                          : i18n("xenon.mindustry.server.console.start"));
        toggle.setOnAction(e -> toggleRuntime(inst));
        JFXButton del = FXUtils.newRaisedButton(i18n("button.delete"));
        del.setOnAction(e -> confirmDelete(inst));
        actions.getChildren().setAll(toggle, del);
        item.setRightGraphic(actions);
        return item;
    }

    private void confirmDelete(ServerInstance inst) {
        JFXButton confirm = new JFXButton(i18n("button.delete"));
        confirm.getStyleClass().add("dialog-error");
        confirm.setOnAction(e -> Schedulers.io().execute(() -> {
            try {
                manager.delete(inst.getId());
                Platform.runLater(() -> {
                    Controllers.showToast(i18n("message.success"));
                    reload();
                });
            } catch (IOException ex) {
                LOG.warning("Failed to delete server " + inst.getId(), ex);
                Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        }));
        Controllers.confirmAction(
                i18n("xenon.mindustry.server.delete.confirm", inst.getName()),
                i18n("message.warning"), MessageDialogPane.MessageType.WARNING, confirm);
    }

    private void toggleRuntime(ServerInstance inst) {
        Schedulers.io().execute(() -> {
            try {
                ServerRuntimeHandle handle = manager.getRuntimeRegistry().get(inst.getId());
                if (handle.isRunning()) {
                    handle.stop();
                } else {
                    handle.start();
                }
                Platform.runLater(this::reload);
            } catch (IOException ex) {
                LOG.warning("Failed to toggle server runtime for " + inst.getId(), ex);
                Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    /**
     * Minimal create-instance dialog: id + display name + port. Everything
     * else uses sane defaults that the detail page's Settings tab can
     * tweak afterwards. Jar install is left for the user to drop into the
     * server root manually for now.
     */
    private void openCreateDialog() {
        com.jfoenix.controls.JFXTextField idField = new com.jfoenix.controls.JFXTextField();
        idField.setPromptText(i18n("xenon.mindustry.server.create.id.hint"));
        com.jfoenix.controls.JFXTextField nameField = new com.jfoenix.controls.JFXTextField();
        nameField.setPromptText(i18n("xenon.mindustry.server.create.name.hint"));
        com.jfoenix.controls.JFXTextField portField = new com.jfoenix.controls.JFXTextField("6567");
        portField.setPromptText(i18n("xenon.mindustry.server.create.port.hint"));

        VBox body = new VBox(8,
                new Label(i18n("xenon.mindustry.server.create.id")), idField,
                new Label(i18n("xenon.mindustry.server.create.name")), nameField,
                new Label(i18n("xenon.mindustry.server.create.port")), portField);
        body.setPadding(new Insets(4, 0, 0, 0));

        com.jfoenix.controls.JFXDialogLayout layout = new com.jfoenix.controls.JFXDialogLayout();
        layout.setHeading(new Label(i18n("xenon.mindustry.server.create")));
        layout.setBody(body);

        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.setOnAction(e -> layout.fireEvent(new determination.xenon.ui.construct.DialogCloseEvent()));
        JFXButton ok = new JFXButton(i18n("button.ok"));
        ok.getStyleClass().add("dialog-accept");
        ok.setOnAction(e -> {
            String id = idField.getText() == null ? "" : idField.getText().trim();
            if (id.isBlank()) {
                Controllers.dialog(i18n("xenon.mindustry.server.create.id.required"),
                        i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
                return;
            }
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
                if (port < 1 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                Controllers.dialog(i18n("xenon.mindustry.server.create.port.invalid"),
                        i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
                return;
            }
            if (manager.hasConfiguredPortConflict(port, id)) {
                Controllers.dialog(i18n("xenon.mindustry.server.settings.check_port.busy", port),
                        i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
                return;
            }
            ServerInstance inst = new ServerInstance();
            inst.setId(id);
            inst.setName(nameField.getText() == null || nameField.getText().isBlank() ? id : nameField.getText().trim());
            inst.setPort(port);
            Schedulers.io().execute(() -> {
                try {
                    manager.save(inst);
                    Platform.runLater(() -> {
                        layout.fireEvent(new determination.xenon.ui.construct.DialogCloseEvent());
                        Controllers.showToast(i18n("message.success"));
                        reload();
                    });
                } catch (IOException ex) {
                    LOG.warning("Failed to create server " + id, ex);
                    Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                            i18n("message.error"), MessageDialogPane.MessageType.ERROR));
                }
            });
        });
        layout.setActions(cancel, ok);
        Controllers.dialog(layout);
    }

    private String runtimeSummary(ServerInstance inst, boolean isRunning) {
        if (isRunning) {
            return i18n("xenon.mindustry.server.status.running");
        }
        return switch (inst.getLastLifecycleState()) {
            case RESTARTING -> i18n("xenon.mindustry.server.status.restarting");
            case CRASHED -> i18n("xenon.mindustry.server.status.crashed");
            case STARTING -> i18n("xenon.mindustry.server.status.starting");
            case RUNNING -> i18n("xenon.mindustry.server.status.running");
            case STOPPED -> i18n("xenon.mindustry.server.status.stopped");
        };
    }

    private void openLocalJarDialog() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("xenon.mindustry.import.choose"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Mindustry server jar", "*.jar"));
        java.io.File selected = FXUtils.showOpenDialog(chooser, Controllers.getStage());
        if (selected == null) {
            return;
        }
        Path jar = selected.toPath();
        String suggestedId = sanitizeId(jar.getFileName().toString().replaceFirst("(?i)\\.jar$", ""));
        Controllers.prompt(i18n("xenon.mindustry.server.create.id"), (input, handler) -> {
            String id = input == null ? "" : input.trim();
            if (id.isBlank()) {
                handler.reject(i18n("xenon.mindustry.server.create.id.required"));
                return;
            }
            if (manager.get(id).isPresent()) {
                handler.reject(i18n("xenon.mindustry.import.id.duplicate"));
                return;
            }
            handler.resolve();
            importLocalServerJar(jar, id);
        }, suggestedId);
    }

    private void importLocalServerJar(Path jar, String id) {
        Schedulers.io().execute(() -> {
            try {
                Path serverRoot = manager.getServerRoot(id);
                Files.createDirectories(serverRoot);
                Path target = serverRoot.resolve(jar.getFileName().toString());
                Files.copy(jar, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                ServerInstance instance = manager.createInstance(
                        id, id, VersionVariant.CUSTOM, 0, "custom", "", target.getFileName().toString(), 17, 6567);
                manager.save(instance);
                Platform.runLater(this::reload);
            } catch (IOException ex) {
                LOG.warning("Failed to import local server jar " + jar, ex);
                Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    private void openInstallDialog() {
        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(8);

        Label variantLabel = new Label(i18n("xenon.mindustry.server.install.variant"));
        com.jfoenix.controls.JFXComboBox<VersionVariant> variantBox = new com.jfoenix.controls.JFXComboBox<>();
        variantBox.getItems().addAll(VersionVariant.VANILLA, VersionVariant.BE, VersionVariant.MINDUSTRY_X);
        variantBox.getSelectionModel().select(VersionVariant.VANILLA);

        Label versionLabel = new Label(i18n("xenon.mindustry.server.install.version"));
        com.jfoenix.controls.JFXComboBox<MindustryRemoteVersion> versionBox = new com.jfoenix.controls.JFXComboBox<>();
        versionBox.setCellFactory(list -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(MindustryRemoteVersion item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getDisplayVersion());
            }
        });
        versionBox.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(MindustryRemoteVersion item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getDisplayVersion());
            }
        });

        com.jfoenix.controls.JFXTextField idField = new com.jfoenix.controls.JFXTextField();
        idField.setPromptText(i18n("xenon.mindustry.server.create.id.hint"));
        com.jfoenix.controls.JFXTextField nameField = new com.jfoenix.controls.JFXTextField();
        nameField.setPromptText(i18n("xenon.mindustry.server.create.name.hint"));
        com.jfoenix.controls.JFXTextField portField = new com.jfoenix.controls.JFXTextField("6567");
        portField.setPromptText(i18n("xenon.mindustry.server.create.port.hint"));

        JFXButton refreshVersions = FXUtils.newRaisedButton(i18n("button.refresh"));
        refreshVersions.setOnAction(e -> loadServerVersions(variantBox.getValue(), versionBox));
        variantBox.valueProperty().addListener((obs, oldV, newV) -> loadServerVersions(newV, versionBox));

        form.add(variantLabel, 0, 0);
        form.add(variantBox, 1, 0);
        form.add(refreshVersions, 2, 0);
        form.add(versionLabel, 0, 1);
        form.add(versionBox, 1, 1, 2, 1);
        form.add(new Label(i18n("xenon.mindustry.server.create.id")), 0, 2);
        form.add(idField, 1, 2, 2, 1);
        form.add(new Label(i18n("xenon.mindustry.server.create.name")), 0, 3);
        form.add(nameField, 1, 3, 2, 1);
        form.add(new Label(i18n("xenon.mindustry.server.create.port")), 0, 4);
        form.add(portField, 1, 4, 2, 1);

        loadServerVersions(variantBox.getValue(), versionBox);

        com.jfoenix.controls.JFXDialogLayout layout = new com.jfoenix.controls.JFXDialogLayout();
        layout.setHeading(new Label(i18n("download")));
        layout.setBody(form);

        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.setOnAction(e -> layout.fireEvent(new determination.xenon.ui.construct.DialogCloseEvent()));
        JFXButton ok = new JFXButton(i18n("button.ok"));
        ok.getStyleClass().add("dialog-accept");
        ok.setOnAction(e -> {
            MindustryRemoteVersion version = versionBox.getValue();
            if (version == null) {
                Controllers.dialog(i18n("xenon.mindustry.server.install.choose_version"),
                        i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
                return;
            }
            String id = idField.getText() == null ? "" : idField.getText().trim();
            if (id.isBlank()) {
                Controllers.dialog(i18n("xenon.mindustry.server.create.id.required"),
                        i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
                return;
            }
            if (manager.get(id).isPresent()) {
                Controllers.dialog(i18n("xenon.mindustry.import.id.duplicate"),
                        i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
                return;
            }
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
            } catch (RuntimeException ex) {
                Controllers.dialog(i18n("xenon.mindustry.server.create.port.invalid"),
                        i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
                return;
            }
            if (manager.hasConfiguredPortConflict(port, id)) {
                Controllers.dialog(i18n("xenon.mindustry.server.settings.check_port.busy", port),
                        i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
                return;
            }
            installServerVersion(id, blankToDefault(nameField.getText(), id), port, version, layout);
        });
        layout.setActions(cancel, ok);
        Controllers.dialog(layout);
    }

    private void loadServerVersions(@Nullable VersionVariant variant,
                                    com.jfoenix.controls.JFXComboBox<MindustryRemoteVersion> versionBox) {
        versionBox.getItems().clear();
        if (variant == null) {
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                List<MindustryRemoteVersion> versions = switch (variant) {
                    case VANILLA -> services.vanillaVersions().refreshServer();
                    case BE -> services.beVersions().refreshServer();
                    case MINDUSTRY_X -> services.mindustryXVersions().refreshServer();
                    default -> List.of();
                };
                versions = versions.stream()
                        .sorted(Comparator.comparingInt(MindustryRemoteVersion::getBuild).reversed())
                        .toList();
                List<MindustryRemoteVersion> result = versions;
                Platform.runLater(() -> {
                    versionBox.getItems().setAll(result);
                    if (!result.isEmpty()) {
                        versionBox.getSelectionModel().select(0);
                    }
                });
            } catch (IOException ex) {
                LOG.warning("Failed to load server versions for " + variant, ex);
                Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    private void installServerVersion(String id,
                                      String name,
                                      int port,
                                      MindustryRemoteVersion remoteVersion,
                                      com.jfoenix.controls.JFXDialogLayout layout) {
        determination.xenon.task.Task<ServerInstance> task =
                determination.xenon.task.Task.supplyAsync(Schedulers.io(), () -> {
                    ServerInstance instance = services.jarInstaller().installServer(id, remoteVersion, null);
                    instance.setName(name);
                    instance.setPort(port);
                    manager.save(instance);
                    return instance;
                }).setName(i18n("xenon.install.task.title"));

        determination.xenon.task.Task<Void> completion = task.whenComplete(Schedulers.javafx(), exception -> {
            if (exception == null) {
                layout.fireEvent(new determination.xenon.ui.construct.DialogCloseEvent());
                Controllers.showToast(i18n("message.success"));
                reload();
            }
        });
        Controllers.taskDialog(completion, i18n("xenon.install.task.title"),
                determination.xenon.util.TaskCancellationAction.NO_CANCEL);
    }

    private static String sanitizeId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "mindustry-server";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
    }

    private static String blankToDefault(@Nullable String text, String dflt) {
        if (text == null || text.isBlank()) {
            return dflt;
        }
        return text.trim();
    }
}
