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
import determination.xenon.mindustry.save.MindustryLaunchSaveService;
import determination.xenon.mindustry.save.MindustrySaveArchive;
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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

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

    public MindustrySaveListPane(XenonGameRepository repository, MindustryVersion version, Path versionRoot) {
        this.repository = repository;
        this.version = version;
        this.versionRoot = versionRoot;
        this.archivesDir = MindustryLaunchSaveService.archivesDir(versionRoot);
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

        HBox toolbar = new HBox(8, refresh, importArchive, openFolder, clearLaunch);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, title, hint, toolbar, launchStatus, status);
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
                MindustryLaunchSaveService.deleteArchive(versionRoot, fileName);
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
