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
    public record Slot(String id, String name, Instant updatedAt, long size, long revision) {
    }

    /// One immutable snapshot of a slot.
    ///
    /// @param id        snapshot id
    /// @param size      file size in bytes, or 0 when unknown
    /// @param sha256    content hash, or empty
    /// @param fileName  original file name, or empty
    /// @param pinned    whether the snapshot is pinned against retention
    /// @param createdAt creation time
    public record Snapshot(String id, long size, String sha256, String fileName,
                           boolean pinned, Instant createdAt) {
    }

    /// Storage quota of the current user.
    ///
    /// @param usedBytes    bytes currently stored
    /// @param quotaBytes   total quota in bytes, or 0 when unlimited/unknown
    /// @param maxFileBytes per-file limit in bytes, or 0 when unknown
    public record Quota(long usedBytes, long quotaBytes, long maxFileBytes) {
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
        return new Quota(
                MdtbbsJson.longOf(data, "used_bytes",
                        MdtbbsJson.longOf(data, "used", 0)),
                MdtbbsJson.longOf(data, "quota_bytes",
                        MdtbbsJson.longOf(data, "quota", 0)),
                MdtbbsJson.longOf(data, "max_file_bytes",
                        MdtbbsJson.longOf(data, "max_file_size", 0)));
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
        api.post("/game-saves/" + slotId + "/snapshots/" + snapshotId + "/restore",
                new JsonObject());
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
        byte[] bytes = Files.readAllBytes(saveFile);
        long size = bytes.length;
        String sha256 = sha256Hex(bytes);

        JsonObject create = new JsonObject();
        create.addProperty("sha256", sha256);
        create.addProperty("size", size);
        create.addProperty("reason", "manual");
        if (baseSnapshotId != null && !baseSnapshotId.isBlank()) {
            create.addProperty("base_snapshot_id", baseSnapshotId);
        }
        JsonObject created = MdtbbsJson.dataObject(
                api.post("/game-saves/" + slotId + "/uploads", create));
        String uploadId = MdtbbsJson.stringOf(created, "upload_id");
        JsonObject upload = MdtbbsJson.objectOf(created, "upload");
        String uploadUrl = upload == null ? "" : MdtbbsJson.stringOf(upload, "url");
        if (uploadId.isBlank() || uploadUrl.isBlank()) {
            throw new IOException("Cloud save upload session is incomplete");
        }

        api.putFile(uploadUrl, saveFile, "application/octet-stream");
        JsonObject committed = MdtbbsJson.dataObject(
                api.post("/game-saves/uploads/" + uploadId + "/commit", new JsonObject()));
        String snapshotId = MdtbbsJson.stringOf(committed, "snapshot_id");
        if (snapshotId.isBlank()) snapshotId = MdtbbsJson.stringOf(committed, "id");
        return snapshotId;
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
        MessageDigest digest = sha256Digest();
        try (InputStream input = response.body();
             OutputStream output = Files.newOutputStream(target,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            long readTotal = 0;
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
        return target;
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
        return new Slot(id, name,
                MdtbbsJson.instantOf(object, "updated_at"),
                size,
                revision);
    }

    /// Parses one snapshot object with tolerant key names.
    private static Snapshot parseSnapshot(JsonObject object) {
        String id = MdtbbsJson.stringOf(object, "id");
        if (id.isBlank()) id = MdtbbsJson.stringOf(object, "snapshot_id");
        return new Snapshot(id,
                MdtbbsJson.longOf(object, "size", 0),
                MdtbbsJson.stringOf(object, "sha256"),
                MdtbbsJson.stringOf(object, "file_name"),
                MdtbbsJson.boolOf(object, "pinned",
                        MdtbbsJson.boolOf(object, "is_pinned", false)),
                MdtbbsJson.instantOf(object, "created_at"));
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
