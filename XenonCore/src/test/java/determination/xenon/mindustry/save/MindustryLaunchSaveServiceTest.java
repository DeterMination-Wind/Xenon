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
package determination.xenon.mindustry.save;

import determination.xenon.mindustry.mod.MindustryCrashRecovery;
import determination.xenon.mindustry.uuid.MindustrySettingsBin;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests launcher-managed Mindustry save archive behavior.
@NotNullByDefault
public final class MindustryLaunchSaveServiceTest {

    /// Imports a complete data archive and exposes Mindustry content counts.
    @Test
    public void importsAndListsDataArchive(@TempDir Path tempDir) throws IOException {
        Path versionRoot = tempDir.resolve("version");
        Path archive = writeDataArchive(tempDir.resolve("存档.zip"));

        MindustrySaveArchive imported = MindustryLaunchSaveService.importArchive(versionRoot, archive);
        List<MindustrySaveArchive> archives = MindustryLaunchSaveService.listArchives(versionRoot);

        assertEquals("存档.zip", imported.file().getFileName().toString());
        assertEquals(1, archives.size());
        MindustrySaveArchive listed = archives.get(0);
        assertEquals(imported.file(), listed.file());
        assertTrue(listed.hasSettings());
        assertEquals(1, listed.maps());
        assertEquals(1, listed.sectors());
        assertEquals(1, listed.replays());
        assertEquals(1, listed.mods());
    }

    /// Extracts the selected archive into a managed runtime data directory.
    @Test
    public void preparesSelectedArchiveAsRuntimeDataDirectory(@TempDir Path tempDir) throws IOException {
        Path versionRoot = tempDir.resolve("version");
        Path defaultDataDir = tempDir.resolve("default-data");
        MindustrySaveArchive imported = MindustryLaunchSaveService.importArchive(
                versionRoot,
                writeDataArchive(tempDir.resolve("存档.zip")));

        Path dataDir = MindustryLaunchSaveService.prepare(
                versionRoot,
                defaultDataDir,
                imported.file().getFileName().toString());

        assertTrue(dataDir.startsWith(MindustryLaunchSaveService.runtimesDir(versionRoot)));
        assertTrue(Files.isRegularFile(dataDir.resolve("settings.bin")));
        assertTrue(Files.isRegularFile(dataDir.resolve("maps").resolve("attack.msav")));
        assertTrue(Files.isRegularFile(dataDir.resolve("saves").resolve("sector.msav")));
        assertTrue(Files.isRegularFile(dataDir.resolve("saves").resolve("battle.mrep")));
        assertTrue(Files.isRegularFile(dataDir.resolve("mods").resolve("example-mod.zip")));
    }

    /// Uses the normal data directory when no archive is selected.
    @Test
    public void returnsDefaultDataDirectoryWithoutSelectedArchive(@TempDir Path tempDir) throws IOException {
        Path versionRoot = tempDir.resolve("version");
        Path defaultDataDir = tempDir.resolve("default-data");

        Path dataDir = MindustryLaunchSaveService.prepare(versionRoot, defaultDataDir, null);

        assertSame(defaultDataDir, dataDir);
    }

