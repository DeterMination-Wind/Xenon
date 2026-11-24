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
import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXTextField;
import determination.xenon.mindustry.schematic.SchematicBlueprint;
import determination.xenon.mindustry.schematic.SchematicManager;
import determination.xenon.mindustry.schematic.SchematicPreview;
import determination.xenon.mindustry.schematic.SchematicReader;
import determination.xenon.mindustry.schematic.SchematicSummary;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.AdvancedListItem;
import determination.xenon.ui.construct.MessageDialogPane;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Per-instance Mindustry schematic manager.
@NotNullByDefault
public final class MindustrySchematicListPane extends BorderPane {

    /// Directory that stores Mindustry `.msch` files for this instance.
    private final Path schematicsDir;

    /// Disk-backed manager for import, export, delete, and listing.
    private final SchematicManager manager;

    /// Status label for loading and visible item counts.
    private final Label status = new Label();

    /// Search box filtering the virtualized schematic list.
    private final JFXTextField search = new JFXTextField();

    /// Virtualized list of schematic summaries.
    private final JFXListView<SchematicSummary> list = new JFXListView<>();

    /// Last fully scanned schematic list before search filtering.
    private @Unmodifiable List<SchematicSummary> allItems = List.of();

    /// Rendered thumbnails keyed by schematic file, rebuilt with each scan.
    private final Map<Path, WritableImage> previews = new HashMap<>();

    /// Thumbnail edge length in pixels.
    private static final int PREVIEW_SIZE = 44;

    /// Creates a schematic pane for one Mindustry data directory.
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

        search.setPromptText(i18n("xenon.mindustry.schematic.search"));
        search.textProperty().addListener((obs, old, value) -> rebuildList());
        HBox.setHgrow(search, Priority.ALWAYS);

