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
package determination.xenon.task;

import org.jetbrains.annotations.NotNullByDefault;

/// A running task that knows how many bytes it has moved.
///
/// [Task#progressProperty()] only carries a ratio, which is enough for a
/// progress bar but not for a time estimate. The task dialog reads this
/// interface to show "speed · remaining time" for the active download; tasks
/// that do not implement it simply keep the ratio-only display.
@NotNullByDefault
public interface ByteProgressTask {

    /// Bytes transferred so far.
    ///
    /// @return byte count, never negative
    long transferredBytes();

    /// Total bytes expected, as far as the task knows.
    ///
    /// @return total byte count, or `0` when the size is unknown
    long totalBytes();
}
