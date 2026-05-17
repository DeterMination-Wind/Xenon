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

import determination.xenon.util.logging.Logger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * A live wrapper around one running {@code java -jar server.jar} process.
 *
 * <p>{@link ServerInstanceManager} hands these out from
 * {@link ServerInstanceManager#start}. The wrapper owns the daemon
 * threads pumping stdout/stderr to caller-supplied consumers, exposes a
 * line-oriented {@link #sendCommand(String)} that writes to the
 * server's stdin, and keeps a bounded history of every command it sent
 * for the UI's "scroll back" feature.</p>
 */
public final class ServerProcess {

    /** Maximum number of commands kept in {@link #commandHistory}. */
    private static final int HISTORY_LIMIT = 200;

    private final String instanceId;
    private final Process process;
    private final BufferedWriter stdin;
    private final Thread stdoutThread;
    private final Thread stderrThread;
    private final Deque<String> commandHistory = new ArrayDeque<>();

    ServerProcess(String instanceId,
                  Process process,
                  Consumer<String> stdout,
                  Consumer<String> stderr) {
        this.instanceId = instanceId;
        this.process = process;
        this.stdin = new BufferedWriter(new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8));
        this.stdoutThread = pump(process.getInputStream(),
                stdout != null ? stdout : ServerProcess::sink,
                "xenon-server-stdout-" + instanceId);
        this.stderrThread = pump(process.getErrorStream(),
                stderr != null ? stderr : ServerProcess::sink,
                "xenon-server-stderr-" + instanceId);
    }

    /** Identifier of the {@link ServerInstance} this process was started from. */
    public String getInstanceId() {
        return instanceId;
    }

    /** Underlying JVM-spawned {@link Process}. */
    public Process getProcess() {
        return process;
    }

    /** Whether the underlying process is still running. */
    public boolean isAlive() {
        return process.isAlive();
    }

    /**
     * Send one console command to the server. The trailing newline is
     * appended automatically. Stored in {@link #getCommandHistory()}
     * (capped at 200 entries).
     */
    public synchronized void sendCommand(String command) throws IOException {
        if (command == null) return;
        if (!process.isAlive()) {
            throw new IOException("server process " + instanceId + " is not alive");
        }
        stdin.write(command);
        stdin.write('\n');
        stdin.flush();
        commandHistory.addLast(command);
        while (commandHistory.size() > HISTORY_LIMIT) {
            commandHistory.removeFirst();
        }
    }

    /** Snapshot of recently sent commands, oldest first. */
    public synchronized List<String> getCommandHistory() {
        return Collections.unmodifiableList(new ArrayList<>(commandHistory));
    }

    /**
     * Future that completes with the process exit code once the process
     * has finished and both pump threads have drained.
     */
    public CompletableFuture<Integer> onExit() {
        return process.onExit().thenApply(p -> {
            try {
                stdoutThread.join(2000);
                stderrThread.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return p.exitValue();
        });
    }

    /** Polite shutdown — sends {@code SIGTERM} on POSIX. */
    public void destroy() {
        process.destroy();
    }

    /** Hard kill — sends {@code SIGKILL} on POSIX. */
    public void destroyForcibly() {
        process.destroyForcibly();
    }

    private static Thread pump(InputStream stream, Consumer<String> consumer, String name) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    try {
                        consumer.accept(line);
                    } catch (RuntimeException e) {
                        Logger.LOG.warning("server log consumer threw", e);
                    }
                }
            } catch (IOException ignored) {
                // pipe broke when the process exited; treat as EOF.
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void sink(String line) {
        // dropped on purpose
    }
}
