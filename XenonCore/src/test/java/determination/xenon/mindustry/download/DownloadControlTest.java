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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the pause / resume / cancel state object.
@NotNullByDefault
public final class DownloadControlTest {

    /// `awaitResume` parks while paused and wakes up on resume.
    @Test
    public void awaitResumeBlocksUntilResume() throws Exception {
        DownloadControl control = new DownloadControl();
        control.pause();
        CountDownLatch returned = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                control.awaitResume();
                returned.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();

        assertFalse(returned.await(200, TimeUnit.MILLISECONDS));
        control.resume();
        assertTrue(returned.await(2, TimeUnit.SECONDS));
    }

    /// Cancel wakes a parked stream immediately.
    @Test
    public void cancelWakesPausedStream() throws Exception {
        DownloadControl control = new DownloadControl();
        control.pause();
        AtomicBoolean returned = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            try {
                control.awaitResume();
                returned.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        worker.start();

        control.cancel();
        worker.join(2000);
        assertTrue(returned.get());
        assertTrue(control.isCancelled());
        assertFalse(control.isPaused());
    }

    /// A fresh control starts running.
    @Test
    public void freshControlIsRunning() {
        DownloadControl control = new DownloadControl();
        assertFalse(control.isPaused());
        assertFalse(control.isCancelled());
    }
}
