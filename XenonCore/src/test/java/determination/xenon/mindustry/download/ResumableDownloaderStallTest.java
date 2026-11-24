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
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the low-throughput watchdog of the resumable downloader.
@NotNullByDefault
public final class ResumableDownloaderStallTest {

    /// A transfer that stops after a few bytes fails fast instead of hanging.
    @Test
    public void abortsStalledTransfer(@TempDir Path tempDir) throws Exception {
        try (StallServer server = StallServer.start()) {
            Path target = tempDir.resolve("payload.bin");
            ResumableDownloader downloader = new ResumableDownloader(
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                    Duration.ofMillis(600), 64 * 1024);

            IOException error = assertThrows(IOException.class,
                    () -> downloader.download(server.url(), target, 200_000, null, null));

            assertTrue(error.getMessage().toLowerCase(Locale.ROOT).contains("stalled"),
                    () -> "expected a stall message, got: " + error.getMessage());
            // Partial data is kept so the next attempt can resume.
            assertTrue(Files.isRegularFile(ResumableDownloader.partPath(target)));
        }
    }

    /// Server that announces 200 KiB, writes one chunk, then hangs.
    private static final class StallServer implements AutoCloseable {
        private final HttpServer server;

        private StallServer(HttpServer server) {
            this.server = server;
        }

        /// Starts the server on a loopback port.
        ///
        /// @return running server
        static StallServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            StallServer result = new StallServer(server);
            server.createContext("/payload.bin", StallServer::handle);
            server.start();
            return result;
        }

        /// Download URL of the stalled payload.
        ///
        /// @return absolute URL
        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/payload.bin";
        }

        /// Writes 1 KiB, flushes, and then blocks long enough for the watchdog.
        private static void handle(HttpExchange exchange) throws IOException {
            exchange.sendResponseHeaders(200, 200_000);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(new byte[1024]);
                out.flush();
                try {
                    Thread.sleep(4_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
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
