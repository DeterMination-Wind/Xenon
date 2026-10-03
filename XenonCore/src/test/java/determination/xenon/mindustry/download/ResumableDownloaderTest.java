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
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests resumable downloads, pausing and cancel cleanup.
@NotNullByDefault
public final class ResumableDownloaderTest {

    /// An existing partial file continues with a Range request.
    @Test
    public void resumesFromPartialFile(@TempDir Path tempDir) throws Exception {
        byte[] data = sequentialBytes(64 * 1024);
        try (RangeServer server = RangeServer.start(data, true, 0L)) {
            Path target = tempDir.resolve("payload.bin");
            int already = 20 * 1024;
            Path part = ResumableDownloader.partPath(target);
            Files.write(part, java.util.Arrays.copyOf(data, already));
            writeMeta(target, data.length);

            new ResumableDownloader().download(server.url(), target, data.length, null, null);

            assertArrayEquals(data, Files.readAllBytes(target));
            assertFalse(Files.exists(part));
            assertTrue(server.requestedRanges().stream().anyMatch(range -> range.startsWith("bytes=" + already + "-")),
                    () -> "expected a range request from " + already + ", got " + server.requestedRanges());
        }
    }

    /// A server that ignores Range restarts from zero and still succeeds.
    @Test
    public void restartsWhenServerIgnoresRange(@TempDir Path tempDir) throws Exception {
        byte[] data = sequentialBytes(32 * 1024);
        try (RangeServer server = RangeServer.start(data, false, 0L)) {
            Path target = tempDir.resolve("payload.bin");
            Files.write(ResumableDownloader.partPath(target), java.util.Arrays.copyOf(data, 10 * 1024));
            writeMeta(target, data.length);

            new ResumableDownloader().download(server.url(), target, data.length, null, null);

            assertArrayEquals(data, Files.readAllBytes(target));
        }
    }

    /// Cancelling removes the partial payload and its metadata.
    @Test
    public void cancelRemovesPartialFiles(@TempDir Path tempDir) throws Exception {
        byte[] data = sequentialBytes(256 * 1024);
        DownloadControl control = new DownloadControl();
        try (RangeServer server = RangeServer.start(data, true, 25L)) {
            Path target = tempDir.resolve("payload.bin");
            assertThrows(IOException.class, () -> new ResumableDownloader()
                    .download(server.url(), target, data.length,
                            (read, total) -> control.cancel(), control));
            assertFalse(Files.exists(target));
            assertFalse(Files.exists(ResumableDownloader.partPath(target)));
            assertFalse(Files.exists(ResumableDownloader.metaPath(target)));
        }
    }

    /// Pausing parks the transfer, keeps the partial file, and resume finishes it.
    @Test
    public void pauseKeepsPartialAndResumeCompletes(@TempDir Path tempDir) throws Exception {
        byte[] data = sequentialBytes(256 * 1024);
        DownloadControl control = new DownloadControl();
        java.util.concurrent.atomic.AtomicBoolean pauseOnce =
                new java.util.concurrent.atomic.AtomicBoolean();
        try (RangeServer server = RangeServer.start(data, true, 10L)) {
            Path target = tempDir.resolve("payload.bin");
            Path part = ResumableDownloader.partPath(target);
            Thread worker = new Thread(() -> {
                try {
                    new ResumableDownloader().download(server.url(), target, data.length,
                            (read, total) -> {
                                // Pause exactly once; later events must not re-pause
                                // after the resume.
                                if (read >= 32 * 1024 && pauseOnce.compareAndSet(false, true)) {
                                    control.pause();
                                }
                            }, control);
                } catch (IOException ignored) {
                    // The assertion below reports the real outcome.
                }
            });
            worker.setDaemon(true);
            worker.start();

            // Wait until the pause takes effect, then confirm the size is stable.
            assertTrue(await(5000, () -> control.isPaused() && Files.exists(part)));
            long firstSize = Files.size(part);
            Thread.sleep(300);
            assertEquals(firstSize, Files.size(part));

            control.resume();
            worker.join(TimeUnit.SECONDS.toMillis(15));
            assertArrayEquals(data, Files.readAllBytes(target));
            assertFalse(Files.exists(part));
        }
    }

    /// Writes the metadata sidecar as the downloader expects it.
    private static void writeMeta(Path target, long total) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("total", Long.toString(total));
        Path meta = ResumableDownloader.metaPath(target);
        Files.createDirectories(meta.getParent());
        try (OutputStream out = Files.newOutputStream(meta)) {
            properties.store(out, "test");
        }
    }

    /// Polls `condition` until it holds or the timeout expires.
    private static boolean await(long timeoutMs, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    /// Deterministic binary payload.
    private static byte[] sequentialBytes(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        return data;
    }

    /// HTTP server with optional Range support and an artificial delay.
    private static final class RangeServer implements AutoCloseable {
        private final HttpServer server;
        private final byte[] data;
        private final boolean rangeSupport;
        private final long delayMs;
        private final List<String> requestedRanges = Collections.synchronizedList(new java.util.ArrayList<>());

        private RangeServer(HttpServer server, byte[] data, boolean rangeSupport, long delayMs) {
            this.server = server;
            this.data = data;
            this.rangeSupport = rangeSupport;
            this.delayMs = delayMs;
        }

        static RangeServer start(byte[] data, boolean rangeSupport, long delayMs) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            RangeServer result = new RangeServer(server, data, rangeSupport, delayMs);
            server.createContext("/payload.bin", result::handle);
            server.start();
            return result;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/payload.bin";
        }

        List<String> requestedRanges() {
            return requestedRanges;
        }

        private void handle(HttpExchange exchange) throws IOException {
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null) {
                requestedRanges.add(range);
            }
            int start = 0;
            if (rangeSupport && range != null && range.startsWith("bytes=")) {
                String spec = range.substring("bytes=".length());
                int dash = spec.indexOf('-');
                if (dash > 0) {
                    try {
                        start = Integer.parseInt(spec.substring(0, dash));
                    } catch (NumberFormatException ignored) {
                        start = 0;
                    }
                }
            }
            if (start < 0 || start > data.length) {
                exchange.sendResponseHeaders(416, -1);
                exchange.close();
                return;
            }
            byte[] payload = java.util.Arrays.copyOfRange(data, start, data.length);
            if (start > 0) {
                exchange.getResponseHeaders().set("Content-Range",
                        "bytes " + start + "-" + (data.length - 1) + "/" + data.length);
                exchange.sendResponseHeaders(206, payload.length);
            } else {
                exchange.sendResponseHeaders(200, payload.length);
            }
            try (OutputStream out = exchange.getResponseBody()) {
                int chunk = 4096;
                for (int offset = 0; offset < payload.length; offset += chunk) {
                    int length = Math.min(chunk, payload.length - offset);
                    out.write(payload, offset, length);
                    out.flush();
                    if (delayMs > 0) {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
