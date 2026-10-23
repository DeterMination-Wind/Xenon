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
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXDialogLayout;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.community.MdtbbsApiException;
import determination.xenon.mindustry.community.MdtbbsApiException;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Quota;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.UploadOptions;
import determination.xenon.mindustry.community.MindustryCloudSaveMetadata;
import determination.xenon.setting.ConfigHolder;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Slot;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.SlotPage;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Snapshot;
import determination.xenon.mindustry.community.MindustryCloudSaveSync;
import determination.xenon.mindustry.community.MindustryCloudSaveSync.CloudHead;
import determination.xenon.mindustry.community.MindustryCloudSaveSync.SaveComparison;
import determination.xenon.mindustry.community.MindustryCloudSaveSync.SaveIdentity;
import determination.xenon.mindustry.save.SaveFileReader;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.TrayIconManager;
import determination.xenon.ui.construct.DialogCloseEvent;
import determination.xenon.ui.construct.MessageDialogPane;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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
///
/// It also hosts the instance-level switch for the automatic read-only check
/// and the simplified conflict prompt: the automatic check never uploads or
/// downloads, and the prompt only offers explicit choices.
@NotNullByDefault
public final class MdtbbsCloudSaveDialog {
    /// Timestamp format for backed-up local saves.
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    /// Display format for snapshot timestamps.
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /// Cloud client shared with the community services.
    private final MdtbbsCloudSavesClient client = CommunityServices.cloudSaves();
    /// Instance whose local saves this dialog compares and uploads.
    private final MindustryVersion version;
    /// Local saves directory of the instance.
    private final Path savesDir;
    /// Instance data directory holding `mods/` and `settings.bin`.
    private final Path dataDir;
    /// Dialog layout whose body is swapped between slots and snapshots.
    private final JFXDialogLayout layout = new JFXDialogLayout();
    /// Progress bar shown while a save uploads, or `null` outside an upload.
    private @Nullable ProgressBar uploadProgressBar;
    /// Percentage label paired with [#uploadProgressBar].
    private @Nullable Label uploadProgressLabel;
    /// Last percentage pushed to the UI, used to drop redundant updates.
    private int lastUploadPercent = -1;

    /// Opens the cloud-save dialog for one instance.
    ///
    /// @param version     installed instance that owns the saves
    /// @param versionRoot instance directory that resolves its data directory
    public static void show(MindustryVersion version, Path versionRoot) {
        if (!CommunityServices.community().isLoggedIn()) {
            Controllers.showToast(i18n("xenon.cloud.login"));
            return;
        }
        new MdtbbsCloudSaveDialog(version, version.resolveDataDir(versionRoot)).open();
    }

    private MdtbbsCloudSaveDialog(MindustryVersion version, Path dataDir) {
        this.version = version;
        this.dataDir = dataDir;
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

        JFXCheckBox autoCheck = new JFXCheckBox(i18n("xenon.cloud.check.auto"));
        autoCheck.setSelected(version.isCloudSaveCheckEnabled());
        autoCheck.selectedProperty().addListener((observable, was, enabled) -> {
            version.setCloudSaveCheckEnabled(enabled);
            persistInstanceSwitch();
        });
        JFXButton checkNow = FXUtils.newRaisedButton(i18n("xenon.cloud.check.now"));
        checkNow.setOnAction(e -> checkNow());
        HBox checks = new HBox(12, autoCheck, checkNow);
        checks.setAlignment(Pos.CENTER_LEFT);

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

        VBox body = new VBox(8, hint, quotaLabel, checks, scroll, actions);
        body.setPadding(new Insets(4, 0, 0, 0));
        layout.setBody(body);
    }

    /// Persists the instance-level switch so it survives a restart.
    private void persistInstanceSwitch() {
        Schedulers.io().execute(() -> {
            try {
                MindustryImportFlow.repositoryForVersion(version).save(version);
            } catch (IOException e) {
                LOG.warning("Failed to persist the cloud-save check switch for "
                        + version.getId(), e);
            }
        });
    }

