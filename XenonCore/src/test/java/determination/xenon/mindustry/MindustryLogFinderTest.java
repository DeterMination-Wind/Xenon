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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests the log resolution used by the crash dialog and the log page.
@NotNullByDefault
public final class MindustryLogFinderTest {

    /// The game's own log wins over every launcher-side artefact.
    @Test
    public void prefersGameLastLog(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        Path lastLog = write(dataDir.resolve("last_log.txt"), "game");
        Path launchLog = write(tempDir.resolve("launch.log"), "launcher");
        Path versionRoot = tempDir.resolve("version");

        assertEquals(lastLog, MindustryLogFinder.resolveLog(dataDir, launchLog, versionRoot));
    }

    /// A finished launch falls back to the exact launcher-captured log.
    @Test
    public void fallsBackToFinishedLaunchLog(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        Path launchLog = write(tempDir.resolve("launch.log"), "launcher");

        assertEquals(launchLog, MindustryLogFinder.resolveLog(dataDir, launchLog, null));
    }

    /// Without a known launch file the newest launcher log of the instance is used.
    @Test
    public void fallsBackToNewestLaunchLogOfInstance(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        Files.createDirectories(dataDir);
        Path versionRoot = tempDir.resolve("version");
        Path logsDir = MindustryLaunchLog.logsDir(versionRoot);
        write(logsDir.resolve("mindustry-20260101-000000.log"), "old");
        Path newest = write(logsDir.resolve("mindustry-20260202-000000.log"), "new");

        assertEquals(newest, MindustryLogFinder.resolveLog(dataDir, null, versionRoot));
    }

    /// A crash report is better than reporting that no log exists.
    @Test
    public void fallsBackToNewestCrashReport(@TempDir Path tempDir) throws IOException {
        Path dataDir = tempDir.resolve("data");
        write(dataDir.resolve("crashes").resolve("crash-report-01.txt"), "crash");

        @Nullable Path resolved = MindustryLogFinder.resolveLog(dataDir, null, null);

        assertEquals("crash-report-01.txt", resolved == null ? null : resolved.getFileName().toString());
    }

    /// An instance that never ran resolves to no file at all.
    @Test
    public void reportsNothingForFreshInstance(@TempDir Path tempDir) {
        assertNull(MindustryLogFinder.resolveLog(tempDir.resolve("data"), null, tempDir.resolve("version")));
        assertNull(MindustryLogFinder.resolveLog(tempDir.resolve("data"), null, null));
    }

    /// Writes one file, creating parent directories.
    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
