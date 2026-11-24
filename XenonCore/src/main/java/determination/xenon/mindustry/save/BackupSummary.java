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
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/// One file in an instance's `saves/backups/` folder.
///
/// Built by [SaveBackupService#listBackups()]. `slotName` is the live save the
/// copy came from, `takenAt` is the stamp encoded in the file name (falling
/// back to the modification time when a user renamed the file by hand), and
/// `note` is the user note from [SaveBackupNotes], when one exists.
///
/// @param file     absolute path of the backup file
/// @param slotName live save name the backup was copied from, never blank
/// @param takenAt  best-known creation time, or `null` when nothing parses
/// @param size     file size in bytes, or `-1` when it cannot be read
/// @param note     user note, or `null` when none is set
@NotNullByDefault
public record BackupSummary(
        Path file,
        String slotName,
        @Nullable Instant takenAt,
        long size,
        @Nullable String note
) {

    /// Validates the required members.
    public BackupSummary {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(slotName, "slotName");
    }

    /// Bare file name, used as the key for notes and restore requests.
    ///
    /// @return the file name without any directory component
    public String fileName() {
        @Nullable Path name = file.getFileName();
        return name == null ? file.toString() : name.toString();
    }
}
