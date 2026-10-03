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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import determination.xenon.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/// Holds the optional GitHub personal access token used for API requests.
///
/// The token lives here instead of the settings layer because the download
/// code runs inside XenonCore, which must not depend on the launcher module.
/// The launcher pushes the configured value once at startup and whenever the
/// setting changes.
///
/// Security contract: the token is attached **only** to requests whose host
/// is `api.github.com`. Mirror hosts and the public cache proxy must never
/// receive it, so all authorization happens through {@link #authorize} with
/// the final (possibly rewritten) request URI.
@NotNullByDefault
public final class GitHubAuth {

    /// GitHub API host allowed to receive the token.
    private static final String API_HOST = "api.github.com";

    /// Token used for API calls; empty string means anonymous access.
    private static final AtomicReference<String> TOKEN = new AtomicReference<>("");

    /// Last observed API rate-limit snapshot for the current token.
    private static final AtomicReference<RateLimit> RATE_LIMIT = new AtomicReference<>();

    /// HTTP client for token verification and rate-limit probes.
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault())
            .build();

    private static final Gson GSON = new Gson();

    private GitHubAuth() {
    }

    /// Snapshot of GitHub's `x-ratelimit-*` response headers.
    ///
    /// @param limit     maximum requests per window
    /// @param remaining requests left in the current window
    /// @param reset     instant at which the window resets
    public record RateLimit(int limit, int remaining, Instant reset) {
    }

    /// Result of validating a token with `GET /user`.
    ///
    /// @param login     the account login name
    /// @param rateLimit the rate limit reported while authenticating
    public record TokenCheck(String login, RateLimit rateLimit) {
    }

    /// Replaces the active token. Blank values disable authentication.
    ///
    /// @param token the personal access token, or `null`/blank for anonymous mode
    public static void setToken(@Nullable String token) {
        TOKEN.set(token == null ? "" : token.trim());
    }

    /// The currently configured token, or empty when running anonymously.
    public static String token() {
        return TOKEN.get();
    }

    /// Whether a token is configured.
    public static boolean hasToken() {
        return !TOKEN.get().isBlank();
    }

    /// Attaches the token to `builder` when `uri` targets the GitHub API.
    ///
    /// Mirrors and cache proxies keep their requests anonymous.
    ///
    /// @param builder request builder to mutate
    /// @param uri     the final request URI, after any mirror rewriting
    public static void authorize(HttpRequest.Builder builder, URI uri) {
        String token = TOKEN.get();
        if (token.isBlank()) {
            return;
        }
        if (!API_HOST.equalsIgnoreCase(uri.getHost())) {
            return;
        }
        builder.header("Authorization", "Bearer " + token);
    }

    /// Records the rate-limit headers carried by a GitHub API response.
    ///
    /// Non-API hosts are ignored so mirrored responses cannot poison the
    /// cached snapshot.
    ///
    /// @param response the response returned for a request built with {@link #authorize}
    public static void recordRateLimit(HttpResponse<?> response) {
        if (!API_HOST.equalsIgnoreCase(response.uri().getHost())) {
            return;
        }
        recordRateLimit(response.headers().firstValue("x-ratelimit-limit").orElse(null),
                response.headers().firstValue("x-ratelimit-remaining").orElse(null),
                response.headers().firstValue("x-ratelimit-reset").orElse(null));
    }

    /// Stores a rate-limit snapshot parsed from raw header values.
    ///
    /// Visible for tests; `null` or malformed values are ignored.
    ///
    /// @param limitHeader     `x-ratelimit-limit` header value
    /// @param remainingHeader `x-ratelimit-remaining` header value
    /// @param resetHeader     `x-ratelimit-reset` header value (epoch seconds)
    public static void recordRateLimit(@Nullable String limitHeader,
                                       @Nullable String remainingHeader,
                                       @Nullable String resetHeader) {
        try {
            if (limitHeader == null || remainingHeader == null || resetHeader == null) {
                return;
            }
            int limit = Integer.parseInt(limitHeader.trim());
            int remaining = Integer.parseInt(remainingHeader.trim());
            long reset = Long.parseLong(resetHeader.trim());
            RATE_LIMIT.set(new RateLimit(limit, remaining, Instant.ofEpochSecond(reset)));
        } catch (NumberFormatException ignored) {
            // Malformed header — keep the previous snapshot.
        }
    }

    /// The last observed rate limit, if any API response carried one.
    public static Optional<RateLimit> rateLimit() {
        return Optional.ofNullable(RATE_LIMIT.get());
    }

    /// Validates a token with `GET https://api.github.com/user`.
    ///
    /// The request goes directly to GitHub (never through a mirror) because
    /// it carries the credential.
    ///
    /// @param token token to validate
    /// @return the account login and the reported rate limit
    /// @throws IOException when GitHub rejects the token or is unreachable
    public static TokenCheck verify(String token) throws IOException {
        if (token == null || token.isBlank()) {
            throw new IOException("Token is empty");
        }
        URI uri = URI.create("https://" + API_HOST + "/user");
        HttpRequest request = HttpRequest.newBuilder(NetworkUtils.resolvePlayMirrorIp(uri))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Xenon-Launcher")
                .header("Authorization", "Bearer " + token.trim())
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while validating the GitHub token", e);
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IOException("GitHub rejected the token (HTTP " + response.statusCode() + ")");
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GitHub returned HTTP " + response.statusCode()
                    + " while validating the token");
        }
        String login;
        try {
            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
            login = json != null && json.has("login") ? json.get("login").getAsString() : "";
        } catch (JsonSyntaxException e) {
            throw new IOException("Unexpected response from GitHub while validating the token", e);
        }
        recordRateLimit(response);
        RateLimit limit = RATE_LIMIT.get();
        if (limit == null) {
            limit = new RateLimit(0, 0, Instant.EPOCH);
        }
        return new TokenCheck(login, limit);
    }

    /// Refreshes the cached rate limit for the currently configured token.
    ///
    /// @throws IOException when GitHub is unreachable
    public static RateLimit refreshRateLimit() throws IOException {
        URI uri = URI.create("https://" + API_HOST + "/rate_limit");
        HttpRequest.Builder builder = HttpRequest.newBuilder(NetworkUtils.resolvePlayMirrorIp(uri))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Xenon-Launcher");
        authorize(builder, uri);
        HttpResponse<String> response;
        try {
            response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while querying the GitHub rate limit", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GitHub returned HTTP " + response.statusCode()
                    + " while querying the rate limit");
        }
        recordRateLimit(response);
        RateLimit limit = RATE_LIMIT.get();
        if (limit == null) {
            throw new IOException("GitHub returned no rate limit information");
        }
        return limit;
    }
}
