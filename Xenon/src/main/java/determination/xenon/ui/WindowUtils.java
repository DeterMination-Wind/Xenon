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

import javafx.application.Platform;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNullByDefault;

import static determination.xenon.util.logging.Logger.LOG;

/// Shared helpers for showing and focusing the launcher window.
///
/// Used by the tray icon and by single-instance negotiation, both of which
/// have to bring a hidden, minimized or background window back to the user.
@NotNullByDefault
public final class WindowUtils {

    /// Brings the given stage to the foreground as reliably as possible.
    ///
    /// Must be called on the JavaFX application thread. The stage is first
    /// shown, restored and focused through the JavaFX API; on Windows the
    /// native `SetForegroundWindow` path is tried next because JavaFX alone
    /// often only flashes the taskbar entry; when native activation is not
    /// available or was refused, the window is raised with a one-frame
    /// always-on-top flip. All failures are logged and never thrown because
    /// bringing a window forward must not break the caller.
    ///
    /// @param stage stage to show, restore and focus
    public static void bringToFront(Stage stage) {
        try {
            if (!stage.isShowing()) {
                stage.show();
            }
            if (stage.isIconified()) {
                stage.setIconified(false);
            }
            stage.toFront();
            stage.requestFocus();

            if (WindowsNativeUtils.forceForeground(stage)) {
                return;
            }

            // Fallback for non-Windows systems and refused native requests:
            // keep the window on top for one frame so the OS raises it.
            stage.setAlwaysOnTop(true);
            stage.toFront();
            stage.requestFocus();
            Platform.runLater(() -> {
                try {
                    stage.setAlwaysOnTop(false);
                } catch (Throwable e) {
                    LOG.warning("Failed to restore the always-on-top state", e);
                }
            });
        } catch (Throwable e) {
            LOG.warning("Failed to bring the launcher window to the front", e);
        }
    }

    private WindowUtils() {
    }
}
