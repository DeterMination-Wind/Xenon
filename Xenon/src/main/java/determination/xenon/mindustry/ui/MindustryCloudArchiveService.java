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
import determination.xenon.mindustry.cloud.MindustryCloudArchiveManifest.FileEntry;
import determination.xenon.mindustry.cloud.MindustryCloudArchiveManifest.ModEntry;
import determination.xenon.mindustry.cloud.MindustryCloudArchivePacker;
import determination.xenon.mindustry.cloud.MindustryCloudArchivePacker.Plan;
import determination.xenon.mindustry.cloud.MindustryCloudArchivePacker.PlannedFile;
import determination.xenon.mindustry.cloud.MindustryCloudManifestInstaller;
import determination.xenon.mindustry.cloud.MindustryCloudManifestPlan;
import determination.xenon.mindustry.cloud.MindustryCloudSyncBaseline;
import determination.xenon.mindustry.cloud.MindustryCloudSyncService;
import determination.xenon.mindustry.cloud.MindustryCloudSyncService.CloudState;
import determination.xenon.mindustry.cloud.MindustryCloudSyncService.Kind;
import determination.xenon.mindustry.cloud.MindustryModFillService;
import determination.xenon.mindustry.community.MdtbbsApiException;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.BlobCheck;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.BlobUpload;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.BlobUrl;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.DownloadResult;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.GameInfo;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.ManifestEntry;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.ManifestSnapshot;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.ModInfo;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Quota;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.RemoteManifest;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Slot;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Snapshot;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.UploadMetadata;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.UploadOptions;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.UploadResult;
import determination.xenon.mindustry.community.MindustryCloudSaveSync;
import determination.xenon.mindustry.download.ProgressCallback;
import determination.xenon.mindustry.mod.MindustryModsIndexRepository;
import determination.xenon.mindustry.mod.MindustryRemoteMod;
import determination.xenon.setting.ConfigHolder;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/// Whole-instance cloud archive operations for one Mindustry instance.
///
/// The archive is stored as one snapshot of a dedicated save slot. Uploads use
/// the normal/base-snapshot guard; conflicts are never resolved automatically.
/// Automatic sync only runs when the instance opted in and its data directory
/// is not shared, so two instances cannot race on the same files.
@NotNullByDefault
public final class MindustryCloudArchiveService {

    /// Maximum number of blobs transferred concurrently.
    private static final int MAX_BLOB_WORKERS = 3;

    /// Attempts per blob before the upload fails.
    private static final int MAX_BLOB_ATTEMPTS = 3;

    /// Retry delay when the server omits `retry_after_seconds`.
    private static final long DEFAULT_RETRY_SECONDS = 2L;

    /// Upper bound for a server-provided retry delay.
    private static final long MAX_RETRY_SECONDS = 30L;

    /// How long worker pools may drain before the caller is interrupted.
    private static final long POOL_SHUTDOWN_SECONDS = 30L;

