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

/// Global download-speed shaper for the Mindustry download chain.
///
/// The shaper is a leaky bucket expressed in the time domain: every accepted
/// request reserves a slot on a single monotone timeline, so every stream that
/// shares one instance also shares one byte budget. `acquire` blocks until the
/// slot it reserved has arrived, which makes the observed average throughput
/// converge to the configured rate regardless of how many streams — racing
/// mirrors, segmented ranges, resumable single streams — run at once.
///
/// The wait is sliced so it stays interruptible and so pause/cancel checks
/// inside the download loops keep firing: no single [#sleepMillis] call lasts
/// longer than [DownloadThrottle#MAX_SLICE_MILLIS], and an [AwaitGate] may
/// abandon the wait between slices.
///
/// A limit of [DownloadThrottle#UNLIMITED] takes a lock-free fast path that
/// neither sleeps nor allocates, so the default configuration behaves exactly
/// as it did before throttling existed.
///
/// The launcher module owns `GlobalConfig` and pushes the user's choice into
/// [DownloadThrottle#setGlobalLimitKbps] so this class stays free of launcher
/// and JavaFX dependencies.
@NotNullByDefault
public final class DownloadThrottle {

    /// Limit value that disables shaping completely.
    public static final int UNLIMITED = 0;

    /// Lowest selectable custom rate, in KiB/s.
    ///
    /// Below roughly 32 KiB/s a 64 KiB read chunk would take longer than two
    /// seconds to be admitted, which makes pause and cancel feel broken.
    public static final int MIN_LIMIT_KBPS = 32;

    /// Upper bound of a single [Sleeper#sleepMillis] call, in milliseconds.
    ///
    /// Keeps long waits interruptible and lets pause/cancel gates run often.
    static final long MAX_SLICE_MILLIS = 200L;

    /// Idle gap after which the reservation timeline is reset instead of carried.
    ///
    /// Carrying the schedule across a short gap is what keeps the average rate
    /// honest: `Thread.sleep` cannot sleep fractional milliseconds, so every
    /// wait rounds up and the surplus has to be absorbed by the next request.
    /// Only a genuinely idle stream resets, which bounds the burst capacity to
    /// this window; a stream the network itself slowed below the limit never
    /// pays a penalty for time it spent waiting on a socket.
    private static final long IDLE_RESET_NANOS = MAX_SLICE_MILLIS * 1_000_000L;

    /// Nanoseconds per millisecond.
    private static final long NANOS_PER_MILLI = 1_000_000L;

    /// Nanoseconds per second.
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /// Bytes per KiB, matching the unit used by [MIN_LIMIT_KBPS].
    private static final long BYTES_PER_KB = 1024L;

    /// Process-wide shaper instance shared by every Mindustry download stream.
    private static final DownloadThrottle GLOBAL = new DownloadThrottle(
            System::nanoTime, DownloadThrottle::sleepOnThread);

    /// Gate that abandons a wait when the current thread is interrupted.
    ///
    /// Used by the downloaders that have no pause/cancel object and rely on
    /// thread interruption to stop a transfer. Reads the flag without clearing
    /// it, so the downloader's own interruption check still fires.
    public static final AwaitGate INTERRUPT_GATE = () -> Thread.currentThread().isInterrupted();

    /// Rate currently configured on [GLOBAL], in KiB/s.
    private static volatile int globalLimitKbps = UNLIMITED;

    /// Polled while a request waits for its scheduled slot.
    @FunctionalInterface
    public interface AwaitGate {
        /// Returns whether the caller wants to abandon the wait.
        ///
        /// Called between wait slices, at most every
        /// [DownloadThrottle#MAX_SLICE_MILLIS] milliseconds.
        ///
        /// @return `true` to stop waiting early; the caller keeps ownership of
        ///         the bytes it asked for and decides what to do next
        boolean shouldAbortAwait();
    }

    /// Source of monotonic time, injectable so tests can drive a fake clock.
    @FunctionalInterface
    interface NanoClock {
        /// Returns the current monotonic timestamp in nanoseconds.
        ///
        /// @return nanoseconds from an arbitrary origin
        long nanoTime();
    }

    /// Blocking sleep primitive, injectable so tests never wait for real time.
    @FunctionalInterface
    interface Sleeper {
        /// Sleeps for the requested duration.
        ///
        /// @param millis duration in milliseconds, always positive and never
        ///               greater than [DownloadThrottle#MAX_SLICE_MILLIS]
        /// @throws InterruptedException when the thread is interrupted
        void sleepMillis(long millis) throws InterruptedException;
    }

