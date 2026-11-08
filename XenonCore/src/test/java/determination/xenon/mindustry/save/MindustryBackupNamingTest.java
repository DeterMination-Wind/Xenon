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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests parsing of the backup file names written into `saves/backups/`.
@NotNullByDefault
public final class MindustryBackupNamingTest {

    /// A plain automatic backup yields its slot and stamp.
    @Test
    public void parsesAutomaticBackup() {
        Path backup = Path.of("My World-20260101-010203.msav");

        assertEquals("My World", MindustryBackupNaming.slotName(backup));
        assertEquals(instant(2026, 1, 1, 1, 2, 3), MindustryBackupNaming.timestamp(backup));
    }

    /// The same-second collision counter is not part of the slot name.
    @Test
    public void parsesCollisionSuffix() {
        Path backup = Path.of("My World-20260101-010203-2.msav");

        assertEquals("My World", MindustryBackupNaming.slotName(backup));
        assertEquals(instant(2026, 1, 1, 1, 2, 3), MindustryBackupNaming.timestamp(backup));
    }

    /// A cloud-head backup carries an extra `cloud` token before the stamp.
    @Test
    public void parsesCloudBackup() {
        Path backup = Path.of("My World-cloud-20260203-040506.msav");

        assertEquals("My World", MindustryBackupNaming.slotName(backup));
        assertEquals(instant(2026, 2, 3, 4, 5, 6), MindustryBackupNaming.timestamp(backup));
    }

    /// A hand-renamed file is treated as the whole slot name rather than rejected.
    @Test
    public void treatsUnstampedFileAsWholeSlot() {
        Path backup = Path.of("renamed by hand.msav");

        assertEquals("renamed by hand", MindustryBackupNaming.slotName(backup));
        assertNull(MindustryBackupNaming.timestamp(backup));
    }

    /// Non-`.msav` and stamp-only names are not backups.
    @Test
    public void rejectsInvalidNames() {
        assertNull(MindustryBackupNaming.slotName(Path.of("notes.txt")));
        assertNull(MindustryBackupNaming.slotName(Path.of("-20260101-010203.msav")));
        assertNull(MindustryBackupNaming.slotName(null));
        assertNull(MindustryBackupNaming.timestamp(Path.of("notes.txt")));
    }

    /// The CLI row includes the slot, timestamp, size and file name.
    @Test
    public void describesOneRow(@TempDir Path tempDir) throws IOException {
        Path backup = tempDir.resolve("My World-20260101-010203.msav");
        Files.write(backup, new byte[]{1, 2, 3, 4});

        String row = MindustryBackupNaming.describe(backup);

        assertTrue(row.startsWith("My World\t2026-01-01 01:02:03\t4\t"), row);
        assertEquals("My World-20260101-010203.msav", row.substring(row.lastIndexOf('\t') + 1));
    }

    /// Builds the instant a stamp denotes in the system time zone.
    private static java.time.Instant instant(int year, int month, int day, int hour, int minute, int second) {
        return LocalDateTime.of(year, month, day, hour, minute, second)
                .atZone(ZoneId.systemDefault()).toInstant();
    }
}
