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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import determination.xenon.JavaFXLauncher;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the manifest-driven integrity check and the retry / failover chain.
///
/// A JDK [HttpServer] stands in for the source family: one endpoint plays the
/// primary source, another the other side of the failover. Every request is
/// counted so the assertions can prove which route was actually used instead
/// of inferring it from the outcome.
@NotNullByDefault
public final class MindustryDownloadVerificationTest {

    /// Bytes the "good" artifact is made of.
    private static final byte[] GOOD = "the-real-mindustry-jar".getBytes(StandardCharsets.UTF_8);

    /// A wrong-length body: shorter than [MindustryDownloadVerificationTest#GOOD].
    private static final byte[] SHORT = "truncated".getBytes(StandardCharsets.UTF_8);

    /// A same-length but different body, so only the hash can reject it.
    private static final byte[] CORRUPT = corruptCopyOfGood();

    /// Starts the JavaFX toolkit because `Task` progress updates use `Platform.runLater`.
    @BeforeAll
    public static void startJavaFx() {
        JavaFXLauncher.start();
    }

    /// Restores the default tier so one test cannot leak into the next.
    @AfterEach
    public void restoreTier() {
        MindustryDownloadSource.setCurrent(MindustryDownloadSource.defaultForLocale());
    }

    /// A matching size and hash let the download through untouched.
    @Test
    public void matchingArtifactPassesVerification(@TempDir Path dir) throws Exception {
        try (FakeSource server = new FakeSource(GOOD, GOOD)) {
            Path target = dir.resolve("Mindustry.jar");
            task(server.primaryUrl(), target, GOOD.length, sha256(GOOD), null).run();

            assertArrayEquals(GOOD, Files.readAllBytes(target));
            assertEquals(1, server.primaryHits(), "a good artifact needs a single request");
            assertEquals(0, server.failoverHits(), "a good artifact must not touch the fallback");
            assertNoResidue(dir, "Mindustry.jar");
        }
    }

    /// A short body is rejected, retried on the same source, then fetched from the other side.
    @Test
    public void sizeMismatchRetriesThenFailsOver(@TempDir Path dir) throws Exception {
        MindustryDownloadSource.setCurrent(MindustryDownloadSource.PREFER_DOMESTIC);
        try (FakeSource server = new FakeSource(SHORT, GOOD)) {
            Path target = dir.resolve("Mindustry.jar");
            task(server.primaryUrl(), target, GOOD.length, sha256(GOOD),
                    server.failoverUrl()).run();

            assertArrayEquals(GOOD, Files.readAllBytes(target),
                    "the other source family must supply the verified artifact");
            assertTrue(server.primaryHits() >= 2,
                    "the primary source must be retried before switching sides, hits="
                            + server.primaryHits());
            assertEquals(1, server.failoverHits(), "the fallback must be used exactly once");
            assertNoResidue(dir, "Mindustry.jar");
        }
    }

    /// A same-length but wrong body is rejected and the artifact is deleted.
    @Test
    public void hashMismatchDeletesTheArtifact(@TempDir Path dir) throws Exception {
        try (FakeSource server = new FakeSource(CORRUPT, GOOD)) {
            Path target = dir.resolve("Mindustry.jar");
            MindustryDownloadTask task = task(server.primaryUrl(), target, GOOD.length,
                    sha256(GOOD), null);

            IOException failure = assertThrows(IOException.class, task::run);

            assertTrue(failure.getMessage().contains("hash does not match"),
                    "the error must name the mismatch, but was: " + failure.getMessage());
            assertFalse(Files.exists(target),
                    "a rejected artifact must be deleted, not resumed by the next attempt");
            assertFalse(ResumableDownloader.hasPartial(target),
                    "a rejected artifact must drop its resume data too");
            assertNoResidue(dir, "Mindustry.jar");
        }
    }

