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
import com.sun.net.httpserver.HttpServer;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/// MindAuth Authorization Code + PKCE flow for the desktop launcher.
///
/// The client binds a loopback callback server on `127.0.0.1`, opens the
/// system browser, validates the returned `state`, and exchanges the
/// authorization code for tokens at `/api/token`. No client secret is ever
/// used or stored; the public client must register the redirect URI
/// `http://127.0.0.1:0/oauth/callback` so any runtime port matches.
@NotNullByDefault
public final class MdtbbsOauthClient {
    /// Default MindAuth issuer.
    public static final String DEFAULT_ISSUER = "https://auth.mdtbbs.cn";
    /// Loopback callback path registered with the public client.
    public static final String CALLBACK_PATH = "/oauth/callback";
    /// How long the launcher waits for the browser round trip.
    private static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(3);
    /// JSON codec for token requests.
    private static final Gson GSON = new Gson();

    /// MindAuth issuer origin.
    private final String issuer;
    /// HTTP client used for token and revoke requests.
    private final HttpClient http;

    /// Creates a client for the production MindAuth issuer.
    public MdtbbsOauthClient() {
        this(DEFAULT_ISSUER, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(ProxySelector.getDefault())
                .build());
    }

    /// Creates a client with an injected issuer and transport for tests.
    ///
    /// @param issuer MindAuth origin, without a trailing slash
    /// @param http   HTTP client used for token calls
    MdtbbsOauthClient(String issuer, HttpClient http) {
        this.issuer = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        this.http = http;
    }

    /// Runs the interactive login flow and returns the granted session.
    ///
    /// @param clientId    registered public client id
    /// @param scope       space-separated scopes to request
    /// @param openBrowser callback that opens the authorization URL in the system browser
    /// @return the token response
    /// @throws IOException when the callback times out, is rejected, or token exchange fails
    public MdtbbsOauthSession authorize(String clientId, String scope,
                                        Consumer<String> openBrowser) throws IOException {
        String verifier = createVerifier();
        String challenge = challenge(verifier);
        String state = UUID.randomUUID().toString();

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IOException("Cannot start the loopback login server", e);
        }

