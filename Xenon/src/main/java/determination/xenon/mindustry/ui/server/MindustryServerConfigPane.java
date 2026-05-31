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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Form view for the Mindustry server's supported `config` command values.
@NotNullByDefault
public final class MindustryServerConfigPane extends BorderPane {

    private final ServerInstance inst;
    private final ServerConfigManager configManager;
    private final Map<String, JFXTextField> textFields = new LinkedHashMap<>();
    private final Map<String, JFXCheckBox> checkBoxes = new LinkedHashMap<>();
    private final Label status = new Label();

    /// Creates a config editor bound to one managed server instance.
    public MindustryServerConfigPane(ServerInstance inst, ServerInstanceManager manager) {
        this.inst = inst;
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
        labelColumn.setMinWidth(140);
        labelColumn.setPrefWidth(190);
        labelColumn.setHalignment(HPos.RIGHT);
        ColumnConstraints valueColumn = new ColumnConstraints();
        valueColumn.setHgrow(Priority.ALWAYS);
        valueColumn.setFillWidth(true);
        form.getColumnConstraints().setAll(labelColumn, valueColumn);

        int row = 0;
        for (ServerConfig.Entry entry : ServerConfig.schema()) {
            form.add(new Label(i18n(labelKey(entry))), 0, row);
            switch (entry.type()) {
                case STRING, INTEGER -> {
                    JFXTextField field = new JFXTextField();
                    field.setMaxWidth(Double.MAX_VALUE);
                    field.setPromptText(String.valueOf(entry.defaultValue()));
                    textFields.put(entry.key(), field);
                    form.add(field, 1, row++);
                }
                case BOOLEAN -> {
                    JFXCheckBox checkBox = new JFXCheckBox();
                    checkBoxes.put(entry.key(), checkBox);
                    form.add(checkBox, 1, row++);
                }
            }
        }

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
        for (ServerConfig.Entry entry : ServerConfig.schema()) {
            Object value = cfg.getOrDefault(entry);
            switch (entry.type()) {
                case STRING, INTEGER -> {
                    JFXTextField field = textFields.get(entry.key());
                    if (field != null) {
                        field.setText(String.valueOf(value));
                    }
                }
                case BOOLEAN -> {
                    JFXCheckBox checkBox = checkBoxes.get(entry.key());
                    if (checkBox != null) {
                        checkBox.setSelected(Boolean.TRUE.equals(value));
                    }
                }
            }
        }
    }

    private void save() {
        ServerConfig cfg = buildConfigFromForm();
        if (cfg == null) {
            return;
        }

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

    private @Nullable ServerConfig buildConfigFromForm() {
        ServerConfig cfg = new ServerConfig();
        for (ServerConfig.Entry entry : ServerConfig.schema()) {
            switch (entry.type()) {
                case STRING -> {
                    JFXTextField field = textFields.get(entry.key());
                    String text = field == null || field.getText() == null ? "" : field.getText().trim();
                    cfg.set(entry.key(), text);
                }
                case INTEGER -> {
                    Integer value = parseInteger(entry);
                    if (value == null) {
                        return null;
                    }
                    cfg.set(entry.key(), value);
                }
                case BOOLEAN -> {
                    JFXCheckBox checkBox = checkBoxes.get(entry.key());
                    cfg.set(entry.key(), checkBox != null && checkBox.isSelected());
                }
            }
        }
        return cfg;
    }

    private @Nullable Integer parseInteger(ServerConfig.Entry entry) {
        JFXTextField field = textFields.get(entry.key());
        String text = field == null || field.getText() == null ? "" : field.getText().trim();
        if (text.isEmpty()) {
            return (Integer) entry.defaultValue();
        }
        try {
            int value = Integer.parseInt(text);
            if ((entry.key().equals("port") || entry.key().equals("socketInputPort"))
                    && (value < 1 || value > 65535)) {
                throw new NumberFormatException();
            }
            if (value < 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException ex) {
            Controllers.dialog(i18n("xenon.mindustry.server.config.invalid_integer", i18n(labelKey(entry))),
                    i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
            return null;
        }
    }

    private static String labelKey(ServerConfig.Entry entry) {
        return "xenon.mindustry.server.config." + entry.key();
    }
}
