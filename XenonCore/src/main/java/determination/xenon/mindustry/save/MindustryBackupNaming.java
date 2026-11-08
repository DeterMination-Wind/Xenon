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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Interprets the file names [`SaveBackupService`] and the cloud-save dialog
/// write into `saves/backups/`.
///
/// Backups follow two shapes:
///
/// * `<slot>-yyyyMMdd-HHmmss[-n].msav` — automatic and manual backups, where
///   `n` disambiguates two copies written within the same second;
/// * `<slot>-cloud-yyyyMMdd-HHmmss.msav` — the cloud head preserved before a
///   force-replace.
///
/// The CLI needs the original slot name back so `--restore-backup` can write
/// to the right `.msav`. A backup whose name carries no recognizable stamp is
/// treated as a whole-file slot name instead of being rejected, because a user
/// may have renamed the file by hand.
@NotNullByDefault
public final class MindustryBackupNaming {

    /// Matches the trailing backup stamp, with its optional cloud marker and
    /// same-second collision counter.
    private static final Pattern STAMP_SUFFIX = Pattern.compile(
            "-((?:cloud-)?)(\\d{8}-\\d{6})(?:-(\\d+))?$");

    /// Parses the stamp itself, in the system time zone used when it was written.
    private static final DateTimeFormatter STAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /// Formats timestamps for human-readable CLI rows.
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /// Prevents instantiation of this utility class.
    private MindustryBackupNaming() {
    }

    /// Recovers the original save slot name from a backup file name.
    ///
    /// The `.msav` extension and one trailing backup stamp are removed. When
    /// the name has no stamp, the whole stem is returned as the slot name.
    ///
    /// @param backupFile backup file path
    /// @return the slot name, or `null` when the file is not a `.msav` or its
    ///     stem is empty
    public static @Nullable String slotName(@Nullable Path backupFile) {
        String stem = stem(backupFile);
        if (stem == null) {
            return null;
        }
        Matcher matcher = STAMP_SUFFIX.matcher(stem);
        if (matcher.find()) {
            stem = stem.substring(0, matcher.start());
        }
        stem = stem.trim();
        return stem.isEmpty() ? null : stem;
    }

    /// Reads the backup timestamp encoded in a file name.
    ///
    /// @param backupFile backup file path
    /// @return the parsed instant, or `null` when the name carries no stamp
    public static @Nullable Instant timestamp(@Nullable Path backupFile) {
        Matcher matcher = stampMatcher(backupFile);
        if (matcher == null) {
            return null;
        }
        try {
            LocalDateTime local = LocalDateTime.parse(matcher.group(2), STAMP_FORMAT);
            return local.atZone(ZoneId.systemDefault()).toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /// Builds one tab-separated row for `--list-backups`.
    ///
    /// Columns are slot name, local timestamp, byte size and file name. A
    /// backup without a stamp falls back to its file modification time, and a
    /// size that cannot be read is rendered as `?`.
    ///
    /// @param backupFile backup file path
    /// @return tab-separated display row
    public static String describe(@Nullable Path backupFile) {
        if (backupFile == null) {
            return "?";
        }
        String fileName = backupFile.getFileName() == null
                ? backupFile.toString()
                : backupFile.getFileName().toString();
        String slot = slotName(backupFile);
        Instant stamp = timestamp(backupFile);
        if (stamp == null) {
            try {
                FileTime modified = Files.getLastModifiedTime(backupFile);
                stamp = modified.toInstant();
            } catch (IOException ignored) {
                stamp = null;
            }
        }
        String when = stamp == null ? "?" : DISPLAY_FORMAT.format(stamp);
        long size;
        try {
            size = Files.size(backupFile);
        } catch (IOException ignored) {
            size = -1L;
        }
        return (slot == null ? fileName : slot)
                + "\t" + when
                + "\t" + (size < 0 ? "?" : Long.toString(size))
                + "\t" + fileName;
    }

    /// Returns the file stem when the path names a `.msav` file.
    ///
    /// @param backupFile backup file path
    /// @return the name without its `.msav` suffix, or `null`
    private static @Nullable String stem(@Nullable Path backupFile) {
        Path fileName = backupFile == null ? null : backupFile.getFileName();
        if (fileName == null) {
            return null;
        }
        String name = fileName.toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(SaveBackupService.MSAV_EXT)) {
            return null;
        }
        return name.substring(0, name.length() - SaveBackupService.MSAV_EXT.length());
    }

    /// Returns a matcher over the trailing stamp, or `null` when absent.
    ///
    /// @param backupFile backup file path
    /// @return a matcher positioned on the stamp, or `null`
    private static @Nullable Matcher stampMatcher(@Nullable Path backupFile) {
        String stem = stem(backupFile);
        if (stem == null) {
            return null;
        }
        Matcher matcher = STAMP_SUFFIX.matcher(stem);
        return matcher.find() ? matcher : null;
    }
}
