/*
 * Xenon Launcher
 * Copyright (C) 2020-2026  Xenon contributors
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
package determination.xenon;

import java.awt.GraphicsEnvironment;

public final class JavaFXLauncher {

    private JavaFXLauncher() {
    }

    private static final boolean started = startToolkit();

    private static boolean startToolkit() {
        if (isHeadless()) {
            return false;
        }
        try {
            javafx.application.Platform.startup(() -> {
            });
            return true;
        } catch (IllegalStateException e) {
            return true;
        } catch (Throwable e) {
            e.printStackTrace();
            return false;
        }
    }

    public static void start() {
    }

    public static boolean isStarted() {
        return started;
    }

    private static boolean isHeadless() {
        return System.getenv("CI") != null || GraphicsEnvironment.isHeadless();
    }
}
