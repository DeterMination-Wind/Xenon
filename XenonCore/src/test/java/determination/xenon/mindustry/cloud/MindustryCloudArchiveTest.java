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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests whole-instance archive packing, validation and restore.
@NotNullByDefault
public final class MindustryCloudArchiveTest {

    /// A packed instance round-trips into a clean data directory.
    @Test
    public void packsAndRestoresInstance(@TempDir Path dir) throws Exception {
        Path source = dataDir(dir.resolve("source"));
        Path archive = dir.resolve("instance.zip");

        MindustryCloudArchivePacker.Result result = MindustryCloudArchivePacker.pack(
                source, archive,
                new MindustryCloudArchivePacker.Options("v157", 157, Set.of()),
                null, null);

        assertEquals(archive, result.archive());
        assertTrue(Files.size(archive) > 0);
        assertEquals(3, result.manifest().files().size());
        assertTrue(result.manifest().mods().stream().anyMatch(
                mod -> mod.packed() && mod.id().equals("test-mod")));

        Path target = dataDir(dir.resolve("target"));
        MindustryCloudArchiveInstaller.RestoreResult restored =
                MindustryCloudArchiveInstaller.install(archive, target, dir.resolve("backups"));

        assertEquals("v157", restored.manifest().gameVersion());
        assertEquals("wave-7", Files.readString(target.resolve("saves/world.msav")));
        assertEquals("settings", Files.readString(target.resolve("settings.bin")));
        assertTrue(Files.isRegularFile(target.resolve("mods/test-mod.zip")));
        assertTrue(restored.missingMods().isEmpty());
        assertTrue(Files.isDirectory(restored.backupDir()));
    }

    /// Indexed Mods are listed but their bytes stay out of the archive.
    @Test
    public void skipsIndexedModBytes(@TempDir Path dir) throws Exception {
        Path source = dataDir(dir.resolve("source"));
        Path archive = dir.resolve("instance.zip");
        MindustryCloudArchivePacker.Result result = MindustryCloudArchivePacker.pack(
                source, archive,
                new MindustryCloudArchivePacker.Options("v157", 157, Set.of("test-mod")),
                null, null);
        assertTrue(result.manifest().mods().get(0).packed() == false);
        Path target = Files.createDirectories(dir.resolve("target"));
        MindustryCloudArchiveInstaller.RestoreResult restored =
                MindustryCloudArchiveInstaller.install(archive, target, dir.resolve("backups"));
        assertEquals(List.of("test-mod"), restored.missingMods());
    }

    /// Existing data is backed up before the restore replaces it.
    @Test
    public void backsUpExistingData(@TempDir Path dir) throws Exception {
        Path source = dataDir(dir.resolve("source"));
        Path archive = dir.resolve("instance.zip");
        MindustryCloudArchivePacker.pack(source, archive,
                new MindustryCloudArchivePacker.Options("v157", 157, Set.of()), null, null);

        Path target = dataDir(dir.resolve("target"));
        Files.writeString(target.resolve("saves/world.msav"), "old-wave");
        MindustryCloudArchiveInstaller.install(archive, target, dir.resolve("backups"));

        assertEquals("wave-7", Files.readString(target.resolve("saves/world.msav")));
        Path backups = dir.resolve("backups");
        try (var stream = Files.walk(backups)) {
            assertTrue(stream.anyMatch(path -> path.toString().endsWith("world.msav")));
        }
    }

    /// A tampered file is rejected before anything is restored.
    @Test
    public void rejectsHashMismatch(@TempDir Path dir) throws Exception {
        Path source = dataDir(dir.resolve("source"));
        Path archive = dir.resolve("instance.zip");
        MindustryCloudArchivePacker.pack(source, archive,
                new MindustryCloudArchivePacker.Options("v157", 157, Set.of()), null, null);
        // Rewrite the save while keeping the old manifest hash.
        try (var fs = java.nio.file.FileSystems.newFileSystem(archive)) {
            Files.writeString(fs.getPath("saves/world.msav"), "tampered");
        }
        Path target = dir.resolve("target");
        assertThrows(IOException.class,
                () -> MindustryCloudArchiveInstaller.install(archive, target, dir.resolve("backups")));
        assertFalse(Files.exists(target.resolve("saves/world.msav")));
    }

    /// A traversing entry is rejected before anything is extracted.
    @Test
    public void rejectsZipSlip(@TempDir Path dir) throws Exception {
        Path archive = dir.resolve("evil.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../evil.txt"));
            zip.write("evil".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Path target = dir.resolve("target");
        assertThrows(IOException.class,
                () -> MindustryCloudArchiveInstaller.install(archive, target, dir.resolve("backups")));
        assertFalse(Files.exists(dir.resolve("evil.txt")));
    }

    /// Cancellation removes the temporary archive and keeps the destination clean.
    @Test
    public void cancellationLeavesNoArchive(@TempDir Path dir) throws Exception {
        Path source = dataDir(dir.resolve("source"));
        Path archive = dir.resolve("instance.zip");
        AtomicBoolean cancelled = new AtomicBoolean(true);
        assertThrows(IOException.class, () -> MindustryCloudArchivePacker.pack(
                source, archive,
                new MindustryCloudArchivePacker.Options("v157", 157, Set.of()),
                null, cancelled));
        assertFalse(Files.exists(archive));
    }

    /// Builds a small instance tree with one Mod archive.
    private static Path dataDir(Path root) throws IOException {
        Files.createDirectories(root.resolve("saves"));
        Files.createDirectories(root.resolve("maps"));
        Files.createDirectories(root.resolve("schematics"));
        Files.createDirectories(root.resolve("mods"));
        Files.writeString(root.resolve("saves/world.msav"), "wave-7");
        Files.writeString(root.resolve("settings.bin"), "settings");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(root.resolve("mods/test-mod.zip")))) {
            zip.putNextEntry(new ZipEntry("mod.hjson"));
            zip.write(("{\n name: test-mod\n displayName: Test Mod\n"
                    + " version: 1.0\n minGameVersion: 146\n}\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return root;
    }
}
