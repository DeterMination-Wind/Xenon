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

/// Pause / resume / cancel state shared between a download UI and the
/// streaming code.
///
/// The download thread polls this object at chunk boundaries. Pausing parks
/// the thread while the partial file stays on disk, so resuming continues
/// from the exact byte offset; cancelling wakes the thread and makes the
/// downloader delete its partial files.
@NotNullByDefault
public final class DownloadControl {

    /// Whether the stream should park until [#resume()] is called.
    private volatile boolean paused;

    /// Whether the stream should abort and clean up.
    private volatile boolean cancelled;

    /// Requests a pause; the stream parks at the next chunk boundary.
    public void pause() {
        paused = true;
    }

    /// Resumes a paused stream.
    public void resume() {
        synchronized (this) {
            paused = false;
            notifyAll();
        }
    }

    /// Whether a pause was requested.
    public boolean isPaused() {
        return paused;
    }

    /// Requests cancellation; a paused stream wakes up immediately.
    public void cancel() {
        synchronized (this) {
            cancelled = true;
            paused = false;
            notifyAll();
        }
    }

    /// Whether cancellation was requested.
    public boolean isCancelled() {
        return cancelled;
    }

    /// Blocks while the stream is paused.
    ///
    /// Returns immediately when running or cancelled.
    ///
    /// @throws InterruptedException when the waiting thread is interrupted
    public void awaitResume() throws InterruptedException {
        synchronized (this) {
            while (paused && !cancelled) {
                wait();
            }
        }
    }
}
