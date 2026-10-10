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
import determination.xenon.mindustry.MindustryClientRuntimeRegistry;
import determination.xenon.mindustry.uuid.MindustrySettingsBackups;
import determination.xenon.mindustry.uuid.MindustrySettingsBin;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.ui.construct.PageAware;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Table editor for one instance's `settings.bin`.
///
/// The page lists every entry with its original Arc type and writes only the
/// keys the user actually changed. Safety rules:
///
/// - every save snapshots the current file into `settings-backups/` first;
/// - a file that fails to parse leaves the page read-only instead of letting a
///   save overwrite unknown data;
/// - saving and restoring are disabled while the instance is running, because
///   the game rewrites the file on exit.
public final class MindustrySettingsEditorPane extends BorderPane implements PageAware {

    /// Keys whose values the launcher writes during launch; editable but marked.
    private static final Set<String> LAUNCHER_MANAGED_KEYS = Set.of("name", "uuid", "name-forced");

    /// Mod enable flags are written by the mod list; editable but marked.
    private static final Pattern LAUNCHER_MANAGED_MOD_KEY = Pattern.compile("mod-.+-enabled");

    /// Maximum number of bytes shown in the hex tooltip of a `byte[]` entry.
    private static final int BYTES_PREVIEW_LIMIT = 256;

    /// How often the page re-checks whether the instance is running.
    private static final Duration RUNTIME_POLL_INTERVAL = Duration.millis(1500);

    /// Timestamp format used when confirming a restore.
    private static final DateTimeFormatter BACKUP_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /// Target data directory, i.e. the directory that owns `settings.bin`.
    private final Path dataDir;

    /// Version id used to detect a running instance; `null` when unavailable.
    private final @Nullable String versionId;

    /// All entries parsed from disk, in key order.
    private final ObservableList<SettingEntry> rows = FXCollections.observableArrayList();

    /// Entries currently visible after the search filter.
    private final ObservableList<SettingEntry> visible = FXCollections.observableArrayList();

    private final TableView<SettingEntry> table = new TableView<>(visible);
    private final JFXTextField search = new JFXTextField();
    private final Label status = new Label();
    private final Label runningHint = new Label(i18n("xenon.mindustry.settings.running"));
    private final BooleanProperty dirty = new SimpleBooleanProperty(false);
    private final BooleanProperty running = new SimpleBooleanProperty(false);
    private final BooleanProperty parseFailed = new SimpleBooleanProperty(false);
    private final BooleanProperty saving = new SimpleBooleanProperty(false);
    private final Timeline runtimePoller =
            new Timeline(new KeyFrame(RUNTIME_POLL_INTERVAL, event -> refreshRunning()));

    /// Creates the editor for one data directory.
    ///
    /// @param dataDir       directory that owns `settings.bin`
    /// @param versionId     instance id checked against the runtime registry; may be `null`
    /// @param archiveRuntime whether the directory is regenerated from a save archive
    public MindustrySettingsEditorPane(Path dataDir, @Nullable String versionId, boolean archiveRuntime) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        this.versionId = versionId;
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.mindustry.settings.title"));
        title.getStyleClass().add("title");
        Label target = new Label(i18n("xenon.mindustry.settings.target", dataDir.toString()));
        target.setWrapText(true);
        VBox headerText = new VBox(2, title, target);
        if (archiveRuntime) {
            Label note = new Label(i18n("xenon.mindustry.settings.archive.runtime"));
            note.setWrapText(true);
            headerText.getChildren().add(note);
        }
        Label hint = new Label(i18n("xenon.mindustry.settings.hint"));
        hint.setWrapText(true);

        search.setPromptText(i18n("xenon.mindustry.settings.search"));
        search.textProperty().addListener((observable, oldValue, newValue) -> applyFilter());
        HBox.setHgrow(search, Priority.ALWAYS);
        status.setWrapText(true);