        HBox toolbar = new HBox(8, refresh, importFile, importClipboard, openFolder);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, title, hint, toolbar, search, status);
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);

        list.getStyleClass().addAll("no-padding", "no-horizontal-scrollbar");
        list.setItems(FXCollections.observableArrayList());
        list.setCellFactory(lv -> new SchematicCell());
        list.setFixedCellSize(68);
        list.setPlaceholder(new Label(i18n("xenon.mindustry.schematic.empty")));
        setCenter(list);

        reload();
    }

    /// Reloads schematic metadata from disk on the IO scheduler.
    private void reload() {
        status.setText(i18n("xenon.mindustry.schematic.loading"));
        list.getItems().clear();
        Schedulers.io().execute(() -> {
            try {
                List<SchematicSummary> items = manager.list();
                Map<Path, WritableImage> rendered = renderPreviews(items);
                Platform.runLater(() -> {
                    previews.clear();
                    previews.putAll(rendered);
                    populate(items);
                });
            } catch (IOException ex) {
                LOG.warning("Failed to scan schematics", ex);
                Platform.runLater(() -> status.setText(
                        i18n("xenon.mindustry.schematic.failed") + " " + ex.getMessage()));
            }
        });
    }

    /// Renders thumbnails for the scanned schematics.
    ///
    /// Parsing is the expensive half (inflate + tile walk), so it runs on the IO
    /// thread with the scan. A schematic that cannot be parsed keeps the generic
    /// icon instead of failing the whole list.
    ///
    /// @param items scanned schematics
    /// @return rendered images keyed by file
    private Map<Path, WritableImage> renderPreviews(List<SchematicSummary> items) {
        Map<Path, WritableImage> rendered = new HashMap<>();
        for (SchematicSummary item : items) {
            try {
                SchematicBlueprint blueprint = SchematicReader.readBlueprint(item.file());
                rendered.put(item.file(), toFxImage(SchematicPreview.render(blueprint, PREVIEW_SIZE)));
            } catch (IOException | RuntimeException ex) {
                LOG.debug("No thumbnail for " + item.file().getFileName() + ": " + ex.getMessage());
            }
        }
        return rendered;
    }

    /// Converts a rendered ARGB buffer into a JavaFX image.
    ///
    /// @param image rendered preview
    /// @return JavaFX image ready for an [ImageView]
    private static WritableImage toFxImage(SchematicPreview.Image image) {
        WritableImage fx = new WritableImage(image.width(), image.height());
        fx.getPixelWriter().setPixels(0, 0, image.width(), image.height(),
                PixelFormat.getIntArgbInstance(), image.pixels(), 0, image.width());
        return fx;
    }

    /// Stores a completed scan and applies the current search filter.
    private void populate(List<SchematicSummary> items) {
        allItems = List.copyOf(items);
        rebuildList();
    }

    /// Rebuilds visible rows from the last scan and current search text.
    private void rebuildList() {
        String q = search.getText() == null
                ? ""
                : search.getText().trim().toLowerCase(Locale.ROOT);
        List<SchematicSummary> visible = new ArrayList<>();
        for (SchematicSummary item : allItems) {
            if (matches(item, q)) {
                visible.add(item);
            }
        }
        list.getItems().setAll(visible);
        status.setText(i18n("xenon.mindustry.schematic.count", visible.size()));
    }

    /// Builds a single schematic row for the virtual list.
    private AdvancedListItem buildRow(SchematicSummary s) {
        AdvancedListItem item = new AdvancedListItem();
        item.setMinWidth(0);
        item.setMaxWidth(Double.MAX_VALUE);
        @Nullable WritableImage thumbnail = previews.get(s.file());
        if (thumbnail != null) {
            ImageView view = new ImageView(thumbnail);
            view.setFitWidth(PREVIEW_SIZE);
            view.setFitHeight(PREVIEW_SIZE);
            view.setSmooth(false);
            view.setMouseTransparent(true);
            javafx.scene.layout.BorderPane.setMargin(view, new Insets(0, 8, 0, 0));
            javafx.scene.layout.BorderPane.setAlignment(view, Pos.CENTER);
            item.setLeftGraphic(view);
        } else {
            item.setLeftIcon(SVG.SCHEMA);
        }
        String name = s.name().isEmpty() ? s.file().getFileName().toString() : s.name();
        item.setTitle(name + "  ·  " + s.width() + "×" + s.height());
        item.setSubtitle(s.description().isEmpty() ? s.file().getFileName().toString() : s.description());

        HBox actions = new HBox(4);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        actions.setMaxWidth(Region.USE_PREF_SIZE);
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

    /// Opens a file chooser and imports the selected `.msch` file.
    private void chooseAndImport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("xenon.mindustry.schematic.import.file"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Mindustry schematic (*.msch)", "*.msch"));
        @Nullable java.io.File f = chooser.showOpenDialog(Controllers.getStage());
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

    /// Imports a schematic from the current system clipboard text.
    private void importFromClipboard() {
        @Nullable String s = Clipboard.getSystemClipboard().getString();
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

    /// Exports the selected schematic to a user-chosen file.
    private void exportAs(SchematicSummary s) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("xenon.mindustry.schematic.export"));
        chooser.setInitialFileName(s.file().getFileName().toString());
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Mindustry schematic (*.msch)", "*.msch"));
        @Nullable java.io.File f = chooser.showSaveDialog(Controllers.getStage());
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

    /// Shows an error dialog for schematic operations.
    private void showError(Throwable ex) {
        Controllers.dialog(ex.getMessage(), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
    }

    /// Returns whether a schematic summary matches the current lowercase query.
    private static boolean matches(SchematicSummary summary, String q) {
        if (q.isEmpty()) return true;
        if (contains(summary.name(), q)) return true;
        if (contains(summary.description(), q)) return true;
        if (contains(summary.file().getFileName().toString(), q)) return true;
        for (Map.Entry<String, String> entry : summary.tags().entrySet()) {
            if (contains(entry.getKey(), q) || contains(entry.getValue(), q)) {
                return true;
            }
        }
        return false;
    }

    /// Performs a null-tolerant lowercase substring check.
    private static boolean contains(@Nullable String text, String q) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(q);
    }

    /// Recyclable virtual-list cell for schematic summaries.
    private final class SchematicCell extends ListCell<SchematicSummary> {
        /// Creates a schematic cell whose width is bound to the list viewport.
        SchematicCell() {
            setText(null);
            FXUtils.limitCellWidth(list, this);
        }

        /// Prevents long row content from increasing the virtual list viewport width.
        @Override
        protected double computePrefWidth(double height) {
            return 0;
        }

        /// Updates the visible row graphic for the current schematic summary.
        @Override
        protected void updateItem(@Nullable SchematicSummary summary, boolean empty) {
            super.updateItem(summary, empty);
            setText(null);
            setGraphic(empty || summary == null ? null : buildRow(summary));
        }
    }
}
