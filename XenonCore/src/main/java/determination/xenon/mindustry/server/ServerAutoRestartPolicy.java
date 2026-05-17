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

import java.util.Objects;

/**
 * Auto-restart policy for one Mindustry dedicated-server instance (W7.6).
 *
 * <p>Reads the user-tunable knobs ({@code autoRestart},
 * {@code autoRestartMaxRetries}, {@code autoRestartDelaySec}) off the
 * underlying {@link ServerInstance}. Pure decision logic — no threads,
 * no I/O. {@link ServerSessionRunner} drives the actual sleep + relaunch.</p>
 *
 * <h3>Decision rules</h3>
 * <ul>
 *     <li>{@code autoRestart} disabled → never restart.</li>
 *     <li>Exit code {@code 0} (clean shutdown) → never restart.</li>
 *     <li>Otherwise restart only while {@code attemptSoFar < maxRetries()}.</li>
 *     <li>{@code maxRetries() <= 0} disables the loop even with auto-restart on.</li>
 *     <li>{@code restartDelaySec()} is clamped to {@code >= 0}.</li>
 * </ul>
 */
public final class ServerAutoRestartPolicy {

    private final ServerInstance inst;

    public ServerAutoRestartPolicy(ServerInstance inst) {
        this.inst = Objects.requireNonNull(inst, "inst");
    }

    /**
     * Decide whether the runner should attempt another launch after the
     * server exited with {@code exitCode}, given that {@code attemptSoFar}
     * relaunches have already happened in the current run.
     *
     * @param exitCode      OS exit code of the just-finished process
     * @param attemptSoFar  number of automatic restarts already performed
     *                      (0 on the first crash after the manual start)
     */
    public boolean shouldRestart(int exitCode, int attemptSoFar) {
        if (!inst.isAutoRestart()) return false;
        if (exitCode == 0) return false;
        if (attemptSoFar < 0) return false;
        return attemptSoFar < maxRetries();
    }

    /** Delay between restarts, in seconds, never negative. */
    public long restartDelaySec() {
        return Math.max(0, inst.getAutoRestartDelaySec());
    }

    /** Hard cap on consecutive automatic restarts, never negative. */
    public int maxRetries() {
        return Math.max(0, inst.getAutoRestartMaxRetries());
    }
}
