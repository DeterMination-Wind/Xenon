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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests launcher-captured Mindustry launch log management.
@NotNullByDefault
public final class MindustryLaunchLogTest {

    /// Allocates a log inside a fresh `logs/` directory of the version root.
    @Test
    public void allocatesLogInsideVersionRoot(@TempDir Path tempDir) {
        Path versionRoot = tempDir.resolve("version");

        @Nullable Path log = MindustryLaunchLog.newLaunchLog(versionRoot);

        assertNotNull(log);
        assertTrue(log.startsWith(MindustryLaunchLog.logsDir(versionRoot)));
        assertTrue(log.getFileName().toString().startsWith("mindustry-"));
        assertTrue(log.getFileName().toString().endsWith(".log"));
        assertFalse(Files.exists(log));
    }

    /// Two allocations in the same second never return the same file name.
    @Test
    public void avoidsDuplicateNames(@TempDir Path tempDir) throws IOException {
        Path versionRoot = tempDir.resolve("version");

        @Nullable Path first = MindustryLaunchLog.newLaunchLog(versionRoot);
        assertNotNull(first);
        writeLog(first, "first");
        @Nullable Path second = MindustryLaunchLog.newLaunchLog(versionRoot);

        assertNotNull(second);
        assertNotEquals(first, second);
    }

    /// Listing returns launcher logs only, newest first.
    @Test
    public void listsLauncherLogsNewestFirst(@TempDir Path tempDir) throws IOException {
        Path versionRoot = tempDir.resolve("version");
        Path logsDir = MindustryLaunchLog.logsDir(versionRoot);
        Files.createDirectories(logsDir);
        writeLog(logsDir.resolve("mindustry-20260101-000000.log"), "old");
        writeLog(logsDir.resolve("mindustry-20260202-000000.log"), "new");
        writeLog(logsDir.resolve("notes.txt"), "not a launch log");

        List<Path> logs = MindustryLaunchLog.listLaunchLogs(versionRoot);

        assertEquals(2, logs.size());
        assertEquals("mindustry-20260202-000000.log", logs.get(0).getFileName().toString());
        assertEquals("mindustry-20260101-000000.log", logs.get(1).getFileName().toString());
        assertEquals(logs.get(0), MindustryLaunchLog.newestLaunchLog(versionRoot));
    }

    /// An instance without a log directory has no newest log.
    @Test
    public void reportsNoLogsForFreshInstance(@TempDir Path tempDir) {
        assertTrue(MindustryLaunchLog.listLaunchLogs(tempDir.resolve("version")).isEmpty());
        assertNull(MindustryLaunchLog.newestLaunchLog(tempDir.resolve("version")));
    }

    /// Allocating a new log prunes the oldest files beyond the retention limit.
    @Test
    public void prunesOldLogs(@TempDir Path tempDir) throws IOException {
        Path versionRoot = tempDir.resolve("version");
        Path logsDir = MindustryLaunchLog.logsDir(versionRoot);
        Files.createDirectories(logsDir);
        for (int day = 1; day <= MindustryLaunchLog.MAX_LOG_FILES + 3; day++) {
            String name = String.format("mindustry-202601%02d-000000.log", day);
            writeLog(logsDir.resolve(name), "day " + day);
        }

        @Nullable Path allocated = MindustryLaunchLog.newLaunchLog(versionRoot);

        assertNotNull(allocated);
        // The allocated file itself is written by the launcher later, so the
        // pruning pass keeps room for exactly one more file.
        List<Path> logs = MindustryLaunchLog.listLaunchLogs(versionRoot);
        assertEquals(MindustryLaunchLog.MAX_LOG_FILES - 1, logs.size());
        assertFalse(logs.stream().anyMatch(path ->
                path.getFileName().toString().equals("mindustry-20260101-000000.log")));
        assertTrue(logs.stream().anyMatch(path ->
                path.getFileName().toString().equals("mindustry-20260113-000000.log")));
    }

    /// Writes a small launcher log file for listing tests.
    private static void writeLog(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
