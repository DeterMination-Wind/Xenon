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
package determination.xenon.ui;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the narrow filter that silences the JDK notification-area failure.
@NotNullByDefault
public final class TrayIconManagerTest {

    /// Message `SystemTray.getSystemTray()` uses when the tray disappeared.
    private static final String TRAY_UNSUPPORTED_MESSAGE =
            "The system tray is not supported on the current platform.";

    /// The JDK tray failure raised from `sun.awt` is recognised.
    @Test
    public void recognisesJdkTrayFailure() {
        UnsupportedOperationException failure = new UnsupportedOperationException(
                TRAY_UNSUPPORTED_MESSAGE);
        withFrame(failure, "sun.awt.X11.XTrayIconPeer$4$1", "run");

        assertTrue(TrayIconManager.isJdkSystemTrayFailure(failure));
    }

    /// The same message raised by launcher code is not swallowed.
    @Test
    public void ignoresLauncherOriginFailure() {
        UnsupportedOperationException failure = new UnsupportedOperationException(
                TRAY_UNSUPPORTED_MESSAGE);
        withFrame(failure, "determination.xenon.ui.TrayIconManager", "uninstall");

        assertFalse(TrayIconManager.isJdkSystemTrayFailure(failure));
    }

    /// An `UnsupportedOperationException` with another message keeps its path.
    @Test
    public void ignoresOtherMessages() {
        UnsupportedOperationException failure = new UnsupportedOperationException("boom");
        withFrame(failure, "sun.awt.X11.XTrayIconPeer", "run");

        assertFalse(TrayIconManager.isJdkSystemTrayFailure(failure));
    }

    /// A different exception type from the same origin keeps its path.
    @Test
    public void ignoresOtherFailureTypes() {
        NullPointerException failure = new NullPointerException(TRAY_UNSUPPORTED_MESSAGE);
        withFrame(failure, "sun.awt.X11.XTrayIconPeer", "run");

        assertFalse(TrayIconManager.isJdkSystemTrayFailure(failure));
    }

    /// Replaces the stack trace with one synthetic frame.
    ///
    /// @param error       failure to rewrite
    /// @param className   declaring class of the synthetic frame
    /// @param methodName  method of the synthetic frame
    private static void withFrame(Throwable error, String className, String methodName) {
        error.setStackTrace(new StackTraceElement[]{
                new StackTraceElement(className, methodName, className + ".java", 42)
        });
    }
}
