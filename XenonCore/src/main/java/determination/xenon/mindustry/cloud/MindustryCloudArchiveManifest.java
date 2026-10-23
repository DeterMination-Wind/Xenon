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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/// Manifest of one whole-instance cloud archive.
///
/// The archive is a regular zip; this manifest lives at
/// [#ENTRY_NAME] and describes the game version, the Mods and every packed
/// file with its SHA-256. Restores refuse archives without a valid manifest.
@NotNullByDefault
public record MindustryCloudArchiveManifest(int schema, String gameVersion, int build,
                                            Instant createdAt, List<ModEntry> mods,
                                            List<FileEntry> files) {

    /// Current manifest schema.
    public static final int SCHEMA = 1;

    /// Manifest entry name inside the archive.
    public static final String ENTRY_NAME = "xenon-cloud.json";

    /// Copies the lists defensively.
    public MindustryCloudArchiveManifest {
        mods = List.copyOf(mods);
        files = List.copyOf(files);
    }

    /// One Mod recorded in the archive.
    ///
    /// @param id       stable internal name
    /// @param name     display name
    /// @param version  Mod version
    /// @param sha256   archive SHA-256, or empty
    /// @param packed   whether the archive contains the Mod bytes
    /// @param fileName packed file name, or empty
    /// @param enabled  whether the Mod was enabled
    public record ModEntry(String id, String name, String version, String sha256,
                           boolean packed, String fileName, boolean enabled) {
    }

    /// One packed file.
    ///
    /// @param path   archive-relative path with `/` separators
    /// @param size   uncompressed size
    /// @param sha256 lowercase hex SHA-256, or empty
    public record FileEntry(String path, long size, String sha256) {
    }

    /// Serialises the manifest.
    ///
    /// @return the JSON object
    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", schema);
        root.addProperty("game_version", gameVersion);
        root.addProperty("build", build);
        root.addProperty("created_at", createdAt.toString());
        JsonArray modArray = new JsonArray();
        for (ModEntry mod : mods) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", mod.id());
            entry.addProperty("name", mod.name());
            entry.addProperty("version", mod.version());
            entry.addProperty("sha256", mod.sha256());
            entry.addProperty("packed", mod.packed());
            entry.addProperty("file_name", mod.fileName());
            entry.addProperty("enabled", mod.enabled());
            modArray.add(entry);
        }
        root.add("mods", modArray);
        JsonArray fileArray = new JsonArray();
        for (FileEntry file : files) {
            JsonObject entry = new JsonObject();
            entry.addProperty("path", file.path());
            entry.addProperty("size", file.size());
            entry.addProperty("sha256", file.sha256());
            fileArray.add(entry);
        }
        root.add("files", fileArray);
        return root;
    }

    /// Parses a manifest.
    ///
    /// @param json manifest JSON
    /// @return the manifest
    /// @throws IllegalArgumentException when the schema or required fields are invalid
    public static MindustryCloudArchiveManifest parse(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("archive manifest is not valid JSON", e);
        }
        int schema = (int) longOf(root, "schema", 0);
        if (schema != SCHEMA) {
            throw new IllegalArgumentException("unsupported archive manifest schema " + schema);
        }
        String gameVersion = stringOf(root, "game_version");
        int build = (int) longOf(root, "build", 0);
        Instant createdAt;
        try {
            createdAt = Instant.parse(stringOf(root, "created_at"));
        } catch (RuntimeException e) {
            createdAt = Instant.EPOCH;
        }
        List<ModEntry> mods = new ArrayList<>();
        JsonArray modArray = arrayOf(root, "mods");
        for (JsonElement element : modArray) {
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            mods.add(new ModEntry(stringOf(object, "id"), stringOf(object, "name"),
                    stringOf(object, "version"), stringOf(object, "sha256"),
                    boolOf(object, "packed", false), stringOf(object, "file_name"),
                    boolOf(object, "enabled", true)));
        }
        List<FileEntry> files = new ArrayList<>();
        JsonArray fileArray = arrayOf(root, "files");
        for (JsonElement element : fileArray) {
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            files.add(new FileEntry(stringOf(object, "path"), longOf(object, "size", 0),
                    stringOf(object, "sha256")));
        }
        return new MindustryCloudArchiveManifest(schema, gameVersion, build, createdAt,
                mods, files);
    }

    /// Reads a string field.
    private static String stringOf(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    /// Reads a long field with a fallback.
    private static long longOf(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        try {
            return value.getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /// Reads a boolean field with a fallback.
    private static boolean boolOf(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        try {
            return value.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /// Reads an array field.
    private static JsonArray arrayOf(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }
}
