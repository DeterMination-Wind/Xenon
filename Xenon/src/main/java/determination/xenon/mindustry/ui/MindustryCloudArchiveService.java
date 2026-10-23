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

import determination.xenon.Metadata;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.MindustryVersion.CloudMode;
import determination.xenon.mindustry.cloud.MindustryCloudArchiveInstaller;
import determination.xenon.mindustry.cloud.MindustryCloudArchivePacker;
import determination.xenon.mindustry.cloud.MindustryCloudSyncBaseline;
import determination.xenon.mindustry.cloud.MindustryCloudSyncService;
import determination.xenon.mindustry.cloud.MindustryCloudSyncService.CloudState;
import determination.xenon.mindustry.cloud.MindustryCloudSyncService.Kind;
import determination.xenon.mindustry.cloud.MindustryModFillService;
import determination.xenon.mindustry.community.MdtbbsApiException;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.DownloadResult;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.GameInfo;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Slot;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Snapshot;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.UploadMetadata;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.UploadOptions;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.UploadResult;
import determination.xenon.mindustry.community.MindustryCloudSaveSync;
import determination.xenon.mindustry.mod.MindustryModsIndexRepository;
import determination.xenon.mindustry.mod.MindustryRemoteMod;
import determination.xenon.setting.ConfigHolder;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Whole-instance cloud archive operations for one Mindustry instance.
///
/// The archive is stored as one snapshot of a dedicated save slot. Uploads use
/// the normal/base-snapshot guard; conflicts are never resolved automatically.
/// Automatic sync only runs when the instance opted in and its data directory
/// is not shared, so two instances cannot race on the same files.
@NotNullByDefault
public final class MindustryCloudArchiveService {

    /// Outcome of a pre-launch or post-exit sync attempt.
    public enum Outcome {
        /// Nothing to do.
        NONE,
        /// A newer cloud archive was restored.
        RESTORED,
        /// A new archive was uploaded.
        UPLOADED,
        /// Both sides changed; the caller should explain the conflict.
        CONFLICT,
        /// Automatic sync is disabled or not applicable.
        SKIPPED
    }

    /// One archive action result.
    ///
    /// @param outcome          what happened
    /// @param missingMods      Mods the archive could not restore
    /// @param snapshotId       snapshot that was uploaded or restored
    public record Result(Outcome outcome, List<String> missingMods, String snapshotId) {
        /// Copies the Mod list.
        public Result {
            missingMods = List.copyOf(missingMods);
        }

        /// Builds a result without missing Mods.
        static Result of(Outcome outcome, String snapshotId) {
            return new Result(outcome, List.of(), snapshotId);
        }
    }

    private final MdtbbsCloudSavesClient client;
    private final MindustryVersion version;
    private final Path versionRoot;
    private final Path dataDir;

    /// Creates a service for one instance.
    ///
    /// @param client     cloud-save client
    /// @param version    instance descriptor
    /// @param versionRoot instance directory
    public MindustryCloudArchiveService(MdtbbsCloudSavesClient client, MindustryVersion version,
                                        Path versionRoot) {
        this.client = client;
        this.version = version;
        this.versionRoot = versionRoot;
        this.dataDir = version.resolveDataDir(versionRoot);
    }

    /// Whether this instance may use archive mode.
    ///
    /// @return `false` for the shared global data directory
    public static boolean supportsArchive(MindustryVersion version) {
        return version.getDataDirPolicy()
                != determination.xenon.mindustry.DataDirectoryPolicy.GLOBAL;
    }

    /// Runs the pre-launch pull, if the instance opted in.
    ///
    /// @return the action result
    /// @throws IOException when a needed pull fails
    public Result preLaunch() throws IOException {
        String slotId = version.getCloudArchiveSlotId();
        if (!eligible() || slotId.isBlank()) {
            return Result.of(Outcome.SKIPPED, "");
        }
        Snapshot latest = latest(slotId);
        if (latest == null) {
            return Result.of(Outcome.SKIPPED, "");
        }
        CloudState cloud = new CloudState(latest.id(), latest.size(), latest.sha256(),
                latest.createdAt());
        MindustryCloudSyncBaseline baseline = MindustryCloudSyncBaseline.load(baselineFile());
        Kind kind = MindustryCloudSyncService.decide(dataDir, cloud, baseline).kind();
        if (kind == Kind.CONFLICT) {
            return Result.of(Outcome.CONFLICT, latest.id());
        }
        if (kind != Kind.PULL) {
            return Result.of(kind == Kind.NO_BASELINE ? Outcome.CONFLICT : Outcome.NONE,
                    latest.id());
        }
        return restore(latest, slotId);
    }

