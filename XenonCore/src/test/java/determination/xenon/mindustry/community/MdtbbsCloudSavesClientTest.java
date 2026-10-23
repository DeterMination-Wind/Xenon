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
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.DownloadResult;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.GameInfo;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.ModInfo;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Quota;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.SaveInfo;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.UploadMetadata;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.UploadOptions;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.UploadResult;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    /// server without adding the version prefix twice, and it must report
    /// monotonically increasing progress that ends at the file size.
    @Test
    public void uploadsThroughReturnedApiPath() throws Exception {
        try (CloudServer server = CloudServer.start()) {
            Path file = Files.createTempFile("xenon-cloud-upload", ".msav");
            Files.writeString(file, "save-bytes");
            long size = Files.size(file);
            List<long[]> reports = new ArrayList<>();

            String snapshotId = server.client().upload("slot1", file, null,
                    (read, total) -> reports.add(new long[]{read, total}));

            assertEquals("snap1", snapshotId);
            assertEquals("PUT /api/v1/game-saves/uploads/u1/file", server.lastUpload());
            assertFalse(reports.isEmpty(), "the upload must report progress");
            long previous = -1;
            for (long[] report : reports) {
                assertTrue(report[0] > previous, "upload progress must increase");
                previous = report[0];
                assertEquals(size, report[1]);
            }
            assertEquals(size, previous, "the final report must equal the file size");
            assertEquals("", server.lastRelease(),
                    "a committed upload must keep its session");
        }
    }

    /// A failed PUT hands the created upload session back to the server.
    @Test
    public void releasesUploadSessionWhenPutFails() throws Exception {
        try (CloudServer server = CloudServer.start()) {
            server.failPut();
            Path file = Files.createTempFile("xenon-cloud-upload", ".msav");
            Files.writeString(file, "save-bytes");

            IOException failure = assertThrows(IOException.class,
                    () -> server.client().upload("slot1", file, null));

            assertEquals("boom", failure.getMessage());
            assertEquals("DELETE /api/v1/game-saves/uploads/u1", server.lastRelease());
        }
    }

    /// A failed commit releases the session the PUT already filled.
    @Test
    public void releasesUploadSessionWhenCommitFails() throws Exception {
        try (CloudServer server = CloudServer.start()) {
            server.failCommit();
            Path file = Files.createTempFile("xenon-cloud-upload", ".msav");
            Files.writeString(file, "save-bytes");

            IOException failure = assertThrows(IOException.class,
                    () -> server.client().upload("slot1", file, null));

            assertEquals("boom", failure.getMessage());
            assertEquals("DELETE /api/v1/game-saves/uploads/u1", server.lastRelease());
        }
    }

    /// A failing release never replaces the original upload error.
    @Test
    public void keepsUploadErrorWhenReleaseFails() throws Exception {
        try (CloudServer server = CloudServer.start()) {
            server.failPut();
            server.failRelease();
            Path file = Files.createTempFile("xenon-cloud-upload", ".msav");
            Files.writeString(file, "save-bytes");

            IOException failure = assertThrows(IOException.class,
                    () -> server.client().upload("slot1", file, null));

            assertEquals("boom", failure.getMessage());
            assertEquals("DELETE /api/v1/game-saves/uploads/u1", server.lastRelease());
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

    /// Uploads send the documented conflict and metadata fields.
    @Test
    public void uploadsConflictAndMetadataFields() throws Exception {
        try (CloudServer server = CloudServer.start()) {
            Path file = Files.createTempFile("xenon-cloud-upload", ".msav");
            Files.writeString(file, "save-bytes");
            UploadMetadata metadata = new UploadMetadata(
                    new GameInfo("v157", 157),
                    new SaveInfo("Salt Flats", 42, 3600),
                    List.of(new ModInfo("example-mod", "Example", "1.2.0", "a".repeat(64))),
                    "device-1");
            UploadOptions options = new UploadOptions("after_exit", "snap0", "normal",
                    null, metadata);

            UploadResult result = server.client().uploadDetailed("slot1", file, options, null);

            assertEquals("snap1", result.snapshotId());
            assertFalse(result.noUploadRequired());
            String body = server.lastUploadBody();
            assertTrue(body.contains("\"reason\":\"after_exit\""));
            assertTrue(body.contains("\"conflict_resolution\":\"normal\""));
            assertTrue(body.contains("\"base_snapshot_id\":\"snap0\""));
            assertTrue(body.contains("\"map_name\":\"Salt Flats\""));
            assertTrue(body.contains("\"playtime_seconds\":3600"));
            assertTrue(body.contains("\"id\":\"example-mod\""));
            assertTrue(body.contains("\"device_id\":\"device-1\""));
        }
    }

    /// A `no_upload_required` response skips the PUT and commit.
    @Test
    public void skipsUploadWhenServerAlreadyHasContent() throws Exception {
        try (CloudServer server = CloudServer.start()) {
            server.noUploadRequired = true;
            Path file = Files.createTempFile("xenon-cloud-upload", ".msav");
            Files.writeString(file, "save-bytes");

            UploadResult result = server.client().uploadDetailed("slot1", file,
                    UploadOptions.normal(null), null);

            assertTrue(result.noUploadRequired());
            assertEquals("snap-current", result.snapshotId());
            assertEquals("", server.lastUpload());
        }
    }

    /// Quota parses the documented slot and retention limits.
    @Test
    public void parsesQuotaLimits() throws Exception {
        try (CloudServer server = CloudServer.start()) {
            Quota quota = server.client().quota();
            assertEquals(1000, quota.usedBytes());
            assertEquals(10_000, quota.quotaBytes());
            assertEquals(2048, quota.maxFileBytes());
            assertEquals(20, quota.slotLimit());
            assertEquals(3, quota.slotCount());
            assertEquals(10, quota.retentionVersions());
            assertEquals(30, quota.retentionDays());
        }
    }

    /// A conflict exposes the suggested resolution and current head.
    @Test
    public void exposesConflictDetails() throws Exception {
        try (CloudServer server = CloudServer.start()) {
            server.conflict = true;
            Path file = Files.createTempFile("xenon-cloud-upload", ".msav");
            Files.writeString(file, "save-bytes");

            MdtbbsApiException failure = assertThrows(MdtbbsApiException.class,
                    () -> server.client().uploadDetailed("slot1", file,
                            UploadOptions.normal("snap0"), null));

            assertEquals("SAVE_CONFLICT", failure.getErrorCode());
            assertEquals("create_conflict_copy", failure.suggestedResolution());
            assertEquals("snap-head", failure.currentSnapshotId());
        }
    }

    /// Downloads expose the ETag and Last-Modified validators.
    @Test
    public void capturesDownloadValidators() throws Exception {
        try (CloudServer server = CloudServer.start()) {
            Path target = Files.createTempFile("xenon-cloud-download", ".msav");
            DownloadResult result = server.client().downloadDetailed("slot1", "snap1", target, null);
            assertEquals("\"sha256-abc\"", result.etag());
            assertEquals("Wed, 01 Oct 2026 12:00:00 GMT", result.lastModified());
            assertEquals(16, result.size());
        }
    }

    /// Slot metadata parses the current head and snapshot display fields.
    @Test
    public void parsesHeadAndSnapshotMetadata() throws Exception {
        try (CloudServer server = CloudServer.start()) {
            Slot slot = server.client().slot("slot1");
            assertEquals("snap-head", slot.currentSnapshotId());
            var snapshots = server.client().snapshots("slot1");
            assertEquals("Salt Flats", snapshots.get(0).mapName());
            assertEquals(12, snapshots.get(0).wave());
            assertEquals(2, snapshots.get(0).modCount());
        }
    }

    /// Local fixture server serving the cloud-save routes.
    private static final class CloudServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> lastUpload = new AtomicReference<>("");
        private final AtomicReference<String> lastDownload = new AtomicReference<>("");
        private final AtomicReference<String> lastRelease = new AtomicReference<>("");
        private final AtomicInteger putStatus = new AtomicInteger(200);
        private final AtomicInteger commitStatus = new AtomicInteger(200);
        private final AtomicInteger releaseStatus = new AtomicInteger(200);
        private final AtomicReference<String> lastUploadBody = new AtomicReference<>("");
        private final AtomicReference<String> lastPatchBody = new AtomicReference<>("");
        volatile boolean noUploadRequired;
        volatile boolean conflict;

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

        String lastRelease() {
            return lastRelease.get();
        }

        String lastUploadBody() {
            return lastUploadBody.get();
        }

        void failPut() {
            putStatus.set(500);
        }

        void failCommit() {
            commitStatus.set(500);
        }

        void failRelease() {
            releaseStatus.set(500);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            byte[] request = exchange.getRequestBody().readAllBytes();
            if (path.equals("/api/v1/capabilities") && method.equals("GET")) {
                exchange.getResponseHeaders().add("Set-Cookie", "csrf_token=cookie-1; Path=/");
                respond(exchange, 200, "{\"data\":{}}");
                return;
            }
            if (path.equals("/api/v1/game-saves/quota") && method.equals("GET")) {
                respond(exchange, 200, QUOTA_JSON);
                return;
            }
            if (path.equals("/api/v1/game-saves") && method.equals("GET")) {
                respond(exchange, 200, SLOTS_JSON);
                return;
            }
            if (path.equals("/api/v1/game-saves/slot1/uploads") && method.equals("POST")) {
                lastUploadBody.set(new String(request, StandardCharsets.UTF_8));
                if (conflict) {
                    respond(exchange, 409, CONFLICT_JSON);
                } else if (noUploadRequired) {
                    respond(exchange, 200, NO_UPLOAD_JSON);
                } else {
                    respond(exchange, 200, UPLOAD_JSON);
                }
                return;
            }
            if (path.equals("/api/v1/game-saves/slot1") && method.equals("GET")) {
                respond(exchange, 200, SLOT_JSON);
                return;
            }
            if (path.equals("/api/v1/game-saves/slot1") && method.equals("PATCH")) {
                lastPatchBody.set(new String(request, StandardCharsets.UTF_8));
                respond(exchange, 200, SLOT_JSON);
                return;
            }
            if (path.equals("/api/v1/game-saves/slot1/snapshots") && method.equals("GET")) {
                respond(exchange, 200, SNAPSHOTS_JSON);
                return;
            }
            if (path.equals("/api/v1/game-saves/uploads/u1/file") && method.equals("PUT")) {
                lastUpload.set(method + " " + path);
                respond(exchange, putStatus.get(), statusBody(putStatus.get()));
                return;
            }
            if (path.equals("/api/v1/game-saves/uploads/u1") && method.equals("DELETE")) {
                lastRelease.set(method + " " + path);
                respond(exchange, releaseStatus.get(), statusBody(releaseStatus.get()));
                return;
            }
            if (path.equals("/api/v1/game-saves/uploads/u1/commit") && method.equals("POST")) {
                respond(exchange, commitStatus.get(), statusBody(commitStatus.get()));
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
                byte[] bytes = SAVE_CONTENT.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.getResponseHeaders().add("ETag", "\"sha256-abc\"");
                exchange.getResponseHeaders().add("Last-Modified",
                        "Wed, 01 Oct 2026 12:00:00 GMT");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                } finally {
                    exchange.close();
                }
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

        /// Success or error payload for the configurable status codes.
        private static String statusBody(int status) {
            if (status / 100 == 2) {
                return "{\"data\":{\"snapshot_id\":\"snap1\"}}";
            }
            return "{\"error\":{\"code\":\"INTERNAL\",\"message\":\"boom\"}}";
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

    private static final String QUOTA_JSON = """
            {"data":{"used_bytes":1000,"limit_bytes":10000,
              "max_file_size_bytes":2048,
              "slots":{"used":3,"limit":20},
              "retention":{"max_unpinned_versions_per_slot":10,"max_unpinned_age_days":30}}}
            """;

    private static final String NO_UPLOAD_JSON = """
            {"data":{"upload_id":"u1","no_upload_required":true,"snapshot_id":"snap-current"}}
            """;

    private static final String CONFLICT_JSON = """
            {"error":{"code":"SAVE_CONFLICT","message":"conflict","retryable":false,
              "details":[{"current_snapshot_id":"snap-head",
                          "suggested_resolution":"create_conflict_copy"}]}}
            """;

    private static final String SLOT_JSON = """
            {"data":{"id":"slot1","name":"My save","updated_at":"2026-09-30T12:00:00Z",
              "current_snapshot":{"id":"snap-head","size":2048,"revision":4}}}
            """;

    private static final String SNAPSHOTS_JSON = """
            {"data":[{"id":"snap1","size":2048,"sha256":"abc","file_name":"a.msav",
              "created_at":"2026-09-30T12:00:00Z","revision":4,
              "save":{"map_name":"Salt Flats","wave":12},
              "mods":[{"id":"a"},{"id":"b"}]}]}
            """;
}
