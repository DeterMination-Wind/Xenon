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
import com.jfoenix.controls.JFXTextField;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.mod.MindustryLocalMod;
import determination.xenon.mindustry.mod.MindustryModManager;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.AdvancedListItem;
import determination.xenon.ui.construct.MessageDialogPane;
import javafx.application.Platform;
import javafx.event.EventTarget;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Per-instance Mindustry mod manager — replaces HMCL's ModListPage for Mindustry instances.
///
/// Supports single-row actions, checkbox multi-selection (plain / Shift /
/// Ctrl clicks), batch enable-disable-delete, and drag-and-drop import of
/// files, folders and nested release bundles.
public final class MindustryModListPane extends BorderPane {

    /// Maximum directory recursion depth for drag-and-drop imports.
    private static final int DROP_DIRECTORY_DEPTH = 4;

    /// Maximum number of failure lines shown in a drop summary dialog.
    private static final int DROP_FAILURE_LINES = 10;

    private final Path dataDir;
    private final Path modsDir;
    private final MindustryModManager manager;
    /// Instance descriptor used for post-install compatibility checks; `null`
    /// when this pane is shared by the server mods page.
    private final @Nullable MindustryVersion version;
    /// Instance root paired with [#version] for compatibility checks; may be `null`.
    private final @Nullable Path versionRoot;
    private final Label status = new Label();
    private final JFXTextField search = new JFXTextField();
    private final VBox listBox = new VBox(2);
    private final ScrollPane scroll = new ScrollPane(listBox);
    private List<MindustryLocalMod> allMods = List.of();

    /// Mods currently rendered, in display order; used for Shift-range selection.
    private List<MindustryLocalMod> visibleMods = List.of();

    /// Files selected for batch operations, keyed by path so a rescan keeps the selection.
    private final Set<Path> selectedFiles = new LinkedHashSet<>();

    /// Checkbox of each rendered row; updated in place when the selection changes.
    private final Map<Path, CheckBox> rowCheckBoxes = new LinkedHashMap<>();

    /// Row that anchors a Shift-range selection; `-1` before the first click.
    private int selectionAnchor = -1;

    private final JFXButton batchEnable;
    private final JFXButton batchDisable;
    private final JFXButton batchDelete;
    private final JFXButton selectAll;
    private final JFXButton clearSelection;

    public MindustryModListPane(Path dataDir) {
        this(dataDir, null, null);
    }