    /// Monotonic clock used to schedule reservations.
    private final NanoClock clock;

    /// Sleep primitive used to reach a reservation.
    private final Sleeper sleeper;

    /// Current rate limit in KiB/s; [DownloadThrottle#UNLIMITED] disables shaping.
    private volatile int limitKbps;

    /// Earliest timestamp at which the next admitted byte may be written.
    ///
    /// Guarded by `this`; monotone while a limit is active.
    private long nextFreeNanos;

    /// Creates a shaper with an injected limit and wait primitives.
    ///
    /// @param limitKbps initial rate in KiB/s, or [DownloadThrottle#UNLIMITED]
    /// @param clock      monotonic clock
    /// @param sleeper    sleep primitive
    DownloadThrottle(int limitKbps, NanoClock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
        this.limitKbps = clamp(limitKbps);
    }

    /// Creates an unlimited shaper with production wait primitives.
    ///
    /// @param clock   monotonic clock
    /// @param sleeper sleep primitive
    DownloadThrottle(NanoClock clock, Sleeper sleeper) {
        this(UNLIMITED, clock, sleeper);
    }

    /// Admits `bytes` into the shared shaper for one download loop.
    ///
    /// Every outbound read loop in the Mindustry download chain calls this
    /// with the size of the chunk it is about to write, so racing mirrors,
    /// segmented ranges and resumable streams all draw from one byte budget.
    /// The fast path — the default, unlimited configuration — returns without
    /// locking, sleeping or allocating.
    ///
    /// @param bytes number of bytes the caller is about to write
    /// @param gate  optional gate polled between wait slices; a `true` result
    ///              abandons the wait and lets the caller re-check its own
    ///              pause/cancel state before continuing
    /// @throws IOException when the wait was interrupted
    static void admit(long bytes, @Nullable AwaitGate gate) throws IOException {
        DownloadThrottle shaper = GLOBAL;
        if (shaper.getLimitKbps() == UNLIMITED) return;
        try {
            shaper.acquire(bytes, gate);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the download limit", e);
        }
    }

    /// Returns the shared shaper used by every Mindustry download stream.
    ///
    /// @return the process-wide instance
    public static DownloadThrottle global() {
        return GLOBAL;
    }

    /// Returns the process-wide rate limit.
    ///
    /// @return rate in KiB/s, or [DownloadThrottle#UNLIMITED]
    public static int getGlobalLimitKbps() {
        return globalLimitKbps;
    }

    /// Updates the process-wide rate limit.
    ///
    /// The change is visible to every stream immediately: a shaper reads its
    /// limit on each [DownloadThrottle#acquire] call, so a running download
    /// reacts to a settings change without being recreated.
    ///
    /// @param kbps new rate in KiB/s; [DownloadThrottle#UNLIMITED] disables
    ///             shaping; smaller positive values are raised to
    ///             [DownloadThrottle#MIN_LIMIT_KBPS]
    public static void setGlobalLimitKbps(int kbps) {
        int value = clamp(kbps);
        globalLimitKbps = value;
        GLOBAL.setLimitKbps(value);
    }

    /// Returns this shaper's rate limit.
    ///
    /// @return rate in KiB/s, or [DownloadThrottle#UNLIMITED]
    public int getLimitKbps() {
        return limitKbps;
    }

    /// Updates this shaper's rate limit.
    ///
    /// @param kbps new rate in KiB/s; see [DownloadThrottle#setGlobalLimitKbps]
    public void setLimitKbps(int kbps) {
        int value = clamp(kbps);
        this.limitKbps = value;
        if (value == UNLIMITED) {
            synchronized (this) {
                // Dropping the limit clears the backlog so a later re-enable
                // does not inherit a stale schedule.
                nextFreeNanos = 0L;
            }
        }
    }

    /// Admits `bytes` into the shaped stream without an abort gate.
    ///
    /// @param bytes number of bytes about to be written
    /// @return always `true`
    /// @throws InterruptedException when the waiting thread is interrupted
    public boolean acquire(long bytes) throws InterruptedException {
        return acquire(bytes, null);
    }

