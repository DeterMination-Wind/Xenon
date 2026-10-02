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

    /// Creates an API failure.
    ///
    /// @param statusCode HTTP status code of the response
    /// @param errorCode  stable `error.code`, or `null` when the body had none
    /// @param message    diagnostic message for logs and toasts
    /// @param retryable  whether retrying the same request may succeed
    public MdtbbsApiException(int statusCode, @Nullable String errorCode,
                              String message, boolean retryable) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /// HTTP status code of the failed response.
    public int getStatusCode() { return statusCode; }

    /// Stable `error.code` value, or `null` when the server omitted it.
    public @Nullable String getErrorCode() { return errorCode; }

    /// Whether the server marked the failure as retryable.
    public boolean isRetryable() { return retryable; }

    /// Whether the failure means authentication is missing or stale.
    public boolean isUnauthorized() { return statusCode == 401; }

    @Override
    public String toString() {
        return "MdtbbsApiException{" + statusCode + " " + errorCode + ": " + getMessage() + "}";
    }
}
