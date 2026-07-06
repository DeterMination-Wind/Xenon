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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests Mindustry mod-reload exit detection.
@NotNullByDefault
public final class MindustryReloadExitDetectorTest {

    /// The exact reload log line with a zero exit code requests relaunch.
    @Test
    public void exactReloadLogWithZeroExitCodeReturnsTrue() {
        assertTrue(MindustryReloadExitDetector.isReloadExit(0,
                List.of("[I] Exiting to reload mods")));
    }

    /// Dependency auto-import reload messages still include the same reload marker.
    @Test
    public void dependencyAutoImportReloadLineReturnsTrue() {
        assertTrue(MindustryReloadExitDetector.isReloadExit(0,
                List.of("[I] Imported dependency; Exiting to reload mods...")));
    }

    /// Manual exits and nonzero exits are not mod reload requests.
    @Test
    public void manualOrNonzeroExitReturnsFalse() {
        assertFalse(MindustryReloadExitDetector.isReloadExit(0,
                List.of("[I] Disposed application")));
        assertFalse(MindustryReloadExitDetector.isReloadExit(1,
                List.of("[I] Exiting to reload mods")));
    }
}
