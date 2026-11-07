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
package determination.xenon.mindustry.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.MindustryVersion.CloudMode;
import determination.xenon.mindustry.DataDirectoryPolicy;
import determination.xenon.mindustry.cloud.MindustryCloudManifestPlan;
import determination.xenon.mindustry.community.MdtbbsApiClient;
import determination.xenon.mindustry.community.MdtbbsApiException;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient;
import determination.xenon.mindustry.ui.MindustryCloudArchiveService.Outcome;
import determination.xenon.mindustry.ui.MindustryCloudArchiveService.Result;
import determination.xenon.setting.ConfigHolder;
import determination.xenon.setting.GlobalConfig;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the delta upload and restore flow of the whole-instance archive service.
@NotNullByDefault
public final class MindustryCloudArchiveServiceTest {
    /// Reflective handle to the global config, installed for every test.
    private @Nullable Field globalConfigField;

    /// Global config captured before the stub was installed.
    private @Nullable Object previousGlobalConfig;

    /// `xenon.cloud.delta` value captured before the test enabled the path.
    private @Nullable String previousDeltaProperty;

    /// Installs a fresh global config so the upload metadata can resolve a
    /// device id without the full launcher bootstrap, and forces the manifest
    /// path on so the assertions do not depend on the host properties.
    @BeforeEach
    public void installGlobalConfig() throws Exception {
        globalConfigField = ConfigHolder.class.getDeclaredField("globalConfigInstance");
        globalConfigField.setAccessible(true);
        previousGlobalConfig = globalConfigField.get(null);
        globalConfigField.set(null, new GlobalConfig());
        previousDeltaProperty = System.getProperty(MindustryCloudManifestPlan.DELTA_PROPERTY);
        System.setProperty(MindustryCloudManifestPlan.DELTA_PROPERTY, "true");
    }

    /// Restores the global config and the delta property the test replaced.
    @AfterEach
    public void restoreGlobalConfig() throws Exception {
        @Nullable Field field = globalConfigField;
        if (field != null) field.set(null, previousGlobalConfig);
        @Nullable String delta = previousDeltaProperty;
        if (delta == null) {
            System.clearProperty(MindustryCloudManifestPlan.DELTA_PROPERTY);
        } else {
            System.setProperty(MindustryCloudManifestPlan.DELTA_PROPERTY, delta);
        }
    }

    /// Every planned blob already on the server produces one check and one
    /// manifest commit carrying the slot head and full metadata.
    @Test
    public void uploadsManifestWithoutTransferringExistingBlobs(@TempDir Path root)
            throws Exception {
        seedInstance(root);
        seedMod(root);
        try (CloudServer server = CloudServer.start()) {
            server.formats = List.of("manifest");
            server.archiveHasSnapshot = true;
            MindustryCloudArchiveService service =
                    new MindustryCloudArchiveService(server.client(), version(), root);

            Result result = service.uploadNow("manual");

            assertEquals(Outcome.UPLOADED, result.outcome());
            assertEquals("snap-manifest", result.snapshotId());
            assertEquals(List.of("POST /api/v1/game-saves/blobs/check",
                    "POST /api/v1/game-saves/slot1/snapshots"), writes(server));
            String body = server.lastManifestBody();
            assertTrue(body.contains("\"format\":\"manifest\""));
            assertTrue(body.contains("\"path\":\"saves/a.msav\""));
            assertTrue(body.contains("\"base_snapshot_id\":\"snap-manifest\""));
            assertTrue(body.contains("\"reason\":\"manual\""));
            assertTrue(body.contains("\"device_id\":\""));
            assertTrue(body.contains("\"version\":\"v157\""));
            assertTrue(body.contains("\"build\":157"));
            assertTrue(body.contains("\"id\":\"test-mod\""));
            assertTrue(body.contains("\"version\":\"1.2.3\""));
        }
    }

    /// A missing blob is created, uploaded and then committed with the manifest.
    @Test
    public void uploadsOnlyMissingManifestBlobs(@TempDir Path root) throws Exception {
        seedInstance(root);
        try (CloudServer server = CloudServer.start()) {
            server.formats = List.of("manifest");
            server.blobMissingFirst = true;
            MindustryCloudArchiveService service =
                    new MindustryCloudArchiveService(server.client(), version(), root);

            Result result = service.uploadNow("manual");

            assertEquals(Outcome.UPLOADED, result.outcome());
            assertEquals(List.of("POST /api/v1/game-saves/blobs/check",
                    "POST /api/v1/game-saves/blobs",
                    "PUT /api/v1/game-saves/blobs/b1/file",
                    "POST /api/v1/game-saves/slot1/snapshots"), writes(server));
        }
    }