    /// Runs the post-exit upload, if the instance opted in.
    ///
    /// @return the action result
    /// @throws IOException when the upload fails
    public Result postExit() throws IOException {
        String slotId = version.getCloudArchiveSlotId();
        if (!eligible() || slotId.isBlank()) {
            return Result.of(Outcome.SKIPPED, "");
        }
        Snapshot latest = latest(slotId);
        CloudState cloud = latest == null
                ? new CloudState("", 0, "", Instant.EPOCH)
                : new CloudState(latest.id(), latest.size(), latest.sha256(), latest.createdAt());
        MindustryCloudSyncBaseline baseline = MindustryCloudSyncBaseline.load(baselineFile());
        Kind kind = MindustryCloudSyncService.decide(dataDir, cloud, baseline).kind();
        if (kind == Kind.CONFLICT || (kind == Kind.NO_BASELINE && latest != null)) {
            return Result.of(Outcome.CONFLICT, latest == null ? "" : latest.id());
        }
        if (kind != Kind.PUSH && kind != Kind.NO_BASELINE) {
            return Result.of(Outcome.NONE, latest == null ? "" : latest.id());
        }
        return upload(slotId, latest, "after_exit");
    }

    /// Uploads the current instance as a new archive snapshot.
    ///
    /// @param reason documented upload reason
    /// @return the upload result
    /// @throws IOException when packing or uploading fails
    public Result uploadNow(String reason) throws IOException {
        String slotId = ensureSlot();
        return upload(slotId, latest(slotId), reason);
    }

    /// Restores the newest archive snapshot.
    ///
    /// @return the restore result
    /// @throws IOException when the download or restore fails
    public Result restoreNow() throws IOException {
        String slotId = version.getCloudArchiveSlotId();
        if (slotId.isBlank()) {
            throw new IOException("no cloud archive slot");
        }
        Snapshot latest = latest(slotId);
        if (latest == null) {
            throw new IOException("the cloud archive has no snapshot");
        }
        return restore(latest, slotId);
    }

    /// Reads the newest snapshot of the archive slot.
    ///
    /// @return the newest snapshot, or `null`
    /// @throws IOException when the request fails
    public @Nullable Snapshot latestSnapshot() throws IOException {
        String slotId = version.getCloudArchiveSlotId();
        return slotId.isBlank() ? null : latest(slotId);
    }

    /// Returns the local baseline path.
    ///
    /// @return `<versionRoot>/cloud-sync.json`
    public Path baselineFile() {
        return versionRoot.resolve(MindustryCloudSyncBaseline.FILE_NAME);
    }

