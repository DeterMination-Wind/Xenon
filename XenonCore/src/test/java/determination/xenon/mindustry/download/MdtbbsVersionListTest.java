/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package determination.xenon.mindustry.download;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import determination.xenon.mindustry.VersionVariant;
import determination.xenon.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests parsing the MDT File manifest into installable desktop jars. */
@NotNullByDefault
public final class MdtbbsVersionListTest {
    @Test
    public void parsesManifestDesktopJar(@TempDir Path tempDir) throws Exception {
        try (FixtureServer server = FixtureServer.start()) {
            MdtbbsVersionList list = new MdtbbsVersionList(
                    new GitHubReleaseClient(null), "1.10.SNAPSHOT",
                    server.manifestUrl(), HttpClient.newHttpClient());
            List<MindustryRemoteVersion> versions = list.refresh();

            assertEquals(1, versions.size());
            MindustryRemoteVersion version = versions.get(0);
            assertEquals(VersionVariant.VANILLA, version.getVariant());
            assertEquals(159, version.getBuild());
            assertEquals("v159.7", version.getTagName());
            assertEquals("stable", version.getBuildType());

            MindustryRemoteVersion.Artifact windows = version.getArtifactFor(OperatingSystem.WINDOWS);
            assertNotNull(windows);
            assertFalse(windows.isArchive());
            assertTrue(windows.getDownloadUrl().contains("/d/Mindustry/v8/build-159.7-stable/Mindustry.jar"));
            assertTrue(windows.getDownloadUrl().startsWith("http://127.0.0.1:"));
            assertEquals("https://github.com/Anuken/Mindustry/releases/download/v159.7/Mindustry.jar",
                    windows.getFallbackUrl());
            assertEquals(101L, windows.getSize());

            VersionCache.save(VersionVariant.VANILLA, tempDir, versions);
            MindustryRemoteVersion cached = VersionCache.load(VersionVariant.VANILLA, tempDir).get(0);
            assertNotNull(cached.getArtifactFor(OperatingSystem.WINDOWS));
            assertEquals(windows.getFallbackUrl(),
                    cached.getArtifactFor(OperatingSystem.WINDOWS).getFallbackUrl());
        }
    }

    private static final class FixtureServer implements AutoCloseable {
        private final HttpServer server;

        static FixtureServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FixtureServer fixture = new FixtureServer(server);
            server.createContext("/api/v1/mindustry/manifest.json", fixture::manifest);
            server.start();
            return fixture;
        }

        private FixtureServer(HttpServer server) {
            this.server = server;
        }

        String manifestUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/api/v1/mindustry/manifest.json";
        }

        private void manifest(HttpExchange exchange) throws IOException {
            respond(exchange, """
                    {"schema_version":1,"unexpected":true,"games":[{"id":"mindustry","releases":[{"tag":"v159.7","channel":"stable","source_repository":"Anuken/Mindustry","assets":[{"file_name":"Mindustry.jar","size":101,"sha256":"abc","platform":"desktop","type":"desktop","download_url":"/d/Mindustry/Main/Stable/v159.7/desktop/Mindustry.jar","extra":1},{"file_name":"Mindustry.jar","size":101,"sha256":"abc","platform":"desktop","type":"desktop","download_url":"/d/Mindustry/v8/build-159.7-stable/Mindustry.jar"},{"file_name":"server-release.jar","size":10,"platform":"server","type":"server","download_url":"/d/Mindustry/v8/build-159.7-stable/server-release.jar"}]}]}]}
                    """);
        }

        private static void respond(HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
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
