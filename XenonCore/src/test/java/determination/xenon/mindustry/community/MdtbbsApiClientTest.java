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

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests API envelope parsing, error mapping, bearer injection and CSRF handling.
@NotNullByDefault
public final class MdtbbsApiClientTest {
    @Test
    public void parsesDataAndMetaEnvelope() throws Exception {
        try (ApiServer server = ApiServer.start()) {
            JsonObject root = client(server, "token-1").get("/threads");
            assertEquals(2, root.getAsJsonObject("data").getAsJsonArray("items").size());
            assertEquals("req-1", root.getAsJsonObject("meta").get("request_id").getAsString());
        }
    }

    @Test
    public void throwsApiExceptionWithStableCode() throws Exception {
        try (ApiServer server = ApiServer.start()) {
            MdtbbsApiException error = assertThrows(MdtbbsApiException.class,
                    () -> client(server, "token-1").get("/messages"));
            assertEquals(403, error.getStatusCode());
            assertEquals("THIRD_PARTY_ACCESS_DISABLED", error.getErrorCode());
        }
    }

    @Test
    public void sendsBearerTokenOnAuthenticatedRequests() throws Exception {
        try (ApiServer server = ApiServer.start()) {
            client(server, "token-42").getAuth("/me", null);
            assertEquals("Bearer token-42", server.header("Authorization"));
        }
    }

    @Test
    public void missingTokenFailsBeforeSendingRequest() throws Exception {
        try (ApiServer server = ApiServer.start()) {
            MdtbbsApiException error = assertThrows(MdtbbsApiException.class,
                    () -> client(server, null).getAuth("/me", null));
            assertTrue(error.isUnauthorized());
            assertEquals("AUTH_REQUIRED", error.getErrorCode());
        }
    }

    @Test
    public void sendsCsrfCookieAndHeaderOnWrites() throws Exception {
        try (ApiServer server = ApiServer.start()) {
            MdtbbsApiClient api = client(server, "token-1");
            api.get("/capabilities");
            api.post("/threads", new JsonObject());

            assertEquals("cookie-1", server.header("X-CSRF-Token"));
            assertTrue(server.header("Cookie") != null && server.header("Cookie").contains("csrf_token=cookie-1"));
        }
    }

    @Test
    public void retriesOnceAfterCsrfRejection() throws Exception {
        try (ApiServer server = ApiServer.start()) {
            server.failFirstCsrf = true;
            MdtbbsApiClient api = client(server, "token-1");
            api.get("/capabilities");
            api.post("/threads", new JsonObject());

            assertEquals(2, server.postAttempts.get());
            assertEquals("cookie-2", server.header("X-CSRF-Token"));
        }
    }

    private static MdtbbsApiClient client(ApiServer server, @Nullable String token) {
        return new MdtbbsApiClient(server.baseUrl(),
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                () -> token);
    }

    private static final class ApiServer implements AutoCloseable {
        private final HttpServer server;
        private final ConcurrentHashMap<String, String> headers = new ConcurrentHashMap<>();
        final AtomicInteger postAttempts = new AtomicInteger();
        volatile boolean failFirstCsrf;

        static ApiServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ApiServer fixture = new ApiServer(server);
            server.createContext("/api/v1", fixture::handle);
            server.start();
            return fixture;
        }

        private ApiServer(HttpServer server) {
            this.server = server;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
        }

        @Nullable String header(String name) {
            return headers.get(name);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            headers.put("Authorization", String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            headers.put("X-CSRF-Token", String.valueOf(exchange.getRequestHeaders().getFirst("X-CSRF-Token")));
            headers.put("Cookie", String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")));

            if (path.endsWith("/capabilities") && method.equals("GET")) {
                exchange.getResponseHeaders().add("Set-Cookie", "csrf_token=cookie-1; Path=/");
                respond(exchange, 200, "{\"data\":{}}");
            } else if (path.endsWith("/threads") && method.equals("GET")) {
                respond(exchange, 200, LIST_JSON);
            } else if (path.endsWith("/threads") && method.equals("POST")) {
                int attempt = postAttempts.incrementAndGet();
                if (failFirstCsrf && attempt == 1) {
                    exchange.getResponseHeaders().add("Set-Cookie", "csrf_token=cookie-2; Path=/");
                    respond(exchange, 403, "{\"success\":false,\"message\":\"CSRF token invalid\"}");
                } else {
                    respond(exchange, 200, "{\"data\":{\"id\":1}}");
                }
            } else if (path.endsWith("/me") && method.equals("GET")) {
                respond(exchange, 200, "{\"data\":{}}");
            } else if (path.endsWith("/messages")) {
                respond(exchange, 403,
                        "{\"error\":{\"code\":\"THIRD_PARTY_ACCESS_DISABLED\",\"message\":\"off\"}}");
            } else {
                respond(exchange, 404, "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"missing\"}}");
            }
        }

        private static void respond(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final String LIST_JSON = """
            {"data":{"items":[{"id":1},{"id":2}]},"meta":{"request_id":"req-1"}}
            """;
}
