/*
 * Xenon Launcher
 * Copyright (C) 2025-2026  Xenon contributors
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
package determination.xenon.ui.image;

/// Animated JavaFX image whose playback can be suspended.
///
/// Launcher backgrounds use this to stop the animation timeline while the
/// window is minimized or hidden, instead of letting it repaint frames that
/// nobody can see.
///
/// @author Glavo
public interface AnimationImage {
    /// Pauses the animation.
    ///
    /// Implementations must tolerate being paused before the first frame and
    /// being paused more than once.
    default void pause() {
    }

    /// Resumes the animation after [#pause()].
    default void resume() {
    }
}