    /// A missing hash only relaxes the check to size; a size mismatch still fails.
    @Test
    public void missingHashStillChecksTheSize(@TempDir Path dir) throws Exception {
        try (FakeSource server = new FakeSource(SHORT, GOOD)) {
            Path target = dir.resolve("Mindustry.jar");
            MindustryDownloadTask task = task(server.primaryUrl(), target, GOOD.length,
                    null, null);

            IOException failure = assertThrows(IOException.class, task::run);

            assertTrue(failure.getMessage().contains("size does not match"),
                    "the error must name the size problem, but was: " + failure.getMessage());
            assertFalse(Files.exists(target));
        }
    }

    /// A domestic-only tier must not reach for the GitHub fallback.
    @Test
    public void domesticOnlyNeverUsesTheFallback(@TempDir Path dir) throws Exception {
        MindustryDownloadSource.setCurrent(MindustryDownloadSource.DOMESTIC_ONLY);
        try (FakeSource server = new FakeSource(SHORT, GOOD)) {
            Path target = dir.resolve("Mindustry.jar");
            MindustryDownloadTask task = task(server.primaryUrl(), target, GOOD.length,
                    sha256(GOOD), server.failoverUrl());

            assertThrows(IOException.class, task::run);

            assertEquals(0, server.failoverHits(),
                    "domestic-only must never request the GitHub fallback");
            assertFalse(Files.exists(target));
        }
    }

    /// An official-only tier must not request a 像素工厂文件站 artifact either.
    @Test
    public void officialOnlyRejectsAFileStationPrimary(@TempDir Path dir) throws Exception {
        MindustryDownloadSource.setCurrent(MindustryDownloadSource.OFFICIAL_ONLY);
        Path target = dir.resolve("Mindustry.jar");
        MindustryDownloadTask task = task(
                "https://file.mdtbbs.cn/d/Mindustry/Main/Stable/v160.7/desktop/Mindustry.jar",
                target, GOOD.length, sha256(GOOD), null);

        IOException failure = assertThrows(IOException.class, task::run);

        assertTrue(failure.getMessage().contains("No download route is allowed"),
                "an official-only tier must refuse a file-station URL, but said: "
                        + failure.getMessage());
        assertFalse(Files.exists(target));
    }

    /// Archive mode verifies and unpacks the zip in one pass.
    @Test
    public void archiveModeVerifiesAndExtracts(@TempDir Path dir) throws Exception {
        byte[] zip = zipContaining(GOOD);
        try (FakeSource server = new FakeSource(zip, GOOD)) {
            Path target = dir.resolve("Mindustry.jar");
            task(server.primaryUrl(), target, zip.length, sha256(zip), null, true).run();

            assertArrayEquals(GOOD, Files.readAllBytes(target));
            assertNoResidue(dir, "Mindustry.jar");
        }
    }

    /// Archive mode rejects a zip whose bytes do not match the manifest.
    @Test
    public void archiveModeRejectsABadZip(@TempDir Path dir) throws Exception {
        byte[] zip = zipContaining(GOOD);
        byte[] corruptZip = zipContaining(CORRUPT);
        try (FakeSource server = new FakeSource(corruptZip, GOOD)) {
            Path target = dir.resolve("Mindustry.jar");
            MindustryDownloadTask task = task(server.primaryUrl(), target, zip.length,
                    sha256(zip), null, true);

            assertThrows(IOException.class, task::run);

            assertFalse(Files.exists(target));
            assertNoResidue(dir, "Mindustry.jar");
        }
    }

    /// Builds a task against the fake source.
    ///
    /// @param url          primary URL
    /// @param target       destination file
    /// @param expectedSize expected size in bytes
    /// @param hash         expected SHA-256, or `null`
    /// @param fallbackUrl  failover URL, or `null`
    /// @return the configured task
    private static MindustryDownloadTask task(String url, Path target, long expectedSize,
                                             String hash, String fallbackUrl) {
        return task(url, target, expectedSize, hash, fallbackUrl, false);
    }