    /// Builds one slot row with upload, restore and delete actions.
    private Region slotRow(Slot slot) {
        Label name = new Label(slot.name().isBlank() ? slot.id() : slot.name());
        name.getStyleClass().add("title-label");
        name.setWrapText(true);
        Label meta = new Label(i18n("xenon.cloud.slot.meta",
                DATE.format(slot.updatedAt()), formatSize(slot.size()), slot.revision()));
        meta.getStyleClass().add("subtitle-label");

        VBox text = new VBox(2, name, meta);
        HBox.setHgrow(text, Priority.ALWAYS);

        JFXButton upload = FXUtils.newRaisedButton(i18n("xenon.cloud.upload"));
        upload.setOnAction(e -> upload(slot));
        JFXButton restore = FXUtils.newRaisedButton(i18n("xenon.cloud.restore"));
        restore.setOnAction(e -> openSnapshots(slot));
        JFXButton rename = FXUtils.newRaisedButton(i18n("xenon.cloud.rename"));
        rename.setOnAction(e -> renameSlot(slot));
        JFXButton delete = FXUtils.newRaisedButton(i18n("xenon.cloud.delete"));
        delete.setOnAction(e -> confirmDelete(slot));

        HBox actions = new HBox(6, upload, restore, rename, delete);
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

    /// Renames a slot after prompting for a new name.
    private void renameSlot(Slot slot) {
        Controllers.prompt(i18n("xenon.cloud.rename.prompt"), (input, handler) -> {
            String name = input == null ? "" : input.trim();
            if (name.isBlank()) {
                handler.reject(i18n("xenon.cloud.create.required"));
                return;
            }
            Schedulers.io().execute(() -> {
                try {
                    client.renameSlot(slot.id(), name);
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
        uploadFile(slot, file.toPath());
    }

    /// Uploads one file into a slot after making room in the storage quota.
    ///
    /// The quota check runs first: when the account is full the oldest
    /// unpinned snapshots are dropped (logged before each request) and the
    /// upload proceeds only when enough room was actually reclaimed.
    ///
    /// While the PUT runs, the dialog body and the tray menu show the same
    /// percentage; both are cleared when the upload settles.
    ///
    /// @param slot   slot to upload into
    /// @param source local `.msav` file
    private void uploadFile(Slot slot, Path source) {
        String reason = "manual";
        @Nullable String head = null;
        try {
            List<Snapshot> snapshots = client.snapshots(slot.id());
            if (!snapshots.isEmpty()) head = snapshots.get(0).id();
        } catch (IOException e) {
            // The upload itself reports the real failure.
        }
        uploadFile(slot, source, optionsFor(source, head, reason, "normal", null));
    }

    /// Builds upload options with collected metadata.
    private UploadOptions optionsFor(Path source, @Nullable String head, String reason,
                                     String resolution, @Nullable String confirmHead) {
        var metadata = MindustryCloudSaveMetadata.collect(source, version, dataDir,
                ConfigHolder.globalConfig().getOrCreateCloudDeviceId());
        return new UploadOptions(reason, head, resolution, confirmHead, metadata);
    }

    /// Uploads one file with explicit conflict options.
    private void uploadFile(Slot slot, Path source, UploadOptions options) {
        showUploadProgress(source);
        Schedulers.io().execute(() -> {
            try {
                long size = Files.size(source);
                MindustryCloudSaveSync.CleanupPlan plan =
                        MindustryCloudSaveSync.enforceQuota(client, slot.id(), size);
                if (!plan.sufficient()) {
                    Platform.runLater(() -> {
                        Controllers.dialog(
                                i18n("xenon.cloud.quota.full", formatSize(size)),
                                i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                        reloadSlots();
                    });
                    return;
                }
                client.uploadDetailed(slot.id(), source, options, this::reportUploadProgress);
                Platform.runLater(() -> {
                    Controllers.showToast(i18n("xenon.cloud.upload.done",
                            source.getFileName().toString()));
                    reloadSlots();
                });
            } catch (MdtbbsApiException e) {
                if ("SAVE_CONFLICT".equals(e.getErrorCode())) {
                    Platform.runLater(() -> showServerConflict(slot, source, options, e));
                    return;
                }
                Platform.runLater(() -> {
                    Controllers.dialog(
                            i18n("xenon.cloud.upload.failed", messageOf(e)),
                            i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                    reloadSlots();
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    Controllers.dialog(
                            i18n("xenon.cloud.upload.failed", messageOf(e)),
                            i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                    // Leave the progress view behind instead of a stuck bar.
                    reloadSlots();
                });
            } finally {
                // The body was already swapped by reloadSlots() or the error
                // dialog; only the transient progress state is cleared here.
                Platform.runLater(this::clearUploadProgress);
            }
        });
    }

    /// Explains a server-side conflict and offers the suggested copy strategy.
    private void showServerConflict(Slot slot, Path source, UploadOptions options,
                                    MdtbbsApiException error) {
        clearUploadProgress();
        Label title = new Label(i18n("xenon.cloud.conflict.server.title"));
        title.getStyleClass().add("title-label");
        Label hint = new Label(i18n("xenon.cloud.conflict.server.hint",
                error.suggestedResolution().isBlank()
                        ? "create_conflict_copy" : error.suggestedResolution()));
        hint.setWrapText(true);
        JFXButton keepBoth = FXUtils.newRaisedButton(i18n("xenon.cloud.conflict.keep_both"));
        keepBoth.setOnAction(e -> uploadFile(slot, source,
                options.withConflict("create_conflict_copy", null)));
        JFXButton cancel = FXUtils.newRaisedButton(i18n("xenon.cloud.conflict.later"));
        cancel.setOnAction(e -> reloadSlots());
        HBox actions = new HBox(8, keepBoth, cancel);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox body = new VBox(8, title, hint, actions);
        body.setPadding(new Insets(4, 0, 0, 0));
        layout.setBody(body);
    }

    /// Replaces the dialog body with a progress view for one upload.
    ///
    /// @param source local `.msav` file being uploaded
    private void showUploadProgress(Path source) {
        Label title = new Label(i18n("xenon.cloud.upload.progress", 0));
        title.getStyleClass().add("title-label");
        Label file = new Label(source.getFileName().toString());
        file.getStyleClass().add("subtitle-label");
        ProgressBar bar = new ProgressBar(0);
        bar.setMaxWidth(Double.MAX_VALUE);

        VBox body = new VBox(8, title, file, bar);
        body.setPadding(new Insets(4, 0, 0, 0));
        layout.setBody(body);

        uploadProgressLabel = title;
        uploadProgressBar = bar;
        lastUploadPercent = 0;
        TrayIconManager.setStatus(i18n("xenon.cloud.upload.progress", 0));
    }

    /// Pushes one progress sample to the dialog and the tray menu.
    ///
    /// Called on the IO thread once per uploaded buffer; only whole-percent
    /// changes are forwarded to the JavaFX thread.
    ///
    /// @param uploaded bytes delivered so far
    /// @param total    expected body size, or -1 when unknown
    private void reportUploadProgress(long uploaded, long total) {
        if (total <= 0) return;
        int percent = (int) Math.min(100, Math.max(0, uploaded * 100 / total));
        if (percent == lastUploadPercent) return;
        lastUploadPercent = percent;
        Platform.runLater(() -> {
            @Nullable ProgressBar bar = uploadProgressBar;
            @Nullable Label label = uploadProgressLabel;
            String text = i18n("xenon.cloud.upload.progress", percent);
            if (bar != null) bar.setProgress(percent / 100.0);
            if (label != null) label.setText(text);
            TrayIconManager.setStatus(text);
        });
    }

    /// Drops the progress view references and restores the tray tooltip.
    ///
    /// Runs on the JavaFX thread. The internal percentage counter is left alone
    /// because it is owned by the upload thread and reset by the next upload.
    private void clearUploadProgress() {
        uploadProgressBar = null;
        uploadProgressLabel = null;
        TrayIconManager.setStatus(null);
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
                snapshot.pinned() ? i18n("xenon.cloud.pinned") : "")
                + snapshotDisplay(snapshot));
        meta.getStyleClass().add("subtitle-label");

        VBox text = new VBox(2, name, meta);
        HBox.setHgrow(text, Priority.ALWAYS);

        JFXButton download = FXUtils.newRaisedButton(i18n("xenon.cloud.download"));
        download.setOnAction(e -> restore(slot, snapshot));
        JFXButton pin = FXUtils.newRaisedButton(snapshot.pinned()
                ? i18n("xenon.cloud.unpin") : i18n("xenon.cloud.pin"));
        pin.setOnAction(e -> setPinned(slot, snapshot, !snapshot.pinned()));
        JFXButton remove = FXUtils.newRaisedButton(i18n("xenon.cloud.snapshot.delete"));
        remove.setOnAction(e -> confirmDeleteSnapshot(slot, snapshot));

        HBox actions = new HBox(6, download, pin, remove);
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

    /// Deletes one snapshot after confirmation.
    private void confirmDeleteSnapshot(Slot slot, Snapshot snapshot) {
        String name = snapshot.fileName().isBlank() ? snapshot.id() : snapshot.fileName();
        Controllers.confirm(i18n("xenon.cloud.snapshot.delete.confirm", name),
                i18n("xenon.cloud.title"), () -> Schedulers.io().execute(() -> {
                    try {
                        client.deleteSnapshot(slot.id(), snapshot.id());
                        Platform.runLater(() -> {
                            Controllers.showToast(i18n("xenon.cloud.snapshot.delete.done"));
                            openSnapshots(slot);
                        });
                    } catch (IOException e) {
                        fail(e);
                    }
                }), null);
    }

    /// Formats the map, wave and Mod count recorded at upload.
    private static String snapshotDisplay(Snapshot snapshot) {
        StringBuilder text = new StringBuilder();
        if (!snapshot.mapName().isBlank()) {
            text.append("  ·  ").append(snapshot.mapName());
        }
        if (snapshot.wave() > 0) {
            text.append("  ·  ").append(i18n("xenon.cloud.snapshot.wave", snapshot.wave()));
        }
        if (snapshot.modCount() > 0) {
            text.append("  ·  ").append(i18n("xenon.cloud.snapshot.mods", snapshot.modCount()));
        }
        return text.toString();
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
    // Read-only comparison
    // ------------------------------------------------------------------

    /// A cloud head paired with the snapshot it was read from.
    ///
    /// The head alone is enough to compare, but acting on the result needs the
    /// snapshot id, which the comparison intentionally does not carry.
    ///
    /// @param head       newest snapshot of one slot, as the comparison sees it
    /// @param snapshotId id of that snapshot
    private record HeadRef(CloudHead head, String snapshotId) {
    }

    /// Runs the read-only comparison and lists the files that need attention.
    private void checkNow() {
        layout.setBody(new Label(i18n("xenon.cloud.loading")));
        Schedulers.io().execute(() -> {
            try {
                List<HeadRef> refs = loadHeads();
                List<CloudHead> heads = new ArrayList<>(refs.size());
                for (HeadRef ref : refs) heads.add(ref.head());
                MindustryCloudSaveSync.CheckResult result =
                        MindustryCloudSaveSync.check(savesDir, () -> heads);
                Platform.runLater(() -> renderCheck(result, refs));
            } catch (IOException e) {
                fail(e);
            }
        });
    }

    /// Reads the newest snapshot of every slot.
    ///
    /// @return one reference per slot that has a snapshot
    /// @throws IOException when the cloud cannot be reached
    private List<HeadRef> loadHeads() throws IOException {
        List<HeadRef> refs = new ArrayList<>();
        SlotPage page = client.slots(null, 50);
        for (Slot slot : page.slots()) {
            if (slot.id().isBlank()) continue;
            List<Snapshot> snapshots = client.snapshots(slot.id());
            Snapshot newest = snapshots.stream()
                    .max(Comparator.comparing(Snapshot::createdAt)).orElse(null);
            if (newest == null) continue;
            refs.add(new HeadRef(new CloudHead(slot.id(), slot.name(), newest.fileName(),
                    newest.size(), newest.sha256(), newest.createdAt()), newest.id()));
        }
        return refs;
    }

    /// Renders the comparison result with a resolve action per file.
    ///
    /// @param result comparison result
    /// @param refs   cloud heads, used to act on a file
    private void renderCheck(MindustryCloudSaveSync.CheckResult result, List<HeadRef> refs) {
        Label title = new Label(i18n("xenon.cloud.check.title"));
        title.getStyleClass().add("title-label");

        VBox rows = new VBox(6);
        if (result.needingAttention().isEmpty()) {
            rows.getChildren().add(new Label(i18n("xenon.cloud.check.empty")));
        }
        for (SaveComparison comparison : result.needingAttention()) {
            rows.getChildren().add(comparisonRow(comparison, refs));
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

    /// Builds one comparison row: file name, both timestamps and sizes, and a resolve action.
    ///
    /// @param comparison one local-vs-cloud comparison
    /// @param refs       cloud heads available for the actions
    /// @return the rendered row
    private Region comparisonRow(SaveComparison comparison, List<HeadRef> refs) {
        Label name = new Label(comparison.fileName());
        name.getStyleClass().add("title-label");

        // Only times, sizes and the file name are shown; the cloud slot ids and
        // snapshot ids stay internal.
        Label meta = new Label(i18n("xenon.cloud.check.meta",
                i18n("xenon.cloud.verdict."
                        + comparison.verdict().name().toLowerCase(Locale.ROOT)),
                DATE.format(comparison.local().timestamp()),
                formatSize(comparison.local().size()),
                DATE.format(comparison.cloud().timestamp()),
                formatSize(comparison.cloud().size())));
        meta.getStyleClass().add("subtitle-label");
        meta.setWrapText(true);

        VBox text = new VBox(2, name, meta);
        HBox.setHgrow(text, Priority.ALWAYS);

        @Nullable HeadRef ref = findHead(comparison.fileName(), refs);
        JFXButton resolve = FXUtils.newRaisedButton(i18n("xenon.cloud.check.resolve"));
        resolve.setDisable(ref == null);
        if (ref != null) {
            resolve.setOnAction(e -> showConflict(comparison, ref));
        }

        HBox actions = new HBox(6, resolve);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(8, text, actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6));
        row.getStyleClass().add("community-reply");
        return row;
    }

    /// Finds the newest cloud head of one file name.
    ///
    /// @param fileName file name to look for
    /// @param refs     cloud heads to search
    /// @return the newest matching head, or `null`
    private static @Nullable HeadRef findHead(String fileName, List<HeadRef> refs) {
        @Nullable HeadRef best = null;
        for (HeadRef ref : refs) {
            if (!ref.head().fileName().equals(fileName)) continue;
            if (best == null || ref.head().createdAt().isAfter(best.head().createdAt())) {
                best = ref;
            }
        }
        return best;
    }

    /// Shows the simplified conflict prompt for one file.
    ///
    /// Deliberately small: both timestamps, both sizes, the file name and the
    /// cloud slot's display name. No internal ids are rendered. The three
    /// choices are the only ways out — nothing happens without one of them.
    ///
    /// @param comparison conflicting pair
    /// @param ref          cloud head to restore from or upload into
    private void showConflict(SaveComparison comparison, HeadRef ref) {
        SaveIdentity local = comparison.local();
        SaveIdentity cloud = comparison.cloud();

        Label title = new Label(i18n("xenon.cloud.conflict.title", comparison.fileName()));
        title.getStyleClass().add("title-label");
        title.setWrapText(true);

        Label cloudSide = new Label(i18n("xenon.cloud.conflict.cloud",
                ref.head().slotName().isBlank()
                        ? i18n("xenon.cloud.slot.unnamed") : ref.head().slotName(),
                DATE.format(cloud.timestamp()), formatSize(cloud.size())));
        cloudSide.getStyleClass().add("subtitle-label");
        cloudSide.setWrapText(true);

        Label localSide = new Label(i18n("xenon.cloud.conflict.local",
                DATE.format(local.timestamp()), formatSize(local.size())));
        localSide.getStyleClass().add("subtitle-label");
        localSide.setWrapText(true);

        Label hint = new Label(i18n("xenon.cloud.conflict.hint"));
        hint.setWrapText(true);

        JFXButton useCloud = FXUtils.newRaisedButton(i18n("xenon.cloud.conflict.use_cloud"));
        useCloud.setOnAction(e -> useCloud(comparison, ref));
        JFXButton keepLocal = FXUtils.newRaisedButton(i18n("xenon.cloud.conflict.keep_local"));
        keepLocal.setOnAction(e -> keepLocal(comparison, ref));
        JFXButton keepBoth = FXUtils.newRaisedButton(i18n("xenon.cloud.conflict.keep_both"));
        keepBoth.setOnAction(e -> keepBoth(comparison, ref));
        JFXButton later = FXUtils.newRaisedButton(i18n("xenon.cloud.conflict.later"));
        later.setOnAction(e -> checkNow());
        HBox actions = new HBox(8, useCloud, keepLocal, keepBoth, later);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(8, title, cloudSide, localSide, hint, actions);
        body.setPadding(new Insets(4, 0, 0, 0));
        layout.setBody(body);
    }

    /// Restores the cloud head over the local file, backing the local one up first.
    ///
    /// @param comparison conflicting pair
    /// @param ref          cloud head to restore
    private void useCloud(SaveComparison comparison, HeadRef ref) {
        Slot slot = slotOf(ref);
        Snapshot snapshot = new Snapshot(ref.snapshotId(), ref.head().size(),
                ref.head().sha256(), comparison.fileName(), false, ref.head().createdAt());
        restore(slot, snapshot);
    }

    /// Uploads the local file into the cloud slot, replacing the cloud head.
    ///
    /// @param comparison conflicting pair
    /// @param ref          cloud head whose slot receives the upload
    private void keepLocal(SaveComparison comparison, HeadRef ref) {
        Path source = savesDir.resolve(comparison.fileName());
        if (!Files.isRegularFile(source)) {
            Controllers.showToast(i18n("xenon.cloud.conflict.missing", comparison.fileName()));
            checkNow();
            return;
        }
        Slot slot = slotOf(ref);
        Schedulers.io().execute(() -> {
            try {
                // Preserve the cloud head locally before force-replacing it.
                Path backups = savesDir.resolve("backups");
                Files.createDirectories(backups);
                Path backup = backups.resolve(stripExtension(comparison.fileName())
                        + "-cloud-" + STAMP.format(java.time.Instant.now()) + ".msav");
                client.download(slot.id(), ref.snapshotId(), backup, null);
                Platform.runLater(() -> uploadFile(slot, source,
                        optionsFor(source, ref.snapshotId(), "conflict",
                                "force_replace_head", ref.snapshotId())));
            } catch (IOException e) {
                Platform.runLater(() -> Controllers.dialog(
                        i18n("xenon.cloud.upload.failed", messageOf(e)),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    /// Uploads the local file as a separate conflict copy.
    private void keepBoth(SaveComparison comparison, HeadRef ref) {
        Path source = savesDir.resolve(comparison.fileName());
        if (!Files.isRegularFile(source)) {
            Controllers.showToast(i18n("xenon.cloud.conflict.missing", comparison.fileName()));
            checkNow();
            return;
        }
        uploadFile(slotOf(ref), source, optionsFor(source, ref.snapshotId(), "conflict",
                "create_conflict_copy", null));
    }

    /// Rebuilds a slot value from a cloud head so existing actions can be reused.
    ///
    /// @param ref cloud head
    /// @return the slot the head belongs to
    private static Slot slotOf(HeadRef ref) {
        return new Slot(ref.head().slotId(), ref.head().slotName(),
                ref.head().createdAt(), ref.head().size(), 0L, ref.snapshotId());
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
