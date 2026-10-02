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

import java.time.Duration;
import java.time.Instant;

/// Persisted MindAuth OAuth tokens for the MDTBBS public client.
///
/// @param accessToken  bearer token used for `/api/v1` requests
/// @param refreshToken rotating refresh token, or `null` when the issuer omitted it
/// @param expiresAt    absolute expiry of the access token
/// @param scope        granted scopes, space separated
/// @param clientId     public client id that owns these tokens
@NotNullByDefault
public record MdtbbsOauthSession(String accessToken, @Nullable String refreshToken,
                                 Instant expiresAt, String scope, String clientId) {

    /// Whether the access token expires within the given safety margin.
    ///
    /// @param margin time reserved for refreshing before the real expiry
    /// @return `true` when a refresh should be attempted now
    public boolean isExpiringWithin(Duration margin) {
        return Instant.now().plus(margin).isAfter(expiresAt);
    }
}