    /// Manual archive actions are rejected for the shared global data directory.
    @Test
    public void rejectsGlobalDataDirectoryForManualActions(@TempDir Path root) throws Exception {
        try (CloudServer server = CloudServer.start()) {
            MindustryVersion version = version();
            version.setDataDirPolicy(DataDirectoryPolicy.GLOBAL);
            MindustryCloudArchiveService service =
                    new MindustryCloudArchiveService(server.client(), version, root);

            assertThrows(IOException.class, () -> service.uploadNow("manual"));
            assertThrows(IOException.class, service::restoreNow);
            assertTrue(server.requests().isEmpty());
        }
    }

    /// A snapshot whose file count disagrees with the manifest is rejected.
    @Test
    public void rejectsManifestFileCountMismatch(@TempDir Path root) throws Exception {
        byte[] save = "save-a".getBytes(StandardCharsets.UTF_8);
        MindustryVersion version = version();
        Path dataDir = version.resolveDataDir(root);
        write(dataDir.resolve("saves/a.msav"), save);
        try (CloudServer server = CloudServer.start()) {
            server.archiveHasSnapshot = true;
            server.archiveFiles = List.of(new FixtureFile("saves/a.msav", save));
            server.snapshotFileCount = 2;
            MindustryCloudArchiveService service =
                    new MindustryCloudArchiveService(server.client(), version, root);

            assertThrows(IOException.class, service::restoreNow);
            assertArrayEquals(save, Files.readAllBytes(dataDir.resolve("saves/a.msav")));
        }
    }

    /// Server-provided snapshot names cannot escape the saves directory.
    @Test
    public void rejectsTraversalInSnapshotFileName() {
        assertTrue(MdtbbsCloudSaveDialog.safeSaveName("..\\..\\evil.msav").isEmpty());
        assertTrue(MdtbbsCloudSaveDialog.safeSaveName("/etc/passwd").isEmpty());
        assertTrue(MdtbbsCloudSaveDialog.safeSaveName("C:\\evil.msav").isEmpty());
        assertTrue(MdtbbsCloudSaveDialog.safeSaveName("..").isEmpty());
        assertTrue(MdtbbsCloudSaveDialog.safeSaveName(".").isEmpty());
        assertEquals("a.msav", MdtbbsCloudSaveDialog.safeSaveName("a.msav"));
        assertEquals("cloud-snap1.msav",
                MdtbbsCloudSaveDialog.safeSaveName("cloud-snap1.msav"));
    }

    /// A rejected manifest commit falls back to the zip flow exactly once.
    @Test
    public void fallsBackToZipWhenManifestIsRejected(@TempDir Path root) throws Exception {
        seedInstance(root);
        try (CloudServer server = CloudServer.start()) {
            server.formats = List.of("manifest");
            server.manifestErrorCode = "SAVE_MANIFEST_INVALID";
            MindustryCloudArchiveService service =
                    new MindustryCloudArchiveService(server.client(), version(), root);

            Result result = service.uploadNow("manual");

            assertEquals(Outcome.UPLOADED, result.outcome());
            assertEquals("snap-zip", result.snapshotId());
            List<String> writes = writes(server);
            assertEquals(1, writes.stream()
                    .filter("POST /api/v1/game-saves/slot1/snapshots"::equals)
                    .count());
            assertTrue(writes.contains("PUT /api/v1/game-saves/uploads/u1/file"));
            assertTrue(writes.indexOf("POST /api/v1/game-saves/slot1/snapshots")
                    < writes.indexOf("PUT /api/v1/game-saves/uploads/u1/file"));
            assertEquals("POST /api/v1/game-saves/uploads/u1/commit",
                    writes.get(writes.size() - 1));
        }
    }

