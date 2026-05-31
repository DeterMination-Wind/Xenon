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
package determination.xenon.mindustry.server;

import determination.xenon.mindustry.server.ServerConsoleSession.ConsoleEvent;
import determination.xenon.mindustry.server.ServerConsoleSession.Exited;
import determination.xenon.mindustry.server.ServerConsoleSession.Restarted;
import determination.xenon.mindustry.server.ServerConsoleSession.Started;
import determination.xenon.mindustry.server.ServerConsoleSession.StderrLine;
import determination.xenon.mindustry.server.ServerConsoleSession.StdoutLine;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/// Shared runtime/session registry layered on top of `ServerInstanceManager`.
///
/// One handle is created per instance id on demand and survives page
/// navigation. The registry owns the active `ServerSessionRunner`,
/// console ring buffer, subscribers, and last-known lifecycle state.
public final class ServerRuntimeRegistry {

    private static final int MAX_BUFFERED_LINES = 2_000;

    private final ServerInstanceManager manager;
    private final Set<RuntimeHandle> handles = new LinkedHashSet<>();

    public ServerRuntimeRegistry(ServerInstanceManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    /// Resolve or create the shared runtime handle for `id`.
    public synchronized ServerRuntimeHandle get(String id) throws IOException {
        Objects.requireNonNull(id, "id");
        for (RuntimeHandle handle : handles) {
            if (handle.getServerId().equals(id)) {
                return handle;
            }
        }
        ServerInstance inst = manager.get(id)
                .orElseThrow(() -> new IOException("server instance not found: " + id));
        RuntimeHandle handle = new RuntimeHandle(inst, manager);
        handles.add(handle);
        return handle;
    }

    private static final class RuntimeHandle implements ServerRuntimeHandle {

        private final ServerInstance instance;
        private final ServerInstanceManager manager;
        private final CopyOnWriteArraySet<Consumer<ConsoleEvent>> listeners = new CopyOnWriteArraySet<>();
        private final ArrayDeque<String> bufferedLines = new ArrayDeque<>();

        private volatile @Nullable ServerSessionRunner runner;
        private volatile ServerInstance.LifecycleState state;
        private volatile @Nullable Integer lastExitCode;
        private volatile String statusMessage;

        private RuntimeHandle(ServerInstance instance, ServerInstanceManager manager) {
            this.instance = Objects.requireNonNull(instance, "instance");
            this.manager = Objects.requireNonNull(manager, "manager");
            this.state = instance.getLastLifecycleState();
            this.lastExitCode = instance.getLastExitCode();
            this.statusMessage = instance.getLastStatusMessage();
        }

        @Override
        public String getServerId() {
            return instance.getId();
        }

        @Override
        public ServerInstance.LifecycleState getState() {
            return state;
        }

        @Override
        public boolean isRunning() {
            ServerSessionRunner local = runner;
            return local != null && local.isRunning();
        }

        @Override
        public @Nullable Integer getLastExitCode() {
            return lastExitCode;
        }

        @Override
        public String getStatusMessage() {
            return statusMessage;
        }

        @Override
        public @UnmodifiableView List<String> getBufferedLines() {
            synchronized (bufferedLines) {
                return List.copyOf(bufferedLines);
            }
        }

        @Override
        public Runnable subscribe(Consumer<ConsoleEvent> listener) {
            Objects.requireNonNull(listener, "listener");
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }

        @Override
        public synchronized void start() {
            if (runner != null && runner.isRunning()) {
                return;
            }
            ServerAutoRestartPolicy policy = new ServerAutoRestartPolicy(instance);
            ServerSessionRunner newRunner = new ServerSessionRunner(instance, manager, policy);
            runner = newRunner;
            updateState(ServerInstance.LifecycleState.STARTING, null, "Starting");
            newRunner.start(this::onEvent);
        }

        @Override
        public void stop() {
            ServerSessionRunner local = runner;
            if (local == null) {
                return;
            }
            updateState(ServerInstance.LifecycleState.STOPPED, lastExitCode, "Stopping");
            local.stop();
        }

        @Override
        public void sendCommand(String command) throws IOException {
            ServerSessionRunner local = runner;
            if (local == null || !local.isRunning()) {
                throw new IOException("server " + instance.getId() + " is not running");
            }
            ServerConsoleSession session = local.currentSession();
            if (session == null) {
                throw new IOException("no active console session for " + instance.getId());
            }
            try {
                session.sendCommand(command);
            } catch (UncheckedIOException ex) {
                throw ex.getCause();
            }
            buffer("> " + command);
        }

        private void onEvent(ConsoleEvent event) {
            if (event instanceof Started) {
                updateState(ServerInstance.LifecycleState.RUNNING, null, "Running");
            } else if (event instanceof Restarted restarted) {
                updateState(ServerInstance.LifecycleState.RESTARTING, null,
                        "Auto-restart attempt " + restarted.attempt());
                buffer("[xenon] Auto-restart attempt " + restarted.attempt());
            } else if (event instanceof StdoutLine line) {
                buffer(line.text());
            } else if (event instanceof StderrLine line) {
                buffer("[stderr] " + line.text());
            } else if (event instanceof Exited exited) {
                ServerInstance.LifecycleState newState = exited.code() == 0
                        ? ServerInstance.LifecycleState.STOPPED
                        : ServerInstance.LifecycleState.CRASHED;
                updateState(newState, exited.code(), "Exited (" + exited.code() + ")");
                buffer("[xenon] Exited (" + exited.code() + ")");
            }
            for (Consumer<ConsoleEvent> listener : listeners) {
                try {
                    listener.accept(event);
                } catch (RuntimeException ex) {
                    Logger.LOG.warning("server console subscriber failed for " + instance.getId() + ": " + ex);
                }
            }
        }

        private void buffer(String line) {
            synchronized (bufferedLines) {
                bufferedLines.addLast(line);
                while (bufferedLines.size() > MAX_BUFFERED_LINES) {
                    bufferedLines.removeFirst();
                }
            }
        }

        private synchronized void updateState(ServerInstance.LifecycleState state,
                                              @Nullable Integer exitCode,
                                              String message) {
            this.state = state;
            this.lastExitCode = exitCode;
            this.statusMessage = message;
            instance.setLastLifecycleState(state);
            instance.setLastExitCode(exitCode);
            instance.setLastStatusMessage(message);
            try {
                manager.save(instance);
            } catch (IOException ex) {
                Logger.LOG.warning("Failed to persist server state for " + instance.getId() + ": " + ex.getMessage());
            }
        }
    }
}
