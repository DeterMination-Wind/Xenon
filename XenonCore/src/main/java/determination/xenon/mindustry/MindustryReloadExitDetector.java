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

import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;

/// Detects the intentional client exit Mindustry performs after mod changes.
@NotNullByDefault
public final class MindustryReloadExitDetector {
    /// Log fragment printed by Mindustry before exiting to reload mods.
    public static final String RELOAD_LOG_FRAGMENT = "Exiting to reload mods";

    private MindustryReloadExitDetector() {
    }

    /// Returns true only for a clean process exit whose recent logs contain Mindustry's reload marker.
    public static boolean isReloadExit(int exitCode, List<String> recentLines) {
        if (exitCode != 0) {
            return false;
        }
        for (String line : recentLines) {
            if (line.contains(RELOAD_LOG_FRAGMENT)) {
                return true;
            }
        }
        return false;
    }
}