    /// A server-side quota rejection of the manifest also degrades to zip.
    @Test
    public void fallsBackToZipWhenManifestQuotaIsExceeded(@TempDir Path root) throws Exception {
        seedInstance(root);
        try (CloudServer server = CloudServer.start()) {
            server.formats = List.of("manifest");
            server.manifestErrorCode = "SAVE_QUOTA_EXCEEDED";
            MindustryCloudArchiveService service =
                    new MindustryCloudArchiveService(server.client(), version(), root);

            Result result = service.uploadNow("manual");

            assertEquals(Outcome.UPLOADED, result.outcome());
            assertEquals("snap-zip", result.snapshotId());
            assertEquals(1, writes(server).stream()
                    .filter("POST /api/v1/game-saves/slot1/snapshots"::equals)
                    .count());
        }
    }

    /// A zip-mode conflict is reported and never retried as a manifest.
    @Test
    public void reportsZipConflictWithoutManifestFallback(@TempDir Path root) throws Exception {
        seedInstance(root);
        try (CloudServer server = CloudServer.start()) {
            server.formats = List.of("zip");
            server.zipConflict = true;
            MindustryCloudArchiveService service =
                    new MindustryCloudArchiveService(server.client(), version(), root);

            Result result = service.uploadNow("manual");

            assertEquals(Outcome.CONFLICT, result.outcome());
            assertEquals("snap-head", result.snapshotId());
            assertEquals(List.of("POST /api/v1/game-saves/slot1/uploads"), writes(server));
        }
    }

    /// Only files that are missing or hash-mismatched are downloaded, and the
    /// restored data directory matches the manifest.
    @Test
    public void restoresManifestDownloadingOnlyChangedBlobs(@TempDir Path root) throws Exception {
        byte[] saveA = "save-a".getBytes(StandardCharsets.UTF_8);
        byte[] saveB = "save-b".getBytes(StandardCharsets.UTF_8);
        MindustryVersion version = version();
        Path dataDir = version.resolveDataDir(root);
        write(dataDir.resolve("saves/a.msav"), saveA);
        write(dataDir.resolve("saves/b.msav"), "save-x".getBytes(StandardCharsets.UTF_8));
        try (CloudServer server = CloudServer.start()) {
            server.archiveHasSnapshot = true;
            server.archiveFiles = List.of(new FixtureFile("saves/a.msav", saveA),
                    new FixtureFile("saves/b.msav", saveB));
            MindustryCloudArchiveService service =
                    new MindustryCloudArchiveService(server.client(), version, root);

            Result result = service.restoreNow();

            assertEquals(Outcome.RESTORED, result.outcome());
            assertEquals("snap-manifest", result.snapshotId());
            assertTrue(server.lastBlobUrl().startsWith("/api/v1/"));
            assertArrayEquals(saveA, Files.readAllBytes(dataDir.resolve("saves/a.msav")));
            assertArrayEquals(saveB, Files.readAllBytes(dataDir.resolve("saves/b.msav")));
            String unchangedHash = sha256(saveA);
            String changedHash = sha256(saveB);
            assertFalse(server.requests().contains("GET /api/v1/blobs/" + unchangedHash));
            assertTrue(server.requests().contains("GET /api/v1/blobs/" + changedHash));
            JsonObject request = JsonParser.parseString(server.lastBlobRequest())
                    .getAsJsonObject();
            JsonArray hashes = request.getAsJsonArray("sha256");
            assertEquals(1, hashes.size());
            assertEquals(changedHash, hashes.get(0).getAsString());
        }
    }

    /// An unsupported manifest token is rejected before any local file changes.
    @Test
    public void rejectsUnsupportedSnapshotFormat(@TempDir Path root) throws Exception {
        byte[] save = "save-a".getBytes(StandardCharsets.UTF_8);
        MindustryVersion version = version();
        Path dataDir = version.resolveDataDir(root);
        write(dataDir.resolve("saves/a.msav"), save);
        try (CloudServer server = CloudServer.start()) {
            server.archiveHasSnapshot = true;
            server.snapshotFormat = "tar";
            MindustryCloudArchiveService service =
                    new MindustryCloudArchiveService(server.client(), version, root);

            MdtbbsApiException failure = assertThrows(MdtbbsApiException.class,
                    service::restoreNow);

            assertEquals("SAVE_SNAPSHOT_FORMAT_MISMATCH", failure.getErrorCode());
            assertArrayEquals(save, Files.readAllBytes(dataDir.resolve("saves/a.msav")));
        }
    }