    /// Minimum age before a stale staging directory is pruned.
    private static final long STAGING_PRUNE_AGE_MILLIS = TimeUnit.MINUTES.toMillis(10);

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
        if (!supportsArchive(version)) {
            throw new IOException("cloud archive is unavailable for the shared global"
                    + " data directory");
        }
        String slotId = ensureSlot();
        return upload(slotId, latest(slotId), reason);
    }

    /// Restores the newest archive snapshot.
    ///
    /// @return the restore result
    /// @throws IOException when the download or restore fails
    public Result restoreNow() throws IOException {
        if (!supportsArchive(version)) {
            throw new IOException("cloud archive is unavailable for the shared global"
                    + " data directory");
        }
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
    ///
    /// The manifest path is attempted only when the feature is enabled and the
    /// server advertises a supported format. Any manifest failure except a
    /// conflict logs and degrades to the zip archive exactly once; a cloud
    /// conflict is reported and never retried.
    ///
    /// @param slotId archive slot id
    /// @param latest newest snapshot, or `null`
    /// @param reason documented upload reason
    /// @return the upload result
    /// @throws IOException when planning, packing or uploading fails
    private Result upload(String slotId, @Nullable Snapshot latest, String reason)
            throws IOException {
        pruneStagingDirectories();
        MindustryCloudArchivePacker.Options packOptions = archiveOptions();
        Quota quota = client.quota();
        String fingerprint = MindustryCloudSyncService.fingerprint(dataDir);
        String modsHash = MindustryCloudSyncService.modsHash(dataDir);
        if (!deltaPotentiallyUsable(quota)) {
            return uploadZip(slotId, packOptions, latest, reason, fingerprint, modsHash, null);
        }
        Plan plan = MindustryCloudArchivePacker.plan(dataDir, packOptions);
        MindustryCloudManifestPlan.Decision decision = MindustryCloudManifestPlan.decide(
                plan, quota, true, MindustryCloudManifestPlan.deltaEnabled());
        if (decision.mode() == MindustryCloudManifestPlan.Mode.MANIFEST
                && isSupportedManifestFormat(decision.format())) {
            UploadOptions options = uploadOptions(latest, reason, plan.mods());
            try {
                return uploadManifest(slotId, plan, decision, quota, options,
                        fingerprint, modsHash);
            } catch (MdtbbsApiException e) {
                if ("SAVE_CONFLICT".equals(e.getErrorCode())) {
                    return Result.of(Outcome.CONFLICT, e.currentSnapshotId());
                }
                Logger.LOG.warning("Cloud archive manifest upload failed with "
                        + e.getErrorCode() + (isManifestFallback(e) ? "" : ", unexpected")
                        + "; falling back to the zip archive", e);
            } catch (IOException | RuntimeException e) {
                Logger.LOG.warning("Cloud archive manifest upload failed; "
                        + "falling back to the zip archive", e);
            }
        }
        return uploadZip(slotId, packOptions, latest, reason, fingerprint, modsHash, plan);
    }

    /// Whether the quota advertises a manifest path this build supports.
    ///
    /// @param quota advertised storage limits and formats
    /// @return whether delta is enabled and a supported non-`zip` format exists
    private static boolean deltaPotentiallyUsable(Quota quota) {
        return MindustryCloudManifestPlan.deltaEnabled()
                && hasSupportedManifestFormat(quota.formats());
    }

    /// Whether any advertised format is a supported manifest token.
    ///
    /// @param formats advertised format tokens
    /// @return `true` when one non-`zip` token is supported
    private static boolean hasSupportedManifestFormat(Iterable<String> formats) {
        for (String format : formats) {
            String candidate = format.trim();
            if (!candidate.isEmpty() && !"zip".equalsIgnoreCase(candidate)
                    && isSupportedManifestFormat(candidate)) {
                return true;
            }
        }
        return false;
    }

    /// Whether a format token is a supported manifest format.
    ///
    /// @param format format token to inspect
    /// @return `true` for a case-insensitive allow-listed manifest format
    private static boolean isSupportedManifestFormat(String format) {
        return MindustryCloudManifestPlan.isSupportedManifestFormat(format);
    }

    /// Builds the pack options for this instance.
    ///
    /// @return the pack configuration
    private MindustryCloudArchivePacker.Options archiveOptions() {
        return new MindustryCloudArchivePacker.Options(
                version.getId() == null ? "" : version.getId(), version.getBuild(),
                indexedModNames());
    }

    /// Builds the upload options shared by both containers.
    ///
    /// The game block is omitted entirely for an instance without a version id,
    /// because the API rejects an empty version string.
    ///
    /// @param latest newest snapshot, or `null`
    /// @param reason documented upload reason
    /// @param mods   Mods recorded by the pack plan
    /// @return the upload options
    private UploadOptions uploadOptions(@Nullable Snapshot latest, String reason,
                                        List<ModEntry> mods) {
        String versionId = version.getId() == null ? "" : version.getId();
        @Nullable GameInfo game = versionId.isBlank()
                ? null : new GameInfo(versionId, version.getBuild());
        List<ModInfo> metadataMods = new ArrayList<>(mods.size());
        for (ModEntry mod : mods) {
            metadataMods.add(new ModInfo(mod.id(), mod.name(), mod.version(), mod.sha256()));
        }
        UploadMetadata metadata = new UploadMetadata(game, null, metadataMods,
                ConfigHolder.globalConfig().getOrCreateCloudDeviceId());
        return new UploadOptions(reason, latest == null ? null : latest.id(), "normal",
                null, metadata);
    }

    /// Uploads the plan as a blob-backed manifest snapshot.
    ///
    /// Only missing blobs are transferred; the quota pre-check charges at least
    /// the planned logical bytes when the server reports a logical size, so a
    /// mostly-deduplicated upload still reserves the full logical footprint.
    ///
    /// @param slotId   archive slot id
    /// @param plan     planned archive content
    /// @param decision manifest decision carrying the wire format
    /// @param quota    advertised quota used by the decision
    /// @param options  conflict strategy, reason and metadata
    /// @param fingerprint local fingerprint captured before the upload
    /// @param modsHash    Mod hash captured before the upload
    /// @return the upload result
    /// @throws IOException when blob transfer or the commit fails
    private Result uploadManifest(String slotId, Plan plan,
                                  MindustryCloudManifestPlan.Decision decision,
                                  Quota quota, UploadOptions options,
                                  String fingerprint, String modsHash) throws IOException {
        Map<String, PlannedFile> byHash = new LinkedHashMap<>();
        long plannedLogical = 0;
        for (PlannedFile file : plan.files()) {
            plannedLogical += Math.max(0L, file.size());
            if (!file.sha256().isBlank()) byHash.putIfAbsent(file.sha256(), file);
        }
        BlobCheck check = client.checkBlobs(byHash.keySet());
        Set<String> missing = new LinkedHashSet<>(check.missing());
        missing.retainAll(byHash.keySet());
        long missingBytes = 0;
        for (String hash : missing) {
            @Nullable PlannedFile file = byHash.get(hash);
            if (file != null) missingBytes += Math.max(0L, file.size());
        }
        long neededBytes = missingBytes;
        if (quota.logicalBytes() > 0 && quota.quotaBytes() > 0) {
            neededBytes = Math.max(missingBytes, plannedLogical);
        }
        MindustryCloudSaveSync.CleanupPlan quotaPlan =
                MindustryCloudSaveSync.enforceQuota(client, slotId, neededBytes);
        if (!quotaPlan.sufficient()) {
            throw new IOException("cloud quota is too small for the instance archive");
        }
        uploadMissingBlobs(byHash, missing, plannedLogical);
        List<ManifestEntry> entries = new ArrayList<>(plan.files().size());
        for (PlannedFile file : plan.files()) {
            entries.add(new ManifestEntry(file.archivePath(), file.size(), file.sha256()));
        }
        ManifestSnapshot snapshot = client.createManifestSnapshot(slotId,
                options.baseSnapshotId(), decision.format(), entries, options);
        if (snapshot.snapshotId().isBlank()) {
            throw new IOException("cloud did not return a snapshot id");
        }
        saveBaseline(slotId, snapshot.snapshotId(), null, fingerprint, modsHash);
        Logger.LOG.info("Uploaded cloud archive manifest " + snapshot.snapshotId()
                + " for " + version.getId() + " (" + snapshot.fileCount() + " files)");
        return Result.of(Outcome.UPLOADED, snapshot.snapshotId());
    }

    /// Uploads the missing blobs with bounded concurrency.
    ///
    /// Progress accumulates the bytes actually delivered and reports them
    /// against the whole planned byte count, matching a zip upload.
    ///
    /// @param byHash       one representative planned file per blob hash
    /// @param missing      hashes the server does not hold
    /// @param plannedBytes total bytes of every planned file
    /// @throws IOException when any blob transfer fails
    private void uploadMissingBlobs(Map<String, PlannedFile> byHash, Set<String> missing,
                                    long plannedBytes) throws IOException {
        if (missing.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(MAX_BLOB_WORKERS, missing.size()));
        AtomicLong uploaded = new AtomicLong();
        try {
            List<Future<?>> futures = new ArrayList<>(missing.size());
            for (String hash : missing) {
                @Nullable PlannedFile candidate = byHash.get(hash);
                if (candidate == null) continue;
                PlannedFile file = candidate;
                futures.add(pool.submit(() -> {
                    AtomicLong previous = new AtomicLong();
                    ProgressCallback progress = (read, total) -> {
                        long delta = Math.max(0L, read - previous.getAndSet(read));
                        if (delta > 0) uploaded.addAndGet(delta);
                    };
                    uploadBlobWithRetry(file, progress);
                    return null;
                }));
            }
            for (Future<?> future : futures) awaitTransfer(future);
        } finally {
            shutdownPool(pool);
        }
        Logger.LOG.info("Uploaded " + missing.size() + " cloud archive blob(s): "
                + uploaded.get() + " of " + plannedBytes + " planned bytes");
    }

    /// Uploads one blob, retrying rate-limited requests three times in total.
    ///
    /// The source is re-hashed after the PUT so a file changed between planning
    /// and upload can never be committed under the planned hash.
    ///
    /// @param file     planned file supplying the bytes and hash
    /// @param progress receiver of cumulative bytes for this blob
    /// @throws IOException when the upload fails, a retry is exhausted, or the
    ///         source changed during the transfer
    private void uploadBlobWithRetry(PlannedFile file, ProgressCallback progress)
            throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                BlobUpload blob = client.createBlob(file.sha256(), file.size());
                client.uploadBlobBytes(blob, file.source(), progress);
                String uploadedSha = sha256(file.source());
                if (!file.sha256().equalsIgnoreCase(uploadedSha)) {
                    Logger.LOG.warning("Cloud archive blob changed during upload: "
                            + file.archivePath());
                    throw new IOException("cloud archive blob changed during upload: "
                            + file.archivePath());
                }
                return;
            } catch (MdtbbsApiException e) {
                if (attempt >= MAX_BLOB_ATTEMPTS || !isRateLimited(e)) throw e;
                long seconds = e.retryAfterSeconds();
                sleepRetry(seconds > 0 ? seconds : DEFAULT_RETRY_SECONDS);
            }
        }
    }

    /// Uploads the plan as one zip archive.
    ///
    /// The pack result is the source of the Mod metadata, so the zip path never
    /// needs a separate plan pass.
    ///
    /// @param slotId      archive slot id
    /// @param packOptions pack configuration
    /// @param latest      newest snapshot, or `null`
    /// @param reason      documented upload reason
    /// @param fingerprint local fingerprint captured before the upload
    /// @param modsHash    Mod hash captured before the upload
    /// @param plan        plan already computed for a manifest attempt, or `null`
    /// @return the upload result
    /// @throws IOException when packing or uploading fails
    private Result uploadZip(String slotId, MindustryCloudArchivePacker.Options packOptions,
                             @Nullable Snapshot latest, String reason,
                             String fingerprint, String modsHash,
                             @Nullable Plan plan) throws IOException {
        Path temp = Files.createTempFile(cacheDir(), "xenon-cloud-archive-", ".zip");
        try {
            MindustryCloudArchivePacker.Result packed = plan == null
                    ? MindustryCloudArchivePacker.pack(dataDir, temp, packOptions, null, null)
                    : MindustryCloudArchivePacker.pack(dataDir, temp, packOptions, plan,
                            null, null);
            UploadOptions options = uploadOptions(latest, reason, packed.manifest().mods());
            MindustryCloudSaveSync.CleanupPlan quotaPlan =
                    MindustryCloudSaveSync.enforceQuota(client, slotId, packed.size());
            if (!quotaPlan.sufficient()) {
                throw new IOException("cloud quota is too small for the instance archive");
            }
            UploadResult result;
            try {
                result = client.uploadDetailed(slotId, temp, options, null);
            } catch (MdtbbsApiException e) {
                if ("SAVE_CONFLICT".equals(e.getErrorCode())) {
                    return Result.of(Outcome.CONFLICT, e.currentSnapshotId());
                }
                throw e;
            }
            if (result.snapshotId().isBlank()) {
                throw new IOException("cloud did not return a snapshot id");
            }
            saveBaseline(slotId, result.snapshotId(), packed, fingerprint, modsHash);
            Logger.LOG.info("Uploaded cloud archive " + result.snapshotId()
                    + " for " + version.getId());
            return Result.of(Outcome.UPLOADED, result.snapshotId());
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /// Downloads and installs one snapshot.
    ///
    /// Only the supported manifest allowlist and plain `zip` are restorable; any
    /// other token is rejected before a local file is touched.
    ///
    /// @param snapshot snapshot to restore
    /// @param slotId   archive slot id
    /// @return the restore result
    /// @throws IOException when the download or restore fails
    private Result restore(Snapshot snapshot, String slotId) throws IOException {
        pruneStagingDirectories();
        String format = snapshot.format();
        if ("zip".equalsIgnoreCase(format)) {
            return restoreZip(snapshot, slotId);
        }
        if (!isSupportedManifestFormat(format)) {
            throw unsupportedFormat(format);
        }
        return restoreManifest(snapshot, slotId);
    }

    /// Downloads and installs one zip snapshot.
    ///
    /// @param snapshot snapshot to restore
    /// @param slotId   archive slot id
    /// @return the restore result
    /// @throws IOException when the download or restore fails
    private Result restoreZip(Snapshot snapshot, String slotId) throws IOException {
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

    /// Restores a manifest snapshot by materializing every referenced blob.
    ///
    /// Files already present locally with the recorded size and hash are copied
    /// into the staging tree instead of being downloaded, so the installer
    /// always sees a complete tree and never deletes identical local data.
    ///
    /// @param snapshot snapshot to restore
    /// @param slotId   archive slot id
    /// @return the restore result
    /// @throws IOException when a blob is missing or the install fails
    private Result restoreManifest(Snapshot snapshot, String slotId) throws IOException {
        RemoteManifest remote = client.manifest(slotId, snapshot.id());
        String format = remote.format().isBlank() ? snapshot.format() : remote.format();
        if (!isSupportedManifestFormat(format)) {
            throw unsupportedFormat(format);
        }
        validateRemoteManifest(remote);
        if (snapshot.fileCount() > 0 && remote.files().size() != snapshot.fileCount()) {
            throw new IOException("cloud manifest file count does not match the snapshot");
        }
        if (!remote.snapshotId().isBlank() && !remote.snapshotId().equals(snapshot.id())) {
            throw new IOException("cloud manifest belongs to a different snapshot");
        }
        if (snapshot.modCount() > 0 && snapshot.mods().isEmpty()) {
            Logger.LOG.warning("Cloud archive snapshot " + snapshot.id() + " reports "
                    + snapshot.modCount() + " Mods without Mod details; the restore cannot"
                    + " verify them");
        }
        Path staging = dataDir.resolve(".xenon-cloud-staging-" + System.nanoTime());
        try {
            Files.createDirectories(staging);
            List<FileEntry> expected = new ArrayList<>(remote.files().size());
            Map<String, List<String>> missingByHash = new LinkedHashMap<>();
            for (ManifestEntry entry : remote.files()) {
                expected.add(new FileEntry(entry.path(), entry.size(), entry.sha256()));
                Path local = dataDir.resolve(entry.path());
                if (matchesLocal(local, entry)) {
                    copyIntoStaging(local, staging.resolve(entry.path()));
                    continue;
                }
                missingByHash.computeIfAbsent(entry.sha256(), key -> new ArrayList<>())
                        .add(entry.path());
            }
            downloadBlobs(slotId, snapshot.id(), missingByHash, staging);
            List<ModEntry> mods = new ArrayList<>(snapshot.mods().size());
            for (ModInfo mod : snapshot.mods()) {
                mods.add(new ModEntry(mod.id(), mod.name(), mod.version(), mod.sha256(),
                        false, "", true));
            }
            MindustryCloudArchiveInstaller.RestoreResult restored =
                    MindustryCloudManifestInstaller.install(staging, dataDir,
                            dataDir.resolve(".xenon-backups").resolve("cloud"), expected, mods);
            List<String> unresolved = new MindustryModFillService().fill(
                    dataDir, restored.missingMods(), Metadata.getCachesDirectory(), null)
                    .unresolved();
            saveBaseline(slotId, snapshot.id(), null);
            Logger.LOG.info("Restored cloud archive manifest " + snapshot.id() + " for "
                    + version.getId() + " (" + expected.size() + " files)");
            return new Result(Outcome.RESTORED, unresolved, snapshot.id());
        } finally {
            deleteTree(staging);
        }
    }

    /// Validates a remote manifest before any local file is created.
    ///
    /// Rejects empty manifests, more entries than the zip installer allows, an
    /// accumulated size above its total bound, unsafe or duplicate paths, and
    /// entries without a hash.
    ///
    /// @param remote remote manifest to validate
    /// @throws IOException when the manifest cannot be restored safely
    private static void validateRemoteManifest(RemoteManifest remote) throws IOException {
        if (remote.files().isEmpty()) {
            throw new IOException("cloud manifest has no files");
        }
        if (remote.files().size() > MindustryCloudArchiveInstaller.MAX_ENTRIES) {
            throw new IOException("cloud manifest has too many entries");
        }
        Set<String> paths = new LinkedHashSet<>();
        long totalBytes = 0;
        for (ManifestEntry entry : remote.files()) {
            if (!MindustryCloudManifestPlan.isRestorablePath(entry.path())) {
                throw new IOException("cloud manifest entry is not restorable: "
                        + entry.path());
            }
            if (!paths.add(entry.path())) {
                throw new IOException("cloud manifest has a duplicate path: " + entry.path());
            }
            if (entry.sha256().isBlank()) {
                throw new IOException("cloud manifest entry has no hash: " + entry.path());
            }
            totalBytes += Math.max(0L, entry.size());
            if (totalBytes > MindustryCloudArchiveInstaller.MAX_TOTAL_BYTES) {
                throw new IOException("cloud manifest is too large");
            }
        }
    }

    /// Downloads the missing manifest blobs into the staging tree.
    ///
    /// @param slotId        archive slot id
    /// @param snapshotId    snapshot id
    /// @param missingByHash blob hash to target paths
    /// @param staging       staging directory
    /// @throws IOException when a URL is missing or a transfer fails
    private void downloadBlobs(String slotId, String snapshotId,
                               Map<String, List<String>> missingByHash, Path staging)
            throws IOException {
        if (missingByHash.isEmpty()) return;
        List<String> hashes = new ArrayList<>(missingByHash.keySet());
        Map<String, String> urls = new LinkedHashMap<>();
        for (BlobUrl blob : client.blobDownloadUrls(slotId, snapshotId, hashes)) {
            if (!blob.sha256().isBlank() && !blob.url().isBlank()) {
                urls.putIfAbsent(blob.sha256(), blob.url());
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(MAX_BLOB_WORKERS, hashes.size()));
        try {
            List<Future<?>> futures = new ArrayList<>(hashes.size());
            for (String hash : hashes) {
                @Nullable String candidateUrl = urls.get(hash);
                @Nullable List<String> paths = missingByHash.get(hash);
                if (candidateUrl == null || candidateUrl.isBlank()
                        || paths == null || paths.isEmpty()) {
                    throw new IOException("cloud save blob download URL is missing");
                }
                String url = candidateUrl;
                Path target = staging.resolve(paths.get(0));
                futures.add(pool.submit(() -> {
                    Path parent = target.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    client.downloadBlob(url, target, hash, null);
                    return null;
                }));
            }
            for (Future<?> future : futures) awaitTransfer(future);
        } finally {
            shutdownPool(pool);
        }
        copySharedBlobs(missingByHash, staging);
    }

    /// Copies blobs that several manifest paths share to the remaining paths.
    ///
    /// @param missingByHash blob hash to target paths
    /// @param staging       staging directory holding the downloaded blob
    /// @throws IOException when a copy fails
    private static void copySharedBlobs(Map<String, List<String>> missingByHash, Path staging)
            throws IOException {
        for (List<String> paths : missingByHash.values()) {
            Path source = staging.resolve(paths.get(0));
            for (int i = 1; i < paths.size(); i++) {
                Path target = staging.resolve(paths.get(i));
                if (source.equals(target)) continue;
                copyIntoStaging(source, target);
            }
        }
    }

    /// Whether the local file already matches a manifest entry.
    ///
    /// @param local local candidate
    /// @param entry manifest entry
    /// @return `true` when the size and hash match
    /// @throws IOException when the local file cannot be read
    private static boolean matchesLocal(Path local, ManifestEntry entry) throws IOException {
        if (!Files.isRegularFile(local)) return false;
        if (entry.size() > 0 && Files.size(local) != entry.size()) return false;
        if (entry.sha256().isBlank()) return false;
        return entry.sha256().equalsIgnoreCase(sha256(local));
    }

    /// Copies one local file into the staging tree.
    ///
    /// @param source source file
    /// @param target staged destination
    /// @throws IOException when the copy fails
    private static void copyIntoStaging(Path source, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /// Computes the lowercase hex SHA-256 of one file.
    ///
    /// @param file readable file
    /// @return the digest
    /// @throws IOException when the file cannot be read or SHA-256 is missing
    private static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 is not available", e);
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    /// Deletes a staging tree, logging instead of masking an earlier failure.
    ///
    /// @param root tree to delete
    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException | RuntimeException e) {
            Logger.LOG.warning("Failed to delete cloud archive staging directory " + root, e);
        }
    }

    /// Waits for one transfer worker and rethrows its failure unwrapped.
    ///
    /// @param future worker future
    /// @throws IOException when the worker failed or the wait was interrupted
    private static void awaitTransfer(Future<?> future) throws IOException {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while transferring cloud archive blobs", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IOException("Cloud archive blob transfer failed", cause);
        }
    }

    /// Whether a blob failure is a server rate limit.
    ///
    /// @param error failure to inspect
    /// @return `true` for HTTP 429 or the `RATE_LIMITED` code
    private static boolean isRateLimited(MdtbbsApiException error) {
        return error.getStatusCode() == 429 || "RATE_LIMITED".equals(error.getErrorCode());
    }

    /// Whether a rejected manifest upload may be retried as a zip archive.
    ///
    /// @param error failure raised by the manifest flow
    /// @return `true` for a manifest/blob validation failure
    private static boolean isManifestFallback(MdtbbsApiException error) {
        @Nullable String code = error.getErrorCode();
        return "SAVE_MANIFEST_INVALID".equals(code)
                || "SAVE_MANIFEST_LIMIT_EXCEEDED".equals(code)
                || "SAVE_BLOB_MISSING".equals(code)
                || "SAVE_QUOTA_EXCEEDED".equals(code);
    }

    /// Builds the typed failure for a snapshot this build cannot restore.
    ///
    /// @param format snapshot format token
    /// @return the 409 failure
    private static MdtbbsApiException unsupportedFormat(String format) {
        String label = format.isBlank() ? "unknown" : format;
        return new MdtbbsApiException(409, "SAVE_SNAPSHOT_FORMAT_MISMATCH",
                "snapshot format " + label + " is not supported by this Xenon version",
                false);
    }

    /// Sleeps before retrying a blob, preserving interrupts.
    ///
    /// The server value is clamped to [MAX_RETRY_SECONDS].
    ///
    /// @param seconds delay in seconds
    /// @throws IOException when the sleep is interrupted
    private static void sleepRetry(long seconds) throws IOException {
        long delay = Math.min(MAX_RETRY_SECONDS, Math.max(1L, seconds));
        try {
            Thread.sleep(delay * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while retrying a cloud archive blob", e);
        }
    }

    /// Stops a worker pool and waits for its workers to finish.
    ///
    /// The caller thread is interrupted when the pool does not drain within
    /// [POOL_SHUTDOWN_SECONDS], so a staging delete cannot race a live writer.
    ///
    /// @param pool pool to stop
    private static void shutdownPool(ExecutorService pool) {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(POOL_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /// Removes staging directories left behind by an interrupted operation.
    ///
    /// Best effort only: a failed prune is logged and never blocks the new
    /// upload or restore.
    private void pruneStagingDirectories() {
        if (!Files.isDirectory(dataDir)) return;
        long cutoff = System.currentTimeMillis() - STAGING_PRUNE_AGE_MILLIS;
        try (Stream<Path> stream = Files.list(dataDir)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (!name.startsWith(".xenon-cloud-staging-") || !Files.isDirectory(path)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(path).toMillis() > cutoff) continue;
                } catch (IOException e) {
                    continue;
                }
                Logger.LOG.info("Pruning stale cloud archive staging directory " + path);
                deleteTree(path);
            }
        } catch (IOException | RuntimeException e) {
            Logger.LOG.info("Cannot prune cloud archive staging directories: " + e.getMessage());
        }
    }

    /// Writes the baseline after a successful sync with fresh local state.
    private void saveBaseline(String slotId, String snapshotId,
                              @Nullable MindustryCloudArchivePacker.Result packed)
            throws IOException {
        saveBaseline(slotId, snapshotId, packed,
                MindustryCloudSyncService.fingerprint(dataDir),
                MindustryCloudSyncService.modsHash(dataDir));
    }

    /// Writes the baseline with fingerprints captured by the caller.
    private void saveBaseline(String slotId, String snapshotId,
                              @Nullable MindustryCloudArchivePacker.Result packed,
                              String fingerprint, String modsHash)
            throws IOException {
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
    ///
    /// A head id that is not present in the snapshot list yields a minimal
    /// snapshot with an unknown (empty) format, so callers never silently drop
    /// the base head and never guess `zip`.
    private @Nullable Snapshot latest(String slotId) throws IOException {
        Slot slot = client.slot(slotId);
        List<Snapshot> snapshots = client.snapshots(slotId);
        String head = slot.currentSnapshotId();
        if (!head.isBlank()) {
            for (Snapshot snapshot : snapshots) {
                if (head.equals(snapshot.id())) {
                    return snapshot;
                }
            }
            return new Snapshot(head, 0, "", "", false, Instant.EPOCH, "", 0, 0, 0,
                    "", 0, List.of());
        }
        if (snapshots.isEmpty()) return null;
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
