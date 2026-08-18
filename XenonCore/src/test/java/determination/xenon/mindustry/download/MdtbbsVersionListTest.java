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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests parsing MDTbbs directory pages into platform artifacts. */
@NotNullByDefault
public final class MdtbbsVersionListTest {
    @Test
    public void parsesBuildAndPlatformArtifacts(@TempDir Path tempDir) throws Exception {
        try (FixtureServer server = FixtureServer.start()) {
            MdtbbsVersionList list = new MdtbbsVersionList(
                    new GitHubReleaseClient(null), "1.10.SNAPSHOT",
                    server.categoryUrl(), server.fileBase(), HttpClient.newHttpClient());
            List<MindustryRemoteVersion> versions = list.refresh();

            assertEquals(1, versions.size());
            MindustryRemoteVersion version = versions.get(0);
            assertEquals(VersionVariant.VANILLA, version.getVariant());
            assertEquals(159, version.getBuild());
            assertEquals("v159.7", version.getTagName());
            assertEquals("stable", version.getBuildType());

            MindustryRemoteVersion.Artifact windows = version.getArtifactFor(OperatingSystem.WINDOWS);
            MindustryRemoteVersion.Artifact linux = version.getArtifactFor(OperatingSystem.LINUX);
            assertNotNull(windows);
            assertNotNull(linux);
            assertTrue(windows.isArchive());
            assertTrue(windows.getDownloadUrl().contains("/d/Mindustry/v8/build-159.7-stable"));
            assertTrue(windows.getDownloadUrl().endsWith("?reques=Xenon+1.10.SNAPSHOT"));
            assertEquals((long) (101.4d * 1024 * 1024), windows.getSize());

            VersionCache.save(VersionVariant.VANILLA, tempDir, versions);
            MindustryRemoteVersion cached = VersionCache.load(VersionVariant.VANILLA, tempDir).get(0);
            assertNotNull(cached.getArtifactFor(OperatingSystem.WINDOWS));
        }
    }

    private static final class FixtureServer implements AutoCloseable {
        private final HttpServer server;

        static FixtureServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FixtureServer fixture = new FixtureServer(server);
            server.createContext("/category/Mindustry/v8", fixture::index);
            server.createContext("/category/Mindustry/v8/build-159.7-stable", fixture::build);
            server.start();
            return fixture;
        }

        private FixtureServer(HttpServer server) {
            this.server = server;
        }

        String categoryUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/category/Mindustry/v8";
        }

        String fileBase() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/d";
        }

        private void index(HttpExchange exchange) throws IOException {
            respond(exchange, "<a href=\"/category/Mindustry/v8/build-159.7-stable\">build</a>");
        }

        private void build(HttpExchange exchange) throws IOException {
            respond(exchange, ""
                    + "<a class=\"term-file\" href=\"/Mindustry/v8/build-159.7-stable/mindustry-windows-64-bit.zip\">"
                    + "<span class=\"size\">101.4 MB</span></a>"
                    + "<a class=\"term-file\" href=\"/Mindustry/v8/build-159.7-stable/mindustry-linux-64-bit.zip\">"
                    + "<span class=\"size\">90 MB</span></a>");
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
