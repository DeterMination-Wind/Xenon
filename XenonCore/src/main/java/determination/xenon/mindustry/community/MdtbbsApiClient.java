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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/// Thin JSON client for the MDTBBS `/api/v1` surface.
///
/// Every request returns the full response envelope so callers can read both
/// `data` and `meta` (pagination). Errors are converted into
/// {@link MdtbbsApiException} with the stable `error.code`. HTTP/1.1 is forced
/// for consistency with the other mainland endpoints.
///
/// The server also enforces a CSRF check on writes: it issues a `csrf_token`
/// cookie and expects the same value in the `X-CSRF-Token` header. This client
/// captures the cookie from responses, echoes it on writes, and retries once
/// with a fresh cookie when the server rejects the token.

/// Thin JSON client for the MDTBBS `/api/v1` surface.
///
/// Every request returns the full response envelope so callers can read both
/// `data` and `meta` (pagination). Errors are converted into
/// {@link MdtbbsApiException} with the stable `error.code`. HTTP/1.1 is forced
/// for consistency with the other mainland endpoints.
@NotNullByDefault
public final class MdtbbsApiClient {
    /// Default API origin including the version prefix.
    public static final String DEFAULT_BASE_URL = "https://mdtbbs.cn/api/v1";
    /// JSON codec shared by all requests.
    private static final Gson GSON = new Gson();
    /// How many times a rate-limited GET is retried before failing.
    private static final int MAX_RATE_LIMIT_RETRIES = 2;
    /// Fallback delay when a 429 response has no `Retry-After` header.
    private static final Duration DEFAULT_RATE_LIMIT_DELAY = Duration.ofSeconds(2);

    /// API origin used by this client.
    private final String baseUrl;
    /// HTTP client used for JSON calls.
    private final HttpClient http;
    /// HTTP client that never follows redirects, used for signed binary downloads.
    private final HttpClient rawHttp;
    /// Supplies the current bearer token, or `null` for anonymous requests.
    private final TokenProvider tokenProvider;
    /// Captured cookies, currently only the `csrf_token` value.
    private final Map<String, String> cookies = new ConcurrentHashMap<>();

    /// Supplies bearer tokens to the API client.
    @FunctionalInterface
    public interface TokenProvider {
        /// Returns the current access token, or `null` when not logged in.
        @Nullable String accessToken();
    }

    /// Creates a client for the production API origin.
    ///
    /// @param tokenProvider bearer-token supplier
    public MdtbbsApiClient(TokenProvider tokenProvider) {
        this(DEFAULT_BASE_URL, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(ProxySelector.getDefault())
                .build(), tokenProvider);
    }