    /// Removes `.msav` auto-load leftovers from the previous save implementation.
    @Test
    public void removesLegacyAutoLoadFilesFromDefaultDataDirectory(@TempDir Path tempDir) throws IOException {
        Path versionRoot = tempDir.resolve("version");
        Path defaultDataDir = tempDir.resolve("default-data");
        Path legacyMod = defaultDataDir.resolve("mods").resolve("xenon-launch-save-loader");
        Path legacySave = defaultDataDir.resolve("saves").resolve("xenon-selected.msav");
        Files.createDirectories(legacyMod);
        Files.createDirectories(legacySave.getParent());
        Files.writeString(legacyMod.resolve("mod.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(legacySave, "legacy", StandardCharsets.UTF_8);

        Path dataDir = MindustryLaunchSaveService.prepare(versionRoot, defaultDataDir, null);

        assertEquals(defaultDataDir, dataDir);
        assertFalse(Files.exists(legacyMod));
        assertFalse(Files.exists(legacySave));
    }

    /// Resolves the effective launch data directory without extracting anything.
    @Test
    public void resolvesLaunchDataDirectoryWithoutExtracting(@TempDir Path tempDir) {
        Path versionRoot = tempDir.resolve("version");
        Path defaultDataDir = tempDir.resolve("default-data");

        assertSame(defaultDataDir,
                MindustryLaunchSaveService.resolveLaunchDataDir(versionRoot, defaultDataDir, null));
        assertSame(defaultDataDir,
                MindustryLaunchSaveService.resolveLaunchDataDir(versionRoot, defaultDataDir, "  "));

        Path runtime = MindustryLaunchSaveService.resolveLaunchDataDir(
                versionRoot, defaultDataDir, "我的存档.zip");
        assertTrue(runtime.startsWith(MindustryLaunchSaveService.runtimesDir(versionRoot)));
        assertFalse(Files.exists(runtime));

        assertSame(defaultDataDir, MindustryLaunchSaveService.resolveLaunchDataDir(
                versionRoot, defaultDataDir, "bad\0name.zip"));
    }

    /// Crash-recovery snapshots survive a runtime rebuild triggered by the archive.
    @Test
    public void keepsCrashRecoverySnapshotsAcrossRuntimeRebuild(@TempDir Path tempDir) throws IOException {
        Path versionRoot = tempDir.resolve("version");
        Path defaultDataDir = tempDir.resolve("default-data");
        MindustrySaveArchive imported = MindustryLaunchSaveService.importArchive(
                versionRoot, writeDataArchive(tempDir.resolve("存档.zip")));
        Path runtime = MindustryLaunchSaveService.prepare(versionRoot, defaultDataDir,
                imported.file().getFileName().toString());

        Path snapshot = MindustryCrashRecovery.takeOver(runtime);
        byte[] snapshotBytes = Files.readAllBytes(snapshot);

        // Touch the archive so the runtime counts as stale and gets rebuilt.
        writeDataArchive(imported.file());

        Path rebuilt = MindustryLaunchSaveService.prepare(versionRoot, defaultDataDir,
                imported.file().getFileName().toString());

        Path preserved = MindustryCrashRecovery.latestSnapshot(rebuilt);
        assertNotNull(preserved);
        assertArrayEquals(snapshotBytes, Files.readAllBytes(preserved));
    }

    /// Rebuilding the runtime keeps user mod additions and disable flags.
    @Test
    public void preservesUserModStateAcrossRuntimeRebuild(@TempDir Path tempDir) throws IOException {
        Path versionRoot = tempDir.resolve("version");
        Path defaultDataDir = tempDir.resolve("default-data");
        MindustrySaveArchive imported = MindustryLaunchSaveService.importArchive(
                versionRoot, writeDataArchive(tempDir.resolve("存档.zip")));
        Path runtime = MindustryLaunchSaveService.prepare(versionRoot, defaultDataDir,
                imported.file().getFileName().toString());

        // Simulate user mod management inside the extracted runtime copy.
        Files.move(runtime.resolve("mods").resolve("example-mod.zip"),
                runtime.resolve("mods").resolve("example-mod.zip.disabled"));
        Files.writeString(runtime.resolve("mods").resolve("user-mod.zip"), "user",
                StandardCharsets.UTF_8);
        Path folderMod = runtime.resolve("mods").resolve("folder-mod");
        Files.createDirectories(folderMod.resolve("nested"));
        Files.writeString(folderMod.resolve("mod.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(folderMod.resolve("nested").resolve("asset.txt"), "asset",
                StandardCharsets.UTF_8);
        MindustrySettingsBin.putValuesOrThrow(runtime,
                Map.of("mod-user-mod-enabled", Boolean.FALSE));

        // Touch the archive so the runtime counts as stale and gets rebuilt.
        writeDataArchive(imported.file());

        Path rebuilt = MindustryLaunchSaveService.prepare(versionRoot, defaultDataDir,
                imported.file().getFileName().toString());

        assertEquals(runtime, rebuilt);
        assertTrue(Files.isRegularFile(rebuilt.resolve("mods").resolve("example-mod.zip.disabled")));
        assertFalse(Files.exists(rebuilt.resolve("mods").resolve("example-mod.zip")));
        assertTrue(Files.isRegularFile(rebuilt.resolve("mods").resolve("user-mod.zip")));
        assertTrue(Files.isRegularFile(rebuilt.resolve("mods").resolve("folder-mod").resolve("mod.json")));
        assertTrue(Files.isRegularFile(
                rebuilt.resolve("mods").resolve("folder-mod").resolve("nested").resolve("asset.txt")));
        assertFalse(MindustrySettingsBin.getBool(rebuilt, "mod-user-mod-enabled", true));
    }

    /// A folder mod provided by the archive wins over the preserved runtime copy.
    @Test
    public void newlyExtractedFolderModWinsOverPreservedCopy(@TempDir Path tempDir) throws IOException {
        Path versionRoot = tempDir.resolve("version");
        Path defaultDataDir = tempDir.resolve("default-data");
        Path archive = writeDataArchiveWithFolderMod(
                tempDir.resolve("folder.zip"), "packed-mod", "from-archive");
        MindustrySaveArchive imported = MindustryLaunchSaveService.importArchive(versionRoot, archive);
        Path runtime = MindustryLaunchSaveService.prepare(versionRoot, defaultDataDir,
                imported.file().getFileName().toString());

        // The user edited the folder mod after it was extracted.
        Files.writeString(runtime.resolve("mods").resolve("packed-mod").resolve("mod.json"),
                "user-edit", StandardCharsets.UTF_8);

        // Touch the archive so the runtime counts as stale and gets rebuilt.
        writeDataArchiveWithFolderMod(imported.file(), "packed-mod", "from-archive");

        Path rebuilt = MindustryLaunchSaveService.prepare(versionRoot, defaultDataDir,
                imported.file().getFileName().toString());

        assertEquals("from-archive", Files.readString(
                rebuilt.resolve("mods").resolve("packed-mod").resolve("mod.json")));
    }

    /// Writes a data archive whose only mod is a directory-style mod.
    private static Path writeDataArchiveWithFolderMod(Path archive, String folderName, String modJson)
            throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            writeBinaryEntry(zip, "settings.bin", validSettingsBytes());
            writeEntry(zip, "mods/" + folderName + "/mod.json", modJson);
            writeEntry(zip, "mods/" + folderName + "/nested/asset.txt", "asset");
        }
        return archive;
    }

    /// A rebuild drops a failed-launch marker that came from the archive.
    @Test
    public void rebuildClearsArchiveShippedLaunchMarker(@TempDir Path tempDir) throws IOException {
        Path versionRoot = tempDir.resolve("version");
        Path defaultDataDir = tempDir.resolve("default-data");
        MindustrySaveArchive imported = MindustryLaunchSaveService.importArchive(
                versionRoot, writeDataArchive(tempDir.resolve("marker.zip"), true));
        Path runtime = MindustryLaunchSaveService.prepare(versionRoot, defaultDataDir,
                imported.file().getFileName().toString());
        assertFalse(Files.exists(runtime.resolve(MindustryCrashRecovery.LAUNCH_MARKER_NAME)));

        // Touch the archive so the runtime counts as stale and gets rebuilt.
        writeDataArchive(imported.file(), true);

        Path rebuilt = MindustryLaunchSaveService.prepare(versionRoot, defaultDataDir,
                imported.file().getFileName().toString());

        assertFalse(Files.exists(rebuilt.resolve(MindustryCrashRecovery.LAUNCH_MARKER_NAME)));
    }

    /// The current-runtime fast path keeps a real failed-launch marker.
    @Test
    public void currentRuntimeKeepsRealLaunchMarker(@TempDir Path tempDir) throws IOException {
        Path versionRoot = tempDir.resolve("version");
        Path defaultDataDir = tempDir.resolve("default-data");
        MindustrySaveArchive imported = MindustryLaunchSaveService.importArchive(
                versionRoot, writeDataArchive(tempDir.resolve("存档.zip")));
        Path runtime = MindustryLaunchSaveService.prepare(versionRoot, defaultDataDir,
                imported.file().getFileName().toString());

        Path marker = runtime.resolve(MindustryCrashRecovery.LAUNCH_MARKER_NAME);
        Files.writeString(marker, "crashed", StandardCharsets.UTF_8);

        Path again = MindustryLaunchSaveService.prepare(versionRoot, defaultDataDir,
                imported.file().getFileName().toString());

        assertEquals(runtime, again);
        assertEquals("crashed", Files.readString(marker));
    }

    /// Writes a representative Mindustry data archive used by launcher save selection.
    private static Path writeDataArchive(Path archive) throws IOException {
        return writeDataArchive(archive, false);
    }

    /// Writes a data archive, optionally carrying a failed-launch marker.
    private static Path writeDataArchive(Path archive, boolean withLaunchMarker) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            writeBinaryEntry(zip, "settings.bin", validSettingsBytes());
            writeEntry(zip, "maps/attack.msav", "map");
            writeEntry(zip, "saves/sector.msav", "sector");
            writeEntry(zip, "saves/battle.mrep", "replay");
            writeEntry(zip, "mods/example-mod.zip", "mod");
            if (withLaunchMarker) {
                writeEntry(zip, MindustryCrashRecovery.LAUNCH_MARKER_NAME, "stale");
            }
        }
        return archive;
    }

    /// Builds a minimal valid Arc settings.bin payload for archive fixtures.
    private static byte[] validSettingsBytes() throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(buffer)) {
            out.writeInt(1);
            out.writeUTF("name");
            out.writeByte(4);
            out.writeUTF("archive");
        }
        return buffer.toByteArray();
    }

    /// Writes one binary zip entry for test data.
    private static void writeBinaryEntry(ZipOutputStream zip, String name, byte[] content)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    /// Writes one UTF-8 zip entry for test data.
    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
