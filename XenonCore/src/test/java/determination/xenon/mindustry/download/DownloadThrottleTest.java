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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the leaky-bucket download shaper.
///
/// Every test drives the shaper with a fake monotonic clock and a fake
/// sleeper, so the whole suite runs without waiting for real time while still
/// exercising the real reservation arithmetic.
@NotNullByDefault
public final class DownloadThrottleTest {

    /// One 64 KiB read chunk, matching the downloaders' buffer size.
    private static final int CHUNK = 64 * 1024;

    /// Nanoseconds per millisecond.
    private static final long NANOS_PER_MILLI = 1_000_000L;

    /// Fake monotonic clock advanced by a fake sleeper.
    ///
    /// A sleep advances the clock to `max(now, entry + duration)`, which models
    /// a wall clock shared by concurrent download threads instead of summing
    /// the overlapping waits.
    private static final class VirtualTime implements DownloadThrottle.NanoClock,
            DownloadThrottle.Sleeper {
        /// Current virtual timestamp in nanoseconds.
        private final AtomicLong nanos = new AtomicLong();

        /// Every slice requested so far, in milliseconds.
        private final List<Long> slices = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public long nanoTime() {
            return nanos.get();
        }

        @Override
        public void sleepMillis(long millis) {
            slices.add(millis);
            long entry = nanos.get();
            long target = entry + millis * NANOS_PER_MILLI;
            nanos.accumulateAndGet(target, Math::max);
        }

        /// Returns the virtual time elapsed so far.
        ///
        /// @return elapsed virtual milliseconds
        long elapsedMillis() {
            return nanos.get() / NANOS_PER_MILLI;
        }

        /// Returns a snapshot of the requested wait slices.
        ///
        /// @return copied slice list in milliseconds
        List<Long> sliceSnapshot() {
            synchronized (slices) {
                return List.copyOf(slices);
            }
        }

        /// Returns the largest requested wait slice.
        ///
        /// @return maximum slice in milliseconds, or 0 when nothing slept
        long maxSliceMillis() {
            long max = 0L;
            for (long slice : sliceSnapshot()) {
                max = Math.max(max, slice);
            }
            return max;
        }
    }

    /// An unlimited shaper never sleeps and never touches the clock.
    @Test
    public void unlimitedNeverSleeps() throws Exception {
        VirtualTime time = new VirtualTime();
        DownloadThrottle throttle = new DownloadThrottle(DownloadThrottle.UNLIMITED, time, time);

        for (int i = 0; i < 64; i++) {
            assertTrue(throttle.acquire(CHUNK));
        }

        assertTrue(time.sliceSnapshot().isEmpty(), "unlimited shaping must take the fast path");
        assertEquals(0L, time.elapsedMillis());
    }

    /// A throttled transfer converges on the configured average rate.
    @Test
    public void throttledTransferMatchesTargetAverage() throws Exception {
        VirtualTime time = new VirtualTime();
        DownloadThrottle throttle = new DownloadThrottle(1024, time, time);

        // 16 x 64 KiB = 1 MiB at 1 MiB/s must occupy exactly one second.
        for (int i = 0; i < 16; i++) {
            throttle.acquire(CHUNK);
        }

        assertEquals(1000L, time.elapsedMillis(),
                "1 MiB at 1024 KiB/s must take one virtual second");
    }

    /// The first admitted chunk is delayed by its own wire time, not admitted for free.
    @Test
    public void firstChunkIsShapedToo() throws Exception {
        VirtualTime time = new VirtualTime();
        DownloadThrottle throttle = new DownloadThrottle(1024, time, time);

        throttle.acquire(CHUNK);

        // 64 KiB at 1 MiB/s is 62.5 ms, rounded up to whole milliseconds.
        assertEquals(63L, time.elapsedMillis());
    }

