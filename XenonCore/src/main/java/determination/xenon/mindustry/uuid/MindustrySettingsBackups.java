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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/// Timestamped backups of one Mindustry instance's `settings.bin`.
///
/// Every editor save takes a snapshot first, so a mistaken table edit can be
/// rolled back even after the game has overwritten the live file. Backups live
/// inside the data directory (`settings-backups/`) and therefore travel with
/// the instance when it is copied or migrated.
///
/// Rotation keeps the newest {@link #KEEP_LIMIT} snapshots; older files are
/// deleted after each successful backup.
@NotNullByDefault
public final class MindustrySettingsBackups {

    /// Directory holding the snapshots, relative to the data directory.
    public static final String DIRECTORY_NAME = "settings-backups";

    /// Number of snapshots retained by {@link #backup(Path)}.
    public static final int KEEP_LIMIT = 10;

    /// File name prefix of a snapshot.
    private static final String FILE_PREFIX = "settings-";

    /// File name suffix of a snapshot.
    private static final String FILE_SUFFIX = ".bin";

    /// Timestamp format embedded in snapshot names; lexicographic order equals chronological order.
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private MindustrySettingsBackups() {
    }

    /// Creates a timestamped snapshot of `settings.bin` and prunes old ones.
    ///
    /// A missing `settings.bin` produces no snapshot, so the first save of a
    /// fresh instance does not record an empty file.
    ///
    /// @param dataDir Mindustry data directory that owns `settings.bin`
    /// @return the created snapshot, or `null` when there was nothing to back up
    /// @throws IOException when the snapshot directory or copy fails
    public static @Nullable Path backup(Path dataDir) throws IOException {
        if (dataDir == null) return null;
        Path settings = dataDir.resolve(MindustrySettingsBin.FILE_NAME);
        if (!Files.isRegularFile(settings)) {
            return null;
        }
        Path dir = backupDir(dataDir);
        Files.createDirectories(dir);
        Path target = uniquePath(dir, TIMESTAMP_FORMAT.format(LocalDateTime.now()));
        Files.copy(settings, target);
        prune(dir);
        return target;
    }

    /// Lists snapshots from newest to oldest.
    ///
    /// @param dataDir Mindustry data directory that owns `settings.bin`
    /// @return the snapshot paths, newest first; empty when none exist
    public static @Unmodifiable List<Path> list(Path dataDir) {
        if (dataDir == null) return List.of();
        Path dir = backupDir(dataDir);
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) continue;
                String name = path.getFileName().toString();
                if (name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)) {
                    result.add(path);
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        result.sort(MindustrySettingsBackups::compareSnapshots);
        return List.copyOf(result);
    }

    /// Restores the newest snapshot over `settings.bin`.
    ///
    /// The current file is snapshotted before the restore, so a restore made in
    /// error can itself be undone. The restored file is validated before it
    /// replaces anything, and `settings_backup.bin` is rewritten to match.
    ///
    /// @param dataDir Mindustry data directory that owns `settings.bin`
    /// @return the snapshot that was restored
    /// @throws IOException when no snapshot exists or the restore fails
    public static Path restoreLatest(Path dataDir) throws IOException {
        List<Path> snapshots = list(dataDir);
        if (snapshots.isEmpty()) {
            throw new IOException("No settings.bin backup available in " + backupDir(dataDir));
        }
        Path latest = snapshots.get(0);
        // Validate before touching the live file; a corrupt snapshot must not
        // destroy the current settings.
        LinkedHashMap<String, Object> values = MindustrySettingsBin.readFileOrThrow(latest);
        backup(dataDir);
        MindustrySettingsBin.replaceWith(dataDir, values);
        return latest;
    }

    /// Returns the snapshot directory for a data directory.
    ///
    /// @param dataDir Mindustry data directory that owns `settings.bin`
    /// @return `<dataDir>/settings-backups`
    public static Path backupDir(Path dataDir) {
        return dataDir.resolve(DIRECTORY_NAME);
    }

    /// Picks a snapshot path that does not exist yet.
    ///
    /// Two saves inside the same second would otherwise share a name and the
    /// older snapshot would be silently overwritten.
    private static Path uniquePath(Path dir, String timestamp) {
        Path candidate = dir.resolve(FILE_PREFIX + timestamp + FILE_SUFFIX);
        for (int index = 2; Files.exists(candidate); index++) {
            candidate = dir.resolve(FILE_PREFIX + timestamp + "-" + index + FILE_SUFFIX);
        }
        return candidate;
    }

    /// Deletes the oldest snapshots beyond {@link #KEEP_LIMIT}.
    private static void prune(Path dir) {
        List<Path> snapshots = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    snapshots.add(path);
                }
            }
        } catch (IOException ignored) {
            return;
        }
        if (snapshots.size() <= KEEP_LIMIT) return;
        snapshots.sort(MindustrySettingsBackups::compareSnapshots);
        for (Path stale : snapshots.subList(KEEP_LIMIT, snapshots.size())) {
            try {
                Files.deleteIfExists(stale);
            } catch (IOException ignored) {
                // Rotation is best-effort; the newest snapshots stay intact.
            }
        }
    }

    /**
     * Orders snapshots from newest to oldest.
     *
     * Timestamps are compared first; the file name breaks ties so snapshots
     * taken within the same filesystem timestamp tick still have a stable
     * order.
     */
    private static int compareSnapshots(Path a, Path b) {
        long aModified = lastModifiedMillis(a);
        long bModified = lastModifiedMillis(b);
        if (aModified != bModified) {
            return Long.compare(bModified, aModified);
        }
        return b.getFileName().toString().compareTo(a.getFileName().toString());
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }
}