        JFXButton save = FXUtils.newRaisedButton(i18n("xenon.mindustry.settings.save"));
        save.setOnAction(event -> save());
        save.disableProperty().bind(dirty.not().or(running).or(parseFailed).or(saving));
        JFXButton discard = FXUtils.newRaisedButton(i18n("xenon.mindustry.settings.discard"));
        discard.setOnAction(event -> load());
        discard.disableProperty().bind(dirty.not().or(parseFailed).or(saving));
        JFXButton restore = FXUtils.newRaisedButton(i18n("xenon.mindustry.settings.restore"));
        restore.setOnAction(event -> confirmRestore());
        restore.disableProperty().bind(running.or(saving));
        JFXButton openBackups = FXUtils.newRaisedButton(i18n("xenon.mindustry.settings.backup.open"));
        openBackups.setOnAction(event -> openBackupDirectory());
        JFXButton openData = FXUtils.newRaisedButton(i18n("xenon.mindustry.settings.data.open"));
        openData.setOnAction(event -> FXUtils.openFolder(dataDir));
        HBox toolbar = new HBox(8, save, discard, restore, openBackups, openData);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        runningHint.setWrapText(true);
        runningHint.visibleProperty().bind(running);
        runningHint.managedProperty().bind(running);
        runningHint.getStyleClass().add("settings-running-hint");

        buildColumns();
        table.setPlaceholder(new Label(i18n("xenon.mindustry.settings.empty")));
        table.setEditable(false);
        table.disableProperty().bind(parseFailed.or(saving));
        VBox.setVgrow(table, Priority.ALWAYS);

