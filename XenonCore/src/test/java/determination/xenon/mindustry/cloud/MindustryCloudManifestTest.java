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
package determination.xenon.mindustry.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import determination.xenon.mindustry.cloud.MindustryCloudArchiveManifest.FileEntry;
import determination.xenon.mindustry.cloud.MindustryCloudArchiveManifest.ModEntry;
import determination.xenon.mindustry.cloud.MindustryCloudArchivePacker.Plan;
import determination.xenon.mindustry.cloud.MindustryCloudArchivePacker.PlannedFile;
import determination.xenon.mindustry.cloud.MindustryCloudManifestPlan.Decision;
import determination.xenon.mindustry.cloud.MindustryCloudManifestPlan.Mode;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Quota;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the pack plan, the manifest decision and the manifest installer.
@NotNullByDefault
public final class MindustryCloudManifestTest {

    /// The archive written by pack matches the plan, including the Mod entries.
    @Test
    public void planMatchesPackedArchive(@TempDir Path dir) throws Exception {
        Path source = instance(dir.resolve("source"));
        writeMod(source, "private-mod");
        writeMod(source, "public-mod");
        MindustryCloudArchivePacker.Options options =
                new MindustryCloudArchivePacker.Options("v157", 157, Set.of("public-mod"));

        Plan plan = MindustryCloudArchivePacker.plan(source, options);
        Path archive = dir.resolve("instance.zip");
        MindustryCloudArchivePacker.Result result = MindustryCloudArchivePacker.pack(
                source, archive, options, null, null);

        List<String> plannedPaths = plan.files().stream()
                .map(PlannedFile::archivePath)
                .toList();
        assertEquals(List.of("saves/world.msav", "maps/arena.msav",
                "schematics/base.msch", "settings.bin", "mods/private-mod.zip"),
                plannedPaths);
        assertEquals(plannedPaths, result.manifest().files().stream()
                .map(FileEntry::path).toList());
        assertEquals(plan.files().stream().map(PlannedFile::size).toList(),
                result.manifest().files().stream().map(FileEntry::size).toList());
        assertEquals(plan.files().stream().map(PlannedFile::sha256).toList(),
                result.manifest().files().stream().map(FileEntry::sha256).toList());

        assertEquals(plan.mods().stream().map(ModEntry::id).toList(),
                result.manifest().mods().stream().map(ModEntry::id).toList());
        assertEquals(plan.mods().stream().map(ModEntry::packed).toList(),
                result.manifest().mods().stream().map(ModEntry::packed).toList());
        assertEquals(2, plan.mods().size());
        assertTrue(plan.mods().stream()
                .anyMatch(mod -> mod.id().equals("private-mod") && mod.packed()));
        assertTrue(plan.mods().stream()
                .anyMatch(mod -> mod.id().equals("public-mod") && !mod.packed()));

        List<String> expectedEntries = new ArrayList<>(plannedPaths);
        expectedEntries.add(MindustryCloudArchiveManifest.ENTRY_NAME);
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertEquals(expectedEntries, zip.stream().map(ZipEntry::getName).toList());
            for (PlannedFile planned : plan.files()) {
                ZipEntry entry = zip.getEntry(planned.archivePath());
                assertNotNull(entry, planned.archivePath());
                byte[] bytes;
                try (var input = zip.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                assertEquals(planned.size(), bytes.length, planned.archivePath());
                assertEquals(planned.sha256(), sha256(bytes), planned.archivePath());
            }
        }
    }

