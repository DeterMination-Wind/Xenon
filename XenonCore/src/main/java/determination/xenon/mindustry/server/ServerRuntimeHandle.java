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
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/// Shared runtime handle for one logical server instance.
///
/// UI pages subscribe to this handle instead of owning their own
/// `ServerSessionRunner`, so navigation does not reset the running state
/// or lose console history.
public interface ServerRuntimeHandle {

    /// Stable instance id this handle belongs to.
    String getServerId();

    /// Latest known lifecycle state.
    ServerInstance.LifecycleState getState();

    /// Whether a process is currently alive for this server.
    boolean isRunning();

    /// Last observed process exit code, or null if unavailable.
    @Nullable Integer getLastExitCode();

    /// Best-effort user-facing status text for the current/last state.
    String getStatusMessage();

    /// Snapshot of the in-memory console ring buffer, oldest first.
    @UnmodifiableView List<String> getBufferedLines();

    /// Subscribe to future console events. Caller should invoke the returned
    /// runnable when it no longer wants updates.
    Runnable subscribe(Consumer<ConsoleEvent> listener);

    /// Start the server if not already running.
    void start();

    /// Request graceful stop. No-op if already stopped.
    void stop();

    /// Send one console command to the current session.
    void sendCommand(String command) throws IOException;
}