    /// Creates a client with an injected origin and transport for tests.
    ///
    /// @param baseUrl       API origin including the version prefix
    /// @param http          HTTP client used for requests
    /// @param tokenProvider bearer-token supplier
    MdtbbsApiClient(String baseUrl, HttpClient http, TokenProvider tokenProvider) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = http;
        this.tokenProvider = tokenProvider;
        this.rawHttp = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(ProxySelector.getDefault())
                .build();
    }

    /// API origin including the version prefix.
    public String baseUrl() { return baseUrl; }

    /// Opens a raw response for a binary API path without following redirects.
    ///
    /// @param path          API path such as `/game-content/maps/1/download/file`
    /// @param query         query parameters, or `null`
    /// @param authenticated whether to attach the bearer token
    /// @return the raw response; the caller must close its body
    /// @throws IOException when the request fails or the API returns 4xx/5xx
    public HttpResponse<InputStream> openRaw(String path, @Nullable Map<String, String> query,
                                             boolean authenticated) throws IOException {
        return openRawUrl(baseUrl + path + buildQuery(query), authenticated);
    }

    /// Opens an absolute URL without following redirects.
    ///
    /// @param url           absolute URL
    /// @param authenticated whether to attach the bearer token
    /// @return the raw response; the caller must close its body
    /// @throws IOException when the request fails or the API returns 4xx/5xx
    public HttpResponse<InputStream> openRawUrl(String url, boolean authenticated) throws IOException {
        String token = authenticated ? tokenProvider.accessToken() : null;
        if (authenticated && (token == null || token.isBlank())) {
            throw new MdtbbsApiException(401, "AUTH_REQUIRED",
                    "Not logged in to MDTBBS", false);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("Accept", "*/*")
                .header("User-Agent", "Xenon-Launcher")
                .GET();
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<InputStream> response = rawHttp.send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            captureCookies(response);
            if (response.statusCode() / 100 == 4 || response.statusCode() / 100 == 5) {
                String body = readAll(response.body());
                throw apiException(response.statusCode(), parseObject(body));
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted opening " + url, e);
        }
    }

    /// Follows a single redirect, dropping the bearer token on cross-host hops.
    ///
    /// @param response      response returned by [`#openRaw`]
    /// @param authenticated whether the original request carried the token
    /// @return the redirect target response, or the original response when it is not a redirect
    /// @throws IOException when the redirect target cannot be opened
    public HttpResponse<InputStream> followRedirect(HttpResponse<InputStream> response,
                                                    boolean authenticated) throws IOException {
        if (response.statusCode() / 100 != 3) return response;
        String location = response.headers().firstValue("Location").orElse(null);
        URI origin = response.uri();
        response.body().close();
        if (location == null) {
            throw new IOException("Redirect without Location from " + origin);
        }
        URI target = origin.resolve(location);
        boolean sameHost = target.getHost() != null
                && target.getHost().equalsIgnoreCase(origin.getHost());
        return openRawUrl(target.toString(), authenticated && sameHost);
    }

    /// Sends an authenticated request with a caller-provided content type and body.
    ///
    /// @param path        API path such as `/game-content/maps/uploads`
    /// @param contentType request `Content-Type`
    /// @param body        raw request bytes
    /// @return the full `{data, meta}` envelope
    /// @throws IOException when the request fails or the API returns an error
    public JsonObject postRaw(String path, String contentType, byte[] body) throws IOException {
        String token = tokenProvider.accessToken();
        if (token == null || token.isBlank()) {
            throw new MdtbbsApiException(401, "AUTH_REQUIRED",
                    "Not logged in to MDTBBS", false);
        }
        boolean csrfRetried = false;
        while (true) {
            String csrfBefore = cookies.get("csrf_token");
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofMinutes(5))
                    .header("Accept", "application/json")
                    .header("Content-Type", contentType)
                    .header("User-Agent", "Xenon-Launcher")
                    .header("Authorization", "Bearer " + token);
            applyCsrf(builder);
            try {
                HttpResponse<String> response = http.send(builder
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                captureCookies(response);
                JsonObject root = parseObject(response.body());
                if (response.statusCode() == 403 && isCsrfFailure(root) && !csrfRetried) {
                    csrfRetried = true;
                    if (Objects.equals(csrfBefore, cookies.get("csrf_token"))) {
                        cookies.remove("csrf_token");
                    }
                    continue;
                }
                if (response.statusCode() / 100 != 2) {
                    throw apiException(response.statusCode(), root);
                }
                return root;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted calling " + path, e);
            }
        }
    }

    /// Uploads a local file to a signed URL with the bearer token.
    ///
    /// The upload client never follows redirects so the token cannot be
    /// forwarded to an unexpected host.
    ///
    /// @param url         absolute or API-relative upload URL
    /// @param file        local file to stream
    /// @param contentType request `Content-Type`
    /// @throws IOException when the upload fails
    public void putFile(String url, java.nio.file.Path file, String contentType) throws IOException {
        String token = tokenProvider.accessToken();
        if (token == null || token.isBlank()) {
            throw new MdtbbsApiException(401, "AUTH_REQUIRED",
                    "Not logged in to MDTBBS", false);
        }
        String absolute = url.startsWith("http") ? url : baseUrl + url;
        boolean sameOrigin = absolute.startsWith(origin());
        boolean csrfRetried = false;
        while (true) {
            String csrfBefore = cookies.get("csrf_token");
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(absolute))
                    .timeout(Duration.ofMinutes(30))
                    .header("Content-Type", contentType)
                    .header("User-Agent", "Xenon-Launcher")
                    .header("Authorization", "Bearer " + token);
            if (sameOrigin) applyCsrf(builder);
            try {
                HttpResponse<String> response = rawHttp.send(builder
                                .PUT(HttpRequest.BodyPublishers.ofFile(file)).build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                captureCookies(response);
                JsonObject root = parseObject(response.body());
                if (response.statusCode() == 403 && isCsrfFailure(root)
                        && sameOrigin && !csrfRetried) {
                    csrfRetried = true;
                    if (Objects.equals(csrfBefore, cookies.get("csrf_token"))) {
                        cookies.remove("csrf_token");
                    }
                    continue;
                }
                if (response.statusCode() / 100 != 2) {
                    throw apiException(response.statusCode(), root);
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted uploading to " + absolute, e);
            }
        }
    }

    /// Reads and closes a response body as UTF-8 text.
    private static String readAll(InputStream stream) throws IOException {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /// Sends an anonymous GET request.
    ///
    /// @param path  API path such as `/threads`
    /// @return the full `{data, meta}` envelope
    /// @throws IOException when the request fails or the API returns an error
    public JsonObject get(String path) throws IOException {
        return request("GET", path, null, null, false);
    }

    /// Sends an anonymous GET request with query parameters.
    ///
    /// @param path  API path such as `/threads`
    /// @param query query parameters in insertion order
    /// @return the full `{data, meta}` envelope
    /// @throws IOException when the request fails or the API returns an error
    public JsonObject get(String path, Map<String, String> query) throws IOException {
        return request("GET", path, query, null, false);
    }

    /// Sends an authenticated GET request.
    ///
    /// @param path  API path such as `/me`
    /// @param query query parameters in insertion order, or `null`
    /// @return the full `{data, meta}` envelope
    /// @throws IOException when the request fails, the token is missing, or the API returns an error
    public JsonObject getAuth(String path, @Nullable Map<String, String> query) throws IOException {
        return request("GET", path, query, null, true);
    }

    /// Sends an authenticated POST request with a JSON body.
    ///
    /// @param path API path such as `/threads`
    /// @param body JSON body, or `null` for an empty object
    /// @return the full `{data, meta}` envelope
    /// @throws IOException when the request fails or the API returns an error
    public JsonObject post(String path, @Nullable JsonObject body) throws IOException {
        return request("POST", path, null, body, true);
    }

    /// Sends an authenticated PATCH request with a JSON body.
    ///
    /// @param path API path to update
    /// @param body JSON body, or `null` for an empty object
    /// @return the full `{data, meta}` envelope
    /// @throws IOException when the request fails or the API returns an error
    public JsonObject patch(String path, @Nullable JsonObject body) throws IOException {
        return request("PATCH", path, null, body, true);
    }

    /// Sends an authenticated PUT request with a JSON body.
    ///
    /// @param path API path to update
    /// @param body JSON body, or `null` for an empty object
    /// @return the full `{data, meta}` envelope
    /// @throws IOException when the request fails or the API returns an error
    public JsonObject put(String path, @Nullable JsonObject body) throws IOException {
        return request("PUT", path, null, body, true);
    }

    /// Sends an authenticated DELETE request.
    ///
    /// @param path  API path to delete
    /// @param query query parameters in insertion order, or `null`
    /// @return the full `{data, meta}` envelope
    /// @throws IOException when the request fails or the API returns an error
    public JsonObject delete(String path, @Nullable Map<String, String> query) throws IOException {
        return request("DELETE", path, query, null, true);
    }

    /// Core request implementation with rate-limit retry.
    private JsonObject request(String method, String path, @Nullable Map<String, String> query,
                               @Nullable JsonObject body, boolean authenticated) throws IOException {
        String token = authenticated ? tokenProvider.accessToken() : null;
        if (authenticated && (token == null || token.isBlank())) {
            throw new MdtbbsApiException(401, "AUTH_REQUIRED",
                    "Not logged in to MDTBBS", false);
        }

        URI uri = URI.create(baseUrl + path + buildQuery(query));
        boolean csrfRetried = false;
        for (int attempt = 0; ; attempt++) {
            String csrfBefore = cookies.get("csrf_token");
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("User-Agent", "Xenon-Launcher");
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            if (!"GET".equals(method)) {
                applyCsrf(builder);
            }
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(
                                GSON.toJson(body), StandardCharsets.UTF_8));
            }

            HttpResponse<String> response;
            try {
                response = http.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted calling " + path, e);
            }
            captureCookies(response);

            JsonObject root = parseObject(response.body());
            if (response.statusCode() == 403 && isCsrfFailure(root) && !csrfRetried) {
                csrfRetried = true;
                if (Objects.equals(csrfBefore, cookies.get("csrf_token"))) {
                    cookies.remove("csrf_token");
                }
                continue;
            }
            if (response.statusCode() == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                sleep(rateLimitDelay(response));
                continue;
            }
            if (response.statusCode() / 100 != 2) {
                throw apiException(response.statusCode(), root);
            }
            return root;
        }
    }

    /// Builds an exception from an API error envelope.
    ///
    /// Legacy web responses use `{success:false, message}` instead of the V1
    /// error object, so both shapes are recognized.
    private static MdtbbsApiException apiException(int status, JsonObject root) {
        JsonObject error = root.has("error") && root.get("error").isJsonObject()
                ? root.getAsJsonObject("error") : null;
        String code = error == null ? null : stringOf(error, "code");
        String message = error == null ? null : stringOf(error, "message");
        if (error == null && isCsrfFailure(root)) {
            code = "CSRF_TOKEN_INVALID";
            message = stringOf(root, "message");
        } else if (error == null && root.has("message")) {
            message = stringOf(root, "message");
        }
        boolean retryable = error != null && error.has("retryable")
                && error.get("retryable").getAsBoolean();
        return new MdtbbsApiException(status, code,
                message == null ? "MDTBBS API returned HTTP " + status : message, retryable);
    }

    /// Captures the `csrf_token` cookie from a response.
    private void captureCookies(HttpResponse<?> response) {
        for (String header : response.headers().allValues("Set-Cookie")) {
            int end = header.indexOf(';');
            String pair = end < 0 ? header : header.substring(0, end);
            int equals = pair.indexOf('=');
            if (equals <= 0) continue;
            String name = pair.substring(0, equals).trim();
            String value = pair.substring(equals + 1).trim();
            if (name.equalsIgnoreCase("csrf_token") && !value.isEmpty()) {
                cookies.put("csrf_token", value);
            }
        }
    }

    /// Adds the CSRF cookie and matching header to a write request.
    private void applyCsrf(HttpRequest.Builder builder) throws IOException {
        String csrf = csrfToken();
        if (csrf != null && !csrf.isBlank()) {
            builder.header("X-CSRF-Token", csrf);
        }
        if (!cookies.isEmpty()) {
            StringBuilder value = new StringBuilder();
            for (Map.Entry<String, String> entry : cookies.entrySet()) {
                if (!value.isEmpty()) value.append("; ");
                value.append(entry.getKey()).append('=').append(entry.getValue());
            }
            builder.header("Cookie", value.toString());
        }
    }

    /// Returns the CSRF token, warming the cookie with a cheap GET when needed.
    private @Nullable String csrfToken() throws IOException {
        String token = cookies.get("csrf_token");
        if (token != null && !token.isBlank()) return token;
        HttpRequest warm = HttpRequest.newBuilder(URI.create(origin() + "/api/v1/capabilities"))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("User-Agent", "Xenon-Launcher")
                .GET()
                .build();
        try {
            captureCookies(http.send(warm, HttpResponse.BodyHandlers.discarding()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching a CSRF token", e);
        }
        return cookies.get("csrf_token");
    }

    /// Whether a response is the legacy CSRF rejection.
    private static boolean isCsrfFailure(JsonObject root) {
        String message = stringOf(root, "message");
        return message != null && message.toLowerCase(Locale.ROOT).contains("csrf");
    }

    /// Scheme and authority of the configured API origin.
    private String origin() {
        URI uri = URI.create(baseUrl);
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    /// Computes the delay before retrying a rate-limited request.
    private static Duration rateLimitDelay(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(value -> {
                    try {
                        return Duration.ofSeconds(Math.max(1, Long.parseLong(value.trim())));
                    } catch (NumberFormatException e) {
                        return DEFAULT_RATE_LIMIT_DELAY;
                    }
                })
                .orElse(DEFAULT_RATE_LIMIT_DELAY);
    }

    /// Sleeps, preserving the interrupt flag.
    private static void sleep(Duration duration) throws IOException {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while backing off", e);
        }
    }

    /// Appends URL-encoded query parameters.
    private static String buildQuery(@Nullable Map<String, String> query) {
        if (query == null || query.isEmpty()) return "";
        StringBuilder builder = new StringBuilder("?");
        boolean first = true;
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (!first) builder.append('&');
            first = false;
            builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    /// Parses a JSON object, returning an empty object for blank or invalid bodies.
    private static JsonObject parseObject(@Nullable String body) {
        if (body == null || body.isBlank()) return new JsonObject();
        try {
            JsonObject root = GSON.fromJson(body, JsonObject.class);
            return root == null ? new JsonObject() : root;
        } catch (JsonParseException e) {
            return new JsonObject();
        }
    }

    /// Reads an optional string field from a JSON object.
    private static @Nullable String stringOf(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}
