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
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.XenonGameRepository;
import determination.xenon.mindustry.save.BackupSummary;
import determination.xenon.mindustry.save.MindustryLaunchSaveService;
import determination.xenon.mindustry.save.MindustrySaveArchive;
import determination.xenon.mindustry.save.SaveBackupNotes;
import determination.xenon.mindustry.save.SaveBackupService;
import determination.xenon.mindustry.save.SaveFileReader;
import determination.xenon.mindustry.trash.MindustryTrashService;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.AdvancedListItem;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.util.DataSizeUnit;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Per-instance Mindustry data archive selector.
public final class MindustrySaveListPane extends BorderPane {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final XenonGameRepository repository;
    private final MindustryVersion version;
    private final Path versionRoot;
    private final Path archivesDir;
    private final Label status = new Label();
    private final Label launchStatus = new Label();
    private final VBox listBox = new VBox(2);

    /// `saves/` folder of the instance data directory (where backups live).
    private final Path savesDir;

    /// Service bound to {@link #savesDir}.
    private final SaveBackupService backupService;

    /// Per-backup user notes.
    private final SaveBackupNotes backupNotes;

    /// Filter box for the backup list.
    private final TextField backupFilter = new TextField();

    /// Status label for the backup list.
    private final Label backupStatus = new Label();

    /// Backup rows host.
    private final VBox backupBox = new VBox(2);

    /// Last scanned backup list before filtering.
    private volatile List<BackupSummary> allBackups = List.of();

