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

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Failure returned by the MDTBBS API.
///
/// User-visible code should branch on the stable `error.code` value instead of
/// the localized message, matching the API conventions.
@NotNullByDefault
public final class MdtbbsApiException extends IOException {
    /// HTTP status code of the failed response.
    private final int statusCode;
    /// Stable machine-readable `error.code`, or `null` when absent.
    private final @Nullable String errorCode;
    /// Whether the documentation marks the request as safe to retry.
    private final boolean retryable;
    /// Machine-readable `error.details` entries, or `null` when absent.
    private final @Nullable JsonArray details;

    /// Creates an API failure.
    ///
    /// @param statusCode HTTP status code of the response
    /// @param errorCode  stable `error.code`, or `null` when the body had none
    /// @param message    diagnostic message for logs and toasts
    /// @param retryable  whether retrying the same request may succeed
    public MdtbbsApiException(int statusCode, @Nullable String errorCode,
                              String message, boolean retryable) {
        this(statusCode, errorCode, message, retryable, null);
    }

    /// Creates an API failure carrying structured details.
    ///
    /// @param statusCode HTTP status code of the response
    /// @param errorCode  stable `error.code`, or `null` when the body had none
    /// @param message    diagnostic message for logs and toasts
    /// @param retryable  whether retrying the same request may succeed
    /// @param details    `error.details` entries, or `null` when absent
    public MdtbbsApiException(int statusCode, @Nullable String errorCode,
                              String message, boolean retryable, @Nullable JsonArray details) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.details = details == null || details.isEmpty() ? null : details.deepCopy();
    }

    /// HTTP status code of the failed response.
    public int getStatusCode() { return statusCode; }

    /// Stable `error.code` value, or `null` when the server omitted it.
    public @Nullable String getErrorCode() { return errorCode; }

    /// Whether the server marked the failure as retryable.
    public boolean isRetryable() { return retryable; }

    /// Structured `error.details` entries.
    ///
    /// @return a defensive copy, or `null` when the error carried none
    public @Nullable JsonArray getDetails() { return details == null ? null : details.deepCopy(); }

    /// Reads one primitive field of the first detail entry.
    ///
    /// Object and array values are ignored instead of throwing, so callers can
    /// probe optional fields safely.
    ///
    /// @param key field name
    /// @return the value, or empty when absent or not a primitive
    public String detailString(String key) {
        if (details == null || details.isEmpty()) return "";
        JsonElement first = details.get(0);
        if (first == null || !first.isJsonObject()) return "";
        JsonElement value = first.getAsJsonObject().get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return "";
        try {
            return value.getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /// The resolution the server suggests for a save conflict, or empty.
    ///
    /// @return `create_conflict_copy`, `force_replace_head` or empty
    public String suggestedResolution() { return detailString("suggested_resolution"); }

    /// The current cloud head the conflict response named, or empty.
    ///
    /// @return the snapshot id, or empty
    public String currentSnapshotId() {
        String direct = detailString("current_snapshot_id");
        return direct.isBlank() ? detailString("snapshot_id") : direct;
    }

    /// The delay the server asked the client to wait before retrying.
    ///
    /// @return `details[0].retry_after_seconds`, or 0 when it is absent or malformed
    public long retryAfterSeconds() {
        if (details == null || details.isEmpty()) return 0;
        JsonElement first = details.get(0);
        if (first == null || !first.isJsonObject()) return 0;
        JsonElement value = first.getAsJsonObject().get("retry_after_seconds");
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return 0;
        try {
            return Math.max(0L, Long.parseLong(value.getAsString().trim()));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /// The blob hashes a blob-missing failure named.
    ///
    /// Accepts `SAVE_BLOB_MISSING` and any code ending in `_BLOB_MISSING`. Every
    /// detail entry may be a string, an object with `sha256`, an array of those,
    /// or an object listing hashes in a `blobs`/`missing` array; malformed
    /// entries are skipped. Any other error code yields an empty list.
    ///
    /// @return an immutable list of missing hashes, never `null`
    public List<String> missingBlobs() {
        if (errorCode == null || !errorCode.endsWith("_BLOB_MISSING") || details == null) {
            return List.of();
        }
        Set<String> hashes = new LinkedHashSet<>();
        for (JsonElement element : details) {
            if (element == null || element.isJsonNull()) continue;
            if (element.isJsonPrimitive()) {
                addHash(hashes, element);
                continue;
            }
            if (element.isJsonArray()) {
                addHashArray(hashes, element);
                continue;
            }
            if (!element.isJsonObject()) continue;
            JsonObject detail = element.getAsJsonObject();
            addHash(hashes, detail.get("sha256"));
            addHashArray(hashes, detail.get("blobs"));
            addHashArray(hashes, detail.get("missing"));
        }
        return List.copyOf(hashes);
    }

    /// Adds one primitive hash to the set when it is a non-blank string.
    ///
    /// @param hashes  destination set
    /// @param element candidate hash element, or `null`
    private static void addHash(Set<String> hashes, @Nullable JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return;
        try {
            String value = element.getAsString();
            if (!value.isBlank()) hashes.add(value);
        } catch (RuntimeException e) {
            // Malformed detail entries must never break error handling.
        }
    }

    /// Adds the hashes of an array that may hold strings or `sha256` objects.
    ///
    /// @param hashes  destination set
    /// @param element candidate array element, or `null`
    private static void addHashArray(Set<String> hashes, @Nullable JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonArray()) return;
        for (JsonElement entry : element.getAsJsonArray()) {
            if (entry == null || entry.isJsonNull()) continue;
            if (entry.isJsonPrimitive()) {
                addHash(hashes, entry);
            } else if (entry.isJsonObject()) {
                addHash(hashes, entry.getAsJsonObject().get("sha256"));
            }
        }
    }

    /// Whether the failure means authentication is missing or stale.
    public boolean isUnauthorized() { return statusCode == 401; }

    @Override
    public String toString() {
        return "MdtbbsApiException{" + statusCode + " " + errorCode + ": " + getMessage() + "}";
    }
}
