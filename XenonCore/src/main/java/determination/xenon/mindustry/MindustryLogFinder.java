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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static determination.xenon.util.logging.Logger.LOG;

/// Picks the log file Xenon should open for one Mindustry instance.
///
/// The game only creates `<dataDir>/last_log.txt` once its JVM has reached the
/// file-logger setup, and the effective data directory can differ from the
/// instance's default one (save-archive runtimes, global policies). The
/// resolver therefore falls back through every artefact the launcher or the
/// game may have left behind instead of reporting "log not found" while a
/// usable file exists.
@NotNullByDefault
public final class MindustryLogFinder {

    /// Resolves the most useful log file of an instance.
    ///
    /// Candidate order:
    /// 1. `<dataDir>/last_log.txt` — the game's own log of its latest run;
    /// 2. `launchLog` (or the newest `<versionRoot>/logs/mindustry-*.log`) —
    ///    the launcher-captured output, which exists even when the JVM dies
    ///    before Mindustry set up its logger;
    /// 3. the newest `<dataDir>/crashes/*.txt` crash report.
    ///
    /// @param dataDir instance data directory; may be `null` when unknown
    /// @param launchLog exact launcher-captured log of a just-finished launch; may be `null`
    /// @param versionRoot instance version root used to discover older launcher logs; may be `null`
    /// @return the file to open, or `null` when the instance has no log at all
    public static @Nullable Path resolveLog(@Nullable Path dataDir,
                                            @Nullable Path launchLog,
                                            @Nullable Path versionRoot) {
        if (dataDir != null) {
            Path lastLog = dataDir.resolve("last_log.txt");
            if (Files.isRegularFile(lastLog)) {
                return lastLog;
            }
        }

        if (launchLog != null && Files.isRegularFile(launchLog)) {
            return launchLog;
        }
        if (versionRoot != null) {
            Path newest = MindustryLaunchLog.newestLaunchLog(versionRoot);
            if (newest != null) {
                return newest;
            }
        }

        if (dataDir != null) {
            return newestCrashReport(dataDir);
        }
        return null;
    }

    /// Newest `crashes/*.txt` file under a Mindustry data directory.
    public static @Nullable Path newestCrashReport(Path dataDir) {
        Path crashes = dataDir.resolve("crashes");
        if (!Files.isDirectory(crashes)) {
            return null;
        }
        Optional<Path> newest = Optional.empty();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(crashes, "*.txt")) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                if (newest.isEmpty() || lastModified(entry) > lastModified(newest.get())) {
                    newest = Optional.of(entry);
                }
            }
        } catch (IOException ex) {
            LOG.warning("Unable to scan Mindustry crash reports in " + crashes + ": " + ex);
            return null;
        }
        return newest.orElse(null);
    }

    /// Last-modified millis, or `0` when the timestamp cannot be read.
    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private MindustryLogFinder() {
    }
}