    /// A blob shared by two manifest paths is downloaded once and copied to the
    /// second staged path.
    @Test
    public void restoresSharedBlobIntoBothPaths(@TempDir Path root) throws Exception {
        byte[] shared = "shared-save".getBytes(StandardCharsets.UTF_8);
        MindustryVersion version = version();
        Path dataDir = version.resolveDataDir(root);
        try (CloudServer server = CloudServer.start()) {
            server.archiveHasSnapshot = true;
            server.archiveFiles = List.of(
                    new FixtureFile("saves/a.msav", shared),
                    new FixtureFile("saves/b.msav", shared));
            MindustryCloudArchiveService service =
                    new MindustryCloudArchiveService(server.client(), version, root);

            Result result = service.restoreNow();

            assertEquals(Outcome.RESTORED, result.outcome());
            assertArrayEquals(shared, Files.readAllBytes(dataDir.resolve("saves/a.msav")));
            assertArrayEquals(shared, Files.readAllBytes(dataDir.resolve("saves/b.msav")));
            String hash = sha256(shared);
            assertEquals(1, server.requests().stream()
                    .filter(("GET /api/v1/blobs/" + hash)::equals)
                    .count());
            JsonObject request = JsonParser.parseString(server.lastBlobRequest())
                    .getAsJsonObject();
            assertEquals(1, request.getAsJsonArray("sha256").size());
        }
    }

    /// A local file whose recorded size differs is re-downloaded; the
    /// inconsistent manifest is then rejected before the data directory changes.
    @Test
    public void redownloadsLocalFileWithRecordedSizeMismatch(@TempDir Path root)
            throws Exception {
        byte[] save = "save-a".getBytes(StandardCharsets.UTF_8);
        MindustryVersion version = version();
        Path dataDir = version.resolveDataDir(root);
        write(dataDir.resolve("saves/a.msav"), save);
        try (CloudServer server = CloudServer.start()) {
            server.archiveHasSnapshot = true;
            server.archiveFiles = List.of(new FixtureFile("saves/a.msav", save,
                    save.length + 1L));
            MindustryCloudArchiveService service =
                    new MindustryCloudArchiveService(server.client(), version, root);

            assertThrows(IOException.class, service::restoreNow);

            assertTrue(server.requests().contains("GET /api/v1/blobs/" + sha256(save)));
            assertArrayEquals(save, Files.readAllBytes(dataDir.resolve("saves/a.msav")));
        }
    }

    /// Builds an instance descriptor pointed at the archive slot.
    private static MindustryVersion version() {
        MindustryVersion version = new MindustryVersion();
        version.setId("v157");
        version.setBuild(157);
        version.setCloudArchiveSlotId("slot1");
        version.setCloudMode(CloudMode.INSTANCE_ARCHIVE);
        version.setCloudAutoSync(true);
        return version;
    }

    /// Seeds the instance data directory with one save file.
    private static void seedInstance(Path root) throws IOException {
        write(version().resolveDataDir(root).resolve("saves/a.msav"),
                "save-a".getBytes(StandardCharsets.UTF_8));
    }

