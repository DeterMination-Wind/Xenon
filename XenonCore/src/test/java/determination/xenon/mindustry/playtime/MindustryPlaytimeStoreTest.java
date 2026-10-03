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
package determination.xenon.mindustry.playtime;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the append-only playtime session log.
@NotNullByDefault
public final class MindustryPlaytimeStoreTest {

    /// Sessions of the same pid are paired and summed.
    @Test
    public void pairsSessionsByPid(@TempDir Path versionRoot) throws IOException {
        MindustryPlaytimeStore store = new MindustryPlaytimeStore(versionRoot);

        store.recordStart(100L, 1_000L);
        store.recordEnd(100L, 5_000L);
        store.recordStart(200L, 10_000L);
        store.recordEnd(200L, 13_000L);

        PlaytimeSummary summary = store.readSummary();
        assertEquals(7_000L, summary.totalActiveMillis());
        assertEquals(10_000L, summary.lastLaunchEpochMillis());
        assertEquals(2, summary.sessions());
    }

    /// A dangling start contributes to the last-launch time but no duration.
    @Test
    public void ignoresDanglingStartForDuration(@TempDir Path versionRoot) throws IOException {
        MindustryPlaytimeStore store = new MindustryPlaytimeStore(versionRoot);

        store.recordStart(100L, 4_200L);

        PlaytimeSummary summary = store.readSummary();
        assertEquals(0L, summary.totalActiveMillis());
        assertEquals(4_200L, summary.lastLaunchEpochMillis());
        assertEquals(0, summary.sessions());
    }

    /// Concurrent starts of different pids never cross-pair.
    @Test
    public void pairsInterleavedPidsIndependently(@TempDir Path versionRoot) throws IOException {
        MindustryPlaytimeStore store = new MindustryPlaytimeStore(versionRoot);

        store.recordStart(1L, 1_000L);
        store.recordStart(2L, 2_000L);
        store.recordEnd(1L, 3_000L);
        store.recordEnd(2L, 4_000L);

        PlaytimeSummary summary = store.readSummary();
        assertEquals(4_000L, summary.totalActiveMillis());
        assertEquals(2_000L, summary.lastLaunchEpochMillis());
        assertEquals(2, summary.sessions());
    }

    /// A dangling start never absorbs the end of a later session that reuses the pid.
    @Test
    public void pairsEndWithMostRecentOpenStartWhenPidIsReused(@TempDir Path versionRoot) throws IOException {
        MindustryPlaytimeStore store = new MindustryPlaytimeStore(versionRoot);

        // The launcher was killed while the game ran, so this start never got an end.
        store.recordStart(100L, 1_000L);
        // Windows later reuses the pid for a fresh session.
        store.recordStart(100L, 50_000L);
        store.recordEnd(100L, 52_000L);

        PlaytimeSummary summary = store.readSummary();
        assertEquals(2_000L, summary.totalActiveMillis());
        assertEquals(50_000L, summary.lastLaunchEpochMillis());
        assertEquals(1, summary.sessions());
    }

    /// Malformed lines and non-positive timestamps are skipped silently.
    @Test
    public void skipsCorruptLines(@TempDir Path versionRoot) throws IOException {
        Path file = versionRoot.resolve(MindustryPlaytimeStore.FILE_NAME);
        Files.createDirectories(versionRoot);
        String valid = "{\"type\":\"start\",\"pid\":7,\"at\":1000}";
        String corrupt = "this is not json";
        String negative = "{\"type\":\"end\",\"pid\":7,\"at\":0}";
        Files.writeString(file, valid + System.lineSeparator()
                        + corrupt + System.lineSeparator()
                        + negative + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE);

        PlaytimeSummary summary = new MindustryPlaytimeStore(versionRoot).readSummary();
        assertEquals(0L, summary.totalActiveMillis());
        assertEquals(1_000L, summary.lastLaunchEpochMillis());
        assertEquals(0, summary.sessions());
    }

    /// A missing log reads as an empty summary instead of throwing.
    @Test
    public void missingFileReadsEmpty(@TempDir Path versionRoot) {
        PlaytimeSummary summary = new MindustryPlaytimeStore(versionRoot).readSummary();
        assertEquals(PlaytimeSummary.EMPTY, summary);
    }

    /// Same-JVM parallel appends all land in the log.
    @Test
    public void parallelAppendsFromThreadsAreComplete(@TempDir Path versionRoot) throws Exception {
        MindustryPlaytimeStore store = new MindustryPlaytimeStore(versionRoot);
        int threads = 2;
        int pairsPerThread = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int thread = 0; thread < threads; thread++) {
            long pid = 900L + thread;
            long base = 1_000L * (thread + 1);
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < pairsPerThread; i++) {
                        long at = base + i * 10_000L;
                        store.recordStart(pid, at);
                        store.recordEnd(pid, at + 1_000L);
                    }
                } catch (IOException | InterruptedException ex) {
                    throw new RuntimeException(ex);
                } finally {
                    done.countDown();
                }
            });
            worker.start();
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers finished");

        PlaytimeSummary summary = store.readSummary();
        assertEquals((long) threads * pairsPerThread * 1_000L, summary.totalActiveMillis());
        assertEquals(threads * pairsPerThread, summary.sessions());
    }

    /// Reads racing with appends report whole sessions and never throw.
    @Test
    public void concurrentReadsSeeCompleteSessions(@TempDir Path versionRoot) throws Exception {
        MindustryPlaytimeStore store = new MindustryPlaytimeStore(versionRoot);
        int writers = 2;
        int sessionsPerWriter = 20;
        long expectedTotal = (long) writers * sessionsPerWriter * 1_000L;
        long expectedSessions = (long) writers * sessionsPerWriter;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(writers);
        for (int writer = 0; writer < writers; writer++) {
            long pid = 500L + writer;
            long base = 1_000_000L * (writer + 1);
            Thread worker = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < sessionsPerWriter; i++) {
                        long at = base + i * 10_000L;
                        store.recordStart(pid, at);
                        store.recordEnd(pid, at + 1_000L);
                    }
                } catch (IOException | InterruptedException ex) {
                    throw new RuntimeException(ex);
                } finally {
                    done.countDown();
                }
            });
            worker.start();
        }

        start.countDown();
        do {
            PlaytimeSummary snapshot = store.readSummary();
            assertTrue(snapshot.totalActiveMillis() >= 0L);
            assertTrue(snapshot.totalActiveMillis() <= expectedTotal);
            assertTrue(snapshot.sessions() <= expectedSessions);
        } while (!done.await(2, TimeUnit.MILLISECONDS));

        PlaytimeSummary summary = store.readSummary();
        assertEquals(expectedTotal, summary.totalActiveMillis());
        assertEquals((int) expectedSessions, summary.sessions());
    }
}