        CompletableFuture<Callback> callback = new CompletableFuture<>();
        server.createContext(CALLBACK_PATH, exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String code = query.get("code");
            String returnedState = query.get("state");
            String error = query.get("error");
            if (code != null && state.equals(returnedState)) {
                callback.complete(new Callback(code, null));
                respond(exchange, 200, SUCCESS_HTML);
            } else if (error != null) {
                callback.complete(new Callback(null, error));
                respond(exchange, 400, FAILURE_HTML);
            } else {
                respond(exchange, 400, FAILURE_HTML);
            }
        });
        server.start();
        String redirectUri = "http://127.0.0.1:" + server.getAddress().getPort() + CALLBACK_PATH;
        try {
            openBrowser.accept(buildAuthorizeUrl(clientId, redirectUri, scope, state, challenge));
            Callback result;
            try {
                result = callback.get(LOGIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new IOException("Timed out waiting for the MindAuth callback", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for MindAuth", e);
            } catch (ExecutionException e) {
                throw new IOException("MindAuth callback failed", e.getCause());
            }
            if (result.error() != null) {
                throw new IOException("MindAuth authorization was denied: " + result.error());
            }
            return exchangeCode(clientId, result.code(), redirectUri, verifier);
        } finally {
            server.stop(0);
        }
    }

    /// Refreshes an existing session; the refresh token rotates on every call.
    ///
    /// @param clientId registered public client id
    /// @param session  session whose refresh token should be used
    /// @return the rotated session
    /// @throws IOException when no refresh token exists or the issuer rejects it
    public MdtbbsOauthSession refresh(String clientId, MdtbbsOauthSession session) throws IOException {
        String refreshToken = session.refreshToken();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IOException("The session has no refresh token");
        }
        JsonObject body = new JsonObject();
        body.addProperty("grant_type", "refresh_token");
        body.addProperty("client_id", clientId);
        body.addProperty("refresh_token", refreshToken);
        MdtbbsOauthSession refreshed = tokenRequest(body, clientId);
        if (refreshed.refreshToken() != null) return refreshed;
        return new MdtbbsOauthSession(refreshed.accessToken(), refreshToken,
                refreshed.expiresAt(), refreshed.scope(), refreshed.clientId());
    }

    /// Revokes the session tokens; failures are logged and ignored.
    ///
    /// @param clientId registered public client id
    /// @param session  session to revoke
    public void revoke(String clientId, MdtbbsOauthSession session) {
        JsonObject body = new JsonObject();
        body.addProperty("client_id", clientId);
        body.addProperty("token", session.refreshToken() == null
                ? session.accessToken() : session.refreshToken());
        HttpRequest request = HttpRequest.newBuilder(URI.create(issuer + "/api/revoke"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        try {
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            Logger.LOG.warning("MindAuth revoke failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Exchanges an authorization code for tokens.
    ///
    /// @param clientId    registered public client id
    /// @param code        authorization code returned by the callback
    /// @param redirectUri exact redirect URI used in the authorize request
    /// @param verifier    original PKCE verifier
    /// @return the granted session
    /// @throws IOException when the issuer rejects the exchange
    MdtbbsOauthSession exchangeCode(String clientId, String code, String redirectUri,
                                    String verifier) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("grant_type", "authorization_code");
        body.addProperty("client_id", clientId);
        body.addProperty("code", code);
        body.addProperty("redirect_uri", redirectUri);
        body.addProperty("code_verifier", verifier);
        return tokenRequest(body, clientId);
    }

    /// Builds the authorization URL opened in the system browser.
    ///
    /// @param clientId        registered public client id
    /// @param redirectUri     loopback redirect URI of the running callback server
    /// @param scope           space-separated scopes
    /// @param state           anti-CSRF state value
    /// @param codeChallenge   S256 challenge of the PKCE verifier
    /// @return the full authorize URL
    public String buildAuthorizeUrl(String clientId, String redirectUri, String scope,
                                    String state, String codeChallenge) {
        return issuer + "/api/authorize"
                + "?response_type=code"
                + "&client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri)
                + "&scope=" + encode(scope)
                + "&state=" + encode(state)
                + "&code_challenge=" + encode(codeChallenge)
                + "&code_challenge_method=S256";
    }

    /// Generates a PKCE `code_verifier` with 256 bits of entropy.
    ///
    /// @return a Base64URL string without padding
    public static String createVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /// Computes the S256 PKCE challenge of a verifier.
    ///
    /// @param verifier verifier produced by {@link #createVerifier()}
    /// @return the Base64URL-encoded SHA-256 digest
    public static String challenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /// Sends one token-endpoint request and parses the response.
    private MdtbbsOauthSession tokenRequest(JsonObject body, String clientId) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(issuer + "/api/token"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during MindAuth token request", e);
        }
        JsonObject root = parseObject(response.body());
        if (response.statusCode() / 100 != 2) {
            throw new IOException(oauthError(root, response.statusCode()));
        }
        String access = stringOf(root, "access_token");
        if (access == null || access.isBlank()) {
            throw new IOException("MindAuth token response has no access_token");
        }
        long expiresIn = root.has("expires_in") ? root.get("expires_in").getAsLong() : 3600L;
        String scope = stringOf(root, "scope");
        return new MdtbbsOauthSession(access, stringOf(root, "refresh_token"),
                Instant.now().plusSeconds(expiresIn), scope == null ? "" : scope, clientId);
    }

    /// Formats an OAuth error response for the exception message.
    private static String oauthError(JsonObject root, int status) {
        if (!root.has("error")) return "MindAuth returned HTTP " + status;
        JsonElement element = root.get("error");
        String code;
        String description = stringOf(root, "error_description");
        if (element.isJsonObject()) {
            JsonObject error = element.getAsJsonObject();
            code = stringOf(error, "code");
            if (description == null) description = stringOf(error, "message");
        } else {
            code = element.isJsonPrimitive() ? element.getAsString() : null;
        }
        return "MindAuth " + status + (code == null ? "" : " " + code)
                + (description == null ? "" : ": " + description);
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

    /// Encodes one query component.
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /// Parses a raw URL query string into decoded key/value pairs.
    private static Map<String, String> parseQuery(@Nullable String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return result;
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) continue;
            String key = URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    /// Writes a small HTML page into the browser callback.
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } finally {
            exchange.close();
        }
    }

    /// Result of one browser callback.
    ///
    /// @param code  authorization code, or `null` when the user denied access
    /// @param error OAuth error code, or `null` on success
    private record Callback(@Nullable String code, @Nullable String error) {
    }

    /// Page shown after a successful callback.
    private static final String SUCCESS_HTML = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <title>登录成功</title></head><body>
            <p>登录成功，请返回 Xenon 启动器。</p>
            </body></html>
            """;
    /// Page shown after a rejected callback.
    private static final String FAILURE_HTML = """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <title>登录未完成</title></head><body>
            <p>登录未完成，请返回 Xenon 启动器重试。</p>
            </body></html>
            """;
}