    /// A file rewritten after planning is recorded with the bytes actually written.
    @Test
    public void packHashesWrittenBytesWhenSourceChanges(@TempDir Path dir) throws Exception {
        Path source = instance(dir.resolve("source"));
        MindustryCloudArchivePacker.Options options =
                new MindustryCloudArchivePacker.Options("v157", 157, Set.of());
        Plan plan = MindustryCloudArchivePacker.plan(source, options);
        PlannedFile stale = plan.files().stream()
                .filter(file -> file.archivePath().equals("maps/arena.msav"))
                .findFirst().orElseThrow();
        Path arena = source.resolve("maps/arena.msav");
        AtomicInteger callbacks = new AtomicInteger();

        Path archive = dir.resolve("instance.zip");
        MindustryCloudArchivePacker.Result result = MindustryCloudArchivePacker.pack(
                source, archive, options, (bytesRead, total) -> {
                    if (callbacks.incrementAndGet() == 1) {
                        try {
                            Files.writeString(arena, "mutated-map-data");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                }, null);

        FileEntry packed = result.manifest().files().stream()
                .filter(file -> file.path().equals("maps/arena.msav"))
                .findFirst().orElseThrow();
        byte[] content = Files.readAllBytes(arena);
        assertEquals(content.length, packed.size());
        assertEquals(sha256(content), packed.sha256());
        assertFalse(stale.sha256().equals(packed.sha256()),
                "the manifest must not reuse the stale plan hash");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry("maps/arena.msav");
            assertNotNull(entry);
            try (var input = zip.getInputStream(entry)) {
                assertEquals(sha256(input.readAllBytes()), packed.sha256());
            }
        }
    }

    /// Every degradation path returns a zip decision with its stable reason.
    @Test
    public void decideFallsBackForEachDegradation() {
        Plan twoFiles = plan(List.of(file("saves/a.msav", 10), file("saves/b.msav", 20)));

        assertZipFallback("not_archive_slot", MindustryCloudManifestPlan.decide(
                twoFiles, quota(1000, List.of("manifest"), 100, 100_000), false, true));
        assertZipFallback("delta_disabled", MindustryCloudManifestPlan.decide(
                twoFiles, quota(1000, List.of("manifest"), 100, 100_000), true, false));
        assertZipFallback("format_unsupported", MindustryCloudManifestPlan.decide(
                twoFiles, quota(1000, List.of("zip"), 100, 100_000), true, true));
        assertZipFallback("format_unsupported", MindustryCloudManifestPlan.decide(
                twoFiles, quota(1000, List.of(), 100, 100_000), true, true));
        assertZipFallback("too_many_files", MindustryCloudManifestPlan.decide(
                twoFiles, quota(1000, List.of("manifest"), 1, 100_000), true, true));
        assertZipFallback("file_too_large", MindustryCloudManifestPlan.decide(
                twoFiles, quota(15, List.of("manifest"), 100, 100_000), true, true));
        assertZipFallback("manifest_too_large", MindustryCloudManifestPlan.decide(
                twoFiles, quota(1000, List.of("manifest"), 100, 1), true, true));
        assertZipFallback("no_files", MindustryCloudManifestPlan.decide(
                plan(List.of()), quota(1000, List.of("manifest"), 100, 100_000), true, true));
    }

    /// Every condition violated at once reports the first documented reason.
    @Test
    public void decideReportsFirstViolationInDocumentedOrder() {
        Plan twoFiles = plan(List.of(file("saves/a.msav", 10), file("saves/b.msav", 20)));
        Quota allViolated = quota(1, List.of("zip"), 1, 1);

        assertZipFallback("not_archive_slot",
                MindustryCloudManifestPlan.decide(twoFiles, allViolated, false, false));
        assertZipFallback("delta_disabled",
                MindustryCloudManifestPlan.decide(twoFiles, allViolated, true, false));
        assertZipFallback("format_unsupported",
                MindustryCloudManifestPlan.decide(twoFiles, allViolated, true, true));
        assertZipFallback("too_many_files", MindustryCloudManifestPlan.decide(twoFiles,
                quota(1, List.of("manifest"), 1, 1), true, true));
        assertZipFallback("file_too_large", MindustryCloudManifestPlan.decide(twoFiles,
                quota(1, List.of("manifest"), 100, 1), true, true));
        assertZipFallback("manifest_too_large", MindustryCloudManifestPlan.decide(twoFiles,
                quota(1000, List.of("manifest"), 100, 1), true, true));
        assertZipFallback("no_files", MindustryCloudManifestPlan.decide(plan(List.of()),
                quota(1000, List.of("manifest"), 100, 1), true, true));
    }

    /// Zero limits mean unlimited for files, file bytes and manifest bytes.
    @Test
    public void decideTreatsZeroLimitsAsUnlimited() {
        Plan oneFile = plan(List.of(file("saves/large.msav", 5_000_000)));
        Decision decision = MindustryCloudManifestPlan.decide(oneFile,
                quota(0, List.of("manifest"), 0, 0), true, true);

        assertEquals(Mode.MANIFEST, decision.mode());
        assertEquals("manifest", decision.reason());
    }

    /// The delta property is off only for `false`, ignoring case and whitespace.
    @Test
    public void deltaEnabledHonoursProperty() {
        String previous = System.getProperty(MindustryCloudManifestPlan.DELTA_PROPERTY);
        try {
            System.clearProperty(MindustryCloudManifestPlan.DELTA_PROPERTY);
            assertTrue(MindustryCloudManifestPlan.deltaEnabled());
            System.setProperty(MindustryCloudManifestPlan.DELTA_PROPERTY, "false");
            assertFalse(MindustryCloudManifestPlan.deltaEnabled());
            System.setProperty(MindustryCloudManifestPlan.DELTA_PROPERTY, " FALSE ");
            assertFalse(MindustryCloudManifestPlan.deltaEnabled());
            System.setProperty(MindustryCloudManifestPlan.DELTA_PROPERTY, "true");
            assertTrue(MindustryCloudManifestPlan.deltaEnabled());
        } finally {
            if (previous == null) {
                System.clearProperty(MindustryCloudManifestPlan.DELTA_PROPERTY);
            } else {
                System.setProperty(MindustryCloudManifestPlan.DELTA_PROPERTY, previous);
            }
        }
    }

    /// A usable advertised format produces a manifest payload with stable JSON.
    @Test
    public void decideSelectsAdvertisedManifestFormat() {
        List<PlannedFile> files = List.of(file("saves/a.msav", 3, "aaa"),
                file("maps/b.msav", 5, "bbb"));
        Decision decision = MindustryCloudManifestPlan.decide(plan(files),
                quota(1000, List.of("", "zip", " xenon-manifest "), 100, 100_000), true, true);

        assertEquals(Mode.MANIFEST, decision.mode());
        assertEquals("xenon-manifest", decision.format());
        assertEquals("manifest", decision.reason());
        assertEquals(files.stream().map(PlannedFile::archivePath).toList(),
                payloadPaths(decision.payload()));
        assertEquals("{\"format\":\"delta-v1\",\"files\":["
                + "{\"path\":\"saves/a.msav\",\"size\":3,\"sha256\":\"aaa\"},"
                + "{\"path\":\"maps/b.msav\",\"size\":5,\"sha256\":\"bbb\"}]}",
                new String(MindustryCloudManifestPlan.serialize("delta-v1", files),
                        StandardCharsets.UTF_8));
    }

    /// Only safe top-level manifest paths are restorable.
    @Test
    public void validatesRestorablePaths() {
        assertTrue(MindustryCloudManifestPlan.isRestorablePath("saves/x.msav"));
        assertTrue(MindustryCloudManifestPlan.isRestorablePath("saves/a..b.msav"));
        assertTrue(MindustryCloudManifestPlan.isRestorablePath("mods/a.jar"));
        assertTrue(MindustryCloudManifestPlan.isRestorablePath("settings.bin"));
        assertFalse(MindustryCloudManifestPlan.isRestorablePath("../x"));
        assertFalse(MindustryCloudManifestPlan.isRestorablePath("/abs"));
        assertFalse(MindustryCloudManifestPlan.isRestorablePath("a\\b"));
        assertFalse(MindustryCloudManifestPlan.isRestorablePath("C:/x"));
        assertFalse(MindustryCloudManifestPlan.isRestorablePath("config/x"));
    }

    /// Windows device names and trailing dots/spaces are rejected.
    @Test
    public void rejectsWindowsReservedEntryNames() throws Exception {
        for (String name : List.of("saves/CON", "saves/con.txt", "maps/NUL.msav",
                "mods/AUX.jar", "saves/COM1.msav", "saves/LPT9",
                "saves/trailing.", "saves/trailing ")) {
            assertThrows(IOException.class,
                    () -> MindustryCloudArchiveInstaller.validateEntryName(name), name);
        }
        MindustryCloudArchiveInstaller.validateEntryName("saves/CONSOLE/world.msav");
        MindustryCloudArchiveInstaller.validateEntryName("mods/console.jar");
    }

    /// A valid staged tree replaces the data and the old tree is backed up.
    @Test
    public void installsManifestTreeAndBacksUpOldData(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        write(data, "saves/world.msav", "old-wave");
        write(data, "settings.bin", "old-settings");
        write(data, "crashes/last_log.txt", "log");

        Path staging = dir.resolve("staging");
        write(staging, "saves/world.msav", "new-wave");
        write(staging, "maps/arena.msav", "arena");
        write(staging, "schematics/base.msch", "schematic");
        write(staging, "settings.bin", "new-settings");
        List<FileEntry> expected = List.of(
                entry(staging, "saves/world.msav"),
                entry(staging, "maps/arena.msav"),
                entry(staging, "schematics/base.msch"),
                entry(staging, "settings.bin"));

        MindustryCloudArchiveInstaller.RestoreResult restored =
                MindustryCloudManifestInstaller.install(staging, data,
                        dir.resolve("backups"), expected, List.of());

        assertEquals(expected, restored.manifest().files());
        assertTrue(restored.missingMods().isEmpty());
        assertEquals("new-wave", Files.readString(data.resolve("saves/world.msav")));
        assertEquals("arena", Files.readString(data.resolve("maps/arena.msav")));
        assertEquals("schematic", Files.readString(data.resolve("schematics/base.msch")));
        assertEquals("new-settings", Files.readString(data.resolve("settings.bin")));
        assertEquals("log", Files.readString(data.resolve("crashes/last_log.txt")));
        assertEquals("old-wave",
                Files.readString(restored.backupDir().resolve("saves/world.msav")));
        assertEquals("old-settings",
                Files.readString(restored.backupDir().resolve("settings.bin")));
    }

    /// Traversing expected paths are rejected before the data directory changes.
    @Test
    public void rejectsTraversalWithoutTouchingData(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        write(data, "saves/world.msav", "old-wave");
        Map<String, String> before = snapshot(data);
        Path staging = dir.resolve("staging");
        write(staging, "saves/world.msav", "new-wave");

        // A matching size/hash makes the outside file tempting to overwrite if
        // containment were checked after hash verification.
        Path outside = dir.resolve("evil.txt");
        Files.writeString(outside, "outside");
        long size = Files.size(outside);
        String hash = MindustryCloudArchiveInstaller.sha256(outside);

        for (String evil : List.of("../evil.txt", "..\\evil", "saves/../../evil.txt",
                "C:/evil.txt", "config/evil.txt", "saves/ev\0il.txt")) {
            List<FileEntry> expected = List.of(new FileEntry(evil, size, hash));
            assertThrows(IOException.class, () -> MindustryCloudManifestInstaller.install(
                    staging, data, dir.resolve("backups"), expected, List.of()), evil);
            assertEquals(before, snapshot(data), evil);
            assertTrue(Files.isRegularFile(outside), evil);
            assertEquals("outside", Files.readString(outside), evil);
        }
        assertFalse(Files.exists(dir.resolve("backups")));
    }

    /// Undeclared staged files are rejected before the data directory changes.
    @Test
    public void rejectsUndeclaredStagedFilesWithoutTouchingData(@TempDir Path dir)
            throws Exception {
        Path data = dir.resolve("data");
        write(data, "saves/world.msav", "old-wave");
        Map<String, String> before = snapshot(data);
        Path staging = dir.resolve("staging");
        write(staging, "saves/world.msav", "new-wave");
        write(staging, "maps/extra.msav", "extra");
        List<FileEntry> expected = List.of(entry(staging, "saves/world.msav"));

        assertThrows(IOException.class, () -> MindustryCloudManifestInstaller.install(
                staging, data, dir.resolve("backups"), expected, List.of()));
        assertEquals(before, snapshot(data));
        assertFalse(Files.exists(dir.resolve("backups")));
    }

    /// An empty manifest cannot restore a non-empty staging tree.
    @Test
    public void rejectsNonEmptyStagingForEmptyManifest(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        write(data, "saves/world.msav", "old-wave");
        Map<String, String> before = snapshot(data);
        Path staging = dir.resolve("staging");
        write(staging, "saves/world.msav", "new-wave");

        assertThrows(IOException.class, () -> MindustryCloudManifestInstaller.install(
                staging, data, dir.resolve("backups"), List.of(), List.of()));
        assertEquals(before, snapshot(data));
        assertFalse(Files.exists(dir.resolve("backups")));
    }

    /// A hash mismatch is rejected before the data directory changes.
    @Test
    public void rejectsHashMismatchWithoutTouchingData(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        write(data, "saves/world.msav", "old-wave");
        Map<String, String> before = snapshot(data);
        Path staging = dir.resolve("staging");
        write(staging, "saves/world.msav", "new-wave");
        List<FileEntry> expected = List.of(new FileEntry("saves/world.msav",
                Files.size(staging.resolve("saves/world.msav")), "0".repeat(64)));

        assertThrows(IOException.class, () -> MindustryCloudManifestInstaller.install(
                staging, data, dir.resolve("backups"), expected, List.of()));
        assertEquals(before, snapshot(data));
        assertFalse(Files.exists(dir.resolve("backups")));
    }

    /// Duplicate normalized manifest paths are rejected before the data changes.
    @Test
    public void rejectsDuplicateManifestPaths(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        write(data, "saves/world.msav", "old-wave");
        Path staging = dir.resolve("staging");
        write(staging, "saves/world.msav", "new-wave");
        FileEntry entry = entry(staging, "saves/world.msav");

        assertThrows(IOException.class, () -> MindustryCloudManifestInstaller.install(
                staging, data, dir.resolve("backups"), List.of(entry, entry), List.of()));
        assertEquals("old-wave", Files.readString(data.resolve("saves/world.msav")));
        assertFalse(Files.exists(dir.resolve("backups")));
    }

    /// A zip-slip entry is rejected before anything is extracted outside staging.
    @Test
    public void rejectsZipSlipEntry(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("data");
        write(data, "saves/world.msav", "old-wave");
        MindustryCloudArchiveManifest manifest = new MindustryCloudArchiveManifest(
                MindustryCloudArchiveManifest.SCHEMA, "v157", 157,
                java.time.Instant.EPOCH, List.of(), List.of());
        Path archive = dir.resolve("evil.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry(MindustryCloudArchiveManifest.ENTRY_NAME));
            zip.write(manifest.toJson().toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("../evil.txt"));
            zip.write("evil".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        assertThrows(IOException.class, () -> MindustryCloudArchiveInstaller.install(
                archive, data, dir.resolve("backups")));
        assertFalse(Files.exists(dir.resolve("evil.txt")));
        assertEquals("old-wave", Files.readString(data.resolve("saves/world.msav")));
    }

    /// Builds a small instance tree without Mods.
    private static Path instance(Path root) throws IOException {
        write(root, "saves/world.msav", "wave-7");
        write(root, "maps/arena.msav", "map-data");
        write(root, "schematics/base.msch", "schematic-data");
        write(root, "settings.bin", "settings");
        return root;
    }

    /// Writes one parseable Mod archive.
    private static void writeMod(Path root, String name) throws IOException {
        Path file = root.resolve("mods").resolve(name + ".zip");
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("mod.hjson"));
            zip.write(("{\n name: " + name + "\n displayName: " + name
                    + "\n version: 1.0\n minGameVersion: 146\n}\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    /// Writes one text file, creating its parents.
    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, content);
    }

    /// Builds a manifest entry from the staged file.
    private static FileEntry entry(Path root, String relative) throws IOException {
        Path file = root.resolve(relative);
        return new FileEntry(relative, Files.size(file),
                MindustryCloudArchiveInstaller.sha256(file));
    }

    /// Builds a planned file without touching the file system.
    private static PlannedFile file(String path, long size) {
        return file(path, size, "sha-" + size);
    }

    /// Builds a planned file with an explicit digest.
    private static PlannedFile file(String path, long size, String sha256) {
        return new PlannedFile(path, Path.of(path), size, sha256);
    }

    /// Builds a plan without Mods.
    private static Plan plan(List<PlannedFile> files) {
        return new Plan(files, List.of());
    }

    /// Asserts a zip fallback with the expected reason.
    private static void assertZipFallback(String reason, Decision decision) {
        assertEquals(Mode.ZIP, decision.mode(), reason);
        assertEquals(reason, decision.reason());
        assertEquals(0, decision.payload().length, reason);
    }

    /// Reads the paths back from a manifest payload.
    private static List<String> payloadPaths(byte[] payload) {
        JsonObject root = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonArray files = root.getAsJsonArray("files");
        List<String> paths = new ArrayList<>();
        for (JsonElement element : files) {
            paths.add(element.getAsJsonObject().get("path").getAsString());
        }
        return paths;
    }

    /// Snapshots a tree as relative path to text content.
    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> snapshot = new TreeMap<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                snapshot.put(relative, Files.readString(path));
            }
        }
        return snapshot;
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

    /// Builds a quota with only the manifest-relevant limits set.
    private static Quota quota(long maxFileBytes, List<String> formats,
                               long maxManifestFiles, long maxManifestBytes) {
        return new Quota(0, 0, maxFileBytes, 0, 0, 0, 0, 0,
                formats, maxManifestFiles, maxManifestBytes);
    }
}
