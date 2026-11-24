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
package determination.xenon.mindustry.download;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/// Watchdog that aborts a transfer which stops making progress.
///
/// Both download paths (`ResumableDownloader` and the direct/racing path in
/// [MirrorDownloader]) read from a blocking `InputStream`. A mirror that sends
/// a few bytes and then hangs would otherwise park the task until the OS
/// abandons the socket, which can take minutes on a mobile hotspot, and a
/// server that trickles at a few hundred bytes per second would look "slow"
/// instead of broken.
///
/// The watchdog samples the transferred byte count twice a second. When a full
/// `window` passes with fewer than `minBytes` delivered, it reports the stall
/// through the `onStall` callback and closes the body, which turns the blocked
/// `read` into a retryable [IOException]. Suspended (paused) transfers are
/// exempt and the window restarts when they resume.
@NotNullByDefault
final class TransferWatchdog implements AutoCloseable {

    /// How often the watchdog samples the byte counter.
    private static final long SAMPLE_INTERVAL_MS = 500L;

    private final InputStream body;
    private final LongSupplier transferred;
    private final @Nullable BooleanSupplier suspended;
    private final Duration window;
    private final long minBytes;
    private final Runnable onStall;
    private final AtomicBoolean stalled = new AtomicBoolean();
    private final Thread thread;

    /// Starts one watchdog for a running transfer.
    ///
    /// @param body        response body; closed when the transfer stalls
    /// @param transferred running byte counter owned by the reader
    /// @param suspended    returns `true` while the user has the transfer paused, or `null` when never paused
    /// @param window      length of one throughput window
    /// @param minBytes    bytes that must arrive inside one window
    /// @param onStall     called once, before the body is closed
    TransferWatchdog(InputStream body, LongSupplier transferred, @Nullable BooleanSupplier suspended,
                     Duration window, long minBytes, Runnable onStall) {
        this.body = Objects.requireNonNull(body, "body");
        this.transferred = Objects.requireNonNull(transferred, "transferred");
        this.suspended = suspended;
        this.window = Objects.requireNonNull(window, "window");
        this.minBytes = Math.max(1L, minBytes);
        this.onStall = Objects.requireNonNull(onStall, "onStall");
        this.thread = new Thread(this::run, "xenon-download-watchdog");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /// Whether the watchdog already aborted this transfer.
    ///
    /// @return `true` once a stall was reported
    boolean stalled() {
        return stalled.get();
    }

    /// Stops the watchdog; safe to call before the body is closed.
    @Override
    public void close() {
        thread.interrupt();
    }

    /// Watchdog loop.
    private void run() {
        long windowStart = System.nanoTime();
        long windowBytes = transferred.getAsLong();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(SAMPLE_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            long current = transferred.getAsLong();
            if (suspended != null && suspended.getAsBoolean()) {
                windowStart = now;
                windowBytes = current;
                continue;
            }
            if (now - windowStart < window.toNanos()) {
                continue;
            }
            long delta = current - windowBytes;
            if (delta < minBytes) {
                stalled.set(true);
                onStall.run();
                try {
                    body.close();
                } catch (IOException ignored) {
                    // The read failure the caller sees is the actionable one.
                }
                return;
            }
            windowStart = now;
            windowBytes = current;
        }
    }
}
