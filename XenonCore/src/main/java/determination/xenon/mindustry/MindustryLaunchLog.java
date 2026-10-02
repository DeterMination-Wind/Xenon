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
package determination.xenon.mindustry;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static determination.xenon.util.logging.Logger.LOG;

/// Manages launcher-captured stdout/stderr logs of launched Mindustry clients.
///
/// Mindustry writes its own `last_log.txt` only after the JVM has reached the
/// game's file-logger setup, so an early launch failure (or a log file that was
/// removed afterwards) leaves the launcher with nothing to show. Xenon records
/// every launched process' merged output under `<versionRoot>/logs/`, which
/// stays valid no matter which data directory policy the instance uses.
///
/// All methods are best effort: when the directory cannot be created or read,
/// the launcher keeps working and simply has one fewer log to offer.
@NotNullByDefault
public final class MindustryLaunchLog {

    /// Sub-directory of a version root that stores launcher-captured logs.
    public static final String LOG_DIR = "logs";

    /// Prefix of launcher-captured log files, also used when pruning.
    private static final String FILE_PREFIX = "mindustry-";

    /// Suffix of launcher-captured log files.
    private static final String FILE_SUFFIX = ".log";

    /// How many launcher-captured log files are kept per instance.
    public static final int MAX_LOG_FILES = 10;

    /// Timestamp format embedded in log file names; lexicographic order matches age.
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());

    /// Resolves the launcher log directory for one instance.
    public static Path logsDir(Path versionRoot) {
        return versionRoot.resolve(LOG_DIR).toAbsolutePath().normalize();
    }

    /// Allocates the file name for the next launch, creating and pruning the
    /// log directory on the way.
    ///
    /// The file itself is created by the launcher when the process is spawned,
    /// so an unused name is harmless. Returns `null` when the directory cannot
    /// be prepared; callers then launch without a launcher-side log.
    public static @Nullable Path newLaunchLog(Path versionRoot) {
        Path dir = logsDir(versionRoot);
        try {
            Files.createDirectories(dir);
            pruneOldLogs(dir, MAX_LOG_FILES - 1);
            String stamp = STAMP.format(Instant.now());
            Path candidate = dir.resolve(FILE_PREFIX + stamp + FILE_SUFFIX);
            for (int index = 1; Files.exists(candidate); index++) {
                candidate = dir.resolve(FILE_PREFIX + stamp + "-" + index + FILE_SUFFIX);
            }
            return candidate;
        } catch (IOException | RuntimeException ex) {
            LOG.warning("Unable to prepare the Mindustry launch log directory " + dir + ": " + ex);
            return null;
        }
    }

    /// Lists launcher-captured logs of one instance, newest first.
    public static @Unmodifiable List<Path> listLaunchLogs(Path versionRoot) {
        Path dir = logsDir(versionRoot);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> logs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry) && isLaunchLog(entry)) {
                    logs.add(entry);
                }
            }
        } catch (IOException ex) {
            LOG.warning("Unable to list Mindustry launch logs in " + dir + ": " + ex);
            return List.of();
        }
        // File names carry a sortable timestamp, so descending order is age order.
        logs.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
        return List.copyOf(logs);
    }

    /// Newest launcher-captured log of one instance, or `null` when none exists.
    public static @Nullable Path newestLaunchLog(Path versionRoot) {
        List<Path> logs = listLaunchLogs(versionRoot);
        return logs.isEmpty() ? null : logs.get(0);
    }

    /// True when `file` is a launcher-captured launch log.
    private static boolean isLaunchLog(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX);
    }

    /// Deletes the oldest launcher logs so at most `keep` files remain.
    private static void pruneOldLogs(Path dir, int keep) {
        if (keep < 0) {
            return;
        }
        List<Path> logs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isRegularFile(entry) && isLaunchLog(entry)) {
                    logs.add(entry);
                }
            }
        } catch (IOException ex) {
            LOG.warning("Unable to scan Mindustry launch logs in " + dir + ": " + ex);
            return;
        }
        logs.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
        for (int index = keep; index < logs.size(); index++) {
            try {
                Files.deleteIfExists(logs.get(index));
            } catch (IOException ex) {
                LOG.warning("Unable to delete old Mindustry launch log " + logs.get(index) + ": " + ex);
            }
        }
    }

    private MindustryLaunchLog() {
    }
}
