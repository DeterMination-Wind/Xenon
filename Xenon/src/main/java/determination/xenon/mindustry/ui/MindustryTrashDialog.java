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
import com.jfoenix.controls.JFXDialogLayout;
import determination.xenon.mindustry.trash.MindustryTrashService;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.DialogCloseEvent;
import determination.xenon.ui.construct.MessageDialogPane;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Recycle-bin browser for deleted instances and save trees.
public final class MindustryTrashDialog {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /// Opens the trash browser for one root.
    ///
    /// @param root root whose `.trash/` is shown
    public static void show(Path root) {
        MindustryTrashService service = new MindustryTrashService(root);
        JFXDialogLayout layout = new JFXDialogLayout();
        layout.setHeading(new Label(i18n("xenon.trash.title")));
        layout.setBody(new Label(i18n("message.doing")));
        JFXButton close = new JFXButton(i18n("button.close"));
        close.getStyleClass().add("dialog-accept");
        close.setOnAction(e -> layout.fireEvent(new DialogCloseEvent()));
        layout.setActions(close);
        Controllers.dialog(layout);
        reload(layout, service);
    }

    /// Reloads the entry list.
    private static void reload(JFXDialogLayout layout, MindustryTrashService service) {
        Schedulers.io().execute(() -> {
            try {
                List<MindustryTrashService.Entry> entries = service.list();
                Platform.runLater(() -> render(layout, service, entries));
            } catch (IOException e) {
                LOG.warning("Cannot list the recycle bin", e);
                Platform.runLater(() -> layout.setBody(new Label(e.getMessage())));
            }
        });
    }

    /// Renders the entries.
    private static void render(JFXDialogLayout layout, MindustryTrashService service,
                               List<MindustryTrashService.Entry> entries) {
        Label hint = new Label(i18n("xenon.trash.hint"));
        hint.getStyleClass().add("subtitle-label");
        hint.setWrapText(true);
        VBox rows = new VBox(6);
        if (entries.isEmpty()) {
            rows.getChildren().add(new Label(i18n("xenon.trash.empty")));
        }
        for (MindustryTrashService.Entry entry : entries) {
            rows.getChildren().add(row(layout, service, entry));
        }
        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(300);
        VBox body = new VBox(8, hint, scroll);
        body.setPadding(new Insets(4, 0, 0, 0));
        layout.setBody(body);
    }

    /// Builds one trash row.
    private static HBox row(JFXDialogLayout layout, MindustryTrashService service,
                            MindustryTrashService.Entry entry) {
        Label name = new Label(entry.original().getFileName().toString());
        name.getStyleClass().add("title-label");
        Label meta = new Label(i18n("xenon.trash.meta",
                entry.kind(), DATE.format(entry.deletedAt())));
        meta.getStyleClass().add("subtitle-label");
        VBox text = new VBox(2, name, meta);
        HBox.setHgrow(text, Priority.ALWAYS);

        JFXButton restore = FXUtils.newRaisedButton(i18n("xenon.trash.restore"));
        restore.setOnAction(e -> Schedulers.io().execute(() -> {
            try {
                service.restore(entry);
                Platform.runLater(() -> {
                    Controllers.showToast(i18n("xenon.trash.restored"));
                    reload(layout, service);
                });
            } catch (IOException ex) {
                Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        }));
        JFXButton remove = FXUtils.newRaisedButton(i18n("xenon.trash.delete"));
        remove.setOnAction(e -> Controllers.confirm(
                i18n("xenon.trash.delete.confirm", name.getText()),
                i18n("xenon.trash.title"), () -> Schedulers.io().execute(() -> {
                    try {
                        service.deletePermanently(entry);
                        Platform.runLater(() -> reload(layout, service));
                    } catch (IOException ex) {
                        Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                                i18n("message.error"), MessageDialogPane.MessageType.ERROR));
                    }
                }), null));

        HBox actions = new HBox(6, restore, remove);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(8, text, actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6));
        row.getStyleClass().add("community-reply");
        return row;
    }

    private MindustryTrashDialog() {
    }
}