    /// Two interleaved streams drawn from one shaper share a single budget.
    @Test
    public void interleavedStreamsShareOneBudget() throws Exception {
        VirtualTime time = new VirtualTime();
        DownloadThrottle throttle = new DownloadThrottle(1024, time, time);

        // Two 512 KiB streams round-robin: 1 MiB total, so one virtual second.
        for (int i = 0; i < 8; i++) {
            throttle.acquire(CHUNK);
            throttle.acquire(CHUNK);
        }

        assertEquals(1000L, time.elapsedMillis(),
                "two streams must not each get their own 1 MiB/s budget");
    }

    /// Concurrent threads reserving at the same time still share one budget.
    @Test
    public void concurrentStreamsShareOneBudget() throws Exception {
        VirtualTime time = new VirtualTime();
        DownloadThrottle throttle = new DownloadThrottle(1024, time, time);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger failures = new AtomicInteger();

        for (int stream = 0; stream < 2; stream++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < 8; i++) {
                        throttle.acquire(CHUNK);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }, "throttle-test-" + stream);
            thread.setDaemon(true);
            thread.start();
        }

        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "both streams must finish");

        assertEquals(0, failures.get());
        // Concurrent streams interleave their reservations; the aggregate
        // virtual time must stay at one second for 1 MiB at 1 MiB/s, and the
        // only slack comes from millisecond rounding of 8 + 8 chunks.
        long elapsed = time.elapsedMillis();
        assertTrue(elapsed >= 1000L && elapsed <= 1016L,
                "concurrent streams shared the budget, but virtual time was " + elapsed + " ms");
    }

    /// Changing the limit mid-stream takes effect on the very next chunk.
    @Test
    public void limitChangeAppliesToRunningStream() throws Exception {
        VirtualTime time = new VirtualTime();
        DownloadThrottle throttle = new DownloadThrottle(1024, time, time);

        for (int i = 0; i < 8; i++) {
            throttle.acquire(CHUNK);
        }
        long slowHalf = time.elapsedMillis();

        throttle.setLimitKbps(4096);
        for (int i = 0; i < 8; i++) {
            throttle.acquire(CHUNK);
        }
        long fastHalf = time.elapsedMillis() - slowHalf;

        assertEquals(500L, slowHalf, "eight chunks at 1 MiB/s take 500 ms");
        assertEquals(125L, fastHalf, "eight chunks at 4 MiB/s take 125 ms");
    }

    /// Dropping to unlimited clears any backlog immediately.
    @Test
    public void switchingToUnlimitedClearsTheBacklog() throws Exception {
        VirtualTime time = new VirtualTime();
        DownloadThrottle throttle = new DownloadThrottle(32, time, time);

        throttle.acquire(CHUNK);
        throttle.setLimitKbps(DownloadThrottle.UNLIMITED);

        long afterReset = time.elapsedMillis();
        for (int i = 0; i < 16; i++) {
            assertTrue(throttle.acquire(CHUNK));
        }

        assertEquals(afterReset, time.elapsedMillis(),
                "an unlimited shaper must not inherit the old schedule");
    }

    /// A wait never exceeds the slice budget so pause/cancel stay responsive.
    @Test
    public void waitSlicesNeverExceedTheBudget() throws Exception {
        VirtualTime time = new VirtualTime();
        DownloadThrottle throttle = new DownloadThrottle(DownloadThrottle.MIN_LIMIT_KBPS, time, time);

        throttle.acquire(CHUNK);

        // 64 KiB at 32 KiB/s is two seconds: ten slices of 200 ms.
        assertEquals(2000L, time.elapsedMillis());
        assertEquals(10, time.sliceSnapshot().size());
        assertEquals(DownloadThrottle.MAX_SLICE_MILLIS, time.maxSliceMillis());
    }

    /// An abort gate stops a long wait early and releases the reservation.
    @Test
    public void abortGateStopsWaitingAndReleasesTheReservation() throws Exception {
        VirtualTime time = new VirtualTime();
        DownloadThrottle throttle = new DownloadThrottle(DownloadThrottle.MIN_LIMIT_KBPS, time, time);
        AtomicInteger polls = new AtomicInteger();

        boolean admitted = throttle.acquire(CHUNK, () -> polls.incrementAndGet() >= 3);

        assertFalse(admitted, "the gate must be able to abandon the wait");
        assertEquals(3, polls.get(), "the gate is polled once per slice");
        assertTrue(time.elapsedMillis() <= 2 * DownloadThrottle.MAX_SLICE_MILLIS,
                "aborting must not wait for the whole reservation");

        // The released reservation must not push the next request further out
        // than a fresh 64 KiB chunk: two seconds from the new baseline.
        long before = time.elapsedMillis();
        throttle.acquire(CHUNK);
        assertEquals(2000L, time.elapsedMillis() - before,
                "an aborted reservation must not be charged to the next request");
    }

    /// A gate that never aborts leaves the normal schedule intact.
    @Test
    public void nonAbortingGateIsHarmless() throws Exception {
        VirtualTime time = new VirtualTime();
        DownloadThrottle throttle = new DownloadThrottle(1024, time, time);

        assertTrue(throttle.acquire(CHUNK, () -> false));
        assertTrue(throttle.acquire(CHUNK, () -> false));

        assertEquals(125L, time.elapsedMillis());
    }

    /// Limits below the floor are raised, and non-positive values disable shaping.
    @Test
    public void limitsAreClampedToTheSupportedRange() {
        VirtualTime time = new VirtualTime();

        DownloadThrottle tooSlow = new DownloadThrottle(1, time, time);
        assertEquals(DownloadThrottle.MIN_LIMIT_KBPS, tooSlow.getLimitKbps());

        DownloadThrottle unlimited = new DownloadThrottle(-5, time, time);
        assertEquals(DownloadThrottle.UNLIMITED, unlimited.getLimitKbps());

        tooSlow.setLimitKbps(-1);
        assertEquals(DownloadThrottle.UNLIMITED, tooSlow.getLimitKbps());
    }

    /// The process-wide limit round-trips and clamps the same way.
    @Test
    public void globalLimitRoundTrips() {
        try {
            DownloadThrottle.setGlobalLimitKbps(2048);
            assertEquals(2048, DownloadThrottle.getGlobalLimitKbps());
            assertEquals(2048, DownloadThrottle.global().getLimitKbps());

            DownloadThrottle.setGlobalLimitKbps(1);
            assertEquals(DownloadThrottle.MIN_LIMIT_KBPS, DownloadThrottle.getGlobalLimitKbps());

            assertEquals(DownloadThrottle.global(), DownloadThrottle.global());
        } finally {
            DownloadThrottle.setGlobalLimitKbps(DownloadThrottle.UNLIMITED);
        }
    }

    /// Zero-byte requests are admitted without touching the schedule.
    @Test
    public void zeroByteRequestsAreFree() throws Exception {
        VirtualTime time = new VirtualTime();
        DownloadThrottle throttle = new DownloadThrottle(1024, time, time);

        assertTrue(throttle.acquire(0L));

        assertTrue(time.sliceSnapshot().isEmpty());
    }

    /// A tiny request still reserves timeline, so bursts cannot leak through.
    @Test
    public void tinyRequestsStillReserveTimeline() throws Exception {
        VirtualTime time = new VirtualTime();
        DownloadThrottle throttle = new DownloadThrottle(DownloadThrottle.MIN_LIMIT_KBPS, time, time);

        // One byte at 32 KiB/s occupies about 30 microseconds of wire time.
        // Sleeps are whole milliseconds, so even this request waits a
        // millisecond rather than passing for free.
        assertTrue(throttle.acquire(1L));
        assertEquals(1L, time.elapsedMillis());

        // The sub-millisecond reservations must keep accumulating: 4000 bytes
        // at 32 KiB/s is 122 ms of wire time, and the whole-millisecond sleeps
        // must land on the 123 ms boundary rather than resetting every call.
        for (int i = 0; i < 3999; i++) {
            throttle.acquire(1L);
        }
        long elapsed = time.elapsedMillis();
        assertTrue(elapsed >= 122L && elapsed <= 124L,
                "sub-millisecond reservations must accumulate instead of vanishing, but "
                        + elapsed + " ms elapsed");
    }
}
