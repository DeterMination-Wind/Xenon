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
package determination.xenon.mindustry;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import determination.xenon.util.platform.NativeUtils;
import determination.xenon.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

/// Detects whether a launched Mindustry client process still owns a
/// user-visible top-level game window.
///
/// The probe is currently Windows-only because that is where users most
/// commonly observe `java.exe` lingering in Task Manager after closing
/// the game window. On unsupported platforms it reports that probing is
/// unavailable instead of guessing from process state alone.
@NotNullByDefault
public final class MindustryClientWindowProbe {

    /// Minimal `user32` binding used by this probe.
    private static final @Nullable User32 USER32 = NativeUtils.load("user32", User32.class);

    /// Returns true when this runtime can inspect top-level windows.
    public static boolean isSupported() {
        return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                && NativeUtils.USE_JNA
                && USER32 != null;
    }

    /// Returns true when `pid` owns at least one visible top-level window.
    ///
    /// Unsupported platforms return false. Callers should check
    /// `isSupported()` before using a false result to make lifecycle
    /// decisions.
    public static boolean hasVisibleWindow(long pid) {
        User32 user32 = USER32;
        if (!isSupported() || user32 == null || pid <= 0) {
            return false;
        }

        AtomicBoolean found = new AtomicBoolean(false);
        user32.EnumWindows((window, ignored) -> {
            IntByReference processId = new IntByReference();
            user32.GetWindowThreadProcessId(window, processId);
            long ownerPid = Integer.toUnsignedLong(processId.getValue());
            if (ownerPid == pid && user32.IsWindowVisible(window)) {
                found.set(true);
                return false;
            }
            return true;
        }, Pointer.NULL);
        return found.get();
    }

    /// `user32.dll` methods required for window enumeration.
    private interface User32 extends StdCallLibrary {

        /// Enumerates all top-level windows in z-order.
        boolean EnumWindows(EnumWindowsProc callback, Pointer data);

        /// Reads the process id that owns `window`.
        int GetWindowThreadProcessId(Pointer window, IntByReference processId);

        /// Returns whether `window` has the visible style.
        boolean IsWindowVisible(Pointer window);
    }

    /// Callback used by `EnumWindows`.
    private interface EnumWindowsProc extends StdCallLibrary.StdCallCallback {

        /// Handles one top-level window; false stops enumeration.
        boolean callback(Pointer window, Pointer data);
    }

    /// Utility class.
    private MindustryClientWindowProbe() {
    }
}
