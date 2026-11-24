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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests duplicate suppression, listing and notes of `saves/backups/`.
@NotNullByDefault
public final class SaveBackupDedupTest {

    /// A second backup of an unchanged save reuses the first file.
    @Test
    public void skipsUnchangedSecondBackup(@TempDir Path tempDir) throws IOException {
        Path saves = tempDir.resolve("saves");
        Files.createDirectories(saves);
        Path slot = saves.resolve("My World.msav");
        Files.writeString(slot, "save-bytes", StandardCharsets.UTF_8);

        SaveBackupService service = new SaveBackupService(saves);
        SaveBackupService.BackupResult first = service.backupWithResult(summary(slot));
        SaveBackupService.BackupResult second = service.backupWithResult(summary(slot));

        assertFalse(first.skipped());
        assertTrue(second.skipped());
        assertEquals(first.file(), second.file());
        assertEquals(1, countBackups(service));
        assertFalse(second.file().getFileName().toString().contains(".xenon-tmp"),
                "temp file leaked");
    }

    /// Changed content still produces a new backup file.
    @Test
    public void writesNewBackupAfterChange(@TempDir Path tempDir) throws IOException {
        Path saves = tempDir.resolve("saves");
        Files.createDirectories(saves);
        Path slot = saves.resolve("My World.msav");
        Files.writeString(slot, "v1", StandardCharsets.UTF_8);

        SaveBackupService service = new SaveBackupService(saves);
        service.backup(summary(slot));
        Files.writeString(slot, "v2-different-and-longer", StandardCharsets.UTF_8);
        SaveBackupService.BackupResult second = service.backupWithResult(summary(slot));

        assertFalse(second.skipped());
        assertEquals(2, countBackups(service));
    }

    /// Listing reports slot names, stamps, sizes and notes, newest first.
    @Test
    public void listsBackupsWithNotes(@TempDir Path tempDir) throws IOException {
        Path saves = tempDir.resolve("saves");
        Files.createDirectories(saves);
        Path slot = saves.resolve("My World.msav");
        Files.writeString(slot, "v1", StandardCharsets.UTF_8);
        SaveBackupService service = new SaveBackupService(saves);
        Path first = service.backup(summary(slot));

        new SaveBackupNotes(service.getBackupsDir()).put(first.getFileName().toString(), "before boss");

        Files.writeString(slot, "v2-with-more-state", StandardCharsets.UTF_8);
        service.backup(summary(slot));

        List<BackupSummary> backups = service.listBackups();
        assertEquals(2, backups.size());
        assertEquals("My World", backups.get(0).slotName());
        assertTrue(backups.get(0).size() > 0);
        BackupSummary noted = backups.stream()
                .filter(b -> b.fileName().equals(first.getFileName().toString()))
                .findFirst()
                .orElseThrow();
        assertEquals("before boss", noted.note());
        assertTrue(noted.takenAt() != null);
    }

    /// Notes survive a reload and blank values clear the entry.
    @Test
    public void notesRoundTrip(@TempDir Path tempDir) throws IOException {
        Path backups = tempDir.resolve("backups");
        SaveBackupNotes notes = new SaveBackupNotes(backups);
        notes.put("a-20260101-010203.msav", "  keep this  ");
        assertEquals("keep this", new SaveBackupNotes(backups).get("a-20260101-010203.msav"));

        notes.put("a-20260101-010203.msav", "   ");
        assertNull(new SaveBackupNotes(backups).get("a-20260101-010203.msav"));
        assertEquals(Map.of(), new SaveBackupNotes(backups).load());
    }

    /// The note document is truncated to the documented limit.
    @Test
    public void noteLengthIsCapped(@TempDir Path tempDir) throws IOException {
        SaveBackupNotes notes = new SaveBackupNotes(tempDir);
        notes.put("x.msav", "y".repeat(SaveBackupNotes.MAX_NOTE_LENGTH + 50));
        assertEquals(SaveBackupNotes.MAX_NOTE_LENGTH,
                notes.get("x.msav").length());
    }

    /// Counts `.msav` files currently in the backups folder.
    ///
    /// @param service service under test
    /// @return number of backup files
    private static long countBackups(SaveBackupService service) throws IOException {
        try (var stream = Files.list(service.getBackupsDir())) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".msav")).count();
        }
    }

    /// Builds a minimal summary pointing at one save file.
    ///
    /// @param file live save path
    /// @return summary accepted by the backup API
    private static SaveSummary summary(Path file) throws IOException {
        return new SaveSummary(file, Files.size(file), Instant.now(), 7,
                "map", null, 0L, 158, 1, null, null, 0L, Map.of());
    }
}
