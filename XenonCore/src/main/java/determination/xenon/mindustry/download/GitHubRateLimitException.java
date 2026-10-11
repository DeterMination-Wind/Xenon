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
package determination.xenon.mindustry.download;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;

/// Raised when GitHub rejects a request because an API rate limit was hit.
///
/// Anonymous requests share a small per-IP quota, so the launcher can offer to
/// configure a personal access token instead of retrying the same mirror.
@NotNullByDefault
public final class GitHubRateLimitException extends IOException {

    private final boolean tokenConfigured;
    private final @Nullable Integer remaining;
    private final @Nullable Instant resetAt;

    /// Creates the exception from a classified GitHub error response.
    ///
    /// @param message the human-readable failure message
    /// @param tokenConfigured whether a GitHub token was attached to the failed request
    /// @param remaining the `x-ratelimit-remaining` value, or `null` when unknown
    /// @param resetAt the `x-ratelimit-reset` instant, or `null` when unknown
    public GitHubRateLimitException(String message, boolean tokenConfigured,
                                    @Nullable Integer remaining, @Nullable Instant resetAt) {
        super(message);
        this.tokenConfigured = tokenConfigured;
        this.remaining = remaining;
        this.resetAt = resetAt;
    }

    /// Returns whether the failed request used a configured GitHub token.
    ///
    /// @return `true` when a token was attached, `false` for anonymous access
    public boolean isTokenConfigured() {
        return tokenConfigured;
    }

    /// Returns the remaining request quota reported by GitHub.
    ///
    /// @return the reported remaining count, or `null` when the header was absent
    public @Nullable Integer getRemaining() {
        return remaining;
    }

    /// Returns the moment the rate limit window resets.
    ///
    /// @return the reported reset instant, or `null` when the header was absent
    public @Nullable Instant getResetAt() {
        return resetAt;
    }
}