        VBox header = new VBox(6, headerText, hint, search, toolbar, runningHint, status);
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);
        setCenter(table);

        runtimePoller.setCycleCount(Animation.INDEFINITE);
        refreshRunning();
        load();
    }

    /// Reloads the file whenever the page becomes visible again.
    ///
    /// With unsaved edits the user is asked first: reloading throws the edits
    /// away, cancel keeps them for a later save.
    @Override
    public void onPageShown() {
        runtimePoller.play();
        refreshRunning();
        if (!dirty.get()) {
            load();
            return;
        }
        JFXButton reload = new JFXButton(i18n("xenon.mindustry.settings.reload"));
        reload.getStyleClass().add("dialog-accept");
        reload.setOnAction(event -> load());
        Controllers.dialog(new MessageDialogPane.Builder(
                i18n("xenon.mindustry.settings.dirty.reload"),
                i18n("xenon.mindustry.settings.title"),
                MessageDialogPane.MessageType.QUESTION)
                .actionOrCancel(reload, null)
                .build());
    }

    /// Offers to save pending edits when the page is left.
    @Override
    public void onPageHidden() {
        runtimePoller.stop();
        if (!dirty.get()) {
            return;
        }
        JFXButton save = new JFXButton(i18n("xenon.mindustry.settings.save"));
        save.getStyleClass().add("dialog-accept");
        save.setOnAction(event -> save());
        JFXButton discard = new JFXButton(i18n("xenon.mindustry.settings.discard"));
        discard.setOnAction(event -> load());
        Controllers.dialog(new MessageDialogPane.Builder(
                i18n("xenon.mindustry.settings.dirty.hidden"),
                i18n("xenon.mindustry.settings.title"),
                MessageDialogPane.MessageType.QUESTION)
                .addAction(save)
                .addAction(discard)
                .addCancel(null)
                .build());
    }

    /// Builds the key / type / value columns.
    private void buildColumns() {
        TableColumn<SettingEntry, SettingEntry> keyColumn =
                new TableColumn<>(i18n("xenon.mindustry.settings.column.key"));
        keyColumn.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue()));
        keyColumn.setCellFactory(column -> new TableCell<>() {
            private final Label keyLabel = new Label();
            private final Label managedLabel = new Label(i18n("xenon.mindustry.settings.managed"));
            private final HBox box = new HBox(6, keyLabel, managedLabel);

            {
                box.setAlignment(Pos.CENTER_LEFT);
                managedLabel.getStyleClass().add("settings-managed-tag");
            }

            @Override
            protected void updateItem(SettingEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setTooltip(null);
                    return;
                }
                keyLabel.setText(item.key);
                boolean managed = isLauncherManaged(item.key);
                managedLabel.setVisible(managed);
                managedLabel.setManaged(managed);
                setTooltip(managed
                        ? new Tooltip(i18n("xenon.mindustry.settings.managed.hint"))
                        : null);
                setGraphic(box);
            }
        });
        keyColumn.setComparator((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.key, b.key));
        keyColumn.setPrefWidth(300);

        TableColumn<SettingEntry, String> typeColumn =
                new TableColumn<>(i18n("xenon.mindustry.settings.column.type"));
        typeColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().kind.displayName()));
        typeColumn.setSortable(false);
        typeColumn.setPrefWidth(110);

        TableColumn<SettingEntry, SettingEntry> valueColumn =
                new TableColumn<>(i18n("xenon.mindustry.settings.column.value"));
        valueColumn.setCellValueFactory(data -> new SimpleObjectProperty<>(data.getValue()));
        valueColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(SettingEntry item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || item == null ? null : buildEditor(item));
            }
        });
        valueColumn.setSortable(false);
        valueColumn.setPrefWidth(360);

        table.getColumns().setAll(List.of(keyColumn, typeColumn, valueColumn));
    }

    /// Builds the type-specific editor used inside the value column.
    private javafx.scene.Node buildEditor(SettingEntry entry) {
        switch (entry.kind) {
            case BOOLEAN: {
                CheckBox checkBox = new CheckBox();
                checkBox.setSelected(Boolean.TRUE.equals(entry.current()));
                checkBox.selectedProperty().addListener((observable, oldValue, newValue) -> {
                    entry.setCurrent(newValue);
                    refreshDirty();
                });
                return checkBox;
            }
            case INT:
                return integerEditor(entry, false);
            case LONG:
                return integerEditor(entry, true);
            case FLOAT:
                return floatEditor(entry);
            case STRING: {
                TextField field = new TextField((String) entry.current());
                field.textProperty().addListener((observable, oldValue, newValue) -> {
                    entry.setCurrent(newValue);
                    refreshDirty();
                });
                return field;
            }
            case BYTES:
            default: {
                byte[] bytes = (byte[]) entry.current();
                Label label = new Label(i18n("xenon.mindustry.settings.bytes", bytes.length));
                label.setTooltip(new Tooltip(hexPreview(bytes)));
                return label;
            }
        }
    }

    /// Integer editor with live validation; invalid text resets on focus loss.
    private TextField integerEditor(SettingEntry entry, boolean longType) {
        TextField field = new TextField(textOf(entry));
        field.setTextFormatter(new TextFormatter<String>(change -> {
            String text = change.getControlNewText();
            if (text.isEmpty() || text.equals("-")) return change;
            return text.matches("-?\\d+") ? change : null;
        }));
        field.textProperty().addListener((observable, oldValue, newValue) -> {
            String text = newValue == null ? "" : newValue.trim();
            if (text.isEmpty() || text.equals("-")) return;
            try {
                long parsed = Long.parseLong(text);
                if (!longType && (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE)) {
                    return;
                }
                entry.setCurrent(longType ? (Object) parsed : (Object) (int) parsed);
                refreshDirty();
            } catch (NumberFormatException ignored) {
                // Incomplete number; keep the previous valid value.
            }
        });
        field.focusedProperty().addListener((observable, wasFocused, focused) -> {
            if (!focused) field.setText(textOf(entry));
        });
        return field;
    }

    /// Float editor with live validation; invalid text resets on focus loss.
    private TextField floatEditor(SettingEntry entry) {
        TextField field = new TextField(textOf(entry));
        field.setTextFormatter(new TextFormatter<String>(change -> {
            String text = change.getControlNewText();
            if (text.isEmpty() || text.equals("-")) return change;
            return text.matches("-?\\d*(\\.\\d*)?([eE][+-]?\\d*)?") && !text.equals(".")
                    ? change
                    : null;
        }));
        field.textProperty().addListener((observable, oldValue, newValue) -> {
            String text = newValue == null ? "" : newValue.trim();
            if (text.isEmpty() || text.equals("-")) return;
            try {
                float parsed = Float.parseFloat(text);
                if (Float.isFinite(parsed)) {
                    entry.setCurrent(parsed);
                    refreshDirty();
                }
            } catch (NumberFormatException ignored) {
                // Incomplete number; keep the previous valid value.
            }
        });
        field.focusedProperty().addListener((observable, wasFocused, focused) -> {
            if (!focused) field.setText(textOf(entry));
        });
        return field;
    }

    /// Reads the file and repopulates the table.
    private void load() {
        status.setText(i18n("message.doing"));
        Schedulers.io().execute(() -> {
            try {
                MindustrySettingsBin.ReadResult result = MindustrySettingsBin.read(dataDir);
                Platform.runLater(() -> populate(result));
            } catch (IOException ex) {
                LOG.warning("Failed to read Mindustry settings.bin from " + dataDir, ex);
                Platform.runLater(() -> showParseFailure(ex));
            }
        });
    }

    /// Applies a successful read to the table.
    private void populate(MindustrySettingsBin.ReadResult result) {
        rows.setAll(result.values().entrySet().stream()
                .map(SettingEntry::new)
                .sorted((a, b) -> a.key.compareToIgnoreCase(b.key))
                .toList());
        parseFailed.set(false);
        dirty.set(false);
        applyFilter();
        status.setText(result.status() == MindustrySettingsBin.ReadStatus.MISSING
                ? i18n("xenon.mindustry.settings.missing")
                : i18n("xenon.mindustry.settings.count", rows.size()));
    }

    /// Switches the page into read-only mode after a parse failure.
    private void showParseFailure(IOException ex) {
        rows.clear();
        visible.clear();
        parseFailed.set(true);
        status.setText(i18n("xenon.mindustry.settings.parse.failed", ex.getMessage()));
    }

    /// Filters the table by key or stringified value.
    private void applyFilter() {
        String query = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            visible.setAll(rows);
        } else {
            visible.setAll(rows.filtered(entry -> entry.matches(query)));
        }
        if (!parseFailed.get()) {
            status.setText(i18n("xenon.mindustry.settings.count", rows.size()));
        }
    }

    /// Recomputes whether any entry differs from the on-disk value.
    private void refreshDirty() {
        dirty.set(rows.stream().anyMatch(SettingEntry::isDirty));
    }

    /// Applies one in-memory edit and saves it, so tests can drive the
    /// backup-then-write flow without rendering table cells.
    ///
    /// Package-private test seam; the production UI edits entries through the
    /// value column instead.
    ///
    /// @param key entry to change
    /// @param value new value in the entry's original Arc type
    void applyEditAndSaveForTests(String key, Object value) {
        for (SettingEntry entry : rows) {
            if (entry.key.equals(key)) {
                entry.setCurrent(value);
            }
        }
        refreshDirty();
        save();
    }

    /// Saves the changed keys after taking a snapshot.
    private void save() {
        if (parseFailed.get() || !dirty.get() || saving.get()) {
            return;
        }
        if (isInstanceRunning()) {
            refreshRunning();
            showWarning(i18n("xenon.mindustry.settings.running"));
            return;
        }
        LinkedHashMap<String, Object> changed = new LinkedHashMap<>();
        for (SettingEntry entry : rows) {
            if (entry.isDirty()) {
                changed.put(entry.key, entry.current());
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        saving.set(true);
        status.setText(i18n("message.doing"));
        Schedulers.io().execute(() -> {
            try {
                MindustrySettingsBackups.backup(dataDir);
                MindustrySettingsBin.putValuesOrThrow(dataDir, changed);
                Platform.runLater(() -> {
                    rows.forEach(SettingEntry::commit);
                    saving.set(false);
                    refreshDirty();
                    status.setText(i18n("xenon.mindustry.settings.saved", changed.size()));
                });
            } catch (IOException | RuntimeException ex) {
                LOG.warning("Failed to save Mindustry settings.bin in " + dataDir, ex);
                Platform.runLater(() -> {
                    saving.set(false);
                    status.setText(i18n("xenon.mindustry.settings.save.failed", messageOf(ex)));
                    showError(ex);
                });
            }
        });
    }

    /// Asks for confirmation and restores the newest snapshot.
    private void confirmRestore() {
        if (isInstanceRunning()) {
            refreshRunning();
            showWarning(i18n("xenon.mindustry.settings.running"));
            return;
        }
        Schedulers.io().execute(() -> {
            List<Path> backups = MindustrySettingsBackups.list(dataDir);
            if (backups.isEmpty()) {
                Platform.runLater(() -> Controllers.dialog(
                        i18n("xenon.mindustry.settings.restore.none"),
                        i18n("xenon.mindustry.settings.restore"),
                        MessageDialogPane.MessageType.INFO));
                return;
            }
            String when = backupTime(backups.get(0));
            Platform.runLater(() -> {
                JFXButton confirm = new JFXButton(i18n("xenon.mindustry.settings.restore"));
                confirm.getStyleClass().add("dialog-accept");
                confirm.setOnAction(event -> restoreLatest());
                Controllers.dialog(new MessageDialogPane.Builder(
                        i18n("xenon.mindustry.settings.restore.confirm", when),
                        i18n("xenon.mindustry.settings.restore"),
                        MessageDialogPane.MessageType.WARNING)
                        .actionOrCancel(confirm, null)
                        .build());
            });
        });
    }

    /// Restores the newest snapshot and reloads the table.
    private void restoreLatest() {
        saving.set(true);
        status.setText(i18n("message.doing"));
        Schedulers.io().execute(() -> {
            try {
                Path restored = MindustrySettingsBackups.restoreLatest(dataDir);
                Platform.runLater(() -> {
                    saving.set(false);
                    load();
                    status.setText(i18n("xenon.mindustry.settings.restored", backupTime(restored)));
                });
            } catch (IOException | RuntimeException ex) {
                LOG.warning("Failed to restore Mindustry settings.bin in " + dataDir, ex);
                Platform.runLater(() -> {
                    saving.set(false);
                    status.setText(i18n("xenon.mindustry.settings.restore.failed", messageOf(ex)));
                    showError(ex);
                });
            }
        });
    }

    /// Creates and opens the snapshot directory.
    private void openBackupDirectory() {
        Path dir = MindustrySettingsBackups.backupDir(dataDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            LOG.warning("Failed to create the settings backup directory " + dir, ex);
        }
        FXUtils.openFolder(dir);
    }

    /// Re-checks whether the instance owning the file is running.
    private void refreshRunning() {
        running.set(isInstanceRunning());
    }

    /// Whether a live client process owns [dataDir].
    private boolean isInstanceRunning() {
        if (versionId == null) {
            return false;
        }
        return MindustryClientRuntimeRegistry.shared().snapshot().containsKey(versionId);
    }

    /// Formats the modified time of one snapshot.
    private static String backupTime(Path backup) {
        try {
            LocalDateTime time = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(backup).toInstant(), ZoneId.systemDefault());
            return BACKUP_TIME_FORMAT.format(time);
        } catch (IOException ex) {
            return backup.getFileName().toString();
        }
    }

    /// Whether `key` is written by the launcher or the mod list.
    private static boolean isLauncherManaged(String key) {
        return LAUNCHER_MANAGED_KEYS.contains(key) || LAUNCHER_MANAGED_MOD_KEY.matcher(key).matches();
    }

    /// Current editor text for one entry.
    private static String textOf(SettingEntry entry) {
        return entry.current() == null ? "" : String.valueOf(entry.current());
    }

    /// Hex preview for a `byte[]` entry tooltip.
    private static String hexPreview(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 3);
        int limit = Math.min(bytes.length, BYTES_PREVIEW_LIMIT);
        for (int i = 0; i < limit; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format("%02x", bytes[i]));
        }
        if (bytes.length > limit) {
            out.append(" ...");
        }
        return out.toString();
    }

    /// Extracts a displayable message from a failed operation.
    private static String messageOf(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private void showWarning(String message) {
        Controllers.dialog(message, i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
    }

    private void showError(Throwable ex) {
        Controllers.dialog(messageOf(ex), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
    }

    /// Kind of one Arc settings value, mirroring `Settings.saveValues`.
    private enum Kind {
        BOOLEAN,
        INT,
        LONG,
        FLOAT,
        STRING,
        BYTES;

        /// Classifies a parsed value.
        static Kind of(Object value) {
            if (value instanceof Boolean) return BOOLEAN;
            if (value instanceof Integer) return INT;
            if (value instanceof Long) return LONG;
            if (value instanceof Float) return FLOAT;
            if (value instanceof String) return STRING;
            return BYTES;
        }

        /// Localized type name shown in the table.
        String displayName() {
            return i18n("xenon.mindustry.settings.type." + name().toLowerCase(Locale.ROOT));
        }
    }

    /// One editable settings entry.
    private static final class SettingEntry {
        private final String key;
        private final Kind kind;
        private Object original;
        private Object current;

        /// Wraps a parsed entry.
        SettingEntry(Map.Entry<String, Object> entry) {
            this.key = entry.getKey();
            this.kind = Kind.of(entry.getValue());
            this.original = entry.getValue();
            this.current = entry.getValue();
        }

        Object current() {
            return current;
        }

        void setCurrent(Object value) {
            this.current = value;
        }

        /// Whether the current value differs from the last saved one.
        boolean isDirty() {
            return !deepEquals(original, current);
        }

        /// Records the current value as the new saved baseline.
        void commit() {
            original = current;
        }

        /// Matches a lower-case search query against the key or the stringified value.
        boolean matches(String lowerCaseQuery) {
            if (key.toLowerCase(Locale.ROOT).contains(lowerCaseQuery)) {
                return true;
            }
            String text = current instanceof byte[] bytes
                    ? bytes.length + " " + hexPreview(bytes)
                    : String.valueOf(current);
            return text.toLowerCase(Locale.ROOT).contains(lowerCaseQuery);
        }

        /// Array-aware equality, because `byte[]` does not compare by content.
        private static boolean deepEquals(@Nullable Object first, @Nullable Object second) {
            if (first instanceof byte[] firstBytes && second instanceof byte[] secondBytes) {
                return Arrays.equals(firstBytes, secondBytes);
            }
            return Objects.equals(first, second);
        }
    }
}