    /// Seeds a parseable Mod jar so upload metadata carries Mod details.
    private static void seedMod(Path root) throws IOException {
        Path modsDir = version().resolveDataDir(root).resolve("mods");
        Files.createDirectories(modsDir);
        Path jar = modsDir.resolve("test-mod.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("mod.json"));
            zip.write(("{\"name\":\"test-mod\",\"displayName\":\"Test Mod\","
                    + "\"version\":\"1.2.3\"}").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    /// Writes one file and creates its parents.
    private static void write(Path file, byte[] content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(file, content);
    }

    /// Lists the mutating requests the service sent.
    private static List<String> writes(CloudServer server) {
        return server.requests().stream()
                .filter(request -> request.startsWith("POST ") || request.startsWith("PUT "))
                .toList();
    }

    /// Computes the lowercase hex SHA-256 of a byte array.
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    /// One file the fixture publishes in a manifest snapshot.
    ///
    /// @param path         slash-separated manifest path
    /// @param content      file bytes served by the raw blob endpoint
    /// @param declaredSize size advertised in the manifest
    private record FixtureFile(String path, byte[] content, long declaredSize) {

        /// Builds an entry whose declared size matches its content.
        ///
        /// @param path    slash-separated manifest path
        /// @param content file bytes
        FixtureFile(String path, byte[] content) {
            this(path, content, content.length);
        }
    }

    /// Local fixture serving the cloud archive routes.
    private static final class CloudServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newFixedThreadPool(8);
        private final List<String> requests = new CopyOnWriteArrayList<>();
        volatile List<String> formats = List.of("manifest");
        volatile boolean blobMissingFirst;
        volatile String manifestErrorCode = "";
        volatile boolean zipConflict;
        volatile boolean archiveHasSnapshot;
        volatile int snapshotFileCount = -1;
        volatile String snapshotFormat = "manifest";
        volatile List<FixtureFile> archiveFiles = List.of();
        private volatile String lastManifestBody = "";
        private volatile String lastBlobRequest = "";
        private volatile String lastBlobUrl = "";

        private CloudServer(HttpServer server) {
            this.server = server;
        }

        static CloudServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CloudServer fixture = new CloudServer(server);
            server.createContext("/api/v1", fixture::handle);
            server.setExecutor(fixture.executor);
            server.start();
            return fixture;
        }

        MdtbbsCloudSavesClient client() {
            try {
                Constructor<MdtbbsApiClient> constructor = MdtbbsApiClient.class
                        .getDeclaredConstructor(String.class, HttpClient.class,
                                MdtbbsApiClient.TokenProvider.class);
                constructor.setAccessible(true);
                MdtbbsApiClient api = constructor.newInstance(baseUrl(),
                        HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .build(),
                        (MdtbbsApiClient.TokenProvider) () -> "token");
                return new MdtbbsCloudSavesClient(api);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("cannot create the MDTBBS API client", e);
            }
        }

        List<String> requests() {
            return List.copyOf(requests);
        }

        String lastManifestBody() {
            return lastManifestBody;
        }

        String lastBlobRequest() {
            return lastBlobRequest;
        }

        String lastBlobUrl() {
            return lastBlobUrl;
        }

        private String baseUrl() {
            return origin() + "/api/v1";
        }

        private String origin() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            byte[] request = exchange.getRequestBody().readAllBytes();
            requests.add(method + " " + path);
            if (path.equals("/api/v1/capabilities") && method.equals("GET")) {
                exchange.getResponseHeaders().add("Set-Cookie",
                        "csrf_token=cookie-1; Path=/");
                respondJson(exchange, 200, envelope(new JsonObject()));
                return;
            }
            if (path.equals("/api/v1/game-saves/quota") && method.equals("GET")) {
                respondJson(exchange, 200, envelope(quota()));
                return;
            }
            if (path.equals("/api/v1/game-saves/slot1") && method.equals("GET")) {
                respondJson(exchange, 200, envelope(slot()));
                return;
            }
            if (path.equals("/api/v1/game-saves/slot1/snapshots") && method.equals("GET")) {
                JsonArray data = new JsonArray();
                if (archiveHasSnapshot) data.add(snapshot());
                respondJson(exchange, 200, envelope(data));
                return;
            }
            if (path.equals("/api/v1/game-saves/blobs/check") && method.equals("POST")) {
                respondJson(exchange, 200, envelope(blobCheck(request)));
                return;
            }
            if (path.equals("/api/v1/game-saves/blobs") && method.equals("POST")) {
                JsonObject data = new JsonObject();
                data.addProperty("upload_id", "b1");
                JsonObject upload = new JsonObject();
                upload.addProperty("method", "PUT");
                upload.addProperty("url", "/api/v1/game-saves/blobs/b1/file");
                data.add("upload", upload);
                respondJson(exchange, 200, envelope(data));
                return;
            }
            if (path.equals("/api/v1/game-saves/blobs/b1/file") && method.equals("PUT")) {
                respondJson(exchange, 200, envelope(new JsonObject()));
                return;
            }
            if (path.equals("/api/v1/game-saves/slot1/snapshots") && method.equals("POST")) {
                lastManifestBody = new String(request, StandardCharsets.UTF_8);
                if (!manifestErrorCode.isBlank()) {
                    respondError(exchange, 409, manifestErrorCode, "manifest rejected");
                } else {
                    JsonObject data = new JsonObject();
                    data.addProperty("snapshot_id", "snap-manifest");
                    data.addProperty("format", formats.isEmpty() ? "manifest" : formats.get(0));
                    data.addProperty("file_count", 1);
                    respondJson(exchange, 200, envelope(data));
                }
                return;
            }
            if (path.equals("/api/v1/game-saves/slot1/snapshots/snap-manifest/manifest")
                    && method.equals("GET")) {
                respondJson(exchange, 200, envelope(remoteManifest()));
                return;
            }
            if (path.equals("/api/v1/game-saves/slot1/snapshots/snap-manifest/blobs/download")
                    && method.equals("POST")) {
                lastBlobRequest = new String(request, StandardCharsets.UTF_8);
                respondJson(exchange, 200, envelope(blobUrls(request)));
                return;
            }
            if (path.startsWith("/api/v1/blobs/") && method.equals("GET")) {
                respondBlob(exchange, path.substring("/api/v1/blobs/".length()));
                return;
            }
            if (path.equals("/api/v1/game-saves/slot1/uploads") && method.equals("POST")) {
                if (zipConflict) {
                    respondConflict(exchange);
                } else {
                    JsonObject data = new JsonObject();
                    data.addProperty("upload_id", "u1");
                    JsonObject upload = new JsonObject();
                    upload.addProperty("method", "PUT");
                    upload.addProperty("url", "/api/v1/game-saves/uploads/u1/file");
                    data.add("upload", upload);
                    respondJson(exchange, 200, envelope(data));
                }
                return;
            }
            if (path.equals("/api/v1/game-saves/uploads/u1/file") && method.equals("PUT")) {
                respondJson(exchange, 200, envelope(new JsonObject()));
                return;
            }
            if (path.equals("/api/v1/game-saves/uploads/u1/commit") && method.equals("POST")) {
                JsonObject data = new JsonObject();
                data.addProperty("snapshot_id", "snap-zip");
                respondJson(exchange, 200, envelope(data));
                return;
            }
            respondError(exchange, 404, "NOT_FOUND", "missing");
        }

        /// Builds the quota payload advertised to the client.
        private JsonObject quota() {
            JsonObject data = new JsonObject();
            data.addProperty("used_bytes", 0);
            data.addProperty("quota_bytes", 1_000_000_000L);
            data.addProperty("max_file_bytes", 1_000_000_000L);
            JsonArray advertised = new JsonArray();
            for (String format : formats) advertised.add(format);
            data.add("formats", advertised);
            JsonObject limits = new JsonObject();
            limits.addProperty("max_files", 10_000);
            limits.addProperty("max_manifest_bytes", 10_000_000L);
            data.add("manifest_limits", limits);
            return data;
        }

        /// Builds the archive slot payload, including the head only when the
        /// fixture claims a snapshot.
        private JsonObject slot() {
            JsonObject data = new JsonObject();
            data.addProperty("id", "slot1");
            data.addProperty("name", "Archive");
            data.addProperty("updated_at", "2026-10-01T00:00:00Z");
            if (archiveHasSnapshot) {
                JsonObject current = new JsonObject();
                current.addProperty("id", "snap-manifest");
                current.addProperty("size", 100);
                current.addProperty("revision", 1);
                data.add("current_snapshot", current);
            }
            return data;
        }

        /// Builds one manifest snapshot entry.
        private JsonObject snapshot() {
            JsonObject snapshot = new JsonObject();
            snapshot.addProperty("id", "snap-manifest");
            snapshot.addProperty("size", 100);
            snapshot.addProperty("sha256", "");
            snapshot.addProperty("file_name", "instance");
            snapshot.addProperty("created_at", "2026-10-01T00:00:00Z");
            snapshot.addProperty("revision", 1);
            snapshot.addProperty("format", snapshotFormat);
            snapshot.addProperty("file_count", snapshotFileCount >= 0
                    ? snapshotFileCount : archiveFiles.size());
            snapshot.add("mods", new JsonArray());
            return snapshot;
        }

        /// Splits the requested hashes into existing and missing entries.
        private JsonObject blobCheck(byte[] request) {
            JsonArray requested = requestedHashes(request);
            JsonArray existing = new JsonArray();
            JsonArray missing = new JsonArray();
            boolean first = true;
            for (JsonElement element : requested) {
                boolean absent = blobMissingFirst && first;
                first = false;
                if (absent) {
                    missing.add(element.getAsString());
                } else {
                    existing.add(element.getAsString());
                }
            }
            JsonObject data = new JsonObject();
            data.add("existing", existing);
            data.add("missing", missing);
            return data;
        }

        /// Builds the remote manifest from the configured files.
        private JsonObject remoteManifest() {
            JsonObject manifest = new JsonObject();
            manifest.addProperty("format", "manifest");
            JsonArray files = new JsonArray();
            for (FixtureFile file : archiveFiles) {
                JsonObject entry = new JsonObject();
                entry.addProperty("path", file.path());
                entry.addProperty("size", file.declaredSize());
                entry.addProperty("sha256", sha256(file.content()));
                files.add(entry);
            }
            manifest.add("files", files);
            JsonObject data = new JsonObject();
            data.addProperty("snapshot_id", "snap-manifest");
            data.add("manifest", manifest);
            return data;
        }

        /// Builds signed download URLs for the requested hashes.
        private JsonObject blobUrls(byte[] request) {
            JsonArray blobs = new JsonArray();
            for (JsonElement element : requestedHashes(request)) {
                String hash = element.getAsString();
                FixtureFile file = findByHash(hash);
                if (file == null) continue;
                JsonObject blob = new JsonObject();
                blob.addProperty("sha256", hash);
                lastBlobUrl = "/api/v1/blobs/" + hash;
                blob.addProperty("url", lastBlobUrl);
                blob.addProperty("size", (long) file.content().length);
                blobs.add(blob);
            }
            JsonObject data = new JsonObject();
            data.add("blobs", blobs);
            return data;
        }

        /// Serves one raw blob.
        private void respondBlob(HttpExchange exchange, String hash) throws IOException {
            @Nullable FixtureFile file = findByHash(hash);
            if (file == null) {
                respondError(exchange, 404, "NOT_FOUND", "missing blob");
                return;
            }
            byte[] bytes = file.content();
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } finally {
                exchange.close();
            }
        }

