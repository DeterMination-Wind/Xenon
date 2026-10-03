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

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the GitHub token attachment rules and rate-limit parsing.
@NotNullByDefault
public final class GitHubAuthTest {

    /// The token is attached to api.github.com requests only.
    @Test
    public void tokenNeverLeaksToMirrors() {
        try {
            GitHubAuth.setToken("secret-token");
            URI api = URI.create("https://api.github.com/user");
            HttpRequest.Builder apiBuilder = HttpRequest.newBuilder(api);
            GitHubAuth.authorize(apiBuilder, api);
            assertTrue(apiBuilder.build().headers().firstValue("Authorization").isPresent());

            URI mirror = URI.create("https://ghproxy.example/https://github.com/a/b");
            HttpRequest.Builder mirrorBuilder = HttpRequest.newBuilder(mirror);
            GitHubAuth.authorize(mirrorBuilder, mirror);
            assertFalse(mirrorBuilder.build().headers().firstValue("Authorization").isPresent());
        } finally {
            GitHubAuth.setToken(null);
        }
    }

    /// Anonymous mode never attaches an Authorization header.
    @Test
    public void anonymousModeAddsNoHeader() {
        GitHubAuth.setToken(null);
        URI api = URI.create("https://api.github.com/user");
        HttpRequest.Builder builder = HttpRequest.newBuilder(api);
        GitHubAuth.authorize(builder, api);
        assertFalse(builder.build().headers().firstValue("Authorization").isPresent());
    }

    /// Rate-limit headers are parsed and exposed.
    @Test
    public void rateLimitHeadersAreParsed() {
        GitHubAuth.recordRateLimit("5000", "4999", "1700000000");
        GitHubAuth.RateLimit limit = GitHubAuth.rateLimit().orElseThrow();
        assertEquals(5000, limit.limit());
        assertEquals(4999, limit.remaining());
        assertEquals(Instant.ofEpochSecond(1700000000L), limit.reset());
    }

    /// Malformed rate-limit values keep the previous snapshot.
    @Test
    public void malformedRateLimitValuesAreIgnored() {
        GitHubAuth.recordRateLimit("100", "50", "1700000000");
        GitHubAuth.recordRateLimit("bogus", "50", "1700000000");
        assertEquals(100, GitHubAuth.rateLimit().orElseThrow().limit());
    }
}
