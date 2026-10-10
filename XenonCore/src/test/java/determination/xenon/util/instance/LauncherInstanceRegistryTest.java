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
package determination.xenon.util.instance;

import com.google.gson.Gson;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests registration, discovery and stale-record cleanup of the instance registry.
@NotNullByDefault
public final class LauncherInstanceRegistryTest {

    /// A registered instance can be discovered from another registry.
    @Test
    public void registerWritesReadableRecord(@TempDir Path directory) throws Exception {
        LauncherInstanceRegistry registry = new LauncherInstanceRegistry(directory);
        LauncherInstance instance = instance(1111L, 45678, 1000L);
        try {
            registry.register(instance);

            assertEquals(List.of(instance), registry.findAll());
        } finally {
            registry.close();
        }
    }

    /// Discovered instances are ordered by startup time.
    @Test
    public void findAllSortsByStartedAt(@TempDir Path directory) throws Exception {
        LauncherInstanceRegistry first = new LauncherInstanceRegistry(directory);
        LauncherInstanceRegistry second = new LauncherInstanceRegistry(directory);
        try {
            first.register(instance(1L, 45679, 2000L));
            second.register(instance(2L, 45680, 1000L));

            List<Long> startedAt = first.findAll().stream()
                    .map(LauncherInstance::startedAt)
                    .toList();

            assertEquals(List.of(1000L, 2000L), startedAt);
        } finally {
            first.close();
            second.close();
        }
    }

    /// Blank, malformed and incomplete records are skipped.
    @Test
    public void findAllSkipsCorruptRecords(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("1-aaaaaaaa.json"), "", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("2-bbbbbbbb.json"), "not json", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("3-cccccccc.json"), "{\"pid\":0}", StandardCharsets.UTF_8);
        LauncherInstanceRegistry registry = new LauncherInstanceRegistry(directory);

        assertTrue(registry.findAll().isEmpty());
    }

    /// Records whose sentinel lock is free are stale and get deleted.
    @Test
    public void deadRecordsAreCleanedUp(@TempDir Path directory) throws Exception {
        Path record = directory.resolve("777-dddddddd.json");
        Path lock = directory.resolve("777-dddddddd.lock");
        Files.writeString(record, new Gson().toJson(instance(777L, 45681, 1000L)), StandardCharsets.UTF_8);
        Files.createFile(lock);
        LauncherInstanceRegistry registry = new LauncherInstanceRegistry(directory,
                (port, timeoutMillis) -> false);

        LauncherInstance instance = registry.findAll().get(0);

        assertFalse(registry.isAlive(instance));
        assertFalse(Files.exists(record));
        assertFalse(Files.exists(lock));
    }

    /// Records whose sentinel lock is held by another registry stay alive.
    @Test
    public void liveRecordsAreKept(@TempDir Path directory) throws Exception {
        LauncherInstanceRegistry owner = new LauncherInstanceRegistry(directory);
        LauncherInstanceRegistry observer = new LauncherInstanceRegistry(directory,
                (port, timeoutMillis) -> false);
        try {
            owner.register(instance(888L, 45682, 1000L));

            LauncherInstance discovered = observer.findAll().get(0);

            assertTrue(observer.isAlive(discovered));
            try (var files = Files.list(directory)) {
                assertEquals(1L, files.filter(file -> file.toString().endsWith(".json")).count());
            }
        } finally {
            owner.close();
        }
        // After the owner exits its record becomes stale and is removed.
        assertFalse(observer.isAlive(instance(888L, 45682, 1000L)));
        assertTrue(observer.findAll().isEmpty());
    }

    /// Unregistering deletes both record files and is idempotent.
    @Test
    public void unregisterRemovesFiles(@TempDir Path directory) throws Exception {
        LauncherInstanceRegistry registry = new LauncherInstanceRegistry(directory);
        registry.register(instance(999L, 45683, 1000L));

        registry.unregister();
        registry.unregister();

        assertTrue(registry.findAll().isEmpty());
        try (var files = Files.list(directory)) {
            assertEquals(0L, files.count());
        }
    }

    /// When the sentinel cannot be read the injectable port probe decides.
    @Test
    public void probeFallbackHandlesUnreadableLock(@TempDir Path directory) throws Exception {
        Path record = directory.resolve("555-eeeeeeee.json");
        Files.writeString(record, new Gson().toJson(instance(555L, 45684, 1000L)), StandardCharsets.UTF_8);
        // A directory cannot be opened as a lock file, which forces the probe path.
        Files.createDirectory(directory.resolve("555-eeeeeeee.lock"));
        AtomicBoolean probed = new AtomicBoolean();
        LauncherInstanceRegistry registry = new LauncherInstanceRegistry(directory, (port, timeoutMillis) -> {
            probed.set(true);
            return true;
        });

        LauncherInstance instance = registry.findAll().get(0);

        assertTrue(registry.isAlive(instance));
        assertTrue(probed.get());
        assertTrue(Files.exists(record));
    }

    /// A failed port probe reports the instance as dead.
    @Test
    public void probeFallbackReportsDeadInstance(@TempDir Path directory) throws Exception {
        Path record = directory.resolve("556-ffffffff.json");
        Files.writeString(record, new Gson().toJson(instance(556L, 45685, 1000L)), StandardCharsets.UTF_8);
        Files.createDirectory(directory.resolve("556-ffffffff.lock"));
        LauncherInstanceRegistry registry = new LauncherInstanceRegistry(directory,
                (port, timeoutMillis) -> false);

        LauncherInstance instance = registry.findAll().get(0);

        assertFalse(registry.isAlive(instance));
    }

    /// Builds a distinct test instance.
    private static LauncherInstance instance(long pid, int port, long startedAt) {
        return new LauncherInstance(pid, port, "token-" + pid, "1.15.0", null, startedAt);
    }
}
