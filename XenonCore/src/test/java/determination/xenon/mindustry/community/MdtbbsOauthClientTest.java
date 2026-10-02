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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests PKCE generation and MindAuth token parsing.
@NotNullByDefault
public final class MdtbbsOauthClientTest {
    @Test
    public void pkceChallengeMatchesRfcVector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                MdtbbsOauthClient.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        assertEquals(43, MdtbbsOauthClient.createVerifier().length());
    }

    @Test
    public void exchangeCodeParsesTokenResponse() throws Exception {
        try (TokenServer server = TokenServer.start("""
                {"access_token":"access-1","refresh_token":"refresh-1",
                 "expires_in":3600,"scope":"openid profile","token_type":"Bearer"}
                """, 200)) {
            MdtbbsOauthSession session = server.client().exchangeCode(
                    "client-1", "code-1", "http://127.0.0.1:1/oauth/callback", "verifier-1");

            assertEquals("access-1", session.accessToken());
            assertEquals("refresh-1", session.refreshToken());
            assertEquals("openid profile", session.scope());
            assertEquals("client-1", session.clientId());
            assertTrue(session.expiresAt().isAfter(java.time.Instant.now()));
        }
    }

    @Test
    public void refreshKeepsOldTokenWhenIssuerOmitsNewOne() throws Exception {
        try (TokenServer server = TokenServer.start("""
                {"access_token":"access-2","expires_in":3600}
                """, 200)) {
            MdtbbsOauthSession current = new MdtbbsOauthSession("access-1", "refresh-1",
                    java.time.Instant.now(), "", "client-1");
            MdtbbsOauthSession refreshed = server.client().refresh("client-1", current);

            assertEquals("access-2", refreshed.accessToken());
            assertEquals("refresh-1", refreshed.refreshToken());
        }
    }

    @Test
    public void exchangeCodeReportsOauthError() throws Exception {
        try (TokenServer server = TokenServer.start("""
                {"error":"invalid_grant","error_description":"code expired"}
                """, 400)) {
            IOException error = assertThrows(IOException.class, () -> server.client().exchangeCode(
                    "client-1", "bad", "http://127.0.0.1:1/oauth/callback", "verifier-1"));
            assertTrue(error.getMessage().contains("invalid_grant"));
            assertTrue(error.getMessage().contains("code expired"));
        }
    }

    private static final class TokenServer implements AutoCloseable {
        private final HttpServer server;
        private final String response;
        private final int status;

        static TokenServer start(String response, int status) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TokenServer fixture = new TokenServer(server, response, status);
            server.createContext("/api/token", fixture::handle);
            server.start();
            return fixture;
        }

        private TokenServer(HttpServer server, String response, int status) {
            this.server = server;
            this.response = response;
            this.status = status;
        }

        MdtbbsOauthClient client() {
            return new MdtbbsOauthClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                            .connectTimeout(Duration.ofSeconds(5)).build());
        }

        private void handle(HttpExchange exchange) throws IOException {
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
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
}
