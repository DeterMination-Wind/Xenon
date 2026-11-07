/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package determination.xenon.mindustry.community;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import determination.xenon.mindustry.download.ProgressCallback;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static determination.xenon.util.logging.Logger.LOG;

/// Reader and writer for the MDTBBS cloud-save surface (`/api/v1/game-saves`).
///
/// Uploads use the documented three-step session (create, raw PUT, commit) and
/// downloads verify the announced SHA-256 before the caller replaces a local
/// save. Authenticated API calls carry the bearer token; media fetched from a
/// signed cross-origin URL is requested without it.
///
/// Delta snapshots add a content-addressed blob layer: [`#checkBlobs`] splits
/// hashes into present/missing, [`#createBlob`] plus [`#uploadBlobBytes`] store
/// the missing ones, and [`#createManifestSnapshot`] commits the file list.
@NotNullByDefault
public final class MdtbbsCloudSavesClient {
    /// REST transport shared with the account coordinator.
    private final MdtbbsApiClient api;

    /// HTTP client for cross-origin blob PUTs that must not carry credentials.
    private static final HttpClient UNAUTHENTICATED_HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault())
            .build();

    /// Creates a cloud-save client over the given transport.
    ///
    /// @param api API transport carrying the bearer token
    public MdtbbsCloudSavesClient(MdtbbsApiClient api) {
        this.api = api;
    }

    /// One cloud save slot.
    ///
    /// @param id         slot id
    /// @param name       display name
    /// @param updatedAt  last update time
    /// @param size       current snapshot size in bytes, or 0 when unknown
    /// @param revision   latest snapshot revision, or 0 when the slot has no snapshot
    /// @param currentSnapshotId current head snapshot id, or empty
    public record Slot(String id, String name, Instant updatedAt, long size, long revision,
                       String currentSnapshotId) {

        /// Builds a slot without a known head id.
        ///
        /// @param id        slot id
        /// @param name      display name
        /// @param updatedAt last update time
        /// @param size      current snapshot size
        /// @param revision  latest snapshot revision
        public Slot(String id, String name, Instant updatedAt, long size, long revision) {
            this(id, name, updatedAt, size, revision, "");
        }
    }

    /// One immutable snapshot of a slot.
    ///
    /// @param id        snapshot id
    /// @param size      file size in bytes, or 0 when unknown
    /// @param sha256    content hash, or empty
    /// @param fileName  original file name, or empty
    /// @param pinned    whether the snapshot is pinned against retention
    /// @param createdAt creation time
    /// @param mapName   map name recorded at upload, or empty
    /// @param wave      wave recorded at upload, or 0
    /// @param modCount  number of Mods recorded at upload, or 0
    /// @param revision  slot revision of this snapshot, or 0
    /// @param format    payload format, `zip` for a single archive or a manifest format
    /// @param fileCount number of files when [`#format`] is not `zip`, or 0
    /// @param mods      Mods recorded at upload
    public record Snapshot(String id, long size, String sha256, String fileName,
                           boolean pinned, Instant createdAt, String mapName, int wave,
                           int modCount, long revision, String format, int fileCount,
                           List<ModInfo> mods) {

        /// Copies the Mod list and derives [`#modCount`] only when unset.
        public Snapshot {
            mods = List.copyOf(mods);
            if (modCount <= 0 && !mods.isEmpty()) modCount = mods.size();
        }

        /// Builds a snapshot without display metadata.
        ///
        /// @param id        snapshot id
        /// @param size      file size in bytes
        /// @param sha256    content hash
        /// @param fileName  original file name
        /// @param pinned    whether the snapshot is pinned
        /// @param createdAt creation time
        public Snapshot(String id, long size, String sha256, String fileName,
                        boolean pinned, Instant createdAt) {
            this(id, size, sha256, fileName, pinned, createdAt, "", 0, 0, 0,
                    "zip", 0, List.of());
        }

        /// Builds a legacy zip snapshot with display metadata.
        ///
        /// @param id        snapshot id
        /// @param size      file size in bytes
        /// @param sha256    content hash
        /// @param fileName  original file name
        /// @param pinned    whether the snapshot is pinned
        /// @param createdAt creation time
        /// @param mapName   map name recorded at upload
        /// @param wave      wave recorded at upload
        /// @param modCount  number of Mods recorded at upload
        /// @param revision  slot revision of this snapshot
        public Snapshot(String id, long size, String sha256, String fileName,
                        boolean pinned, Instant createdAt, String mapName, int wave,
                        int modCount, long revision) {
            this(id, size, sha256, fileName, pinned, createdAt, mapName, wave, modCount,
                    revision, "zip", 0, List.of());
        }

        /// Whether this snapshot is a manifest payload instead of a single archive.
        ///
        /// A manifest snapshot cannot be restored by downloading one file; it is
        /// assembled from its blobs by the instance-archive flow or the web panel.
        ///
        /// @return `true` when [`#format`] is anything other than `zip`
        public boolean manifest() {
            return !"zip".equalsIgnoreCase(format);
        }
    }

    /// Storage quota of the current user.
    ///
    /// @param usedBytes        bytes currently stored
    /// @param quotaBytes       total quota in bytes, or 0 when unlimited/unknown
    /// @param maxFileBytes     per-file limit in bytes, or 0 when unknown
    /// @param slotLimit        maximum number of slots, or 0 when unknown
    /// @param slotCount        number of slots in use, or 0 when unknown
    /// @param retentionVersions unpinned snapshots kept per slot, or 0 when unknown
    /// @param retentionDays    days an unpinned snapshot is kept, or 0 when unknown
    /// @param logicalBytes     logical bytes charged against the quota, or 0 when unknown
    /// @param formats          snapshot formats the account may create
    /// @param maxManifestFiles maximum files in one manifest snapshot, or 0 when unknown
    /// @param maxManifestBytes maximum total manifest bytes, or 0 when unknown
    public record Quota(long usedBytes, long quotaBytes, long maxFileBytes,
                        long slotLimit, long slotCount, long retentionVersions,
                        long retentionDays, long logicalBytes, List<String> formats,
                        long maxManifestFiles, long maxManifestBytes) {

        /// Copies the supported-format list.
        public Quota {
            formats = List.copyOf(formats);
        }

        /// Builds a quota without slot or retention limits.
        ///
        /// @param usedBytes    bytes currently stored
        /// @param quotaBytes   total quota in bytes
        /// @param maxFileBytes per-file limit in bytes
        public Quota(long usedBytes, long quotaBytes, long maxFileBytes) {
            this(usedBytes, quotaBytes, maxFileBytes, 0, 0, 0, 0,
                    0, List.of(), 0, 0);
        }

        /// Builds a quota with slot and retention limits but no manifest limits.
        ///
        /// @param usedBytes         bytes currently stored
        /// @param quotaBytes        total quota in bytes
        /// @param maxFileBytes      per-file limit in bytes
        /// @param slotLimit         maximum number of slots
        /// @param slotCount         number of slots in use
        /// @param retentionVersions unpinned snapshots kept per slot
        /// @param retentionDays     days an unpinned snapshot is kept
        public Quota(long usedBytes, long quotaBytes, long maxFileBytes,
                     long slotLimit, long slotCount, long retentionVersions,
                     long retentionDays) {
            this(usedBytes, quotaBytes, maxFileBytes, slotLimit, slotCount,
                    retentionVersions, retentionDays, 0, List.of(), 0, 0);
        }
    }

    /// Game version metadata attached to an upload.
    ///
    /// @param version game version such as `v157`, or empty
    /// @param build   game build number, or 0
    public record GameInfo(String version, int build) {
    }

    /// Save display metadata attached to an upload.
    ///
    /// @param mapName         map name, or empty
    /// @param wave            current wave, or 0
    /// @param playtimeSeconds accumulated playtime, or 0
    public record SaveInfo(String mapName, int wave, long playtimeSeconds) {
    }

    /// One Mod attached to an upload.
    ///
    /// @param id      stable Mod id (`internalName`)
    /// @param name    display name
    /// @param version Mod version
    /// @param sha256  Mod archive SHA-256, or empty
    public record ModInfo(String id, String name, String version, String sha256) {
    }

    /// Optional metadata attached to an upload.
    ///
    /// @param game     game version, or `null`
    /// @param save     save display metadata, or `null`
    /// @param mods     Mods used by the save, at most 100
    /// @param deviceId opaque device id, or empty
    public record UploadMetadata(@Nullable GameInfo game, @Nullable SaveInfo save,
                                 List<ModInfo> mods, String deviceId) {
        /// Stores at most 100 Mods.
        public UploadMetadata {
            mods = List.copyOf(mods.size() > 100 ? mods.subList(0, 100) : mods);
        }
    }

    /// Conflict and metadata options for one upload.
    ///
    /// @param reason                 documented reason token
    /// @param baseSnapshotId         expected cloud head, or `null`
    /// @param conflictResolution     `normal`, `create_conflict_copy` or `force_replace_head`
    /// @param confirmCurrentSnapshotId explicit head confirmation for a forced replace
    /// @param metadata               optional display metadata
    public record UploadOptions(String reason, @Nullable String baseSnapshotId,
                                String conflictResolution,
                                @Nullable String confirmCurrentSnapshotId,
                                @Nullable UploadMetadata metadata) {

        /// Builds a normal upload based on an optional head.
        ///
        /// @param baseSnapshotId expected cloud head, or `null`
        /// @return the options
        public static UploadOptions normal(@Nullable String baseSnapshotId) {
            return new UploadOptions("manual", baseSnapshotId, "normal", null, null);
        }

        /// Returns a copy that retries with a different conflict strategy.
        ///
        /// @param resolution strategy to use
        /// @param confirmedHead head id confirmed by the user, or `null`
        /// @return the new options
        public UploadOptions withConflict(String resolution, @Nullable String confirmedHead) {
            return new UploadOptions(reason, baseSnapshotId, resolution, confirmedHead, metadata);
        }

        /// Returns a copy with display metadata.
        ///
        /// @param value metadata to attach
        /// @return the new options
        public UploadOptions withMetadata(UploadMetadata value) {
            return new UploadOptions(reason, baseSnapshotId, conflictResolution,
                    confirmCurrentSnapshotId, value);
        }
    }

    /// Result of an upload.
    ///
    /// @param snapshotId      committed snapshot id, or empty
    /// @param noUploadRequired whether the server already held identical bytes
    public record UploadResult(String snapshotId, boolean noUploadRequired) {
    }

    /// Result of a snapshot download.
    ///
    /// @param path         verified local file
    /// @param etag         response `ETag`, or empty
    /// @param lastModified response `Last-Modified`, or empty
    /// @param size         bytes written
    public record DownloadResult(Path path, String etag, String lastModified, long size) {
    }

    /// One file of a manifest snapshot.
    ///
    /// @param path   slash-separated path inside the snapshot
    /// @param size   file size in bytes
    /// @param sha256 content hash of the file
    public record ManifestEntry(String path, long size, String sha256) {
    }

    /// Which content-addressed blobs already exist on the server.
    ///
    /// @param existing hashes the server already holds
    /// @param missing  hashes the caller must upload
    public record BlobCheck(List<String> existing, List<String> missing) {

        /// Copies both hash lists.
        public BlobCheck {
            existing = List.copyOf(existing);
            missing = List.copyOf(missing);
        }
    }

    /// A blob upload session returned before the raw PUT.
    ///
    /// @param uploadId         upload session id, or the stored snapshot id when
    ///                         the server already had the blob
    /// @param url              PUT target, or empty when no upload is required
    /// @param noUploadRequired whether the server already holds the blob
    public record BlobUpload(String uploadId, String url, boolean noUploadRequired) {
    }

    /// Result of committing a manifest snapshot.
    ///
    /// @param snapshotId committed snapshot id, or empty when the response omitted it
    /// @param format     payload format recorded by the server
    /// @param fileCount  number of manifest files recorded by the server
    public record ManifestSnapshot(String snapshotId, String format, int fileCount) {
    }

    /// A remote manifest payload.
    ///
    /// @param snapshotId id of the snapshot the manifest belongs to
    /// @param format     payload format
    /// @param files      manifest entries in server order
    public record RemoteManifest(String snapshotId, String format, List<ManifestEntry> files) {

        /// Copies the entry list.
        public RemoteManifest {
            files = List.copyOf(files);
        }
    }

    /// One signed download URL for a manifest blob.
    ///
    /// @param sha256 content hash the URL serves
    /// @param url    absolute or API-relative download URL
    /// @param size   blob size in bytes, or 0 when unknown
    public record BlobUrl(String sha256, String url, long size) {
    }

    /// One page of slots.
    ///
    /// @param slots      page entries
    /// @param nextCursor opaque cursor for the next page, or empty
    /// @param hasMore    whether another page exists
    public record SlotPage(List<Slot> slots, String nextCursor, boolean hasMore) {
        public SlotPage {
            slots = List.copyOf(slots);
        }
    }

    /// Lists cloud save slots.
    ///
    /// @param cursor opaque cursor, or `null` for the first page
    /// @param limit  page size, clamped to 1..50
    /// @return one page of slots
    /// @throws IOException when the request fails
    public SlotPage slots(@Nullable String cursor, int limit) throws IOException {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("limit", Integer.toString(Math.max(1, Math.min(50, limit))));
        if (cursor != null && !cursor.isBlank()) parameters.put("cursor", cursor);
        JsonObject root = api.getAuth("/game-saves", parameters);
        List<Slot> slots = new ArrayList<>();
        for (JsonElement element : MdtbbsJson.dataArray(root)) {
            JsonObject object = MdtbbsJson.asObject(element);
            if (object != null) slots.add(parseSlot(object));
        }
        JsonObject meta = MdtbbsJson.objectOf(root, "meta");
        String nextCursor = meta == null ? "" : MdtbbsJson.stringOf(meta, "next_cursor");
        // The cursor list no longer reports `has_more`; a non-blank cursor means another page.
        boolean hasMore = !nextCursor.isBlank();
        return new SlotPage(slots, nextCursor, hasMore);
    }

    /// Loads the storage quota.
    ///
    /// Missing fields fall back to `0`/empty so an older server never breaks the
    /// dialog; this method only throws when the request itself fails.
    ///
    /// @return the quota; zero fields mean unknown
    /// @throws IOException when the request fails
    public Quota quota() throws IOException {
        JsonObject data = MdtbbsJson.dataObject(api.getAuth("/game-saves/quota", null));
        JsonObject slots = MdtbbsJson.objectOf(data, "slots");
        JsonObject retention = MdtbbsJson.objectOf(data, "retention");
        JsonObject manifestLimits = MdtbbsJson.objectOf(data, "manifest_limits");
        return new Quota(
                MdtbbsJson.longOf(data, "used_bytes",
                        MdtbbsJson.longOf(data, "used", 0)),
                MdtbbsJson.longOf(data, "quota_bytes",
                        MdtbbsJson.longOf(data, "limit_bytes",
                                MdtbbsJson.longOf(data, "quota", 0))),
                MdtbbsJson.longOf(data, "max_file_bytes",
                        MdtbbsJson.longOf(data, "max_file_size_bytes",
                                MdtbbsJson.longOf(data, "max_file_size", 0))),
                slots == null ? 0 : MdtbbsJson.longOf(slots, "limit", 0),
                slots == null ? 0 : MdtbbsJson.longOf(slots, "used", 0),
                retention == null ? 0
                        : MdtbbsJson.longOf(retention, "max_unpinned_versions_per_slot", 0),
                retention == null ? 0
                        : MdtbbsJson.longOf(retention, "max_unpinned_age_days", 0),
                MdtbbsJson.longOf(data, "logical_bytes", 0),
                stringList(data, "formats"),
                manifestLimits == null ? 0
                        : MdtbbsJson.longOf(manifestLimits, "max_files",
                                MdtbbsJson.longOf(manifestLimits, "max_manifest_files", 0)),
                manifestLimits == null ? 0
                        : MdtbbsJson.longOf(manifestLimits, "max_manifest_bytes",
                                MdtbbsJson.longOf(manifestLimits, "max_bytes", 0)));
    }

    /// Reads one slot.
    ///
    /// @param slotId slot id
    /// @return the slot
    /// @throws IOException when the request fails
    public Slot slot(String slotId) throws IOException {
        JsonObject data = MdtbbsJson.dataObject(api.getAuth("/game-saves/" + slotId, null));
        JsonObject slot = MdtbbsJson.objectOf(data, "slot");
        return parseSlot(slot == null ? data : slot);
    }

    /// Renames a slot.
    ///
    /// @param slotId slot id
    /// @param name   new display name
    /// @throws IOException when the request fails
    public void renameSlot(String slotId, String name) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        api.patch("/game-saves/" + slotId, body);
    }

    /// Creates a slot.
    ///
    /// @param name display name
    /// @return the created slot id, or -1 when the response omitted it
    /// @throws IOException when the request fails
    public String createSlot(String name) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        JsonObject data = MdtbbsJson.dataObject(api.post("/game-saves", body));
        String id = MdtbbsJson.stringOf(data, "id");
        return id.isBlank() ? MdtbbsJson.stringOf(data, "slot_id") : id;
    }

    /// Deletes a slot without touching any local save.
    ///
    /// @param slotId slot id
    /// @throws IOException when the request fails
    public void deleteSlot(String slotId) throws IOException {
        api.delete("/game-saves/" + slotId, null);
    }

    /// Lists the retained snapshots of a slot.
    ///
    /// @param slotId slot id
    /// @return snapshots, newest first when the server orders them
    /// @throws IOException when the request fails
    public List<Snapshot> snapshots(String slotId) throws IOException {
        JsonObject root = api.getAuth("/game-saves/" + slotId + "/snapshots", null);
        List<Snapshot> snapshots = new ArrayList<>();
        for (JsonElement element : MdtbbsJson.dataArray(root)) {
            JsonObject object = MdtbbsJson.asObject(element);
            if (object != null) snapshots.add(parseSnapshot(object));
        }
        return snapshots;
    }

    /// Restores a snapshot as a new revision of the slot.
    ///
    /// @param slotId     slot id
    /// @param snapshotId snapshot id
    /// @throws IOException when the request fails
    public void restore(String slotId, String snapshotId) throws IOException {
        restore(slotId, snapshotId, null);
    }

    /// Restores a snapshot and pins the expected current head.
    ///
    /// @param slotId            slot id
    /// @param snapshotId        snapshot id
    /// @param confirmCurrentId  expected current head, or `null` to skip the check
    /// @throws IOException when the request fails
    public void restore(String slotId, String snapshotId, @Nullable String confirmCurrentId)
            throws IOException {
        JsonObject body = new JsonObject();
        if (confirmCurrentId != null && !confirmCurrentId.isBlank()) {
            body.addProperty("confirm_current_snapshot_id", confirmCurrentId);
        }
        api.post("/game-saves/" + slotId + "/snapshots/" + snapshotId + "/restore", body);
    }

    /// Pins or unpins a snapshot.
    ///
    /// @param slotId     slot id
    /// @param snapshotId snapshot id
    /// @param pinned     desired state
    /// @throws IOException when the request fails
    public void setPinned(String slotId, String snapshotId, boolean pinned) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("pinned", pinned);
        api.patch("/game-saves/" + slotId + "/snapshots/" + snapshotId, body);
    }

    /// Deletes a snapshot.
    ///
    /// @param slotId     slot id
    /// @param snapshotId snapshot id
    /// @throws IOException when the request fails
    public void deleteSnapshot(String slotId, String snapshotId) throws IOException {
        api.delete("/game-saves/" + slotId + "/snapshots/" + snapshotId, null);
    }

    /// Uploads a local save as a new immutable snapshot.
    ///
    /// @param slotId         slot id
    /// @param saveFile       local `.msav` file
    /// @param baseSnapshotId expected cloud head, or `null` to accept any
    /// @return the committed snapshot id, or empty when the response omitted it
    /// @throws IOException when hashing, uploading or committing fails
    public String upload(String slotId, Path saveFile,
                         @Nullable String baseSnapshotId) throws IOException {
        return upload(slotId, saveFile, baseSnapshotId, null);
    }

    /// Uploads a local save and reports how many bytes the PUT delivered.
    ///
    /// The server creates an upload session before the raw PUT and expects a
    /// commit afterwards. When the PUT or the commit fails, the abandoned
    /// session is handed back with a best-effort DELETE so it cannot linger on
    /// the server; the cleanup never replaces the original failure.
    ///
    /// @param slotId         slot id
    /// @param saveFile       local `.msav` file
    /// @param baseSnapshotId expected cloud head, or `null` to accept any
    /// @param progress       optional receiver of cumulative uploaded bytes
    /// @return the committed snapshot id, or empty when the response omitted it
    /// @throws IOException when hashing, uploading or committing fails
    public String upload(String slotId, Path saveFile,
                         @Nullable String baseSnapshotId,
                         @Nullable ProgressCallback progress) throws IOException {
        return uploadDetailed(slotId, saveFile, UploadOptions.normal(baseSnapshotId), progress)
                .snapshotId();
    }

    /// Uploads a local save with conflict and metadata options.
    ///
    /// The server creates an upload session before the raw PUT and expects a
    /// commit afterwards. A response with `no_upload_required` already names a
    /// committed snapshot, so the PUT and commit are skipped. When the PUT or
    /// the commit fails, the abandoned session is handed back with a best-effort
    /// DELETE so it cannot linger on the server; the cleanup never replaces the
    /// original failure.
    ///
    /// @param slotId   slot id
    /// @param saveFile local `.msav` file
    /// @param options  conflict strategy, base head and display metadata
    /// @param progress optional receiver of cumulative uploaded bytes
    /// @return the committed snapshot and whether bytes were skipped
    /// @throws IOException when hashing, uploading or committing fails
    public UploadResult uploadDetailed(String slotId, Path saveFile, UploadOptions options,
                                       @Nullable ProgressCallback progress) throws IOException {
        long size = Files.size(saveFile);
        String sha256 = sha256Hex(saveFile);

        JsonObject create = new JsonObject();
        create.addProperty("sha256", sha256);
        create.addProperty("size", size);
        create.addProperty("reason", options.reason());
        create.addProperty("conflict_resolution", options.conflictResolution());
        if (options.baseSnapshotId() != null && !options.baseSnapshotId().isBlank()) {
            create.addProperty("base_snapshot_id", options.baseSnapshotId());
        }
        if (options.confirmCurrentSnapshotId() != null
                && !options.confirmCurrentSnapshotId().isBlank()) {
            create.addProperty("confirm_current_snapshot_id",
                    options.confirmCurrentSnapshotId());
        }
        UploadMetadata metadata = options.metadata();
        applyMetadata(create, metadata);
        JsonObject created = MdtbbsJson.dataObject(
                api.post("/game-saves/" + slotId + "/uploads", create));
        String uploadId = MdtbbsJson.stringOf(created, "upload_id");
        if (uploadId.isBlank()) {
            throw new IOException("Cloud save upload session is incomplete");
        }
        if (MdtbbsJson.boolOf(created, "no_upload_required", false)) {
            return new UploadResult(MdtbbsJson.stringOf(created, "snapshot_id"), true);
        }

        try {
            JsonObject upload = MdtbbsJson.objectOf(created, "upload");
            String uploadUrl = upload == null ? "" : MdtbbsJson.stringOf(upload, "url");
            if (uploadUrl.isBlank()) {
                throw new IOException("Cloud save upload session is incomplete");
            }

            String resolved = api.resolveUrl(uploadUrl);
            if (sameApiOrigin(resolved)) {
                api.putFile(resolved, saveFile, "application/octet-stream", progress);
            } else {
                putFileCrossOrigin(resolved, saveFile, progress);
            }
            JsonObject committed = MdtbbsJson.dataObject(
                    api.post("/game-saves/uploads/" + uploadId + "/commit", new JsonObject()));
            String snapshotId = MdtbbsJson.stringOf(committed, "snapshot_id");
            if (snapshotId.isBlank()) snapshotId = MdtbbsJson.stringOf(committed, "id");
            return new UploadResult(snapshotId, false);
        } catch (IOException | RuntimeException failure) {
            releaseUpload(uploadId);
            throw failure;
        }
    }

    /// Best-effort DELETE of an upload session that will never be committed.
    ///
    /// Failure is logged and swallowed: the caller rethrows the original upload
    /// error, which is the one the user needs to see.
    ///
    /// @param uploadId abandoned upload session id
    private void releaseUpload(String uploadId) {
        try {
            api.delete("/game-saves/uploads/" + uploadId, null);
            LOG.info("Released abandoned cloud save upload session " + uploadId);
        } catch (IOException | RuntimeException cleanupFailure) {
            LOG.warning("Failed to release abandoned cloud save upload session " + uploadId,
                    cleanupFailure);
        }
    }

    // ------------------------------------------------------------------
    // Delta manifest and blob API
    // ------------------------------------------------------------------

    /// Asks the server which content-addressed blobs it already holds.
    ///
    /// An empty input short-circuits without a request, so the server never
    /// receives an empty `sha256` array.
    ///
    /// @param sha256s hashes to look up
    /// @return the split between present and missing hashes
    /// @throws IOException when the request fails
    public BlobCheck checkBlobs(Collection<String> sha256s) throws IOException {
        if (sha256s.isEmpty()) return new BlobCheck(List.of(), List.of());
        JsonObject body = new JsonObject();
        body.add("sha256", sha256Array(sha256s));
        JsonObject data = MdtbbsJson.dataObject(
                api.post("/game-saves/blobs/check", body));
        List<String> existing = stringList(data, "existing");
        if (existing.isEmpty()) existing = stringList(data, "exists");
        return new BlobCheck(existing, stringList(data, "missing"));
    }

    /// Registers a blob before its raw PUT.
    ///
    /// The 1.8 contract has no commit step: a successful PUT finalises the
    /// blob. When that PUT fails, [`#uploadBlobBytes`] sends a best-effort
    /// `DELETE /game-saves/blobs/uploads/{id}` so the abandoned session cannot
    /// linger; the cleanup is logged and never replaces the upload error.
    ///
    /// A response with `no_upload_required` already names the stored object, so
    /// the caller must skip the PUT entirely.
    ///
    /// @param sha256 hex SHA-256 of the blob
    /// @param size   blob size in bytes
    /// @return the upload session, or a session flagged as already present
    /// @throws IOException when the request fails or a required field is missing
    public BlobUpload createBlob(String sha256, long size) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("sha256", sha256);
        body.addProperty("size", size);
        JsonObject data = MdtbbsJson.dataObject(api.post("/game-saves/blobs", body));
        boolean noUploadRequired = MdtbbsJson.boolOf(data, "no_upload_required", false);
        JsonObject upload = MdtbbsJson.objectOf(data, "upload");
        String url = upload == null ? "" : MdtbbsJson.stringOf(upload, "url");
        String uploadId = MdtbbsJson.stringOf(data, "upload_id");
        if (uploadId.isBlank()) {
            // An already-stored blob may only name the snapshot it produced.
            uploadId = MdtbbsJson.stringOf(data, "snapshot_id");
        }
        if (!noUploadRequired && url.isBlank()) {
            throw new IOException("Cloud save blob upload session is incomplete");
        }
        return new BlobUpload(uploadId, url, noUploadRequired);
    }

    /// Uploads blob bytes to the URL the server returned.
    ///
    /// The URL is resolved first. The bearer token is only attached for the API
    /// origin; a cross-origin signed URL is written by a local unauthenticated
    /// client so the credentials cannot leak. `MdtbbsApiClient` exposes no
    /// unauthenticated PUT, so the anonymous request mirrors the raw-GET client.
    ///
    /// The PUT finalises the blob; there is no commit call. When it fails, the
    /// session is released with a best-effort DELETE that never replaces the
    /// original error.
    ///
    /// @param blob     session created by [`#createBlob`]
    /// @param file     local blob file
    /// @param progress optional receiver of cumulative uploaded bytes
    /// @throws IOException when the upload fails
    public void uploadBlobBytes(BlobUpload blob, Path file,
                                @Nullable ProgressCallback progress) throws IOException {
        if (blob.noUploadRequired()) return;
        if (blob.url().isBlank()) {
            throw new IOException("Cloud save blob upload URL is missing");
        }
        String resolved = api.resolveUrl(blob.url());
        try {
            if (sameApiOrigin(resolved)) {
                api.putFile(resolved, file, "application/octet-stream", progress);
            } else {
                putFileCrossOrigin(resolved, file, progress);
            }
        } catch (IOException | RuntimeException failure) {
            releaseBlob(blob.uploadId());
            throw failure;
        }
    }

    /// Best-effort DELETE of a blob session whose PUT failed.
    ///
    /// Failure is logged and swallowed: the caller rethrows the original upload
    /// error, which is the one the user needs to see.
    ///
    /// @param uploadId abandoned blob session id, or empty when unknown
    private void releaseBlob(String uploadId) {
        if (uploadId.isBlank()) return;
        try {
            api.delete("/game-saves/blobs/uploads/" + uploadId, null);
            LOG.info("Released abandoned cloud save blob session " + uploadId);
        } catch (IOException | RuntimeException cleanupFailure) {
            LOG.warning("Failed to release abandoned cloud save blob session " + uploadId,
                    cleanupFailure);
        }
    }

    /// Writes a file to a cross-origin signed URL without MDTBBS credentials.
    ///
    /// @param absoluteUrl resolved absolute URL
    /// @param file        local file to upload
    /// @param progress    optional receiver of cumulative uploaded bytes
    /// @throws IOException when the upload fails or the response is an error
    private static void putFileCrossOrigin(String absoluteUrl, Path file,
                                           @Nullable ProgressCallback progress)
            throws IOException {
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofFile(file);
        if (progress != null) body = new CountingBodyPublisher(body, progress);
        HttpRequest request = HttpRequest.newBuilder(URI.create(absoluteUrl))
                .timeout(Duration.ofMinutes(30))
                .header("Content-Type", "application/octet-stream")
                .header("User-Agent", "Xenon-Launcher")
                .PUT(body)
                .build();
        try {
            HttpResponse<String> response = UNAUTHENTICATED_HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw uploadFailure(response.statusCode(), response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted uploading to " + absoluteUrl, e);
        }
    }

    /// Builds an upload failure, keeping the API error code when one is present.
    ///
    /// @param status HTTP status code
    /// @param body   response body
    /// @return the typed API failure, or a generic status failure for non-JSON bodies
    private static IOException uploadFailure(int status, String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject()) {
                JsonObject error = MdtbbsJson.objectOf(root.getAsJsonObject(), "error");
                if (error != null) {
                    @Nullable String code = MdtbbsJson.nullableString(error, "code");
                    String message = MdtbbsJson.stringOf(error, "message");
                    boolean retryable = MdtbbsJson.boolOf(error, "retryable", false);
                    JsonArray details = MdtbbsJson.arrayOf(error, "details");
                    return new MdtbbsApiException(status, code,
                            message.isBlank()
                                    ? "Cloud save blob upload returned HTTP " + status
                                    : message,
                            retryable, details.isEmpty() ? null : details);
                }
            }
        } catch (RuntimeException ignored) {
            // Fall through to the generic status failure.
        }
        return new IOException("Cloud save blob upload returned HTTP " + status);
    }

    /// Commits a manifest snapshot built from already-uploaded blobs.
    ///
    /// The base head comes from `baseSnapshotId` when non-blank, otherwise from
    /// [`UploadOptions#baseSnapshotId`]. Passing two different non-blank heads
    /// is a caller error and is rejected before any request is sent.
    ///
    /// @param slotId         slot id
    /// @param baseSnapshotId expected cloud head, or `null`/blank to use the options' head
    /// @param format         manifest format token
    /// @param files          manifest entries in upload order
    /// @param options        conflict strategy, reason and display metadata
    /// @return the committed snapshot id, format and file count
    /// @throws IllegalArgumentException when the two base heads disagree
    /// @throws IOException when the request fails or the response is unusable
    public ManifestSnapshot createManifestSnapshot(String slotId,
                                                   @Nullable String baseSnapshotId,
                                                   String format,
                                                   List<ManifestEntry> files,
                                                   UploadOptions options) throws IOException {
        JsonObject body = new JsonObject();
        body.add("manifest", encodeManifest(format, files));
        body.addProperty("reason", options.reason());
        body.addProperty("conflict_resolution", options.conflictResolution());
        String resolvedBase = resolveBaseSnapshotId(baseSnapshotId, options.baseSnapshotId());
        if (!resolvedBase.isBlank()) {
            body.addProperty("base_snapshot_id", resolvedBase);
        }
        if (options.confirmCurrentSnapshotId() != null
                && !options.confirmCurrentSnapshotId().isBlank()) {
            body.addProperty("confirm_current_snapshot_id",
                    options.confirmCurrentSnapshotId());
        }
        applyMetadata(body, options.metadata());
        JsonObject data = MdtbbsJson.dataObject(
                api.post("/game-saves/" + slotId + "/snapshots", body));
        JsonObject snapshot = MdtbbsJson.objectOf(data, "snapshot");
        JsonObject source = snapshot == null ? data : snapshot;
        String snapshotId = MdtbbsJson.stringOf(source, "snapshot_id");
        if (snapshotId.isBlank()) snapshotId = MdtbbsJson.stringOf(source, "id");
        String resultFormat = MdtbbsJson.stringOf(source, "format");
        if (resultFormat.isBlank()) resultFormat = format;
        int fileCount = (int) MdtbbsJson.longOf(source, "file_count", files.size());
        return new ManifestSnapshot(snapshotId, resultFormat, fileCount);
    }

    /// Resolves the manifest base head from the explicit parameter and options.
    ///
    /// @param explicit    explicit base head, or `null`
    /// @param fromOptions base head carried by the options, or `null`
    /// @return the resolved non-blank head, or empty when neither was set
    /// @throws IllegalArgumentException when both non-blank values differ
    private static String resolveBaseSnapshotId(@Nullable String explicit,
                                                @Nullable String fromOptions) {
        boolean hasExplicit = explicit != null && !explicit.isBlank();
        boolean hasOptions = fromOptions != null && !fromOptions.isBlank();
        if (hasExplicit && hasOptions && !explicit.equals(fromOptions)) {
            throw new IllegalArgumentException(
                    "base snapshot id differs from the upload options");
        }
        if (hasExplicit) return explicit;
        return hasOptions ? fromOptions : "";
    }

    /// Reads the manifest of a snapshot.
    ///
    /// Both the nested `data.manifest` and a flat `data` payload with `format`
    /// and `files` are accepted.
    ///
    /// @param slotId     slot id
    /// @param snapshotId snapshot id
    /// @return the manifest entries
    /// @throws IOException when the request fails
    public RemoteManifest manifest(String slotId, String snapshotId) throws IOException {
        JsonObject data = MdtbbsJson.dataObject(api.getAuth(
                "/game-saves/" + slotId + "/snapshots/" + snapshotId + "/manifest", null));
        JsonObject manifest = MdtbbsJson.objectOf(data, "manifest");
        JsonObject source = manifest == null ? data : manifest;
        String id = MdtbbsJson.stringOf(source, "snapshot_id");
        if (id.isBlank()) id = MdtbbsJson.stringOf(data, "snapshot_id");
        if (id.isBlank()) id = snapshotId;
        return new RemoteManifest(id,
                MdtbbsJson.stringOf(source, "format"),
                parseManifestEntries(MdtbbsJson.arrayOf(source, "files")));
    }

    /// Requests signed download URLs for manifest blobs.
    ///
    /// An empty input short-circuits without a request. Entries without a hash
    /// or URL are skipped and repeated hashes keep their first URL.
    ///
    /// @param slotId     slot id
    /// @param snapshotId snapshot id
    /// @param sha256s    hashes to fetch
    /// @return one URL per distinct hash the server could serve
    /// @throws IOException when the request fails
    public List<BlobUrl> blobDownloadUrls(String slotId, String snapshotId,
                                          Collection<String> sha256s) throws IOException {
        if (sha256s.isEmpty()) return List.of();
        JsonObject body = new JsonObject();
        body.add("sha256", sha256Array(sha256s));
        JsonObject data = MdtbbsJson.dataObject(api.post(
                "/game-saves/" + slotId + "/snapshots/" + snapshotId + "/blobs/download",
                body));
        JsonArray blobs = MdtbbsJson.arrayOf(data, "blobs");
        if (blobs.size() == 0) blobs = MdtbbsJson.arrayOf(data, "urls");
        List<BlobUrl> urls = new ArrayList<>(blobs.size());
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement element : blobs) {
            JsonObject object = MdtbbsJson.asObject(element);
            if (object == null) continue;
            String sha256 = MdtbbsJson.stringOf(object, "sha256");
            String url = MdtbbsJson.stringOf(object, "url");
            if (sha256.isBlank() || url.isBlank() || !seen.add(sha256)) continue;
            urls.add(new BlobUrl(sha256, url, MdtbbsJson.longOf(object, "size", 0)));
        }
        return List.copyOf(urls);
    }

    /// Downloads one manifest blob and verifies its content hash.
    ///
    /// The URL may be absolute or API-relative; it is resolved against the API
    /// origin before the request. The bearer token is only attached when the
    /// resolved URL belongs to the API origin, so a signed CDN URL never
    /// receives the user's token.
    ///
    /// @param absoluteUrl    absolute or API-relative blob URL
    /// @param target         destination file
    /// @param expectedSha256 expected lowercase hex hash
    /// @param progress       optional progress callback
    /// @throws IOException when the download fails, the hash is blank, or the
    ///         hash does not match
    public void downloadBlob(String absoluteUrl, Path target, String expectedSha256,
                             @Nullable ProgressCallback progress) throws IOException {
        if (expectedSha256.isBlank()) {
            throw new IOException("Cloud save blob hash is missing");
        }
        String resolved = api.resolveUrl(absoluteUrl);
        downloadVerified(resolved, target, expectedSha256, sameApiOrigin(resolved), progress);
    }

    /// Adds the optional `game`/`save`/`mods`/`device_id` metadata block.
    ///
    /// Shared by the single-save upload and the manifest-snapshot commit so both
    /// requests carry exactly the same metadata shape.
    ///
    /// @param body     request body to extend
    /// @param metadata metadata to encode, or `null` to add nothing
    private static void applyMetadata(JsonObject body, @Nullable UploadMetadata metadata) {
        if (metadata == null) return;
        if (metadata.game() != null) {
            JsonObject game = new JsonObject();
            if (!metadata.game().version().isBlank()) {
                game.addProperty("version", metadata.game().version());
            }
            if (metadata.game().build() > 0) {
                game.addProperty("build", metadata.game().build());
            }
            body.add("game", game);
        }
        if (metadata.save() != null) {
            JsonObject save = new JsonObject();
            if (!metadata.save().mapName().isBlank()) {
                save.addProperty("map_name", metadata.save().mapName());
            }
            save.addProperty("wave", Math.max(0, metadata.save().wave()));
            save.addProperty("playtime_seconds",
                    Math.max(0L, metadata.save().playtimeSeconds()));
            body.add("save", save);
        }
        if (!metadata.mods().isEmpty()) {
            JsonArray mods = new JsonArray();
            for (ModInfo mod : metadata.mods()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", mod.id());
                if (!mod.name().isBlank()) entry.addProperty("name", mod.name());
                if (!mod.version().isBlank()) entry.addProperty("version", mod.version());
                if (!mod.sha256().isBlank()) entry.addProperty("sha256", mod.sha256());
                mods.add(entry);
            }
            body.add("mods", mods);
        }
        if (!metadata.deviceId().isBlank()) {
            body.addProperty("device_id", metadata.deviceId());
        }
    }

    /// Encodes a manifest block.
    ///
    /// @param format manifest format token
    /// @param files  entries to encode
    /// @return the `{format, files}` object
    private static JsonObject encodeManifest(String format, List<ManifestEntry> files) {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", format);
        JsonArray entries = new JsonArray();
        for (ManifestEntry file : files) {
            JsonObject entry = new JsonObject();
            entry.addProperty("path", file.path());
            entry.addProperty("size", file.size());
            entry.addProperty("sha256", file.sha256());
            entries.add(entry);
        }
        manifest.add("files", entries);
        return manifest;
    }

    /// Parses a manifest entry array, skipping malformed entries.
    ///
    /// @param array raw `files` array
    /// @return parsed entries in server order
    private static List<ManifestEntry> parseManifestEntries(JsonArray array) {
        List<ManifestEntry> files = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            JsonObject object = MdtbbsJson.asObject(element);
            if (object == null) continue;
            files.add(new ManifestEntry(MdtbbsJson.stringOf(object, "path"),
                    MdtbbsJson.longOf(object, "size", 0),
                    MdtbbsJson.stringOf(object, "sha256")));
        }
        return files;
    }

    /// Builds the `sha256` JSON array of a request body.
    ///
    /// @param sha256s hashes to encode
    /// @return the array in iteration order
    private static JsonArray sha256Array(Collection<String> sha256s) {
        JsonArray array = new JsonArray();
        for (String sha256 : sha256s) array.add(sha256);
        return array;
    }

    /// Reads a string array, skipping non-primitive and null elements.
    ///
    /// Guarantees an immutable result so quota parsing can never throw.
    ///
    /// @param object parent object
    /// @param key    field name
    /// @return the parsed strings, possibly empty
    private static List<String> stringList(JsonObject object, String key) {
        JsonArray array = MdtbbsJson.arrayOf(object, key);
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) continue;
            try {
                values.add(element.getAsString());
            } catch (RuntimeException e) {
                // Ignore malformed entries instead of failing the whole parse.
            }
        }
        return List.copyOf(values);
    }

    /// Streams an absolute URL into a file and verifies its SHA-256.
    ///
    /// The destination directory and digest are prepared before the request is
    /// opened, so a local failure cannot leak the response body. The expected
    /// hash is trimmed before comparison.
    ///
    /// @param absoluteUrl    absolute URL to fetch
    /// @param target         destination file
    /// @param expectedSha256 expected lowercase hex hash, or empty to skip the check
    /// @param authenticated  whether to attach the bearer token
    /// @param progress       optional progress callback
    /// @return the verified file plus its `ETag` and `Last-Modified`
    /// @throws IOException when the download fails or the hash does not match
    private DownloadResult downloadVerified(String absoluteUrl, Path target,
                                            String expectedSha256, boolean authenticated,
                                            @Nullable ProgressCallback progress)
            throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        MessageDigest digest = sha256Digest();
        HttpResponse<InputStream> response = api.openRawUrl(absoluteUrl, authenticated);
        response = api.followRedirect(response, authenticated);
        if (response.statusCode() / 100 != 2) {
            try (InputStream ignored = response.body()) {
                // Drain before failing so the connection can be reused.
            }
            throw new IOException("Cloud save download returned HTTP " + response.statusCode());
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(0L);
        String etag = response.headers().firstValue("ETag").orElse("");
        String lastModified = response.headers().firstValue("Last-Modified").orElse("");
        long readTotal = 0;
        try (InputStream input = response.body();
             OutputStream output = Files.newOutputStream(target,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                readTotal += read;
                if (progress != null) progress.onProgress(readTotal, total);
            }
        }
        String expected = expectedSha256.trim();
        if (!expected.isEmpty()) {
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!expected.equalsIgnoreCase(actual)) {
                Files.deleteIfExists(target);
                throw new IOException("Cloud save hash mismatch");
            }
        }
        return new DownloadResult(target, etag, lastModified, readTotal);
    }

    /// Whether an absolute URL belongs to the configured API origin.
    ///
    /// @param absoluteUrl absolute URL to inspect
    /// @return whether scheme, host and effective port match the API origin
    private boolean sameApiOrigin(String absoluteUrl) {
        try {
            URI candidate = URI.create(absoluteUrl);
            URI base = URI.create(api.baseUrl());
            if (candidate.getScheme() == null || base.getScheme() == null) return false;
            if (!candidate.getScheme().equalsIgnoreCase(base.getScheme())) return false;
            if (candidate.getHost() == null || base.getHost() == null) return false;
            if (!candidate.getHost().equalsIgnoreCase(base.getHost())) return false;
            return effectivePort(candidate) == effectivePort(base);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /// Resolves the effective port of a URI, applying the scheme default.
    ///
    /// @param uri URI to inspect
    /// @return the explicit or default port
    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /// Downloads and verifies a snapshot into the target file.
    ///
    /// @param slotId     slot id
    /// @param snapshotId snapshot id
    /// @param target     destination `.msav` file
    /// @param progress   optional progress callback
    /// @return the destination path
    /// @throws IOException when the download fails or the hash does not match
    public Path download(String slotId, String snapshotId, Path target,
                         @Nullable ProgressCallback progress) throws IOException {
        return downloadDetailed(slotId, snapshotId, target, progress).path();
    }

    /// Downloads a snapshot, verifies it and returns response validators.
    ///
    /// @param slotId     slot id
    /// @param snapshotId snapshot id
    /// @param target     destination `.msav` file
    /// @param progress   optional progress callback
    /// @return the verified file plus its `ETag` and `Last-Modified`
    /// @throws IOException when the download fails or the hash does not match
    public DownloadResult downloadDetailed(String slotId, String snapshotId, Path target,
                                           @Nullable ProgressCallback progress) throws IOException {
        JsonObject data = MdtbbsJson.dataObject(api.post(
                "/game-saves/" + slotId + "/snapshots/" + snapshotId + "/download",
                new JsonObject()));
        JsonObject download = MdtbbsJson.objectOf(data, "download");
        if (download == null) download = data;
        String url = MdtbbsJson.stringOf(download, "url");
        if (url.isBlank()) throw new IOException("Cloud save download URL is missing");
        String resolved = api.resolveUrl(url);
        return downloadVerified(resolved, target,
                MdtbbsJson.stringOf(download, "sha256"),
                sameApiOrigin(resolved), progress);
    }

    /// Parses one slot object with tolerant key names.
    private static Slot parseSlot(JsonObject object) {
        String id = MdtbbsJson.stringOf(object, "id");
        if (id.isBlank()) id = MdtbbsJson.stringOf(object, "slot_id");
        String name = MdtbbsJson.stringOf(object, "name");
        if (name.isBlank()) name = MdtbbsJson.stringOf(object, "title");
        JsonObject current = MdtbbsJson.objectOf(object, "current_snapshot");
        long legacySize = MdtbbsJson.longOf(object, "size",
                MdtbbsJson.longOf(object, "current_size", 0));
        long size = current == null ? legacySize
                : MdtbbsJson.longOf(current, "size", legacySize);
        long revision = current == null ? 0 : MdtbbsJson.longOf(current, "revision", 0);
        String currentId = current == null ? "" : MdtbbsJson.stringOf(current, "id");
        if (currentId.isBlank() && current != null) {
            currentId = MdtbbsJson.stringOf(current, "snapshot_id");
        }
        return new Slot(id, name,
                MdtbbsJson.instantOf(object, "updated_at"),
                size,
                revision,
                currentId);
    }

    /// Parses one snapshot object with tolerant key names.
    private static Snapshot parseSnapshot(JsonObject object) {
        String id = MdtbbsJson.stringOf(object, "id");
        if (id.isBlank()) id = MdtbbsJson.stringOf(object, "snapshot_id");
        JsonObject save = MdtbbsJson.objectOf(object, "save");
        List<ModInfo> mods = new ArrayList<>();
        for (JsonElement element : MdtbbsJson.arrayOf(object, "mods")) {
            JsonObject mod = MdtbbsJson.asObject(element);
            if (mod == null) continue;
            mods.add(new ModInfo(MdtbbsJson.stringOf(mod, "id"),
                    MdtbbsJson.stringOf(mod, "name"),
                    MdtbbsJson.stringOf(mod, "version"),
                    MdtbbsJson.stringOf(mod, "sha256")));
        }
        String format = MdtbbsJson.stringOf(object, "format");
        if (format.isBlank()) format = "zip";
        return new Snapshot(id,
                MdtbbsJson.longOf(object, "size", 0),
                MdtbbsJson.stringOf(object, "sha256"),
                MdtbbsJson.stringOf(object, "file_name"),
                MdtbbsJson.boolOf(object, "pinned",
                        MdtbbsJson.boolOf(object, "is_pinned", false)),
                MdtbbsJson.instantOf(object, "created_at"),
                save == null ? "" : MdtbbsJson.stringOf(save, "map_name"),
                save == null ? 0 : (int) MdtbbsJson.longOf(save, "wave", 0),
                (int) MdtbbsJson.longOf(object, "mod_count", 0),
                MdtbbsJson.longOf(object, "revision", 0),
                format,
                (int) MdtbbsJson.longOf(object, "file_count", 0),
                mods);
    }

    /// Computes the lowercase hex SHA-256 of a file without loading it into memory.
    ///
    /// @param file readable file
    /// @return the digest
    /// @throws IOException when the file cannot be read
    private static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Creates a SHA-256 digest instance.
    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }
}