    /// Admits `bytes` into the shaped stream, blocking until the schedule allows them.
    ///
    /// The caller must not write the bytes before this method returns `true`.
    /// When the gate aborts the wait the method returns `false` and the
    /// reservation is released, so the caller keeps whatever it read and stays
    /// responsible for re-checking its own pause/cancel state.
    ///
    /// @param bytes number of bytes the caller is about to write
    /// @param gate  optional callback polled between wait slices
    /// @return `true` when the bytes may be written, `false` when `gate` aborted
    /// @throws InterruptedException when the waiting thread is interrupted
    public boolean acquire(long bytes, @Nullable AwaitGate gate) throws InterruptedException {
        int kbps = limitKbps;
        if (kbps <= UNLIMITED || bytes <= 0L) {
            // Fast path: no lock, no wait, no allocation.
            return true;
        }

        long now = clock.nanoTime();
        long start;
        long deadline;
        synchronized (this) {
            // Re-read inside the lock so a concurrent limit change cannot
            // schedule work against a rate that is already gone.
            if (limitKbps <= UNLIMITED) return true;
            if (nextFreeNanos < now && now - nextFreeNanos > IDLE_RESET_NANOS) {
                // A genuinely idle stream starts from the present instead of
                // bursting the time it spent quiet. Sub-millisecond lag is not
                // idle time: it is rounding residue from the previous sleep and
                // must be carried so the average rate stays on target.
                nextFreeNanos = now;
            }
            start = nextFreeNanos;
            deadline = saturatingAdd(start, durationNanos(bytes, limitKbps));
            nextFreeNanos = deadline;
        }

        while (true) {
            long remaining = deadline - clock.nanoTime();
            if (remaining <= 0L) {
                return true;
            }
            if (gate != null && gate.shouldAbortAwait()) {
                releaseReservation(start, deadline);
                return false;
            }
            long slice = Math.min(millisCeil(remaining), MAX_SLICE_MILLIS);
            sleeper.sleepMillis(slice);
        }
    }

    /// Gives back a reservation that was abandoned before it was used.
    ///
    /// Only the tail reservation is released: once another stream has reserved
    /// a later slot the timeline must stay monotone.
    ///
    /// @param start    slot start timestamp that was reserved
    /// @param deadline slot end timestamp that was reserved
    private void releaseReservation(long start, long deadline) {
        synchronized (this) {
            if (nextFreeNanos == deadline) {
                nextFreeNanos = start;
            }
        }
    }

    /// Converts a byte count into the wire time it consumes at `kbps`.
    ///
    /// @param bytes bytes to schedule
    /// @param kbps  rate in KiB/s, already known to be positive
    /// @return nanoseconds the bytes occupy on the shaped timeline, at least one
    private static long durationNanos(long bytes, int kbps) {
        double nanos = bytes * (double) NANOS_PER_SECOND / ((double) kbps * BYTES_PER_KB);
        if (nanos < 1.0d) {
            // Sub-nanosecond reservations would let tiny requests slip through
            // for free; one nanosecond keeps the timeline monotone.
            return 1L;
        }
        if (nanos >= Long.MAX_VALUE) return Long.MAX_VALUE;
        long whole = (long) nanos;
        // Round up so a run of tiny requests can never drift faster than the rate.
        return nanos > whole ? whole + 1L : whole;
    }

    /// Adds two timestamps without wrapping around on overflow.
    ///
    /// @param base     starting timestamp
    /// @param duration non-negative duration
    /// @return the sum, saturating at [Long#MAX_VALUE]
    private static long saturatingAdd(long base, long duration) {
        long sum = base + duration;
        return sum < base ? Long.MAX_VALUE : sum;
    }

    /// Rounds a nanosecond duration up to whole milliseconds.
    ///
    /// @param nanos duration in nanoseconds, positive
    /// @return milliseconds to sleep, at least one
    private static long millisCeil(long nanos) {
        long millis = (nanos + NANOS_PER_MILLI - 1L) / NANOS_PER_MILLI;
        return Math.max(1L, millis);
    }

    /// Clamps a raw limit to the supported range.
    ///
    /// @param kbps raw value from settings
    /// @return [DownloadThrottle#UNLIMITED] or a rate of at least
    ///         [DownloadThrottle#MIN_LIMIT_KBPS]
    private static int clamp(int kbps) {
        if (kbps <= UNLIMITED) return UNLIMITED;
        return Math.max(kbps, MIN_LIMIT_KBPS);
    }

    /// Production sleep primitive that never exceeds the slice budget.
    ///
    /// @param millis duration in milliseconds
    /// @throws InterruptedException when the thread is interrupted
    private static void sleepOnThread(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
