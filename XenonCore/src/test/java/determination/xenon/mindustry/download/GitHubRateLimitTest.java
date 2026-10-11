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
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the GitHub rate-limit response classification matrix.
@NotNullByDefault
public final class GitHubRateLimitTest {

    /// `429` always means the API rate limit was hit.
    @Test
    public void treats429AsRateLimited() {
        assertTrue(GitHubReleaseClient.isRateLimited(429, null, null, true));
        assertTrue(GitHubReleaseClient.isRateLimited(429, "42", "{}", true));
    }

    /// A `403` with an exhausted quota header is a rate limit.
    @Test
    public void treats403WithZeroRemainingAsRateLimited() {
        assertTrue(GitHubReleaseClient.isRateLimited(403, "0", null, true));
        assertTrue(GitHubReleaseClient.isRateLimited(403, " 0 ", null, false));
    }

    /// A `403` whose body mentions a rate limit is classified the same way.
    @Test
    public void treats403RateLimitBodyAsRateLimited() {
        String body = "{\"message\":\"API rate limit exceeded for 1.2.3.4\",\"documentation_url\":\"...\"}";
        assertTrue(GitHubReleaseClient.isRateLimited(403, null, body, true));
        assertTrue(GitHubReleaseClient.isRateLimited(403, "12", body, true));
        assertTrue(GitHubReleaseClient.isRateLimited(403, null, "RATE LIMIT reached", true));
    }

    /// Anonymous `403` answers are treated as the anonymous quota.
    @Test
    public void treatsAnonymous403AsRateLimited() {
        assertTrue(GitHubReleaseClient.isRateLimited(403, null, null, false));
        assertTrue(GitHubReleaseClient.isRateLimited(403, "5", "not found", false));
    }

    /// A `403` with a token and no rate-limit hints is a real failure.
    @Test
    public void ignoresNonRateLimit403WithToken() {
        assertFalse(GitHubReleaseClient.isRateLimited(403, null, null, true));
        assertFalse(GitHubReleaseClient.isRateLimited(403, "57", "{\"message\":\"Forbidden\"}", true));
    }

    /// Successful and unrelated responses are never classified as rate limits.
    @Test
    public void ignoresOtherStatusCodes() {
        assertFalse(GitHubReleaseClient.isRateLimited(200, null, null, false));
        assertFalse(GitHubReleaseClient.isRateLimited(404, null, null, false));
        assertFalse(GitHubReleaseClient.isRateLimited(500, "0", "rate limit", false));
    }

    /// The exception carries the token state, remaining quota and reset time.
    @Test
    public void exceptionExposesParsedRateLimitDetails() {
        Instant reset = Instant.parse("2026-07-06T12:00:00Z");
        GitHubRateLimitException exception = new GitHubRateLimitException(
                "GitHub rate limit reached", false, 0, reset);

        assertEquals("GitHub rate limit reached", exception.getMessage());
        assertFalse(exception.isTokenConfigured());
        assertEquals(0, exception.getRemaining());
        assertEquals(reset, exception.getResetAt());
    }

    /// The optional rate-limit details default to `null`.
    @Test
    public void exceptionAllowsMissingDetails() {
        GitHubRateLimitException exception = new GitHubRateLimitException(
                "GitHub rate limit reached", true, null, null);

        assertTrue(exception.isTokenConfigured());
        assertNull(exception.getRemaining());
        assertNull(exception.getResetAt());
    }
}
