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
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Quota;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Slot;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.SlotPage;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Snapshot;
import determination.xenon.mindustry.save.SaveFileReader;
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
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Cloud-save browser dialog for one Mindustry instance.
///
/// Lists the signed-in user's MDTBBS slots, uploads a local `.msav` as a new
/// immutable snapshot, and restores a chosen snapshot after backing up the
/// local file that would be replaced. All network work runs on the IO
/// scheduler; the dialog body is rebuilt whenever slots change.
public final class MdtbbsCloudSaveDialog {
    /// Timestamp format for backed-up local saves.
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    /// Display format for snapshot timestamps.
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /// Cloud client shared with the community services.
    private final MdtbbsCloudSavesClient client = CommunityServices.cloudSaves();
    /// Local saves directory of the instance.
    private final Path savesDir;
    /// Dialog layout whose body is swapped between slots and snapshots.
    private final JFXDialogLayout layout = new JFXDialogLayout();

    /// Opens the cloud-save dialog for one instance.
    ///
    /// @param dataDir instance data directory; `saves/` is created on demand
    public static void show(Path dataDir) {
        if (!CommunityServices.community().isLoggedIn()) {
            Controllers.showToast(i18n("xenon.cloud.login"));
            return;
        }
        new MdtbbsCloudSaveDialog(dataDir).open();
    }

    private MdtbbsCloudSaveDialog(Path dataDir) {
        this.savesDir = dataDir.resolve("saves");
    }

    /// Builds and shows the dialog.
    private void open() {
        layout.setHeading(new Label(i18n("xenon.cloud.title")));
        layout.setBody(new Label(i18n("xenon.cloud.loading")));
        JFXButton close = new JFXButton(i18n("button.ok"));
        close.getStyleClass().add("dialog-accept");
        close.setOnAction(e -> layout.fireEvent(new DialogCloseEvent()));
        layout.setActions(close);
        Controllers.dialog(layout);
        reloadSlots();
    }

    // ------------------------------------------------------------------
    // Slots
    // ------------------------------------------------------------------