        /// Finds a configured file by its digest.
        private @Nullable FixtureFile findByHash(String hash) {
            for (FixtureFile file : archiveFiles) {
                if (sha256(file.content()).equals(hash)) return file;
            }
            return null;
        }

        /// Reads the `sha256` array of a request body.
        private static JsonArray requestedHashes(byte[] request) {
            if (request.length == 0) return new JsonArray();
            try {
                JsonElement root = JsonParser.parseString(
                        new String(request, StandardCharsets.UTF_8));
                if (!root.isJsonObject()) return new JsonArray();
                JsonElement hashes = root.getAsJsonObject().get("sha256");
                return hashes != null && hashes.isJsonArray()
                        ? hashes.getAsJsonArray() : new JsonArray();
            } catch (RuntimeException e) {
                return new JsonArray();
            }
        }

        /// Responds with a save conflict carrying the current head.
        private static void respondConflict(HttpExchange exchange) throws IOException {
            JsonObject error = new JsonObject();
            error.addProperty("code", "SAVE_CONFLICT");
            error.addProperty("message", "conflict");
            error.addProperty("retryable", false);
            JsonArray details = new JsonArray();
            JsonObject detail = new JsonObject();
            detail.addProperty("current_snapshot_id", "snap-head");
            details.add(detail);
            error.add("details", details);
            JsonObject root = new JsonObject();
            root.add("error", error);
            respondJson(exchange, 409, root);
        }

        /// Wraps one payload in the `data` envelope.
        private static JsonObject envelope(JsonElement data) {
            JsonObject root = new JsonObject();
            root.add("data", data);
            return root;
        }

        /// Responds with a structured API error.
        private static void respondError(HttpExchange exchange, int status,
                                         String code, String message) throws IOException {
            JsonObject error = new JsonObject();
            error.addProperty("code", code);
            error.addProperty("message", message);
            error.addProperty("retryable", false);
            JsonObject root = new JsonObject();
            root.add("error", error);
            respondJson(exchange, status, root);
        }

        /// Writes a JSON response.
        private static void respondJson(HttpExchange exchange, int status, JsonElement body)
                throws IOException {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
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
            executor.shutdownNow();
        }
    }
}
