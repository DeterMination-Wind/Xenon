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
package determination.xenon.mindustry.uuid;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests settings.bin snapshot rotation and restore.
@NotNullByDefault
public final class MindustrySettingsBackupsTest {

    /// Creates a timestamped snapshot under `settings-backups/`.
    @Test
    public void createsSnapshotInsideBackupDirectory(@TempDir Path tempDir) throws IOException {
        MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of("name", "Xenon"));

        Path snapshot = MindustrySettingsBackups.backup(tempDir);

        assertNotNull(snapshot);
        assertEquals(MindustrySettingsBackups.backupDir(tempDir), snapshot.getParent());
        assertTrue(snapshot.getFileName().toString().startsWith("settings-"));
        assertTrue(snapshot.getFileName().toString().endsWith(".bin"));
        assertEquals(MindustrySettingsBin.read(tempDir).values(),
                MindustrySettingsBin.readFileOrThrow(snapshot));
    }

    /// Backing up an instance without settings.bin does not create an empty snapshot.
    @Test
    public void skipsBackupWhenSettingsFileIsMissing(@TempDir Path tempDir) throws IOException {
        assertNull(MindustrySettingsBackups.backup(tempDir));
        assertTrue(MindustrySettingsBackups.list(tempDir).isEmpty());
    }

    /// Rotation keeps the ten newest snapshots and deletes the rest.
    @Test
    public void keepsOnlyTenNewestSnapshots(@TempDir Path tempDir) throws IOException {
        MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of("counter", 0));
        for (int i = 1; i <= 12; i++) {
            MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of("counter", i));
            MindustrySettingsBackups.backup(tempDir);
        }

        List<Path> snapshots = MindustrySettingsBackups.list(tempDir);

        assertEquals(MindustrySettingsBackups.KEEP_LIMIT, snapshots.size());
    }

    /// list() orders snapshots from newest to oldest.
    @Test
    public void listsSnapshotsNewestFirst(@TempDir Path tempDir) throws IOException {
        MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of("counter", 0));
        Path first = MindustrySettingsBackups.backup(tempDir);
        Path second = MindustrySettingsBackups.backup(tempDir);
        Path third = MindustrySettingsBackups.backup(tempDir);
        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(third);
        // Copies take the current time; pin explicit mtimes so ordering is deterministic.
        Files.setLastModifiedTime(first, FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(second, FileTime.fromMillis(2_000L));
        Files.setLastModifiedTime(third, FileTime.fromMillis(3_000L));

        assertEquals(List.of(third, second, first), MindustrySettingsBackups.list(tempDir));
    }

    /// restoreLatest replaces settings.bin and first snapshots the pre-restore state.
    @Test
    public void restoreLatestRestoresContentAndBacksUpCurrent(@TempDir Path tempDir) throws IOException {
        MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of("nickname", "before"));
        Path snapshot = MindustrySettingsBackups.backup(tempDir);
        assertNotNull(snapshot);
        Files.setLastModifiedTime(snapshot, FileTime.fromMillis(1_000L));
        MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of("nickname", "after"));

        Path restored = MindustrySettingsBackups.restoreLatest(tempDir);

        assertEquals(snapshot, restored);
        assertEquals("before", MindustrySettingsBin.read(tempDir).values().get("nickname"));
        List<Path> snapshots = MindustrySettingsBackups.list(tempDir);
        assertEquals(2, snapshots.size());
        Path preRestore = snapshots.get(0);
        assertEquals("after", MindustrySettingsBin.readFileOrThrow(preRestore).get("nickname"));
        assertEquals(MindustrySettingsBin.read(tempDir).values(),
                MindustrySettingsBin.readFileOrThrow(
                        tempDir.resolve(MindustrySettingsBin.BACKUP_NAME)));
    }

    /// Restoring without any snapshot is reported instead of silently doing nothing.
    @Test
    public void restoreLatestFailsWithoutSnapshots(@TempDir Path tempDir) throws IOException {
        assertThrows(IOException.class, () -> MindustrySettingsBackups.restoreLatest(tempDir));
    }

    /// A corrupt snapshot is rejected before it can replace the live settings.
    @Test
    public void restoreLatestRefusesCorruptSnapshot(@TempDir Path tempDir) throws IOException {
        MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of("nickname", "live"));
        Path dir = MindustrySettingsBackups.backupDir(tempDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("settings-20260101-000000.bin"), "not a settings file");

        assertThrows(IOException.class, () -> MindustrySettingsBackups.restoreLatest(tempDir));
        assertEquals("live", MindustrySettingsBin.read(tempDir).values().get("nickname"));
    }
}
