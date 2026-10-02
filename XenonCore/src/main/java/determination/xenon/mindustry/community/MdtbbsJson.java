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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/// Defensive readers for the MDTBBS JSON surface.
///
/// Every reader tolerates missing, `null` and malformed values so an optional
/// new field can never break an older launcher build.
@NotNullByDefault
public final class MdtbbsJson {
    /// Reads the `data` object of an envelope.
    ///
    /// @param root response envelope
    /// @return the `data` object, or an empty object
    public static JsonObject dataObject(JsonObject root) {
        JsonObject data = objectOf(root, "data");
        return data == null ? new JsonObject() : data;
    }

    /// Reads the `data` array of an envelope.
    ///
    /// @param root response envelope
    /// @return the `data` array, or an empty array
    public static JsonArray dataArray(JsonObject root) {
        JsonElement data = root.get("data");
        return data != null && data.isJsonArray() ? data.getAsJsonArray() : new JsonArray();
    }

    /// Casts an element to a JSON object, or returns `null`.
    ///
    /// @param element candidate element
    /// @return the object, or `null`
    public static @Nullable JsonObject asObject(@Nullable JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /// Reads a nested object field.
    ///
    /// @param parent parent object
    /// @param key    field name
    /// @return the nested object, or `null`
    public static @Nullable JsonObject objectOf(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    /// Reads a nested array field.
    ///
    /// @param parent parent object
    /// @param key    field name
    /// @return the array, or an empty array
    public static JsonArray arrayOf(JsonObject parent, String key) {
        JsonElement element = parent.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    /// Reads a string field.
    ///
    /// @param object parent object
    /// @param key    field name
    /// @return the string, or an empty string
    public static String stringOf(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    /// Reads a nullable string field.
    ///
    /// @param object parent object
    /// @param key    field name
    /// @return the string, or `null`
    public static @Nullable String nullableString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    /// Reads a long field with a fallback.
    ///
    /// @param object   parent object
    /// @param key      field name
    /// @param fallback value used when the field is missing or malformed
    /// @return the parsed value or the fallback
    public static long longOf(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return fallback;
        try {
            return element.getAsLong();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /// Reads a boolean field with a fallback.
    ///
    /// @param object   parent object
    /// @param key      field name
    /// @param fallback value used when the field is missing or malformed
    /// @return the parsed value or the fallback
    public static boolean boolOf(JsonObject object, String key, boolean fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) return fallback;
        try {
            return element.getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /// Reads an ISO-8601 timestamp, falling back to the epoch.
    ///
    /// @param object parent object
    /// @param key    field name
    /// @return the parsed instant or {`Instant#EPOCH`}
    public static Instant instantOf(JsonObject object, String key) {
        String text = stringOf(object, key);
        if (text.isBlank()) return Instant.EPOCH;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return Instant.EPOCH;
        }
    }

    /// Reads a string array field.
    ///
    /// @param object parent object
    /// @param key    field name
    /// @return each element as a string; the result may be empty
    public static java.util.List<String> stringListOf(JsonObject object, String key) {
        JsonArray array = arrayOf(object, key);
        java.util.List<String> result = new java.util.ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) continue;
            result.add(element.getAsString());
        }
        return result;
    }

    private MdtbbsJson() {
    }
}
