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
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXTextField;
import determination.xenon.mindustry.server.PortChecker;
import determination.xenon.mindustry.server.ServerInstance;
import determination.xenon.mindustry.server.ServerInstanceManager;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.MessageDialogPane;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/**
 * Per-instance settings page: jar path / port / Java home / JVM args /
 * data-dir policy / auto-restart knobs / display name. These are the
 * fields persisted on {@link ServerInstance}; everything else (the
 * Mindustry server's own config.json knobs) lives on the Config tab.
 *
 * <p>Saving here calls {@link ServerInstanceManager#save(ServerInstance)},
 * which atomically rewrites the instance's {@code server.json}. A live
 * server keeps running with its current command line until the user
 * stops + starts it; the page surfaces this in the save toast.</p>
 */
public final class MindustryServerSettingsPane extends BorderPane {

    private final ServerInstance inst;
    private final ServerInstanceManager manager;

    private final JFXTextField nameField = new JFXTextField();
    private final JFXTextField jarField = new JFXTextField();
    private final JFXTextField portField = new JFXTextField();
    private final JFXTextField javaHomeField = new JFXTextField();
    private final JFXTextField javaReqField = new JFXTextField();
    private final TextArea jvmArgsField = new TextArea();
    private final JFXCheckBox autoRestartBox = new JFXCheckBox();
    private final JFXTextField autoRestartMaxField = new JFXTextField();
    private final JFXTextField autoRestartDelayField = new JFXTextField();
    private final JFXCheckBox scriptAgentBox = new JFXCheckBox();

    private final Label status = new Label();

    public MindustryServerSettingsPane(ServerInstance inst, ServerInstanceManager manager) {
        this.inst = inst;
        this.manager = manager;
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.mindustry.server.tab.settings"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.mindustry.server.settings.hint"));
        hint.setWrapText(true);

        JFXButton reload = FXUtils.newRaisedButton(i18n("button.refresh"));
        reload.setOnAction(e -> populate());
        JFXButton save = FXUtils.newRaisedButton(i18n("button.save"));
        save.setOnAction(e -> save());
        JFXButton checkPort = FXUtils.newRaisedButton(i18n("xenon.mindustry.server.settings.check_port"));
        checkPort.setOnAction(e -> checkPortFree());
        HBox toolbar = new HBox(8, reload, save, checkPort, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, title, hint, toolbar);
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(8);
        form.setPadding(new Insets(8));

        int row = 0;
        form.add(new Label(i18n("xenon.mindustry.server.settings.name")), 0, row);
        form.add(nameField, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.settings.jar")), 0, row);
        form.add(jarField, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.settings.port")), 0, row);
        form.add(portField, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.settings.java_home")), 0, row);
        form.add(javaHomeField, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.settings.java_req")), 0, row);
        form.add(javaReqField, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.settings.jvm_args")), 0, row);
        jvmArgsField.setPrefRowCount(3);
        form.add(jvmArgsField, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.settings.auto_restart")), 0, row);
        form.add(autoRestartBox, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.settings.auto_restart_max")), 0, row);
        form.add(autoRestartMaxField, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.settings.auto_restart_delay")), 0, row);
        form.add(autoRestartDelayField, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.settings.script_agent")), 0, row);
        form.add(scriptAgentBox, 1, row++);

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        FXUtils.smoothScrolling(scroll);
        setCenter(scroll);

        populate();
    }

    private void populate() {
        nameField.setText(inst.getName());
        jarField.setText(nullToEmpty(inst.getJarPath()));
        portField.setText(String.valueOf(inst.getPort()));
        javaHomeField.setText(nullToEmpty(inst.getJavaHome()));
        javaReqField.setText(String.valueOf(inst.getJavaReq()));
        jvmArgsField.setText(inst.getJvmArgs());
        autoRestartBox.setSelected(inst.isAutoRestart());
        autoRestartMaxField.setText(String.valueOf(inst.getAutoRestartMaxRetries()));
        autoRestartDelayField.setText(String.valueOf(inst.getAutoRestartDelaySec()));
        scriptAgentBox.setSelected(inst.isScriptAgent());
        status.setText("");
    }

    private void save() {
        try {
            inst.setName(nameField.getText().trim());
            inst.setJarPath(emptyToNull(jarField.getText()));
            inst.setPort(parseIntOrDefault(portField.getText(), 6567));
            inst.setJavaHome(emptyToNull(javaHomeField.getText()));
            inst.setJavaReq(parseIntOrDefault(javaReqField.getText(), 17));
            inst.setJvmArgs(jvmArgsField.getText() == null ? "" : jvmArgsField.getText().trim());
            inst.setAutoRestart(autoRestartBox.isSelected());
            inst.setAutoRestartMaxRetries(parseIntOrDefault(autoRestartMaxField.getText(), 5));
            inst.setAutoRestartDelaySec(parseIntOrDefault(autoRestartDelayField.getText(), 10));
            inst.setScriptAgent(scriptAgentBox.isSelected());
        } catch (RuntimeException ex) {
            Controllers.dialog(ex.getMessage(),
                    i18n("message.error"), MessageDialogPane.MessageType.ERROR);
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                manager.save(inst);
                Platform.runLater(() -> {
                    status.setText(i18n("xenon.mindustry.server.settings.saved"));
                    Controllers.showToast(i18n("message.success"));
                });
            } catch (IOException ex) {
                LOG.warning("Failed to save server " + inst.getId(), ex);
                Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    /**
     * Probe the configured port and surface the result in the status
     * label. The check binds + releases the port immediately so it's
     * safe to run while no server is up; if a server <em>is</em> up on
     * the same port (e.g. this very instance), the probe will report
     * "in use", which is the correct answer.
     */
    private void checkPortFree() {
        int port = parseIntOrDefault(portField.getText(), -1);
        if (port < 1) {
            status.setText(i18n("xenon.mindustry.server.create.port.invalid"));
            return;
        }
        status.setText(i18n("xenon.mindustry.server.settings.check_port.checking"));
        Schedulers.io().execute(() -> {
            boolean free = PortChecker.isPortFree(port);
            boolean duplicate = manager.hasConfiguredPortConflict(port, inst.getId());
            Platform.runLater(() -> status.setText(
                    (!duplicate && free)
                            ? i18n("xenon.mindustry.server.settings.check_port.free", port)
                            : i18n("xenon.mindustry.server.settings.check_port.busy", port)));
        });
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static int parseIntOrDefault(String s, int dflt) {
        if (s == null || s.isBlank()) return dflt;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return dflt;
        }
    }
}
