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
import determination.xenon.mindustry.schematic.SchematicManager;
import determination.xenon.mindustry.schematic.SchematicSummary;
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
import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/** Per-instance Mindustry schematic manager. */
public final class MindustrySchematicListPane extends BorderPane {

    private final Path schematicsDir;
    private final SchematicManager manager;
    private final Label status = new Label();
    private final VBox listBox = new VBox(2);

    public MindustrySchematicListPane(Path dataDir) {
        this.schematicsDir = dataDir.resolve("schematics");
        this.manager = new SchematicManager(schematicsDir);
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.mindustry.schematic.title"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.mindustry.schematic.hint", schematicsDir.toString()));
        hint.setWrapText(true);

        JFXButton refresh = FXUtils.newRaisedButton(i18n("button.refresh"));
        refresh.setOnAction(e -> reload());
        JFXButton importFile = FXUtils.newRaisedButton(i18n("xenon.mindustry.schematic.import.file"));
        importFile.setOnAction(e -> chooseAndImport());
        JFXButton importClipboard = FXUtils.newRaisedButton(i18n("xenon.mindustry.schematic.import.clipboard"));
        importClipboard.setOnAction(e -> importFromClipboard());
        JFXButton openFolder = FXUtils.newRaisedButton(i18n("folder.schematics"));
        openFolder.setOnAction(e -> FXUtils.openFolder(schematicsDir));

        HBox toolbar = new HBox(8, refresh, importFile, importClipboard, openFolder);
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
        status.setText(i18n("xenon.mindustry.schematic.loading"));
        listBox.getChildren().clear();
        Schedulers.io().execute(() -> {
            try {
                List<SchematicSummary> items = manager.list();
                Platform.runLater(() -> populate(items));
            } catch (IOException ex) {
                LOG.warning("Failed to scan schematics", ex);
                Platform.runLater(() -> status.setText(
                        i18n("xenon.mindustry.schematic.failed") + " " + ex.getMessage()));
            }
        });
    }

    private void populate(List<SchematicSummary> items) {
        listBox.getChildren().clear();
        status.setText(i18n("xenon.mindustry.schematic.count", items.size()));
        if (items.isEmpty()) {
            Label empty = new Label(i18n("xenon.mindustry.schematic.empty"));
            empty.setPadding(new Insets(8));
            listBox.getChildren().add(empty);
            return;
        }
        for (SchematicSummary s : items) {
            listBox.getChildren().add(buildRow(s));
        }
    }

    private AdvancedListItem buildRow(SchematicSummary s) {
        AdvancedListItem item = new AdvancedListItem();
        item.setLeftIcon(SVG.SCHEMA);
        String name = s.name().isEmpty() ? s.file().getFileName().toString() : s.name();
        item.setTitle(name + "  ·  " + s.width() + "×" + s.height());
        item.setSubtitle(s.description().isEmpty() ? s.file().getFileName().toString() : s.description());

        HBox actions = new HBox(4);
        JFXButton copy = FXUtils.newRaisedButton(i18n("xenon.mindustry.schematic.copy"));
        copy.setOnAction(e -> Schedulers.io().execute(() -> {
            try {
                String b64 = manager.exportBase64(s.file());
                Platform.runLater(() -> {
                    ClipboardContent cc = new ClipboardContent();
                    cc.putString(b64);
                    Clipboard.getSystemClipboard().setContent(cc);
                    Controllers.showToast(i18n("xenon.mindustry.schematic.copy.done"));
                });
            } catch (IOException ex) {
                Platform.runLater(() -> showError(ex));
            }
        }));
        JFXButton export = FXUtils.newRaisedButton(i18n("xenon.mindustry.schematic.export"));
        export.setOnAction(e -> exportAs(s));
        JFXButton delete = FXUtils.newRaisedButton(i18n("button.delete"));
        delete.setOnAction(e -> Schedulers.io().execute(() -> {
            try {
                manager.delete(s.file());
                Platform.runLater(this::reload);
            } catch (IOException ex) {
                Platform.runLater(() -> showError(ex));
            }
        }));
        actions.getChildren().setAll(copy, export, delete);
        item.setRightGraphic(actions);
        return item;
    }

    private void chooseAndImport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("xenon.mindustry.schematic.import.file"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Mindustry schematic (*.msch)", "*.msch"));
        java.io.File f = FXUtils.showOpenDialog(chooser, Controllers.getStage());
        if (f == null) return;
        Schedulers.io().execute(() -> {
            try {
                manager.importFile(f.toPath());
                Platform.runLater(this::reload);
            } catch (IOException ex) {
                LOG.warning("Failed to import schematic " + f, ex);
                Platform.runLater(() -> showError(ex));
            }
        });
    }

    private void importFromClipboard() {
        String s = Clipboard.getSystemClipboard().getString();
        if (s == null || s.isBlank()) {
            Controllers.dialog(i18n("xenon.mindustry.schematic.import.clipboard.empty"),
                    i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                manager.importBase64(s);
                Platform.runLater(this::reload);
            } catch (IOException ex) {
                Platform.runLater(() -> showError(ex));
            }
        });
    }

    private void exportAs(SchematicSummary s) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("xenon.mindustry.schematic.export"));
        chooser.setInitialFileName(s.file().getFileName().toString());
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Mindustry schematic (*.msch)", "*.msch"));
        java.io.File f = FXUtils.showSaveDialog(chooser, Controllers.getStage());
        if (f == null) return;
        Schedulers.io().execute(() -> {
            try {
                manager.exportFile(s.file(), f.toPath());
                Platform.runLater(() -> Controllers.showToast(i18n("xenon.mindustry.schematic.export.done")));
            } catch (IOException ex) {
                Platform.runLater(() -> showError(ex));
            }
        });
    }

    private void showError(Throwable ex) {
        Controllers.dialog(ex.getMessage(), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
    }
}
