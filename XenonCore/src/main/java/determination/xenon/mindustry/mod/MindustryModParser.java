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
package determination.xenon.mindustry.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import determination.xenon.util.logging.Logger;
import org.hjson.JsonValue;
import org.hjson.Stringify;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Parses Mindustry mod and plugin archives into local metadata snapshots.
///
/// Descriptors are checked at the archive root first. Archives whose files
/// all share one top-level directory are also checked inside that wrapper,
/// which covers GitHub-style source downloads without accepting arbitrary
/// nested descriptors such as `libs/mod.hjson`.
@NotNullByDefault
public final class MindustryModParser {
    /// Descriptor names checked in priority order.
    private static final String @Unmodifiable [] CANDIDATES = {
            "mod.hjson", "mod.json", "plugin.hjson", "plugin.json"
    };

    /// Prevents instantiation of this utility class.
    private MindustryModParser() {}

    /// Parses one mod archive, trying all supported descriptor candidates.
    ///
    /// @throws MindustryModParseException if no descriptor exists or every
    ///     descriptor candidate is malformed
    /// @throws IOException if the archive cannot be read
    public static MindustryLocalMod parse(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            @Nullable MindustryModParseException firstFailure = null;
            for (ZipEntry entry : findDescriptorEntries(zip)) {
                try (InputStream in = zip.getInputStream(entry)) {
                    String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    return build(file, parseDescriptor(file, entry.getName(), raw));
                } catch (MindustryModParseException ex) {
                    if (firstFailure == null) {
                        firstFailure = ex;
                    }
                }
            }
            if (firstFailure != null) {
                throw new MindustryModParseException(
                        "Failed to parse Mindustry mod descriptor in " + file
                                + " (first failure: " + firstFailure.getMessage() + ")",
                        firstFailure);
            }
        }
        throw new MindustryModParseException(
                "No mod.json / mod.hjson / plugin.json / plugin.hjson in " + file);
    }

    /// Finds root descriptors or descriptors inside one archive-wide wrapper directory.
    private static @Unmodifiable List<ZipEntry> findDescriptorEntries(ZipFile zip) {
        List<ZipEntry> rootEntries = findDescriptorEntries(zip, "");
        if (!rootEntries.isEmpty()) {
            return List.copyOf(rootEntries);
        }

        @Nullable String wrapper = findCommonTopLevelDirectory(zip);
        if (wrapper == null) {
            return List.of();
        }
        return List.copyOf(findDescriptorEntries(zip, wrapper + "/"));
    }

    /// Finds descriptors at one exact archive path prefix in candidate priority order.
    private static List<ZipEntry> findDescriptorEntries(ZipFile zip, String prefix) {
        List<ZipEntry> result = new ArrayList<>(CANDIDATES.length);
        for (String candidate : CANDIDATES) {
            ZipEntry entry = zip.getEntry(prefix + candidate);
            if (entry != null && !entry.isDirectory()) {
                result.add(entry);
            }
        }
        return result;
    }

    /// Returns the shared top-level directory when every file is wrapped by it.
    private static @Nullable String findCommonTopLevelDirectory(ZipFile zip) {
        @Nullable String common = null;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) {
                continue;
            }
            String name = entry.getName();
            int slash = name.indexOf('/');
            if (slash <= 0) {
                return null;
            }
            String topLevel = name.substring(0, slash);
            if (common == null) {
                common = topLevel;
            } else if (!common.equals(topLevel)) {
                return null;
            }
        }
        return common;
    }

    /// Parses one descriptor while preserving JSON-first behavior for `.json` files.
    private static JsonObject parseDescriptor(Path file, String descriptorName, String raw)
            throws MindustryModParseException {
        if (descriptorName.endsWith(".hjson")) {
            return parseHjsonOrJson(raw, file, descriptorName);
        }
        try {
            return JsonParser.parseString(raw).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException strictFailure) {
            Logger.LOG.log(System.Logger.Level.DEBUG,
                    "Strict JSON parse failed for " + file + " (" + descriptorName
                            + "), retrying as hjson",
                    strictFailure);
            return parseHjsonOrJson(raw, file, descriptorName);
        }
    }

    /// Parses full Hjson syntax and converts the result to Gson's object model.
    private static JsonObject parseHjsonOrJson(String raw, Path file, String descriptorName)
            throws MindustryModParseException {
        try {
            JsonValue hjson = JsonValue.readHjson(raw);
            return JsonParser.parseString(hjson.toString(Stringify.PLAIN)).getAsJsonObject();
        } catch (RuntimeException hjsonFailure) {
            try {
                String arcCompatible = escapeNewlinesInQuotedStrings(raw);
                JsonValue hjson = JsonValue.readHjson(arcCompatible);
                return JsonParser.parseString(hjson.toString(Stringify.PLAIN)).getAsJsonObject();
            } catch (RuntimeException arcCompatibleFailure) {
                hjsonFailure.addSuppressed(arcCompatibleFailure);
                throw new MindustryModParseException(
                        "Failed to parse " + descriptorName + " in " + file,
                        hjsonFailure);
            }
        }
    }

    /// Escapes raw line breaks inside quoted strings, matching Arc's permissive Hjson reader.
    private static String escapeNewlinesInQuotedStrings(String raw) {
        StringBuilder result = new StringBuilder(raw.length());
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (!quoted) {
                result.append(current);
                if (current == '"') {
                    quoted = true;
                }
                continue;
            }

            if (escaped) {
                result.append(current);
                escaped = false;
            } else if (current == '\\') {
                result.append(current);
                escaped = true;
            } else if (current == '"') {
                result.append(current);
                quoted = false;
            } else if (current == '\r') {
                result.append("\\n");
                if (index + 1 < raw.length() && raw.charAt(index + 1) == '\n') {
                    index++;
                }
            } else if (current == '\n') {
                result.append("\\n");
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }

    /// Builds the launcher metadata model from a parsed descriptor object.
    private static MindustryLocalMod build(Path file, JsonObject obj) {
        @Nullable String name = string(obj, "name");
        @Nullable String displayName = string(obj, "displayName");
        @Nullable String author = string(obj, "author");
        @Nullable String version = string(obj, "version");
        @Nullable String description = string(obj, "description");
        @Nullable String main = string(obj, "main");
        int minGameVersion = minGameVersion(obj);
        boolean java = booleanValue(obj, "java");
        List<String> dependencies = stringList(obj, "dependencies");
        return new MindustryLocalMod(file, name, displayName, author, version,
                description, main, minGameVersion, java, dependencies);
    }

    /// Reads the optional minimum game build, accepting numeric and string values.
    private static int minGameVersion(JsonObject obj) {
        if (!obj.has("minGameVersion") || obj.get("minGameVersion").isJsonNull()) {
            return 0;
        }
        JsonElement value = obj.get("minGameVersion");
        try {
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                return value.getAsInt();
            }
            if (value.isJsonPrimitive()) {
                String text = value.getAsString().trim();
                int dot = text.indexOf('.');
                if (dot >= 0) {
                    text = text.substring(0, dot);
                }
                return text.isEmpty() ? 0 : Integer.parseInt(text);
            }
        } catch (RuntimeException ignored) {
            // Invalid metadata should not hide an otherwise loadable mod.
        }
        return 0;
    }

    /// Reads an optional boolean descriptor value.
    private static boolean booleanValue(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return false;
        }
        try {
            return obj.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return "true".equalsIgnoreCase(obj.get(key).getAsString());
        }
    }

    /// Reads an optional array of primitive strings.
    private static @Unmodifiable List<String> stringList(JsonObject obj, String key) {
        if (!obj.has(key) || !obj.get(key).isJsonArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        JsonArray values = obj.getAsJsonArray(key);
        for (JsonElement value : values) {
            if (value != null && value.isJsonPrimitive()) {
                result.add(value.getAsString());
            }
        }
        return List.copyOf(result);
    }

    /// Reads one optional primitive string property.
    private static @Nullable String string(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return null;
        }
        JsonElement value = obj.get(key);
        return value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
