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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /// Writes a representative Mindustry data archive used by launcher save selection.
    private static Path writeDataArchive(Path archive) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            writeEntry(zip, "settings.bin", "settings");
            writeEntry(zip, "maps/attack.msav", "map");
            writeEntry(zip, "saves/sector.msav", "sector");
            writeEntry(zip, "saves/battle.mrep", "replay");
            writeEntry(zip, "mods/example-mod.zip", "mod");
        }
        return archive;
    }

    /// Writes one UTF-8 zip entry for test data.
    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
