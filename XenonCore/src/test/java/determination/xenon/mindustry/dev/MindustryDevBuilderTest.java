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
package determination.xenon.mindustry.dev;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the development build runner and debounced watcher.
@NotNullByDefault
public final class MindustryDevBuilderTest {

    /// A successful command resolves the newest matching artifact.
    @Test
    public void buildsAndFindsArtifact(@TempDir Path dir) throws Exception {
        Path artifact = Files.writeString(dir.resolve("app.jar"), "jar");
        CopyOnWriteArrayList<String> log = new CopyOnWriteArrayList<>();
        var result = MindustryDevBuilder.build(new MindustryDevBuilder.Options(
                dir, "echo build-ok", "*.jar", Duration.ofSeconds(30)), log::add, null);
        assertTrue(result.succeeded());
        assertNotNull(result.artifact());
        assertEquals(artifact, result.artifact());
        assertTrue(log.stream().anyMatch(line -> line.contains("build-ok")),
                () -> "log was " + log);
    }

    /// A missing project directory is a hard error.
    @Test
    public void rejectsMissingProject(@TempDir Path dir) {
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> MindustryDevBuilder.build(new MindustryDevBuilder.Options(
                        dir.resolve("missing"), "echo x", "*.jar", Duration.ofSeconds(5)),
                        line -> {
                        }, null));
    }

    /// The watcher fires once for a burst of writes.
    @Test
    public void watcherDebouncesChanges(@TempDir Path dir) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        Files.writeString(dir.resolve("Main.java"), "class Main {}");
        try (MindustryDevWatcher ignored = new MindustryDevWatcher(dir, () -> {
            calls.incrementAndGet();
            latch.countDown();
        })) {
            for (int i = 0; i < 5; i++) {
                Files.writeString(dir.resolve("Main.java"), "class Main { int x = " + i + "; }");
                Thread.sleep(100);
            }
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            Thread.sleep(500);
            assertEquals(1, calls.get());
        }
    }
}
