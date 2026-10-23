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
import determination.xenon.mindustry.download.ProgressCallback;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static determination.xenon.util.logging.Logger.LOG;

/// Reader and writer for the MDTBBS cloud-save surface (`/api/v1/game-saves`).
///
/// Uploads use the documented three-step session (create, raw PUT, commit) and
/// downloads verify the announced SHA-256 before the caller replaces a local
/// save. All calls are scoped to the authenticated user by the bearer token.
@NotNullByDefault
public final class MdtbbsCloudSavesClient {
    /// REST transport shared with the account coordinator.
    private final MdtbbsApiClient api;

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
    public record Snapshot(String id, long size, String sha256, String fileName,
                           boolean pinned, Instant createdAt, String mapName, int wave,
                           int modCount, long revision) {

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
            this(id, size, sha256, fileName, pinned, createdAt, "", 0, 0, 0);
        }
    }

    /// Storage quota of the current user.
    ///
    /// @param usedBytes    bytes currently stored
    /// @param quotaBytes   total quota in bytes, or 0 when unlimited/unknown
    /// @param maxFileBytes per-file limit in bytes, or 0 when unknown
    /// @param slotLimit    maximum number of slots, or 0 when unknown
    /// @param slotCount    number of slots in use, or 0 when unknown
    /// @param retentionVersions unpinned snapshots kept per slot, or 0 when unknown
    /// @param retentionDays days an unpinned snapshot is kept, or 0 when unknown
    public record Quota(long usedBytes, long quotaBytes, long maxFileBytes,
                        long slotLimit, long slotCount, long retentionVersions,
                        long retentionDays) {

        /// Builds a quota without slot or retention limits.
        ///
        /// @param usedBytes    bytes currently stored
        /// @param quotaBytes   total quota in bytes
        /// @param maxFileBytes per-file limit in bytes
        public Quota(long usedBytes, long quotaBytes, long maxFileBytes) {
            this(usedBytes, quotaBytes, maxFileBytes, 0, 0, 0, 0);
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
    /// @return the quota; zero fields mean unknown
    /// @throws IOException when the request fails
    public Quota quota() throws IOException {
        JsonObject data = MdtbbsJson.dataObject(api.getAuth("/game-saves/quota", null));
        JsonObject slots = MdtbbsJson.objectOf(data, "slots");
        JsonObject retention = MdtbbsJson.objectOf(data, "retention");
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
                        : MdtbbsJson.longOf(retention, "max_unpinned_age_days", 0));
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
        byte[] bytes = Files.readAllBytes(saveFile);
        long size = bytes.length;
        String sha256 = sha256Hex(bytes);

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
        if (metadata != null) {
            if (metadata.game() != null) {
                JsonObject game = new JsonObject();
                if (!metadata.game().version().isBlank()) {
                    game.addProperty("version", metadata.game().version());
                }
                if (metadata.game().build() > 0) {
                    game.addProperty("build", metadata.game().build());
                }
                create.add("game", game);
            }
            if (metadata.save() != null) {
                JsonObject save = new JsonObject();
                if (!metadata.save().mapName().isBlank()) {
                    save.addProperty("map_name", metadata.save().mapName());
                }
                save.addProperty("wave", Math.max(0, metadata.save().wave()));
                save.addProperty("playtime_seconds",
                        Math.max(0L, metadata.save().playtimeSeconds()));
                create.add("save", save);
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
                create.add("mods", mods);
            }
            if (!metadata.deviceId().isBlank()) {
                create.addProperty("device_id", metadata.deviceId());
            }
        }
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

            api.putFile(uploadUrl, saveFile, "application/octet-stream", progress);
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
        String expectedHash = MdtbbsJson.stringOf(download, "sha256");

        String absolute = api.resolveUrl(url);
        HttpResponse<InputStream> response = api.openRawUrl(absolute, true);
        response = api.followRedirect(response, true);
        if (response.statusCode() / 100 != 2) {
            try (InputStream ignored = response.body()) {
                // Drain before failing so the connection can be reused.
            }
            throw new IOException("Cloud save download returned HTTP " + response.statusCode());
        }
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        long total = response.headers().firstValueAsLong("Content-Length").orElse(0L);
        String etag = response.headers().firstValue("ETag").orElse("");
        String lastModified = response.headers().firstValue("Last-Modified").orElse("");
        MessageDigest digest = sha256Digest();
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
        if (!expectedHash.isBlank()) {
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!expectedHash.equalsIgnoreCase(actual)) {
                Files.deleteIfExists(target);
                throw new IOException("Cloud save hash mismatch");
            }
        }
        return new DownloadResult(target, etag, lastModified, readTotal);
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
        JsonArray mods = MdtbbsJson.arrayOf(object, "mods");
        return new Snapshot(id,
                MdtbbsJson.longOf(object, "size", 0),
                MdtbbsJson.stringOf(object, "sha256"),
                MdtbbsJson.stringOf(object, "file_name"),
                MdtbbsJson.boolOf(object, "pinned",
                        MdtbbsJson.boolOf(object, "is_pinned", false)),
                MdtbbsJson.instantOf(object, "created_at"),
                save == null ? "" : MdtbbsJson.stringOf(save, "map_name"),
                save == null ? 0 : (int) MdtbbsJson.longOf(save, "wave", 0),
                mods.size(),
                MdtbbsJson.longOf(object, "revision", 0));
    }

    /// Computes the lowercase hex SHA-256 of a byte array.
    private static String sha256Hex(byte[] bytes) throws IOException {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
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
