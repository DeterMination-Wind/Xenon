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
package determination.xenon.mindustry.community;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Slot;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.SlotPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests cloud-save listing, upload URL resolution and verified downloads.
@NotNullByDefault
public final class MdtbbsCloudSavesClientTest {
    /// Slot fields come from `current_snapshot`, and a non-blank `next_cursor`
    /// means another page exists.
    @Test
    public void parsesSlotsWithCurrentSnapshotAndCursor() throws Exception {
        try (CloudServer server = CloudServer.start()) {
            SlotPage page = server.client().slots(null, 30);

            assertEquals(2, page.slots().size());
            Slot current = page.slots().get(0);
            assertEquals("slot1", current.id());
            assertEquals("My save", current.name());
            assertEquals(2048, current.size());
            assertEquals(4, current.revision());

            Slot legacy = page.slots().get(1);
            assertEquals(777, legacy.size());
            assertEquals(0, legacy.revision());

            assertEquals("cursor-1", page.nextCursor());
            assertTrue(page.hasMore());
        }
    }

    /// The upload PUT must target the `/api/v1/...` path returned by the
    /// server without adding the version prefix twice.
    @Test
    public void uploadsThroughReturnedApiPath() throws Exception {
        try (CloudServer server = CloudServer.start()) {
            Path file = Files.createTempFile("xenon-cloud-upload", ".msav");
            Files.writeString(file, "save-bytes");

            String snapshotId = server.client().upload("slot1", file, null);

            assertEquals("snap1", snapshotId);
            assertEquals("PUT /api/v1/game-saves/uploads/u1/file", server.lastUpload());
        }
    }

    /// The download GET must use the returned API path and pass SHA-256
    /// verification.
    @Test
    public void downloadsSnapshotWithHashVerification() throws Exception {
        try (CloudServer server = CloudServer.start()) {
            Path target = Files.createTempFile("xenon-cloud-download", ".msav");

            Path result = server.client().download("slot1", "snap1", target, null);

            assertEquals(target, result);
            assertEquals("cloud-save-bytes", Files.readString(target));
            assertEquals("GET /api/v1/game-saves/slot1/snapshots/snap1/file",
                    server.lastDownload());
        }
    }

    /// Local fixture server serving the cloud-save routes.
    private static final class CloudServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> lastUpload = new AtomicReference<>("");
        private final AtomicReference<String> lastDownload = new AtomicReference<>("");

        private CloudServer(HttpServer server) {
            this.server = server;
        }

        static CloudServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CloudServer fixture = new CloudServer(server);
            server.createContext("/api/v1", fixture::handle);
            server.start();
            return fixture;
        }

        MdtbbsCloudSavesClient client() {
            MdtbbsApiClient api = new MdtbbsApiClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                    () -> "token");
            return new MdtbbsCloudSavesClient(api);
        }

        String lastUpload() {
            return lastUpload.get();
        }

        String lastDownload() {
            return lastDownload.get();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            exchange.getRequestBody().readAllBytes();
            if (path.equals("/api/v1/capabilities") && method.equals("GET")) {
                exchange.getResponseHeaders().add("Set-Cookie", "csrf_token=cookie-1; Path=/");
                respond(exchange, 200, "{\"data\":{}}");
                return;
            }
            if (path.equals("/api/v1/game-saves") && method.equals("GET")) {
                respond(exchange, 200, SLOTS_JSON);
                return;
            }
            if (path.equals("/api/v1/game-saves/slot1/uploads") && method.equals("POST")) {
                respond(exchange, 200, UPLOAD_JSON);
                return;
            }
            if (path.equals("/api/v1/game-saves/uploads/u1/file") && method.equals("PUT")) {
                lastUpload.set(method + " " + path);
                respond(exchange, 200, "{}");
                return;
            }
            if (path.equals("/api/v1/game-saves/uploads/u1/commit") && method.equals("POST")) {
                respond(exchange, 200, "{\"data\":{\"snapshot_id\":\"snap1\"}}");
                return;
            }
            if (path.equals("/api/v1/game-saves/slot1/snapshots/snap1/download")
                    && method.equals("POST")) {
                respond(exchange, 200, DOWNLOAD_JSON);
                return;
            }
            if (path.equals("/api/v1/game-saves/slot1/snapshots/snap1/file")
                    && method.equals("GET")) {
                lastDownload.set(method + " " + path);
                respond(exchange, 200, SAVE_CONTENT);
                return;
            }
            respond(exchange, 404, "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"missing\"}}");
        }

        private static void respond(HttpExchange exchange, int status, String body)
                throws IOException {
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

    private static final String SLOTS_JSON = """
            {"data":[
              {"id":"slot1","name":"My save","updated_at":"2026-09-30T12:00:00Z",
               "current_snapshot":{"size":2048,"revision":4}},
              {"id":"slot2","name":"Legacy save","updated_at":"2026-09-29T12:00:00Z",
               "size":777,"snapshot_count":3}
            ],"meta":{"next_cursor":"cursor-1"}}
            """;

    private static final String UPLOAD_JSON = """
            {"data":{"upload_id":"u1","upload":{
              "method":"PUT","url":"/api/v1/game-saves/uploads/u1/file",
              "headers":{"Content-Type":"application/octet-stream"}
            }}}
            """;

    private static final String DOWNLOAD_JSON = """
            {"data":{"download":{
              "method":"GET","url":"/api/v1/game-saves/slot1/snapshots/snap1/file",
              "sha256":"7c03be12098f04a3d058e90bb78b9e3503dcf15fc28b740d52cadd75fcde1493"
            }}}
            """;

    private static final String SAVE_CONTENT = "cloud-save-bytes";
}