    /// Uploads one archive snapshot.
    private Result upload(String slotId, @Nullable Snapshot latest, String reason)
            throws IOException {
        Path temp = Files.createTempFile(cacheDir(), "xenon-cloud-archive-", ".zip");
        try {
            MindustryCloudArchivePacker.Result packed = MindustryCloudArchivePacker.pack(
                    dataDir, temp,
                    new MindustryCloudArchivePacker.Options(version.getId() == null ? ""
                            : version.getId(), version.getBuild(), indexedModNames()),
                    null, null);
            MindustryCloudSaveSync.CleanupPlan plan =
                    MindustryCloudSaveSync.enforceQuota(client, slotId, packed.size());
            if (!plan.sufficient()) {
                throw new IOException("cloud quota is too small for the instance archive");
            }
            UploadMetadata metadata = new UploadMetadata(
                    new GameInfo(version.getId() == null ? "" : version.getId(),
                            version.getBuild()), null, List.of(),
                    ConfigHolder.globalConfig().getOrCreateCloudDeviceId());
            UploadOptions options = new UploadOptions(reason,
                    latest == null ? null : latest.id(), "normal", null, metadata);
            UploadResult result;
            try {
                result = client.uploadDetailed(slotId, temp, options, null);
            } catch (MdtbbsApiException e) {
                if ("SAVE_CONFLICT".equals(e.getErrorCode())) {
                    return Result.of(Outcome.CONFLICT, e.currentSnapshotId());
                }
                throw e;
            }
            saveBaseline(slotId, result.snapshotId(), packed);
            Logger.LOG.info("Uploaded cloud archive " + result.snapshotId()
                    + " for " + version.getId());
            return Result.of(Outcome.UPLOADED, result.snapshotId());
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /// Downloads and installs one snapshot.
    private Result restore(Snapshot snapshot, String slotId) throws IOException {
        Path temp = Files.createTempFile(cacheDir(), "xenon-cloud-archive-", ".zip");
        try {
            DownloadResult download = client.downloadDetailed(slotId, snapshot.id(), temp, null);
            MindustryCloudArchiveInstaller.RestoreResult restored =
                    MindustryCloudArchiveInstaller.install(temp, dataDir,
                            dataDir.resolve(".xenon-backups").resolve("cloud"));
            List<String> unresolved = new MindustryModFillService().fill(
                    dataDir, restored.missingMods(), Metadata.getCachesDirectory(), null).unresolved();
            saveBaseline(slotId, snapshot.id(), null);
            Logger.LOG.info("Restored cloud archive " + snapshot.id() + " for " + version.getId()
                    + " (" + download.size() + " bytes)");
            return new Result(Outcome.RESTORED, unresolved, snapshot.id());
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /// Writes the baseline after a successful sync.
    private void saveBaseline(String slotId, String snapshotId,
                              @Nullable MindustryCloudArchivePacker.Result packed)
            throws IOException {
        String fingerprint = MindustryCloudSyncService.fingerprint(dataDir);
        String modsHash = MindustryCloudSyncService.modsHash(dataDir);
        long size = packed == null ? 0 : packed.size();
        // The server snapshot id is the authoritative cloud identity; the
        // local baseline does not need a second archive hash.
        String hash = "";
        MindustryCloudSyncBaseline baseline = new MindustryCloudSyncBaseline(
                MindustryCloudSyncBaseline.SCHEMA, slotId, snapshotId, hash, size,
                fingerprint, modsHash, Instant.now());
        MindustryCloudSyncBaseline.save(baselineFile(), baseline);
    }

    /// Ensures the archive slot exists.
    private String ensureSlot() throws IOException {
        String existing = version.getCloudArchiveSlotId();
        if (!existing.isBlank()) {
            return existing;
        }
        String name = "Xenon archive - " + version.getName();
        String created = client.createSlot(name);
        if (created.isBlank()) {
            throw new IOException("cloud did not return an archive slot id");
        }
        version.setCloudArchiveSlotId(created);
        persistVersion();
        return created;
    }

    /// Reads the newest snapshot of a slot, preferring the slot head.
    private @Nullable Snapshot latest(String slotId) throws IOException {
        Slot slot = client.slot(slotId);
        List<Snapshot> snapshots = client.snapshots(slotId);
        if (snapshots.isEmpty()) {
            return null;
        }
        if (!slot.currentSnapshotId().isBlank()) {
            for (Snapshot snapshot : snapshots) {
                if (slot.currentSnapshotId().equals(snapshot.id())) {
                    return snapshot;
                }
            }
        }
        return snapshots.stream().max(Comparator.comparing(Snapshot::createdAt)).orElse(null);
    }

    /// Whether automatic archive sync is allowed for this instance.
    private boolean eligible() {
        return version.getCloudMode() == CloudMode.INSTANCE_ARCHIVE
                && version.isCloudAutoSync()
                && supportsArchive(version);
    }

    /// Persists the instance manifest after creating a slot.
    private void persistVersion() throws IOException {
        MindustryImportFlow.repositoryForVersion(version).save(version);
    }

    /// Collects the public-indexed Mod names for the packer.
    private static Set<String> indexedModNames() {
        Set<String> names = new HashSet<>();
        try {
            for (MindustryRemoteMod mod : new MindustryModsIndexRepository(
                    Metadata.getCachesDirectory()).loadCache()) {
                if (mod.getName() != null) {
                    names.add(mod.getName().toLowerCase(Locale.ROOT));
                }
            }
        } catch (RuntimeException e) {
            Logger.LOG.info("Cannot read the Mod index for the archive: " + e.getMessage());
        }
        return names;
    }

    /// Cache directory for temporary archives.
    private static Path cacheDir() throws IOException {
        Path dir = Metadata.getCachesDirectory().resolve("cloud-archive");
        Files.createDirectories(dir);
        return dir;
    }
}