    /// Reloads quota and slots into the dialog body.
    private void reloadSlots() {
        layout.setBody(new Label(i18n("xenon.cloud.loading")));
        Schedulers.io().execute(() -> {
            try {
                Quota quota = client.quota();
                SlotPage page = client.slots(null, 50);
                Platform.runLater(() -> renderSlots(quota, page.slots()));
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    /// Renders the quota line and slot rows.
    private void renderSlots(Quota quota, List<Slot> slots) {
        Label hint = new Label(i18n("xenon.cloud.hint"));
        hint.getStyleClass().add("subtitle-label");
        hint.setWrapText(true);
        Label quotaLabel = new Label(formatQuota(quota));
        quotaLabel.getStyleClass().add("subtitle-label");

        VBox rows = new VBox(6);
        if (slots.isEmpty()) {
            rows.getChildren().add(new Label(i18n("xenon.cloud.empty")));
        }
        for (Slot slot : slots) {
            rows.getChildren().add(slotRow(slot));
        }

        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(280);

        JFXButton create = FXUtils.newRaisedButton(i18n("xenon.cloud.create"));
        create.setOnAction(e -> createSlot());
        HBox actions = new HBox(create);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(8, hint, quotaLabel, scroll, actions);
        body.setPadding(new Insets(4, 0, 0, 0));
        layout.setBody(body);
    }

    /// Builds one slot row with upload, restore and delete actions.
    private Region slotRow(Slot slot) {
        Label name = new Label(slot.name().isBlank() ? slot.id() : slot.name());
        name.getStyleClass().add("title-label");
        name.setWrapText(true);
        Label meta = new Label(i18n("xenon.cloud.slot.meta",
                DATE.format(slot.updatedAt()), formatSize(slot.size()), slot.snapshots()));
        meta.getStyleClass().add("subtitle-label");

        VBox text = new VBox(2, name, meta);
        HBox.setHgrow(text, Priority.ALWAYS);

        JFXButton upload = FXUtils.newRaisedButton(i18n("xenon.cloud.upload"));
        upload.setOnAction(e -> upload(slot));
        JFXButton restore = FXUtils.newRaisedButton(i18n("xenon.cloud.restore"));
        restore.setOnAction(e -> openSnapshots(slot));
        JFXButton delete = FXUtils.newRaisedButton(i18n("xenon.cloud.delete"));
        delete.setOnAction(e -> confirmDelete(slot));

        HBox actions = new HBox(6, upload, restore, delete);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(8, text, actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6));
        row.getStyleClass().add("community-reply");
        return row;
    }

    /// Creates a slot after prompting for a name.
    private void createSlot() {
        Controllers.prompt(i18n("xenon.cloud.create.prompt"), (input, handler) -> {
            String name = input == null ? "" : input.trim();
            if (name.isBlank()) {
                handler.reject(i18n("xenon.cloud.create.required"));
                return;
            }
            Schedulers.io().execute(() -> {
                try {
                    client.createSlot(name);
                    Platform.runLater(() -> {
                        handler.resolve();
                        reloadSlots();
                    });
                } catch (IOException e) {
                    Platform.runLater(() -> handler.reject(messageOf(e)));
                }
            });
        });
    }

    /// Deletes a slot after confirmation.
    private void confirmDelete(Slot slot) {
        Controllers.confirm(i18n("xenon.cloud.delete.confirm", slot.name()),
                i18n("xenon.cloud.title"), () -> Schedulers.io().execute(() -> {
                    try {
                        client.deleteSlot(slot.id());
                        Platform.runLater(() -> {
                            Controllers.showToast(i18n("xenon.cloud.delete.done"));
                            reloadSlots();
                        });
                    } catch (IOException e) {
                        fail(e);
                    }
                }), null);
    }

    // ------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------

    /// Uploads a local save into a slot.
    private void upload(Slot slot) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("xenon.cloud.upload"));
        chooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("Mindustry save (*.msav)", "*.msav"));
        if (Files.isDirectory(savesDir)) chooser.setInitialDirectory(savesDir.toFile());
        java.io.File file = FXUtils.showOpenDialog(chooser, Controllers.getStage());
        if (file == null) return;
        Path source = file.toPath();

        Schedulers.io().execute(() -> {
            try {
                String head = null;
                List<Snapshot> snapshots = client.snapshots(slot.id());
                if (!snapshots.isEmpty()) head = snapshots.get(0).id();
                client.upload(slot.id(), source, head);
                Platform.runLater(() -> {
                    Controllers.showToast(i18n("xenon.cloud.upload.done", source.getFileName().toString()));
                    reloadSlots();
                });
            } catch (IOException e) {
                Platform.runLater(() -> Controllers.dialog(
                        i18n("xenon.cloud.upload.failed", messageOf(e)),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    // ------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------

    /// Shows the snapshot list of a slot.
    private void openSnapshots(Slot slot) {
        layout.setBody(new Label(i18n("xenon.cloud.loading")));
        Schedulers.io().execute(() -> {
            try {
                List<Snapshot> snapshots = client.snapshots(slot.id());
                Platform.runLater(() -> renderSnapshots(slot, snapshots));
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    /// Renders the snapshot list with a back action.
    private void renderSnapshots(Slot slot, List<Snapshot> snapshots) {
        Label title = new Label(i18n("xenon.cloud.snapshots"));
        title.getStyleClass().add("title-label");

        VBox rows = new VBox(6);
        if (snapshots.isEmpty()) {
            rows.getChildren().add(new Label(i18n("xenon.cloud.snapshots.empty")));
        }
        for (Snapshot snapshot : snapshots) {
            rows.getChildren().add(snapshotRow(slot, snapshot));
        }

        ScrollPane scroll = new ScrollPane(rows);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(280);

        JFXButton back = FXUtils.newRaisedButton(i18n("xenon.cloud.back"));
        back.setOnAction(e -> reloadSlots());
        HBox actions = new HBox(back);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(8, title, scroll, actions);
        body.setPadding(new Insets(4, 0, 0, 0));
        layout.setBody(body);
    }

    /// Builds one snapshot row with download and pin actions.
    private Region snapshotRow(Slot slot, Snapshot snapshot) {
        Label name = new Label(snapshot.fileName().isBlank() ? snapshot.id() : snapshot.fileName());
        name.getStyleClass().add("title-label");
        Label meta = new Label(i18n("xenon.cloud.snapshot.meta",
                DATE.format(snapshot.createdAt()), formatSize(snapshot.size()),
                snapshot.pinned() ? i18n("xenon.cloud.pinned") : ""));
        meta.getStyleClass().add("subtitle-label");

        VBox text = new VBox(2, name, meta);
        HBox.setHgrow(text, Priority.ALWAYS);

        JFXButton download = FXUtils.newRaisedButton(i18n("xenon.cloud.download"));
        download.setOnAction(e -> restore(slot, snapshot));
        JFXButton pin = FXUtils.newRaisedButton(snapshot.pinned()
                ? i18n("xenon.cloud.unpin") : i18n("xenon.cloud.pin"));
        pin.setOnAction(e -> setPinned(slot, snapshot, !snapshot.pinned()));

        HBox actions = new HBox(6, download, pin);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(8, text, actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6));
        row.getStyleClass().add("community-reply");
        return row;
    }

    /// Downloads a snapshot and replaces the matching local save after a backup.
    private void restore(Slot slot, Snapshot snapshot) {
        Schedulers.io().execute(() -> {
            try {
                Files.createDirectories(savesDir);
                Path target = savesDir.resolve(snapshot.fileName().isBlank()
                        ? "cloud-" + snapshot.id() + ".msav" : snapshot.fileName());
                Path temp = Files.createTempFile(savesDir, "xenon-cloud-", ".msav");
                try {
                    client.download(slot.id(), snapshot.id(), temp, null);
                    SaveFileReader.readHeaderLenient(temp);
                    if (Files.exists(target)) {
                        Path backups = savesDir.resolve("backups");
                        Files.createDirectories(backups);
                        String base = stripExtension(target.getFileName().toString());
                        Files.copy(target, backups.resolve(base + "-" + STAMP.format(java.time.Instant.now())
                                + ".msav"), StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temp);
                }
                Platform.runLater(() -> {
                    Controllers.showToast(i18n("xenon.cloud.restore.done", target.getFileName().toString()));
                    reloadSlots();
                });
            } catch (IOException e) {
                Platform.runLater(() -> Controllers.dialog(
                        i18n("xenon.cloud.restore.failed", messageOf(e)),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    /// Toggles the pinned flag of a snapshot.
    private void setPinned(Slot slot, Snapshot snapshot, boolean pinned) {
        Schedulers.io().execute(() -> {
            try {
                client.setPinned(slot.id(), snapshot.id(), pinned);
                Platform.runLater(() -> openSnapshots(slot));
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /// Replaces the dialog body with a loading label after a failure.
    private void fail(IOException error) {
        LOG.warning("MDTBBS cloud save operation failed", error);
        Platform.runLater(() -> layout.setBody(new Label(messageOf(error))));
    }

    /// Formats the quota line.
    private static String formatQuota(Quota quota) {
        if (quota.quotaBytes() <= 0) {
            return i18n("xenon.cloud.quota.unlimited", formatSize(quota.usedBytes()));
        }
        return i18n("xenon.cloud.quota", formatSize(quota.usedBytes()),
                formatSize(quota.quotaBytes()));
    }

    /// Formats a byte count in binary units.
    private static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /// Strips the file extension from a name.
    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /// Returns a readable error message.
    private static String messageOf(IOException error) {
        return MdtbbsMessages.describe(error);
    }
}
