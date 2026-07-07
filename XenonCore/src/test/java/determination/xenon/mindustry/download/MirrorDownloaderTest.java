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
import determination.xenon.task.FetchTask;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/// Tests mirror racing progress and speed accounting.
@NotNullByDefault
public final class MirrorDownloaderTest {
    /// Canonical GitHub URL shape used to force the mirror-racing path.
    private static final String GITHUB_URL = "https://github.com/Anuken/Mindustry/releases/download/v1/test.bin";

    /// Ensures global speed events include bytes read from all active mirror streams.
    @Test
    public void racingMirrorsReportAggregatePayloadSpeed(@TempDir Path tempDir) throws Exception {
        byte[] data = sequentialBytes(384 * 1024);
        int chunkSize = 8192;
        long chunkDelayMs = 50L;
        long singleStreamBytesPerSecond = chunkSize * 1000L / chunkDelayMs;
        CountDownLatch aggregateSpeedSeen = new CountDownLatch(1);
        List<Long> speeds = Collections.synchronizedList(new ArrayList<>());

        Consumer<FetchTask.SpeedEvent> listener = event -> {
            long speed = event.getSpeed();
            speeds.add(speed);
            if (speed > singleStreamBytesPerSecond) {
                aggregateSpeedSeen.countDown();
            }
        };
        FetchTask.SPEED_EVENT.registerWeak(listener);

        try (TestMirrorServer server = TestMirrorServer.start(data, chunkSize, chunkDelayMs)) {
            Path target = tempDir.resolve("aggregate.bin");
            new MirrorDownloader(tempDir, server.mirrors(), false, 1200L)
                    .download(GITHUB_URL, target, data.length, null);

            assertArrayEquals(data, Files.readAllBytes(target));
        }

        assertTrue(aggregateSpeedSeen.await(2L, TimeUnit.SECONDS),
                () -> "Expected speed above one stream (" + singleStreamBytesPerSecond
                        + " B/s), got " + speeds);
    }

    /// Ensures racing output is correct and progress never counts duplicate mirror bytes.
    @Test
    public void racingMirrorsWriteWinnerWithoutOverReportingProgress(@TempDir Path tempDir) throws Exception {
        byte[] data = sequentialBytes(64 * 1024);
        List<Long> progressReads = Collections.synchronizedList(new ArrayList<>());

        try (TestMirrorServer server = TestMirrorServer.start(data, 4096, 5L)) {
            Path target = tempDir.resolve("winner.bin");
            new MirrorDownloader(tempDir, server.mirrors(), false, 100L)
                    .download(GITHUB_URL, target, data.length, (read, total) -> {
                        progressReads.add(read);
                        assertEquals(data.length, total);
                    });

            assertArrayEquals(data, Files.readAllBytes(target));
        }

        assertFalse(progressReads.isEmpty());
        assertEquals(data.length, progressReads.get(progressReads.size() - 1));
        assertTrue(progressReads.stream().allMatch(read -> read >= 0L && read <= data.length),
                () -> "Progress exceeded final size: " + progressReads);
    }

    /// Creates deterministic binary test data.
    private static byte[] sequentialBytes(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        return data;
    }

    /// Local HTTP server that exposes two equivalent mirror prefixes.
    private static final class TestMirrorServer implements AutoCloseable {
        /// Backing HTTP server.
        private final HttpServer server;
        /// Executor used so mirror streams can run concurrently.
        private final ExecutorService executor;
        /// Payload served by every full download request.
        private final byte[] data;
        /// Bytes written before each flush.
        private final int chunkSize;
        /// Delay between chunks in milliseconds.
        private final long chunkDelayMs;

        /// Starts a local mirror server.
        static TestMirrorServer start(byte[] data, int chunkSize, long chunkDelayMs) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            TestMirrorServer mirrorServer = new TestMirrorServer(server, executor, data, chunkSize, chunkDelayMs);
            server.createContext("/mirror-a", mirrorServer::handle);
            server.createContext("/mirror-b", mirrorServer::handle);
            server.setExecutor(executor);
            server.start();
            return mirrorServer;
        }

        /// Creates a local mirror server wrapper.
        private TestMirrorServer(HttpServer server, ExecutorService executor,
                                 byte[] data, int chunkSize, long chunkDelayMs) {
            this.server = server;
            this.executor = executor;
            this.data = data;
            this.chunkSize = chunkSize;
            this.chunkDelayMs = chunkDelayMs;
        }

        /// Returns the mirror base URLs to inject into the downloader.
        List<String> mirrors() {
            int port = server.getAddress().getPort();
            return List.of(
                    "http://127.0.0.1:" + port + "/mirror-a",
                    "http://127.0.0.1:" + port + "/mirror-b"
            );
        }

        /// Handles both probe and full payload requests.
        private void handle(HttpExchange exchange) throws IOException {
            try {
                if (exchange.getRequestHeaders().getFirst("Range") != null) {
                    exchange.getResponseHeaders().set("Content-Range", "bytes 0-0/" + data.length);
                    exchange.sendResponseHeaders(206, 1L);
                    exchange.getResponseBody().write(data, 0, 1);
                    return;
                }

                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream body = exchange.getResponseBody()) {
                    for (int offset = 0; offset < data.length; offset += chunkSize) {
                        int len = Math.min(chunkSize, data.length - offset);
                        body.write(data, offset, len);
                        body.flush();
                        if (offset + len < data.length) {
                            Thread.sleep(chunkDelayMs);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while serving test payload", e);
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
