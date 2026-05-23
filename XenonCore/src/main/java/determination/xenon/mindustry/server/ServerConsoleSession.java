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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * High-level console session wrapped around one {@link ServerProcess}.
 *
 * <p>Adds three things on top of {@link ServerProcess}:</p>
 * <ul>
 *     <li>typed {@link ConsoleEvent} stream (Started/Stdout/Stderr/Exited)</li>
 *     <li>parsing of Mindustry's {@code [I]/[W]/[E]} stdout prefixes</li>
 *     <li>a UI-friendly snapshot of the last 200 console commands</li>
 * </ul>
 *
 * <p>Each session corresponds to exactly one process spawn. Use
 * {@link ServerSessionRunner} to get a "keep running" wrapper that
 * re-creates fresh sessions on crash.</p>
 */
public final class ServerConsoleSession {

    /** Maximum number of commands kept in {@link #commandHistory()}. */
    private static final int HISTORY_LIMIT = 200;

    /** Mindustry server log prefix: {@code [I] message} / {@code [W] ...} / {@code [E] ...}. */
    private static final Pattern LEVEL = Pattern.compile("^\\[([IWE])\\].*");

    /** Log level constants used inside {@link StdoutLine#level()}. */
    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_WARN = "WARN";
    public static final String LEVEL_ERR = "ERR";

    // ---------- ConsoleEvent algebra ----------

    /** Sealed event type emitted to the UI consumer passed to {@link #start}. */
    public sealed interface ConsoleEvent
            permits Started, StdoutLine, StderrLine, Exited, Restarted {}

    /** Emitted once after the underlying process has been launched. */
    public static record Started() implements ConsoleEvent {}

    /** A single parsed stdout line. {@code level} is one of {@link #LEVEL_INFO}/{@link #LEVEL_WARN}/{@link #LEVEL_ERR}. */
    public static record StdoutLine(String level, String text) implements ConsoleEvent {}

    /** A single stderr line. Level is implicit (always treated as error-grade). */
    public static record StderrLine(String text) implements ConsoleEvent {}

    /** Emitted once after the process has exited and pump threads drained. */
    public static record Exited(int code) implements ConsoleEvent {}

    /**
     * Emitted by {@link ServerSessionRunner} (not by {@link ServerConsoleSession}
     * directly) when an automatic restart kicks in. Lives here so all
     * console-stream events share one sealed type.
     */
    public static record Restarted(int attempt) implements ConsoleEvent {}

    // ---------- instance state ----------

    private final ServerInstance inst;
    private final ServerInstanceManager mgr;
    private final Deque<String> commandHistory = new ArrayDeque<>();

    private volatile ServerProcess proc;
    private volatile Consumer<ConsoleEvent> ui;

    public ServerConsoleSession(ServerInstance inst, ServerInstanceManager mgr) {
        this.inst = Objects.requireNonNull(inst, "inst");
        this.mgr = Objects.requireNonNull(mgr, "mgr");
    }

    // ---------- lifecycle ----------

    /**
     * Spawn the underlying server process and start streaming events to
     * {@code ui}. Must be called at most once per session.
     */
    public void start(Consumer<ConsoleEvent> ui) {
        Objects.requireNonNull(ui, "ui");
        if (proc != null) {
            throw new IllegalStateException("session for " + inst.getId() + " already started");
        }
        this.ui = ui;
        try {
            proc = mgr.start(inst.getId(), this::onStdout, this::onStderr);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        emit(new Started());
        proc.onExit().whenComplete((code, err) -> {
            int c = code != null ? code : -1;
            if (err != null) {
                Logger.LOG.warning("server " + inst.getId() + " exit observation failed: " + err);
            }
            emit(new Exited(c));
        });
    }

    /**
     * Send one console command to the running server. The trailing
     * newline is added automatically. The command is also appended to
     * {@link #commandHistory()} (capped at 200 entries).
     */
    public void sendCommand(String cmd) {
        if (cmd == null) return;
        ServerProcess p = proc;
        if (p == null || !p.isAlive()) {
            throw new UncheckedIOException(new IOException(
                    "server " + inst.getId() + " is not running"));
        }
        try {
            p.sendCommand(cmd);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        synchronized (commandHistory) {
            commandHistory.addLast(cmd);
            while (commandHistory.size() > HISTORY_LIMIT) {
                commandHistory.removeFirst();
            }
        }
    }

    /**
     * Stop the server process. Tries a polite {@code SIGTERM} first; if
     * the process has not died within 5 s the session falls back to
     * {@code SIGKILL}. For the in-game {@code "stop"} command flow plus
     * 3-second grace period see {@link ServerSessionRunner#stop()}.
     */
    public void stop() {
        ServerProcess p = proc;
        if (p == null) return;
        p.destroy();
        try {
            if (!p.getProcess().waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    /** Whether this session owns a still-running process. */
    public boolean isRunning() {
        ServerProcess p = proc;
        return p != null && p.isAlive();
    }

    /** Snapshot of recently sent commands, oldest first. */
    public List<String> commandHistory() {
        synchronized (commandHistory) {
            return Collections.unmodifiableList(new ArrayList<>(commandHistory));
        }
    }

    /** The Mindustry server instance this session targets. */
    public ServerInstance getInstance() {
        return inst;
    }

    // ---------- internals ----------

    private void onStdout(String line) {
        if (line == null) return;
        emit(new StdoutLine(parseLevel(line), line));
    }

    private void onStderr(String line) {
        if (line == null) return;
        emit(new StderrLine(line));
    }

    private void emit(ConsoleEvent event) {
        Consumer<ConsoleEvent> sink = ui;
        if (sink == null) return;
        try {
            sink.accept(event);
        } catch (RuntimeException ex) {
            Logger.LOG.warning("console UI consumer threw on " + event + ": " + ex);
        }
    }

    private static String parseLevel(String line) {
        Matcher m = LEVEL.matcher(line);
        if (!m.matches()) return LEVEL_INFO;
        switch (m.group(1)) {
            case "I": return LEVEL_INFO;
            case "W": return LEVEL_WARN;
            case "E": return LEVEL_ERR;
            default:  return LEVEL_INFO;
        }
    }
}
