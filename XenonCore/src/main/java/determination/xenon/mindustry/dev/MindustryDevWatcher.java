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

import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/// Watches a development project and coalesces edit bursts into one callback.
///
/// Build outputs (`build/`), Gradle caches (`.gradle/`), `out/`, `target/` and
/// IDEA metadata are ignored. A change schedules the callback one second later;
/// further changes inside that window reset the timer, so saving several files
/// produces one rebuild rather than a storm.
@NotNullByDefault
public final class MindustryDevWatcher implements AutoCloseable {

    /// Debounce window.
    private static final long DEBOUNCE_MILLIS = 1500L;

    /// Directories that never trigger a rebuild.
    private static final String[] IGNORED = {
            "build", ".gradle", "out", "target", ".idea", ".git", "node_modules"
    };

    private final Path projectDir;
    private final Runnable onChange;
    private final WatchService watchService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "xenon-dev-watch");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<WatchKey, Path> keys = new HashMap<>();
    private volatile ScheduledFuture<?> pending;

    /// Creates and starts a watcher.
    ///
    /// @param projectDir project directory
    /// @param onChange   callback fired after a debounced change
    /// @throws IOException when the directory cannot be watched
    public MindustryDevWatcher(Path projectDir, Runnable onChange) throws IOException {
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.onChange = onChange;
        this.watchService = FileSystems.getDefault().newWatchService();
        registerTree(this.projectDir);
        Thread worker = new Thread(this::loop, "xenon-dev-watch-events");
        worker.setDaemon(true);
        worker.start();
    }

    /// Registers the project tree, skipping ignored directories.
    private void registerTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (!dir.equals(root) && isIgnored(root, dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                WatchKey key = dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                keys.put(key, dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /// Receives watch events until [#close()].
    private void loop() {
        while (!closed.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                return;
            }
            Path dir = keys.get(key);
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    schedule();
                    continue;
                }
                Object context = event.context();
                if (!(context instanceof Path relative) || dir == null) {
                    continue;
                }
                Path changed = dir.resolve(relative);
                if (isIgnored(projectDir, changed)) {
                    continue;
                }
                schedule();
            }
            if (!key.reset()) {
                keys.remove(key);
                if (keys.isEmpty()) {
                    return;
                }
            }
        }
    }

    /// Schedules one debounced callback.
    private void schedule() {
        if (closed.get()) {
            return;
        }
        ScheduledFuture<?> current = pending;
        if (current != null) {
            current.cancel(false);
        }
        pending = scheduler.schedule(() -> {
            if (closed.get()) {
                return;
            }
            try {
                onChange.run();
            } catch (RuntimeException e) {
                Logger.LOG.warning("Development change callback failed: " + e.getMessage());
            }
        }, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
    }

    /// Whether a path lives under an ignored directory.
    private static boolean isIgnored(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            for (String ignored : IGNORED) {
                if (part.toString().equalsIgnoreCase(ignored)) {
                    return true;
                }
            }
        }
        return false;
    }

    /// Stops the watcher.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> current = pending;
        if (current != null) {
            current.cancel(false);
        }
        scheduler.shutdownNow();
        try {
            watchService.close();
        } catch (IOException ignored) {
            // Best effort.
        }
    }
}