    /// Creates a mod list pane for one instance.
    ///
    /// @param dataDir Mindustry data directory that owns `mods/`
    /// @param version instance descriptor used for post-install compatibility
    ///                checks; may be `null`
    /// @param versionRoot instance root paired with `version`; may be `null`
    public MindustryModListPane(Path dataDir, @Nullable MindustryVersion version, @Nullable Path versionRoot) {
        this.dataDir = dataDir;
        this.version = version;
        this.versionRoot = versionRoot;
        this.modsDir = dataDir.resolve("mods");
        this.manager = new MindustryModManager(modsDir);
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.mindustry.modlist.title"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.mindustry.modlist.hint", modsDir.toString()));
        hint.setWrapText(true);

        JFXButton refresh = FXUtils.newRaisedButton(i18n("button.refresh"));
        refresh.setOnAction(e -> reload());
        JFXButton install = FXUtils.newRaisedButton(i18n("xenon.mindustry.modlist.install"));
        install.setOnAction(e -> chooseAndInstall());
        JFXButton openFolder = FXUtils.newRaisedButton(i18n("folder.mod"));
        openFolder.setOnAction(e -> FXUtils.openFolder(modsDir));

        search.setPromptText(i18n("xenon.mindustry.modlist.search"));
        search.textProperty().addListener((obs, oldValue, newValue) -> rebuildList());
        HBox.setHgrow(search, Priority.ALWAYS);

        HBox toolbar = new HBox(8, refresh, install, openFolder);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        selectAll = FXUtils.newRaisedButton(i18n("xenon.mindustry.modlist.select.all"));
        selectAll.setOnAction(e -> selectVisible(true));
        clearSelection = FXUtils.newRaisedButton(i18n("xenon.mindustry.modlist.select.clear"));
        clearSelection.setOnAction(e -> selectVisible(false));
        batchEnable = FXUtils.newRaisedButton(i18n("xenon.mindustry.modlist.batch.enable", 0));
        batchEnable.setOnAction(e -> applyBatchEnabled(true));
        batchDisable = FXUtils.newRaisedButton(i18n("xenon.mindustry.modlist.batch.disable", 0));
        batchDisable.setOnAction(e -> applyBatchEnabled(false));
        batchDelete = FXUtils.newRaisedButton(i18n("xenon.mindustry.modlist.batch.delete", 0));
        batchDelete.setOnAction(e -> confirmBatchDelete());
        HBox batchToolbar = new HBox(8, selectAll, clearSelection, batchEnable, batchDisable, batchDelete);
        batchToolbar.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, title, hint, toolbar, batchToolbar, search, status);
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        FXUtils.smoothScrolling(scroll);
        setCenter(scroll);

        installDragAndDrop();
        refreshSelectionUi();
        reload();
    }

    /** Refresh the list from the instance's {@code mods/} folder. */
    public void refreshFromDisk() {
        reload();
    }

    private void reload() {
        double keepV = scroll.getVvalue();
        status.setText(i18n("xenon.mindustry.modlist.loading"));
        listBox.getChildren().clear();
        rowCheckBoxes.clear();
        Schedulers.io().execute(() -> {
            // scanAll keeps unreadable archives visible: a folder holding only
            // release zips or dev jars must not look like "no mods installed".
            List<MindustryLocalMod> mods = manager.scanAll();
            Platform.runLater(() -> {
                populate(mods);
                Platform.runLater(() -> scroll.setVvalue(keepV));
            });
        });
    }

    private void populate(List<MindustryLocalMod> mods) {
        allMods = List.copyOf(mods);
        rebuildList();
    }

    private void rebuildList() {
        listBox.getChildren().clear();
        rowCheckBoxes.clear();
        String query = search.getText() == null
                ? ""
                : search.getText().trim().toLowerCase(Locale.ROOT);
        List<MindustryLocalMod> visible = new ArrayList<>();
        int unparsable = 0;
        for (MindustryLocalMod mod : allMods) {
            if (matches(mod, query)) {
                visible.add(mod);
                if (mod.isUnparsable()) unparsable++;
            }
        }
        visibleMods = List.copyOf(visible);
        // Drop selections whose files disappeared (delete, rescan after install).
        Set<Path> available = new LinkedHashSet<>();
        for (MindustryLocalMod mod : allMods) {
            available.add(mod.getFile());
        }
        selectedFiles.retainAll(available);
        if (!selectedFiles.contains(anchorFile())) {
            selectionAnchor = -1;
        }
        int installed = visible.size() - unparsable;
        status.setText(unparsable == 0
                ? i18n("xenon.mindustry.modlist.count", installed)
                : i18n("xenon.mindustry.modlist.count.unparsable", installed, unparsable));
        if (visible.isEmpty()) {
            Label empty = new Label(i18n("xenon.mindustry.modlist.empty"));
            empty.setPadding(new Insets(8));
            listBox.getChildren().add(empty);
            refreshSelectionUi();
            return;
        }
        for (MindustryLocalMod mod : visible) {
            listBox.getChildren().add(buildRow(mod));
        }
        refreshSelectionUi();
    }

    /// The file of the anchor row, or `null` when no valid anchor remains.
    private @Nullable Path anchorFile() {
        if (selectionAnchor < 0 || selectionAnchor >= visibleMods.size()) {
            return null;
        }
        return visibleMods.get(selectionAnchor).getFile();
    }

    private AdvancedListItem buildRow(MindustryLocalMod mod) {
        AdvancedListItem item = new AdvancedListItem();
        CheckBox checkBox = new CheckBox();
        checkBox.setFocusTraversable(false);
        checkBox.setSelected(selectedFiles.contains(mod.getFile()));
        checkBox.setOnAction(event -> {
            toggleSelection(mod);
            selectionAnchor = visibleMods.indexOf(mod);
            refreshSelectionUi();
        });
        rowCheckBoxes.put(mod.getFile(), checkBox);
        item.setLeftGraphic(selectionGraphic(mod, checkBox));

        String label = mod.displayName();
        if (mod.isUnparsable()) {
            label += "  [" + i18n("xenon.mindustry.modlist.unparsable") + "]";
        } else {
            if (mod.getVersion() != null && !mod.getVersion().isBlank()) label += "  v" + mod.getVersion();
            if (!mod.isEnabled()) label += "  [" + i18n("xenon.mindustry.modlist.disabled") + "]";
            if (mod.isIgnoredByDuplicate()) label += "  [" + i18n("xenon.mindustry.modlist.ignored") + "]";
        }
        item.setTitle(label);

        if (mod.isUnparsable()) {
            item.setSubtitle(unparsableSubtitle(mod));
        } else {
            item.setSubtitle(readableSubtitle(mod));
        }

        HBox actions = new HBox(4);
        if (!mod.isUnparsable()) {
            JFXButton toggle = FXUtils.newRaisedButton(
                    mod.isEnabled() ? i18n("xenon.mindustry.modlist.disable") : i18n("xenon.mindustry.modlist.enable"));
            toggle.setOnAction(e -> {
                try {
                    if (mod.isEnabled()) manager.disable(mod); else manager.enable(mod);
                    reload();
                } catch (IOException ex) {
                    showError(ex);
                }
            });
            actions.getChildren().add(toggle);
        }
        JFXButton delete = FXUtils.newRaisedButton(i18n("button.delete"));
        delete.setOnAction(e -> {
            try {
                manager.delete(mod);
                selectedFiles.remove(mod.getFile());
                reload();
            } catch (IOException ex) {
                showError(ex);
            }
        });
        actions.getChildren().add(delete);
        item.setRightGraphic(actions);

        // Plain click selects one row (clicking it again clears), Shift extends
        // the range and Ctrl toggles one row. Clicks on row controls (checkbox,
        // buttons) keep their own behavior.
        item.setOnMouseClicked(event -> {
            if (isInteractiveTarget(event.getTarget(), item)) {
                return;
            }
            handleRowClick(mod, event);
        });
        return item;
    }

    /// Left graph: selection checkbox followed by the mod type icon.
    private HBox selectionGraphic(MindustryLocalMod mod, CheckBox checkBox) {
        SVG icon = mod.isUnparsable()
                ? SVG.WARNING
                : (mod.isJava() ? SVG.DEPLOYED_CODE : SVG.EXTENSION);
        Node iconNode = icon.createIcon(24);
        iconNode.setMouseTransparent(true);
        HBox box = new HBox(10, checkBox, iconNode);
        box.setAlignment(Pos.CENTER_LEFT);
        AdvancedListItem.setAlignment(box, Pos.CENTER);
        return box;
    }

    /// Applies a plain / Shift / Ctrl click to the current selection.
    private void handleRowClick(MindustryLocalMod mod, MouseEvent event) {
        int index = visibleMods.indexOf(mod);
        if (index < 0) {
            return;
        }
        Path file = mod.getFile();
        if (event.isShiftDown() && selectionAnchor >= 0 && selectionAnchor < visibleMods.size()) {
            int from = Math.min(selectionAnchor, index);
            int to = Math.max(selectionAnchor, index);
            if (!event.isControlDown()) {
                selectedFiles.clear();
            }
            for (int i = from; i <= to; i++) {
                selectedFiles.add(visibleMods.get(i).getFile());
            }
        } else if (event.isControlDown()) {
            if (!selectedFiles.remove(file)) {
                selectedFiles.add(file);
            }
            selectionAnchor = index;
        } else if (selectedFiles.size() == 1 && selectedFiles.contains(file)) {
            selectedFiles.clear();
            selectionAnchor = index;
        } else {
            selectedFiles.clear();
            selectedFiles.add(file);
            selectionAnchor = index;
        }
        refreshSelectionUi();
    }

    /// Adds or removes one row from the selection.
    private void toggleSelection(MindustryLocalMod mod) {
        Path file = mod.getFile();
        if (!selectedFiles.remove(file)) {
            selectedFiles.add(file);
        }
    }

    /// Selects or clears every currently visible row.
    private void selectVisible(boolean select) {
        if (select) {
            for (MindustryLocalMod mod : visibleMods) {
                selectedFiles.add(mod.getFile());
            }
        } else {
            for (MindustryLocalMod mod : visibleMods) {
                selectedFiles.remove(mod.getFile());
            }
        }
        refreshSelectionUi();
    }

    /// Synchronizes checkboxes and batch button state with [#selectedFiles].
    private void refreshSelectionUi() {
        for (Map.Entry<Path, CheckBox> entry : rowCheckBoxes.entrySet()) {
            entry.getValue().setSelected(selectedFiles.contains(entry.getKey()));
        }
        int count = selectedFiles.size();
        batchEnable.setText(i18n("xenon.mindustry.modlist.batch.enable", count));
        batchDisable.setText(i18n("xenon.mindustry.modlist.batch.disable", count));
        batchDelete.setText(i18n("xenon.mindustry.modlist.batch.delete", count));
        batchEnable.setDisable(count == 0);
        batchDisable.setDisable(count == 0);
        batchDelete.setDisable(count == 0);
        selectAll.setDisable(visibleMods.isEmpty());
        clearSelection.setDisable(count == 0);
    }

    /// Mods selected in the current scan.
    private List<MindustryLocalMod> selectedMods() {
        if (selectedFiles.isEmpty()) {
            return List.of();
        }
        List<MindustryLocalMod> result = new ArrayList<>();
        for (MindustryLocalMod mod : allMods) {
            if (selectedFiles.contains(mod.getFile())) {
                result.add(mod);
            }
        }
        return result;
    }

    /// Enables or disables every selected mod with one settings.bin write.
    private void applyBatchEnabled(boolean enabled) {
        List<MindustryLocalMod> targets = selectedMods();
        if (targets.isEmpty()) {
            return;
        }
        Schedulers.io().execute(() -> {
            List<MindustryCompatibility.Issue> before = evaluateAfterInstall();
            MindustryModManager.BatchResult result = manager.setEnabled(targets, enabled);
            List<MindustryCompatibility.Issue> issues =
                    MindustryCompatibility.newlyIntroduced(before, evaluateAfterInstall());
            Platform.runLater(() -> {
                selectedFiles.clear();
                reload();
                reportBatch(result);
                showIssues(issues);
            });
        });
    }

    /// Deletes every selected mod, asking for confirmation first.
    private void confirmBatchDelete() {
        List<MindustryLocalMod> targets = selectedMods();
        if (targets.isEmpty()) {
            return;
        }
        JFXButton confirm = new JFXButton(i18n("button.delete"));
        confirm.getStyleClass().add("dialog-error");
        confirm.setOnAction(e -> Schedulers.io().execute(() -> {
            MindustryModManager.BatchResult result = manager.deleteAll(targets);
            Platform.runLater(() -> {
                selectedFiles.clear();
                reload();
                reportBatch(result);
            });
        }));
        Controllers.confirmAction(
                i18n("xenon.mindustry.modlist.batch.delete.confirm", targets.size()),
                i18n("message.warning"),
                MessageDialogPane.MessageType.WARNING,
                confirm);
    }

    /// Reports a batch outcome in the status line and a dialog for failures.
    private void reportBatch(MindustryModManager.BatchResult result) {
        status.setText(i18n("xenon.mindustry.modlist.batch.done", result.applied().size()));
        if (result.failures().isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder(
                i18n("xenon.mindustry.modlist.batch.failed", result.failures().size()));
        result.failures().forEach((file, reason) ->
                text.append('\n').append("· ").append(file).append(": ").append(reason));
        Controllers.dialog(text.toString(), i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
    }

    /// Subtitle of a readable mod row: duplicate note, author and description.
    private static String readableSubtitle(MindustryLocalMod mod) {
        StringBuilder subtitle = new StringBuilder();
        if (mod.isIgnoredByDuplicate()) {
            subtitle.append(i18n("xenon.mindustry.modlist.ignored_by", mod.getIgnoredByFileName()));
        }
        if (mod.getAuthor() != null && !mod.getAuthor().isBlank()) {
            if (subtitle.length() > 0) subtitle.append("  —  ");
            subtitle.append(mod.getAuthor());
        }
        if (mod.getDescription() != null && !mod.getDescription().isBlank()) {
            if (subtitle.length() > 0) subtitle.append("  —  ");
            String desc = mod.getDescription().replaceAll("\\s+", " ").trim();
            if (desc.length() > 100) desc = desc.substring(0, 100) + "...";
            subtitle.append(desc);
        }
        return subtitle.toString();
    }

    /** Subtitle of a broken archive row: what failed, plus the raw parse error. */
    private static String unparsableSubtitle(MindustryLocalMod mod) {
        String reason = mod.getParseError();
        if (reason == null || reason.isBlank()) {
            return i18n("xenon.mindustry.modlist.unparsable.hint");
        }
        String compact = reason.replaceAll("\\s+", " ").trim();
        if (compact.length() > 140) compact = compact.substring(0, 140) + "...";
        return i18n("xenon.mindustry.modlist.unparsable.hint") + "  —  " + compact;
    }

    private boolean matches(MindustryLocalMod mod, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return contains(mod.displayName(), query)
                || contains(mod.getName(), query)
                || contains(mod.getInternalName(), query)
                || contains(mod.getAuthor(), query)
                || contains(mod.getDescription(), query)
                || contains(mod.getVersion(), query)
                || contains(mod.getFile().getFileName().toString(), query)
                || contains(mod.getIgnoredByFileName(), query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    /// Whether a click landed on a checkbox or button inside a row.
    private static boolean isInteractiveTarget(EventTarget target, Node row) {
        Object node = target;
        while (node instanceof Node current) {
            if (current instanceof ButtonBase || current instanceof CheckBox) {
                return true;
            }
            if (current == row) {
                return false;
            }
            node = current.getParent();
        }
        return false;
    }

    private void chooseAndInstall() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("xenon.mindustry.modlist.install"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Mindustry mod (*.jar, *.zip)", "*.jar", "*.zip"));
        java.io.File f = FXUtils.showOpenDialog(chooser, Controllers.getStage());
        if (f == null) return;
        Schedulers.io().execute(() -> {
            try {
                Files.createDirectories(modsDir);
                InstallSummary summary = runInstall(List.of(f.toPath()));
                Platform.runLater(() -> {
                    reload();
                    if (!summary.failures.isEmpty()) {
                        showError(new IOException(String.join("\n", summary.failures)));
                    } else {
                        showIssues(summary.issues);
                    }
                });
            } catch (IOException ex) {
                LOG.warning("Failed to install mod " + f, ex);
                Platform.runLater(() -> showError(ex));
            }
        });
    }

    /// Installs archives dragged onto the pane: files, folders, nested bundles.
    private void installDragAndDrop() {
        setOnDragOver(event -> {
            if (event.getGestureSource() != this && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
                setDropHighlight(true);
            }
            event.consume();
        });
        setOnDragExited(event -> {
            Bounds bounds = localToScene(getBoundsInLocal());
            if (bounds == null || !bounds.contains(event.getSceneX(), event.getSceneY())) {
                setDropHighlight(false);
            }
            event.consume();
        });
        setOnDragDropped(event -> {
            setDropHighlight(false);
            List<File> files = event.getDragboard().getFiles();
            boolean accepted = files != null && !files.isEmpty();
            event.setDropCompleted(accepted);
            event.consume();
            if (accepted) {
                importDropped(files);
            }
        });
    }

    /// Expands dropped files and folders, then installs every archive found.
    private void importDropped(List<File> dropped) {
        status.setText(i18n("xenon.mindustry.modlist.drop.importing"));
        Schedulers.io().execute(() -> {
            List<Path> archives = new ArrayList<>();
            for (File file : dropped) {
                collectArchives(file.toPath(), archives, DROP_DIRECTORY_DEPTH);
            }
            if (archives.isEmpty()) {
                Platform.runLater(() -> {
                    status.setText(i18n("xenon.mindustry.modlist.drop.empty"));
                    Controllers.dialog(i18n("xenon.mindustry.modlist.drop.empty.hint"),
                            i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
                });
                return;
            }
            InstallSummary summary = runInstall(archives);
            Platform.runLater(() -> {
                reload();
                showDropSummary(summary);
                showIssues(summary.issues);
            });
        });
    }

    /// Collects `.jar` / `.zip` files, recursing into visible directories.
    private static void collectArchives(Path path, List<Path> out, int depth) {
        if (depth < 0) {
            return;
        }
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                for (Path child : stream) {
                    Path name = child.getFileName();
                    if (name != null && name.toString().startsWith(".")) {
                        continue;
                    }
                    collectArchives(child, out, depth - 1);
                }
            } catch (IOException ignored) {
                // Unreadable directories are skipped; other dropped items still import.
            }
            return;
        }
        if (Files.isRegularFile(path) && MindustryModManager.isModArchive(path)) {
            out.add(path);
        }
    }

    /// Runs [install][MindustryModManager#install(Path)] for every archive.
    ///
    /// Must run on the IO scheduler. A failing archive never aborts the rest.
    private InstallSummary runInstall(List<Path> archives) {
        InstallSummary summary = new InstallSummary();
        List<MindustryCompatibility.Issue> before = evaluateAfterInstall();
        for (Path archive : archives) {
            try {
                MindustryModManager.InstallResult result = manager.install(archive);
                summary.imported++;
                summary.replaced += result.replacedFiles().size();
            } catch (IOException | RuntimeException ex) {
                LOG.warning("Failed to install mod " + archive, ex);
                summary.failures.add(archive.getFileName() + ": " + messageOf(ex));
            }
        }
        summary.issues = MindustryCompatibility.newlyIntroduced(before, evaluateAfterInstall());
        return summary;
    }

    /// Toggles the drag highlight style class.
    private void setDropHighlight(boolean active) {
        if (active) {
            if (!getStyleClass().contains("mod-drop-highlight")) {
                getStyleClass().add("mod-drop-highlight");
            }
        } else {
            getStyleClass().remove("mod-drop-highlight");
        }
    }

    /// Shows the imported / replaced / failed recap of one drop.
    private void showDropSummary(InstallSummary summary) {
        status.setText(i18n("xenon.mindustry.modlist.drop.summary",
                summary.imported, summary.replaced, summary.failures.size()));
        if (summary.failures.isEmpty()) {
            Controllers.dialog(
                    i18n("xenon.mindustry.modlist.drop.summary", summary.imported, summary.replaced, 0),
                    i18n("message.success"), MessageDialogPane.MessageType.SUCCESS);
            return;
        }
        StringBuilder text = new StringBuilder(
                i18n("xenon.mindustry.modlist.drop.summary",
                        summary.imported, summary.replaced, summary.failures.size()));
        int shown = Math.min(summary.failures.size(), DROP_FAILURE_LINES);
        for (int i = 0; i < shown; i++) {
            text.append('\n').append("· ").append(summary.failures.get(i));
        }
        if (summary.failures.size() > shown) {
            text.append('\n').append(i18n("xenon.mindustry.modlist.drop.more",
                    summary.failures.size() - shown));
        }
        Controllers.dialog(text.toString(), i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
    }

    /// Shows newly introduced compatibility issues, when this pane is attached
    /// to an instance.
    private void showIssues(List<MindustryCompatibility.Issue> issues) {
        if (!issues.isEmpty()) {
            MindustryCompatibility.showIssues(i18n("xenon.mindustry.install.compat.title"), issues);
        }
    }

    /// Re-evaluates instance compatibility after a mod install.
    ///
    /// Returns an empty list when this pane was created without instance
    /// metadata, so the server mods page keeps its existing behavior. Must run
    /// on the IO scheduler.
    private List<MindustryCompatibility.Issue> evaluateAfterInstall() {
        MindustryVersion version = this.version;
        Path versionRoot = this.versionRoot;
        if (version == null || versionRoot == null) {
            return List.of();
        }
        return MindustryCompatibility.forInstanceOrEmpty(version, versionRoot, dataDir);
    }

    private void showError(Throwable ex) {
        Controllers.dialog(messageOf(ex), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
    }

    /// Extracts a displayable message from a failed operation.
    private static String messageOf(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    /// Aggregated outcome of one import run.
    private static final class InstallSummary {
        private int imported;
        private int replaced;
        private final List<String> failures = new ArrayList<>();
        private List<MindustryCompatibility.Issue> issues = List.of();
    }
}