    /// Builds a task against the fake source, optionally in archive mode.
    ///
    /// @param url          primary URL
    /// @param target       destination file
    /// @param expectedSize expected size in bytes
    /// @param hash         expected SHA-256, or `null`
    /// @param fallbackUrl  failover URL, or `null`
    /// @param archive      whether the URL is a zip
    /// @return the configured task
    private static MindustryDownloadTask task(String url, Path target, long expectedSize,
                                             String hash, String fallbackUrl,
                                             boolean archive) {
        Path caches = target.getParent();
        if (caches == null) throw new AssertionError("the test target must have a parent");
        return new MindustryDownloadTask(url, target, expectedSize, caches, archive,
                fallbackUrl, hash);
    }

    /// Asserts the directory holds neither a partial file nor a metadata sidecar.
    ///
    /// @param dir  directory to inspect
    /// @param name artifact file name
    /// @throws IOException when the directory cannot be listed
    private static void assertNoResidue(Path dir, String name) throws IOException {
        assertFalse(Files.exists(dir.resolve(name + ResumableDownloader.PART_SUFFIX)),
                "a finished download must not leave a .part file");
        assertFalse(Files.exists(dir.resolve(name + ResumableDownloader.META_SUFFIX)),
                "a finished download must not leave a .part.meta file");
    }

    /// Builds a zip holding one `Mindustry.jar` entry.
    ///
    /// @param jar bytes to store as the entry
    /// @return the zip bytes
    /// @throws IOException when the archive cannot be produced
    private static byte[] zipContaining(byte[] jar) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("Mindustry.jar"));
            zip.write(jar);
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    /// Flips one byte of the good payload, keeping the length identical.
    ///
    /// @return a same-length payload that only a hash check can reject
    private static byte[] corruptCopyOfGood() {
        byte[] copy = GOOD.clone();
        copy[0] ^= 0x01;
        return copy;
    }

    /// Computes the lowercase-hex SHA-256 of a byte array.
    ///
    /// @param bytes input
    /// @return the digest as lowercase hex
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is missing", e);
        }
    }

    /// A two-endpoint HTTP source used as the primary and the failover.
    private static final class FakeSource implements AutoCloseable {
        /// Server serving both endpoints on one loopback port.
        private final HttpServer server;

        /// Port the server listens on.
        private final int port;

        /// Requests served by the primary endpoint.
        private final AtomicInteger primaryHits = new AtomicInteger();

        /// Requests served by the failover endpoint.
        private final AtomicInteger failoverHits = new AtomicInteger();

        /// Binds a loopback server and registers the two endpoints.
        ///
        /// @param primaryBody  body served by the primary endpoint
        /// @param failoverBody body served by the failover endpoint
        /// @throws IOException when the server cannot bind
        FakeSource(byte[] primaryBody, byte[] failoverBody) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = server.getAddress().getPort();
            server.createContext("/primary", exchange -> {
                primaryHits.incrementAndGet();
                respond(exchange, primaryBody);
            });
            server.createContext("/failover", exchange -> {
                failoverHits.incrementAndGet();
                respond(exchange, failoverBody);
            });
            server.setExecutor(null);
            server.start();
        }

        /// URL of the primary endpoint.
        ///
        /// @return absolute URL
        String primaryUrl() {
            return "http://127.0.0.1:" + port + "/primary/Mindustry.jar";
        }

        /// URL of the failover endpoint.
        ///
        /// @return absolute URL
        String failoverUrl() {
            return "http://127.0.0.1:" + port + "/failover/Mindustry.jar";
        }

        /// Number of requests the primary endpoint served.
        ///
        /// @return request count
        int primaryHits() {
            return primaryHits.get();
        }

        /// Number of requests the failover endpoint served.
        ///
        /// @return request count
        int failoverHits() {
            return failoverHits.get();
        }

        /// Writes one response body.
        ///
        /// @param exchange exchange to answer
        /// @param body     bytes to send
        /// @throws IOException when the response cannot be written
        private static void respond(HttpExchange exchange, byte[] body) throws IOException {
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/java-archive");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
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