    public MindustrySaveListPane(XenonGameRepository repository, MindustryVersion version, Path versionRoot) {
        this.repository = repository;
        this.version = version;
        this.versionRoot = versionRoot;
        this.archivesDir = MindustryLaunchSaveService.archivesDir(versionRoot);
        this.savesDir = version.resolveDataDir(versionRoot).resolve("saves");
        this.backupService = new SaveBackupService(savesDir);
        this.backupNotes = new SaveBackupNotes(backupService.getBackupsDir());
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.mindustry.save.title"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.mindustry.save.hint", archivesDir.toString()));
        hint.setWrapText(true);

        JFXButton refresh = FXUtils.newRaisedButton(i18n("button.refresh"));
        refresh.setOnAction(e -> reload());
        JFXButton importArchive = FXUtils.newRaisedButton(i18n("xenon.mindustry.save.import"));
        importArchive.setOnAction(e -> chooseAndImport());
        JFXButton openFolder = FXUtils.newRaisedButton(i18n("folder.saves"));
        openFolder.setOnAction(e -> FXUtils.openFolder(archivesDir));
        JFXButton clearLaunch = FXUtils.newRaisedButton(i18n("xenon.mindustry.save.launch.clear"));
        clearLaunch.setOnAction(e -> setLaunchSave(null));
        JFXButton cloud = FXUtils.newRaisedButton(i18n("xenon.cloud.title"));
        cloud.setOnAction(e -> MdtbbsCloudSaveDialog.show(version, versionRoot));

        HBox toolbar = new HBox(8, refresh, importArchive, cloud, openFolder, clearLaunch);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, title, hint, toolbar, launchStatus, status);
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);

        ScrollPane scroll = new ScrollPane(listBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        FXUtils.smoothScrolling(scroll);

        VBox all = new VBox(10, scroll, buildBackupSection());
        VBox.setVgrow(scroll, Priority.ALWAYS);
        setCenter(all);

        reload();
        reloadBackups();
    }

    private void reload() {
        status.setText(i18n("xenon.mindustry.save.loading"));
        listBox.getChildren().clear();
        Schedulers.io().execute(() -> {
            try {
                List<MindustrySaveArchive> archives = MindustryLaunchSaveService.listArchives(versionRoot);
                Platform.runLater(() -> populate(archives));
            } catch (IOException ex) {
                LOG.warning("Failed to scan Mindustry save archives", ex);
                Platform.runLater(() -> status.setText(
                        i18n("xenon.mindustry.save.failed") + " " + ex.getMessage()));
            }
        });
    }

    /// Builds the local `saves/backups/` section: toolbar, filter, rows.
    ///
    /// @return section root
    private javafx.scene.Node buildBackupSection() {
        Label title = new Label(i18n("xenon.mindustry.backup.title"));
        title.getStyleClass().add("title");

        JFXButton backupNow = FXUtils.newRaisedButton(i18n("xenon.mindustry.backup.now"));
        backupNow.setOnAction(e -> backupAllSaves());
        JFXButton refresh = FXUtils.newRaisedButton(i18n("button.refresh"));
        refresh.setOnAction(e -> reloadBackups());
        JFXButton openFolder = FXUtils.newRaisedButton(i18n("xenon.mindustry.backup.open"));
        openFolder.setOnAction(e -> FXUtils.openFolder(backupService.getBackupsDir()));

        backupFilter.setPromptText(i18n("xenon.mindustry.backup.filter"));
        backupFilter.textProperty().addListener((obs, old, value) -> rebuildBackupList());
        HBox.setHgrow(backupFilter, Priority.ALWAYS);

        HBox toolbar = new HBox(8, backupNow, refresh, openFolder, backupFilter);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, title, toolbar, backupStatus, backupBox);
    }

    /// Scans `saves/backups/` and repopulates the list.
    private void reloadBackups() {
        backupStatus.setText(i18n("xenon.mindustry.backup.loading"));
        Schedulers.io().execute(() -> {
            try {
                List<BackupSummary> backups = backupService.listBackups();
                Platform.runLater(() -> {
                    allBackups = backups;
                    rebuildBackupList();
                });
            } catch (IOException ex) {
                LOG.warning("Failed to scan save backups", ex);
                Platform.runLater(() -> backupStatus.setText(
                        i18n("xenon.mindustry.backup.failed") + " " + ex.getMessage()));
            }
        });
    }

    /// Rebuilds visible backup rows from the last scan and the filter text.
    private void rebuildBackupList() {
        String query = backupFilter.getText() == null
                ? ""
                : backupFilter.getText().trim().toLowerCase(Locale.ROOT);
        List<BackupSummary> visible = new ArrayList<>();
        for (BackupSummary backup : allBackups) {
            if (matchesBackup(backup, query)) {
                visible.add(backup);
            }
        }
        backupBox.getChildren().clear();
        if (visible.isEmpty()) {
            Label empty = new Label(i18n("xenon.mindustry.backup.empty"));
            empty.setPadding(new Insets(6));
            backupBox.getChildren().add(empty);
        } else {
            for (BackupSummary backup : visible) {
                backupBox.getChildren().add(buildBackupRow(backup));
            }
        }
        backupStatus.setText(i18n("xenon.mindustry.backup.count", visible.size()));
    }

    /// Whether a backup matches the filter by slot name, timestamp or note.
    ///
    /// @param backup backup row
    /// @param query  lowercase query, empty for "everything"
    /// @return `true` when the row should be visible
    private static boolean matchesBackup(BackupSummary backup, String query) {
        if (query.isEmpty()) return true;
        if (backup.slotName().toLowerCase(Locale.ROOT).contains(query)) return true;
        if (backup.fileName().toLowerCase(Locale.ROOT).contains(query)) return true;
        String note = backup.note();
        if (note != null && note.toLowerCase(Locale.ROOT).contains(query)) return true;
        return backup.takenAt() != null
                && STAMP.format(backup.takenAt()).toLowerCase(Locale.ROOT).contains(query);
    }

    /// Builds one backup row with restore / note / delete actions.
    ///
    /// @param backup backup row
    /// @return row node
    private AdvancedListItem buildBackupRow(BackupSummary backup) {
        AdvancedListItem item = new AdvancedListItem();
        item.setLeftIcon(SVG.ARCHIVE);
        item.setTitle(backup.slotName() + "  ·  "
                + (backup.takenAt() == null ? backup.fileName() : STAMP.format(backup.takenAt())));
        String size = backup.size() < 0 ? "?" : DataSizeUnit.format(backup.size());
        String note = backup.note();
        item.setSubtitle(note == null
                ? i18n("xenon.mindustry.backup.subtitle", size, backup.fileName())
                : i18n("xenon.mindustry.backup.subtitle.note", size, backup.fileName(), note));

        HBox actions = new HBox(4);
        JFXButton restore = FXUtils.newRaisedButton(i18n("xenon.mindustry.backup.restore"));
        restore.setOnAction(e -> restoreBackup(backup));
        JFXButton editNote = FXUtils.newRaisedButton(i18n("xenon.mindustry.backup.note"));
        editNote.setOnAction(e -> editNote(backup, note));
        JFXButton delete = FXUtils.newRaisedButton(i18n("button.delete"));
        delete.setOnAction(e -> deleteBackup(backup));
        actions.getChildren().setAll(restore, editNote, delete);
        item.setRightGraphic(actions);
        return item;
    }

    /// Backs up every live save, skipping ones already backed up unchanged.
    private void backupAllSaves() {
        Schedulers.io().execute(() -> {
            int written = 0;
            int skipped = 0;
            try {
                if (!java.nio.file.Files.isDirectory(savesDir)) {
                    Platform.runLater(() -> Controllers.showToast(
                            i18n("xenon.mindustry.backup.none")));
                    return;
                }
                try (var stream = java.nio.file.Files.list(savesDir)) {
                    for (Path save : stream.filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".msav")).toList()) {
                        try {
                            SaveBackupService.BackupResult result =
                                    backupService.backupWithResult(SaveFileReader.readHeaderLenient(save));
                            if (result.skipped()) {
                                skipped++;
                            } else {
                                written++;
                            }
                        } catch (IOException unreadable) {
                            // One corrupt or locked save must not stop the batch.
                            LOG.warning("Skipping unreadable save " + save.getFileName(), unreadable);
                        }
                    }
                }
                int finalWritten = written;
                int finalSkipped = skipped;
                Platform.runLater(() -> {
                    Controllers.showToast(i18n("xenon.mindustry.backup.now.done",
                            finalWritten, finalSkipped));
                    reloadBackups();
                });
            } catch (IOException ex) {
                Platform.runLater(() -> showError(ex));
            }
        });
    }

    /// Restores one backup over its live save, backing up the current file first.
    ///
    /// @param backup backup to restore
    private void restoreBackup(BackupSummary backup) {
        Schedulers.io().execute(() -> {
            try {
                Path target = savesDir.resolve(backup.slotName() + SaveBackupService.MSAV_EXT);
                boolean safetyCopy = false;
                if (java.nio.file.Files.isRegularFile(target)) {
                    safetyCopy = backupBeforeRestore(target);
                }
                backupService.restore(backup.file(), target);
                boolean copied = safetyCopy;
                Platform.runLater(() -> {
                    Controllers.showToast(i18n(copied
                            ? "xenon.mindustry.backup.restored"
                            : "xenon.mindustry.backup.restored.nobackup",
                            backup.fileName()));
                    reloadBackups();
                });
            } catch (IOException ex) {
                Platform.runLater(() -> showError(ex));
            }
        });
    }

    /// Copies the live file of a slot aside before a restore overwrites it.
    ///
    /// A corrupted save is the most likely reason to restore, so an unreadable
    /// header only skips the safety copy instead of blocking the restore.
    ///
    /// @param target live save that is about to be overwritten
    /// @return `true` when the safety copy was written
    private boolean backupBeforeRestore(Path target) {
        try {
            backupService.backup(SaveFileReader.readHeaderLenient(target));
            return true;
        } catch (IOException ex) {
            LOG.warning("Cannot back up the current save before restoring " + target, ex);
            return false;
        }
    }

    /// Edits the note of one backup.
    ///
    /// @param backup  backup row
    /// @param current current note, or `null`
    private void editNote(BackupSummary backup, @Nullable String current) {
        Controllers.prompt(i18n("xenon.mindustry.backup.note.prompt"),
                (input, handler) -> {
                    try {
                        backupNotes.put(backup.fileName(), input);
                        handler.resolve();
                        reloadBackups();
                    } catch (IOException ex) {
                        handler.reject(ex.getMessage());
                    }
                }, current == null ? "" : current);
    }

    /// Deletes one backup file and its note.
    ///
    /// @param backup backup to delete
    private void deleteBackup(BackupSummary backup) {
        Schedulers.io().execute(() -> {
            try {
                new MindustryTrashService(versionRoot).trash(backup.file(), "backup");
                backupNotes.remove(backup.fileName());
                Platform.runLater(this::reloadBackups);
            } catch (IOException ex) {
                Platform.runLater(() -> showError(ex));
            }
        });
    }

    private void populate(List<MindustrySaveArchive> archives) {
        listBox.getChildren().clear();
        updateLaunchStatus();
        status.setText(i18n("xenon.mindustry.save.count", archives.size()));
        if (archives.isEmpty()) {
            Label empty = new Label(i18n("xenon.mindustry.save.empty"));
            empty.setPadding(new Insets(8));
            listBox.getChildren().add(empty);
            return;
        }
        for (MindustrySaveArchive archive : archives) {
            listBox.getChildren().add(buildRow(archive));
        }
    }

    private AdvancedListItem buildRow(MindustrySaveArchive archive) {
        AdvancedListItem item = new AdvancedListItem();
        item.setLeftIcon(SVG.ARCHIVE);
        String fileName = archive.file().getFileName().toString();
        boolean launchSelected = fileName.equals(version.getLaunchSaveFile());
        String title = fileName;
        if (launchSelected) {
            title += "  [" + i18n("xenon.mindustry.save.launch.selected") + "]";
        }
        item.setTitle(title);
        item.setSubtitle(summary(archive));

        HBox actions = new HBox(4);
        JFXButton launch = FXUtils.newRaisedButton(launchSelected
                ? i18n("xenon.mindustry.save.launch.selected")
                : i18n("xenon.mindustry.save.launch.use"));
        launch.setDisable(launchSelected);
        launch.setOnAction(e -> setLaunchSave(fileName));
        JFXButton delete = FXUtils.newRaisedButton(i18n("button.delete"));
        delete.setOnAction(e -> deleteArchive(fileName));
        actions.getChildren().setAll(launch, delete);
        item.setRightGraphic(actions);
        return item;
    }

    private String summary(MindustrySaveArchive archive) {
        return DataSizeUnit.format(archive.size())
                + "  ·  "
                + STAMP.format(archive.lastModified())
                + "  ·  "
                + i18n("xenon.mindustry.save.archive.contents",
                archive.maps(), archive.sectors(), archive.replays(), archive.mods());
    }

    private void chooseAndImport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("xenon.mindustry.save.import"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(i18n("xenon.mindustry.save.archive"), "*.zip"));
        @Nullable File selected = FXUtils.showOpenDialog(chooser, Controllers.getStage());
        if (selected == null) {
            return;
        }

        Schedulers.io().execute(() -> {
            try {
                MindustrySaveArchive imported = MindustryLaunchSaveService.importArchive(versionRoot, selected.toPath());
                Platform.runLater(() -> {
                    Controllers.showToast(i18n("xenon.mindustry.save.import.done",
                            imported.file().getFileName().toString()));
                    reload();
                });
            } catch (IOException ex) {
                LOG.warning("Failed to import Mindustry save archive " + selected, ex);
                Platform.runLater(() -> showError(ex));
            }
        });
    }

    private void deleteArchive(String fileName) {
        Schedulers.io().execute(() -> {
            try {
                if (fileName.equals(version.getLaunchSaveFile())) {
                    version.setLaunchSaveFile(null);
                    repository.save(version);
                }
                Path archive = versionRoot.resolve(MindustryLaunchSaveService.ARCHIVES_DIR)
                        .resolve(fileName);
                if (java.nio.file.Files.exists(archive)) {
                    new MindustryTrashService(versionRoot).trash(archive, "save");
                }
                Platform.runLater(this::reload);
            } catch (IOException ex) {
                Platform.runLater(() -> showError(ex));
            }
        });
    }

    private void updateLaunchStatus() {
        String selected = version.getLaunchSaveFile();
        launchStatus.setText(selected == null || selected.isBlank()
                ? i18n("xenon.mindustry.save.launch.none")
                : i18n("xenon.mindustry.save.launch.current", selected));
    }

    private void setLaunchSave(@Nullable String fileName) {
        Schedulers.io().execute(() -> {
            try {
                version.setLaunchSaveFile(fileName == null || fileName.isBlank() ? null : fileName);
                repository.save(version);
                Platform.runLater(() -> {
                    Controllers.showToast(i18n("message.success"));
                    reload();
                });
            } catch (IOException ex) {
                Platform.runLater(() -> showError(ex));
            }
        });
    }

    private void showError(Throwable ex) {
        Controllers.dialog(ex.getMessage(), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
    }
}
