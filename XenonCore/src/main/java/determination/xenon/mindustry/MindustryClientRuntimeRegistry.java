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

import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/// Shared runtime registry for launched Mindustry client processes.
///
/// The registry keeps one live handle per version id, removes handles
/// when the process exits, and cleans Windows "java process still alive
/// but the game window is gone" leftovers before allowing a new launch.
@NotNullByDefault
public final class MindustryClientRuntimeRegistry {

    /// Singleton registry used by the launcher UI.
    private static final MindustryClientRuntimeRegistry SHARED = new MindustryClientRuntimeRegistry();

    /// Poll interval for process/window health checks.
    private static final Duration MONITOR_INTERVAL = Duration.ofSeconds(1);

    /// Time before a never-visible JVM may be replaced by a new launch request.
    private static final Duration STARTUP_WINDOW_TIMEOUT = Duration.ofSeconds(45);

    /// Grace period after a previously visible window disappears.
    private static final Duration VANISHED_WINDOW_GRACE = Duration.ofSeconds(3);

    /// Number of recent stdout/stderr lines retained for diagnostics.
    private static final int RECENT_LINE_LIMIT = 120;

    /// Daemon scheduler shared by all client process handles.
    private static final ScheduledExecutorService MONITOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "xenon-mindustry-client-monitor");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, RuntimeHandle> handles = new LinkedHashMap<>();

    /// Returns the shared launcher-wide client runtime registry.
    public static MindustryClientRuntimeRegistry shared() {
        return SHARED;
    }

    /// Start a client unless one is already alive for `id`.
    ///
    /// If an older handle is still alive but has no visible Windows game
    /// window, it is treated as a stale leftover, destroyed, and replaced
    /// by the new launch. Listener callbacks are delivered on internal
    /// monitor threads; UI callers should marshal to their UI scheduler.
    public synchronized LaunchResult launch(String id,
                                            LaunchOptions options,
                                            Consumer<ClientEvent> listener,
                                            @Nullable Consumer<String> stdout,
                                            @Nullable Consumer<String> stderr) throws IOException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(listener, "listener");

        RuntimeHandle existing = handles.get(id);
        if (existing != null) {
            if (!existing.isAlive()) {
                handles.remove(id);
            } else if (existing.isStaleWindowlessProcess()) {
                existing.terminateStaleProcess();
                handles.remove(id);
                emit(listener, new WindowlessProcessTerminated(id, existing.pid(), existing.recentLines(), true));
            } else {
                emit(listener, new AlreadyRunning(id, existing.pid()));
                return new LaunchResult(LaunchDisposition.ALREADY_RUNNING, existing);
            }
        }

        RuntimeHandle handle = new RuntimeHandle(id, options, listener, stdout, stderr);
        try {
            handle.start();
        } catch (IOException ex) {
            emit(listener, new LaunchFailed(id, ex));
            throw ex;
        }
        handles.put(id, handle);
        emit(listener, new Started(id, handle.pid()));
        handle.monitorExit(this);
        handle.monitorWindow(this);
        return new LaunchResult(LaunchDisposition.STARTED, handle);
    }

    /// Snapshot of live handles keyed by version id.
    public synchronized @Unmodifiable Map<String, RuntimeHandle> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(handles));
    }

    private synchronized void removeIfCurrent(String id, RuntimeHandle handle) {
        handles.remove(id, handle);
    }

    private static void emit(Consumer<ClientEvent> listener, ClientEvent event) {
        try {
            listener.accept(event);
        } catch (RuntimeException ex) {
            Logger.LOG.warning("Mindustry client listener failed for " + event + ": " + ex);
        }
    }

    /// Result of a launch request.
    public record LaunchResult(LaunchDisposition disposition, RuntimeHandle handle) {
    }

    /// Whether a launch request started a new process or reused an existing one.
    public enum LaunchDisposition {
        /// A new Mindustry JVM was started.
        STARTED,
        /// A live process already existed for the requested version id.
        ALREADY_RUNNING
    }

    /// Events emitted by client runtime handles.
    public sealed interface ClientEvent
            permits Started, AlreadyRunning, Exited, LaunchFailed, WindowlessProcessTerminated {
    }

    /// Emitted after a new process has been spawned.
    public record Started(String id, long pid) implements ClientEvent {
    }

    /// Emitted when a launch request is ignored because a visible process already exists.
    public record AlreadyRunning(String id, long pid) implements ClientEvent {
    }

    /// Emitted once a process exits normally or with an error code.
    public record Exited(String id, long pid, int exitCode, @Unmodifiable List<String> recentLines)
            implements ClientEvent {
    }

    /// Emitted when process creation itself failed.
    public record LaunchFailed(String id, Throwable error) implements ClientEvent {
    }

    /// Emitted when a process is killed because no game window remains visible.
    public record WindowlessProcessTerminated(String id,
                                              long pid,
                                              @Unmodifiable List<String> recentLines,
                                              boolean relaunching)
            implements ClientEvent {
    }

    /// Live handle for one launched Mindustry JVM.
    public static final class RuntimeHandle {

        private final String id;
        private final LaunchOptions options;
        private final Consumer<ClientEvent> listener;
        private final @Nullable Consumer<String> stdout;
        private final @Nullable Consumer<String> stderr;
        private final Deque<String> recentLines = new ArrayDeque<>();
        private final Instant startedAt = Instant.now();

        private volatile @Nullable XenonLauncher.MindustryProcess process;
        private volatile boolean visibleWindowSeen;
        private volatile boolean staleTerminated;
        private volatile @Nullable Instant firstMissingVisibleWindowAt;
        private volatile @Nullable ScheduledFuture<?> windowMonitor;

        private RuntimeHandle(String id,
                              LaunchOptions options,
                              Consumer<ClientEvent> listener,
                              @Nullable Consumer<String> stdout,
                              @Nullable Consumer<String> stderr) {
            this.id = id;
            this.options = options;
            this.listener = listener;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        /// Version id this handle belongs to.
        public String id() {
            return id;
        }

        /// Process id, or `-1` before process creation completes.
        public long pid() {
            XenonLauncher.MindustryProcess local = process;
            return local == null ? -1L : local.getProcess().pid();
        }

        /// Whether the underlying JVM process is still alive.
        public boolean isAlive() {
            XenonLauncher.MindustryProcess local = process;
            return local != null && local.isAlive();
        }

        /// Immutable snapshot of recent stdout/stderr lines.
        public @Unmodifiable List<String> recentLines() {
            synchronized (recentLines) {
                return Collections.unmodifiableList(new ArrayList<>(recentLines));
            }
        }

        private void start() throws IOException {
            process = XenonLauncher.launch(options,
                    line -> {
                        remember(line);
                        Consumer<String> sink = stdout;
                        if (sink != null) {
                            sink.accept(line);
                        }
                    },
                    line -> {
                        remember("[stderr] " + line);
                        Consumer<String> sink = stderr;
                        if (sink != null) {
                            sink.accept(line);
                        }
                    });
        }

        private void monitorExit(MindustryClientRuntimeRegistry registry) {
            XenonLauncher.MindustryProcess local = process;
            if (local == null) {
                return;
            }
            local.onExit().whenComplete((exitCode, error) -> {
                ScheduledFuture<?> monitor = windowMonitor;
                if (monitor != null) {
                    monitor.cancel(false);
                }
                registry.removeIfCurrent(id, this);
                int code = exitCode == null ? -1 : exitCode;
                if (error != null) {
                    Logger.LOG.warning("Mindustry client exit observation failed for " + id, error);
                }
                if (!staleTerminated) {
                    emit(listener, new Exited(id, pid(), code, recentLines()));
                }
            });
        }

        private void monitorWindow(MindustryClientRuntimeRegistry registry) {
            if (!MindustryClientWindowProbe.isSupported()) {
                return;
            }
            windowMonitor = MONITOR.scheduleWithFixedDelay(() -> checkWindow(registry),
                    MONITOR_INTERVAL.toMillis(),
                    MONITOR_INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS);
        }

        private void checkWindow(MindustryClientRuntimeRegistry registry) {
            if (!isAlive()) {
                return;
            }
            long pid = pid();
            boolean hasWindow = MindustryClientWindowProbe.hasVisibleWindow(pid);
            if (hasWindow) {
                visibleWindowSeen = true;
                firstMissingVisibleWindowAt = null;
                return;
            }

            if (!visibleWindowSeen) {
                return;
            }

            Instant now = Instant.now();
            if (firstMissingVisibleWindowAt == null) {
                firstMissingVisibleWindowAt = now;
                return;
            }
            if (Duration.between(firstMissingVisibleWindowAt, now)
                    .compareTo(VANISHED_WINDOW_GRACE) < 0) {
                return;
            }

            terminateStaleProcess();
            registry.removeIfCurrent(id, this);
            emit(listener, new WindowlessProcessTerminated(id, pid, recentLines(), false));
        }

        private boolean isStaleWindowlessProcess() {
            if (!isAlive() || !MindustryClientWindowProbe.isSupported()) {
                return false;
            }
            boolean hasWindow = MindustryClientWindowProbe.hasVisibleWindow(pid());
            if (hasWindow) {
                visibleWindowSeen = true;
                firstMissingVisibleWindowAt = null;
                return false;
            }
            if (visibleWindowSeen) {
                Instant firstMissing = firstMissingVisibleWindowAt;
                if (firstMissing == null) {
                    firstMissing = Instant.now();
                    firstMissingVisibleWindowAt = firstMissing;
                }
                return Duration.between(firstMissing, Instant.now()).compareTo(VANISHED_WINDOW_GRACE) >= 0;
            }
            return Duration.between(startedAt, Instant.now()).compareTo(STARTUP_WINDOW_TIMEOUT) >= 0;
        }

        private void terminateStaleProcess() {
            XenonLauncher.MindustryProcess local = process;
            if (local == null || !local.isAlive()) {
                return;
            }
            Logger.LOG.warning("Terminating windowless Mindustry process " + id + " pid=" + pid());
            staleTerminated = true;
            ScheduledFuture<?> monitor = windowMonitor;
            if (monitor != null) {
                monitor.cancel(false);
            }
            local.destroyForcibly();
        }

        private void remember(String line) {
            synchronized (recentLines) {
                recentLines.addLast(line);
                while (recentLines.size() > RECENT_LINE_LIMIT) {
                    recentLines.removeFirst();
                }
            }
        }
    }

    private MindustryClientRuntimeRegistry() {
    }
}
