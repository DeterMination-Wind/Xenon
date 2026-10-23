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

    /// Reads one string field of the first detail entry.
    ///
    /// @param key field name
    /// @return the value, or empty when absent
    public String detailString(String key) {
        if (details == null || details.isEmpty()) return "";
        JsonElement first = details.get(0);
        if (!first.isJsonObject()) return "";
        JsonObject object = first.getAsJsonObject();
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
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

    /// Whether the failure means authentication is missing or stale.
    public boolean isUnauthorized() { return statusCode == 401; }

    @Override
    public String toString() {
        return "MdtbbsApiException{" + statusCode + " " + errorCode + ": " + getMessage() + "}";
    }
}
