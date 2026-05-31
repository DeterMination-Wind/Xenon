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
import determination.xenon.mindustry.server.ServerConfig;
import determination.xenon.mindustry.server.ServerConfigManager;
import determination.xenon.mindustry.server.ServerInstance;
import determination.xenon.mindustry.server.ServerInstanceManager;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.MessageDialogPane;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/**
 * Form view for the Mindustry server's {@code config/config.json}. Reads
 * with {@link ServerConfigManager}, lets the user edit the common knobs
 * (name / motd / port / public / round limit / whitelist / description),
 * writes back on Save.
 *
 * <p>Every field maps to a {@link Boolean}/{@link Integer}/{@link String}
 * on {@link ServerConfig}. We treat the empty text-field as "leave at
 * Mindustry default" by writing {@code null} into the corresponding
 * setter — the manager's serialiser then drops it from the JSON, which
 * matches Mindustry's "only persist explicitly-set keys" behaviour.</p>
 */
public final class MindustryServerConfigPane extends BorderPane {

    private final ServerInstance inst;
    private final ServerInstanceManager manager;
    private final ServerConfigManager configManager;

    private final JFXTextField nameField = new JFXTextField();
    private final JFXTextField motdField = new JFXTextField();
    private final JFXTextField portField = new JFXTextField();
    private final JFXCheckBox publicBox = new JFXCheckBox();
    private final JFXCheckBox autoUpdateBox = new JFXCheckBox();
    private final JFXTextField roundLimitField = new JFXTextField();
    private final JFXCheckBox whitelistBox = new JFXCheckBox();
    private final JFXTextField descriptionField = new JFXTextField();

    private final Label status = new Label();

    public MindustryServerConfigPane(ServerInstance inst, ServerInstanceManager manager) {
        this.inst = inst;
        this.manager = manager;
        this.configManager = new ServerConfigManager(inst, manager);

        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.mindustry.server.tab.config"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.mindustry.server.config.hint"));
        hint.setWrapText(true);

        JFXButton reload = FXUtils.newRaisedButton(i18n("button.refresh"));
        reload.setOnAction(e -> reload());
        JFXButton save = FXUtils.newRaisedButton(i18n("button.save"));
        save.setOnAction(e -> save());
        HBox toolbar = new HBox(8, reload, save, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, title, hint, toolbar);
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(8);
        form.setPadding(new Insets(8));
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(96);
        labelColumn.setPrefWidth(132);
        labelColumn.setHalignment(HPos.RIGHT);
        ColumnConstraints valueColumn = new ColumnConstraints();
        valueColumn.setHgrow(Priority.ALWAYS);
        valueColumn.setFillWidth(true);
        form.getColumnConstraints().setAll(labelColumn, valueColumn);

        nameField.setMaxWidth(Double.MAX_VALUE);
        motdField.setMaxWidth(Double.MAX_VALUE);
        portField.setMaxWidth(Double.MAX_VALUE);
        roundLimitField.setMaxWidth(Double.MAX_VALUE);
        descriptionField.setMaxWidth(Double.MAX_VALUE);

        int row = 0;
        form.add(new Label(i18n("xenon.mindustry.server.config.name")), 0, row);
        form.add(nameField, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.config.motd")), 0, row);
        form.add(motdField, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.config.port")), 0, row);
        form.add(portField, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.config.public")), 0, row);
        form.add(publicBox, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.config.auto_update")), 0, row);
        form.add(autoUpdateBox, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.config.round_limit")), 0, row);
        form.add(roundLimitField, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.config.whitelist")), 0, row);
        form.add(whitelistBox, 1, row++);
        form.add(new Label(i18n("xenon.mindustry.server.config.description")), 0, row);
        form.add(descriptionField, 1, row++);

        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        FXUtils.smoothScrolling(scroll);
        setCenter(scroll);

        reload();
    }

    private void reload() {
        status.setText(i18n("xenon.mindustry.server.config.loading"));
        Schedulers.io().execute(() -> {
            try {
                ServerConfig cfg = configManager.load();
                Platform.runLater(() -> {
                    populate(cfg);
                    status.setText(i18n("xenon.mindustry.server.config.loaded"));
                });
            } catch (IOException ex) {
                LOG.warning("Failed to load server config for " + inst.getId(), ex);
                Platform.runLater(() -> status.setText(
                        i18n("xenon.mindustry.server.config.load_failed", ex.getMessage())));
            }
        });
    }

    private void populate(ServerConfig cfg) {
        nameField.setText(nullToEmpty(cfg.getName()));
        motdField.setText(nullToEmpty(cfg.getMotd()));
        portField.setText(cfg.getPort() == null ? "" : String.valueOf(cfg.getPort()));
        publicBox.setSelected(Boolean.TRUE.equals(cfg.getIsPublic()));
        autoUpdateBox.setSelected(Boolean.TRUE.equals(cfg.getAutoUpdate()));
        roundLimitField.setText(cfg.getRoundLimit() == null ? "" : String.valueOf(cfg.getRoundLimit()));
        whitelistBox.setSelected(Boolean.TRUE.equals(cfg.getWhitelist()));
        descriptionField.setText(nullToEmpty(cfg.getDescription()));
    }

    private void save() {
        ServerConfig cfg = new ServerConfig();
        cfg.setName(emptyToNull(nameField.getText()));
        cfg.setMotd(emptyToNull(motdField.getText()));
        cfg.setPort(parseIntOrNull(portField.getText()));
        cfg.setIsPublic(publicBox.isSelected() ? Boolean.TRUE : null);
        cfg.setAutoUpdate(autoUpdateBox.isSelected() ? Boolean.TRUE : null);
        cfg.setRoundLimit(parseIntOrNull(roundLimitField.getText()));
        cfg.setWhitelist(whitelistBox.isSelected() ? Boolean.TRUE : null);
        cfg.setDescription(emptyToNull(descriptionField.getText()));

        status.setText(i18n("xenon.mindustry.server.config.saving"));
        Schedulers.io().execute(() -> {
            try {
                configManager.save(cfg);
                Platform.runLater(() -> {
                    status.setText(i18n("xenon.mindustry.server.config.saved"));
                    Controllers.showToast(i18n("message.success"));
                });
            } catch (IOException ex) {
                LOG.warning("Failed to save server config for " + inst.getId(), ex);
                Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
