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
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * "Keep running" wrapper that combines {@link ServerConsoleSession} with
 * {@link ServerAutoRestartPolicy} (W7.6).
 *
 * <p>One runner == one logical server lifetime. On each underlying
 * process exit the runner consults the policy and, if approved, sleeps
 * {@code restartDelaySec()}, emits {@link Restarted} and starts a brand
 * new {@link ServerConsoleSession}. The same {@code Consumer<ConsoleEvent>}
 * is reused across restarts so the UI sees a single seamless log.</p>
 *
 * <p>{@link #stop()} performs a graceful shutdown: writes {@code "stop"}
 * to the server's stdin (Mindustry's built-in shutdown command), waits
 * up to 3 s, then falls back to {@code destroyForcibly}. Stopping also
 * disables auto-restart for the remainder of this runner's life.</p>
 */
public final class ServerSessionRunner {

    /** Grace period for the polite {@code stop\n} command before SIGKILL. */
    private static final long GRACEFUL_STOP_MS = 3_000L;

    private final ServerInstance inst;
    private final ServerInstanceManager mgr;
    private final ServerAutoRestartPolicy policy;

    private final AtomicReference<ServerConsoleSession> current = new AtomicReference<>();
    private volatile Thread supervisor;
    private volatile boolean stopRequested = false;

    public ServerSessionRunner(ServerInstance inst,
                               ServerInstanceManager mgr,
                               ServerAutoRestartPolicy policy) {
        this.inst = Objects.requireNonNull(inst, "inst");
        this.mgr = Objects.requireNonNull(mgr, "mgr");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Begin the supervised lifecycle. Returns immediately; all events
     * (Started / Stdout / Stderr / Exited / Restarted) flow through
     * {@code ui} on whichever thread emits them.
     */
    public void start(Consumer<ConsoleEvent> ui) {
        Objects.requireNonNull(ui, "ui");
        if (supervisor != null) {
            throw new IllegalStateException("runner for " + inst.getId() + " already started");
        }
        Thread t = new Thread(() -> supervise(ui), "xenon-server-runner-" + inst.getId());
        t.setDaemon(true);
        supervisor = t;
        t.start();
    }

    /**
     * Request a graceful shutdown. Sends {@code "stop"} to the server,
     * waits up to 3 s, then forces termination. Suppresses any future
     * auto-restart even if the policy would otherwise allow one.
     */
    public void stop() {
        stopRequested = true;
        ServerConsoleSession session = current.get();
        if (session == null || !session.isRunning()) return;

        // Try the in-game shutdown first.
        boolean politeSent = false;
        try {
            session.sendCommand("stop");
            politeSent = true;
        } catch (RuntimeException ex) {
            Logger.LOG.warning("polite stop failed for " + inst.getId() + ": " + ex);
        }

        long deadline = System.currentTimeMillis() + GRACEFUL_STOP_MS;
        if (politeSent) {
            while (System.currentTimeMillis() < deadline && session.isRunning()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (session.isRunning()) {
            session.stop();
        }
    }

    /** Whether the supervised process is currently alive. */
    public boolean isRunning() {
        ServerConsoleSession s = current.get();
        return s != null && s.isRunning();
    }

    /** Most recent live session, or {@code null} if the runner has never started. */
    public ServerConsoleSession currentSession() {
        return current.get();
    }

    // ---------- supervisor loop ----------

    private void supervise(Consumer<ConsoleEvent> ui) {
        int attempt = 0;
        while (true) {
            ExitWatcher watcher = new ExitWatcher();
            ServerConsoleSession session = new ServerConsoleSession(inst, mgr);
            current.set(session);
            try {
                session.start(event -> {
                    if (event instanceof Exited e) {
                        watcher.complete(e.code());
                        // Don't re-emit Exited here yet; the supervisor decides
                        // whether the user should see Exited or Restarted.
                        return;
                    }
                    ui.accept(event);
                });
            } catch (RuntimeException ex) {
                Logger.LOG.warning("failed to start server " + inst.getId() + ": " + ex);
                ui.accept(new Exited(-1));
                return;
            }

            int code = watcher.await();

            if (stopRequested) {
                ui.accept(new Exited(code));
                return;
            }
            if (!policy.shouldRestart(code, attempt)) {
                ui.accept(new Exited(code));
                return;
            }

            long delay = policy.restartDelaySec();
            if (delay > 0) {
                try {
                    TimeUnit.SECONDS.sleep(delay);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    ui.accept(new Exited(code));
                    return;
                }
            }
            if (stopRequested) {
                ui.accept(new Exited(code));
                return;
            }
            attempt++;
            ui.accept(new Restarted(attempt));
            // loop: a fresh ConsoleSession will re-emit Started on its own
        }
    }

    /**
     * Tiny one-shot latch around the {@link Exited} event, since the
     * runner needs to block the supervisor thread until the underlying
     * process is fully done.
     */
    private static final class ExitWatcher {
        private final Object lock = new Object();
        private boolean done = false;
        private int code = -1;

        void complete(int c) {
            synchronized (lock) {
                if (done) return;
                done = true;
                code = c;
                lock.notifyAll();
            }
        }

        int await() {
            synchronized (lock) {
                while (!done) {
                    try {
                        lock.wait();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
                return code;
            }
        }
    }

    // Suppress unused import warning if javac considers IOException unused.
    @SuppressWarnings("unused")
    private static final Class<?> IO_REF = IOException.class;
}
