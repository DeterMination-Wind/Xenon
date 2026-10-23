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
package determination.xenon.mindustry.cloud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/// Local baseline of the last successful whole-instance sync.
///
/// The baseline records which cloud snapshot and which local fingerprint were
/// in sync, so the next launch can distinguish "only I changed", "only the
/// cloud changed" and "both changed". It lives beside the instance manifest and
/// is only a local cache; deleting it simply falls back to a user decision.
@NotNullByDefault
public record MindustryCloudSyncBaseline(int schema, String slotId, String snapshotId,
                                         String archiveSha256, long archiveSize,
                                         String localFingerprint, String modsHash,
                                         Instant syncedAt) {

    /// Current baseline schema.
    public static final int SCHEMA = 1;

    /// File name inside the instance root.
    public static final String FILE_NAME = "cloud-sync.json";

    /// Serialises the baseline.
    ///
    /// @return the JSON object
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", schema);
        root.addProperty("slot_id", slotId);
        root.addProperty("snapshot_id", snapshotId);
        root.addProperty("archive_sha256", archiveSha256);
        root.addProperty("archive_size", archiveSize);
        root.addProperty("local_fingerprint", localFingerprint);
        root.addProperty("mods_hash", modsHash);
        root.addProperty("synced_at", syncedAt.toString());
        return root;
    }

    /// Loads a baseline.
    ///
    /// @param file baseline file
    /// @return the baseline, or `null` when the file is absent or invalid
    public static @Nullable MindustryCloudSyncBaseline load(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (longOf(root, "schema", 0) != SCHEMA) {
                return null;
            }
            String snapshotId = stringOf(root, "snapshot_id");
            if (snapshotId.isBlank()) {
                return null;
            }
            Instant syncedAt;
            try {
                syncedAt = Instant.parse(stringOf(root, "synced_at"));
            } catch (RuntimeException e) {
                syncedAt = Instant.EPOCH;
            }
            return new MindustryCloudSyncBaseline(SCHEMA,
                    stringOf(root, "slot_id"), snapshotId,
                    stringOf(root, "archive_sha256"),
                    longOf(root, "archive_size", 0),
                    stringOf(root, "local_fingerprint"),
                    stringOf(root, "mods_hash"), syncedAt);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /// Writes a baseline atomically.
    ///
    /// @param file     baseline file
    /// @param baseline baseline to store
    /// @throws IOException when the file cannot be written
    public static void save(Path file, MindustryCloudSyncBaseline baseline) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, baseline.toJson().toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Reads a string field.
    private static String stringOf(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    /// Reads a long field.
    private static long longOf(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        try {
            return value.getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
