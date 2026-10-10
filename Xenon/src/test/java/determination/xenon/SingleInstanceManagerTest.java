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
package determination.xenon;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/// Tests the option plumbing of the single-instance manager.
///
/// Only the paths that do not open a control server or touch the instance
/// directory are exercised here; negotiation itself is covered by the
/// XenonCore tests.
@NotNullByDefault
public final class SingleInstanceManagerTest {

    /// Removes the mode override after every test.
    @AfterEach
    public void clearMode() {
        System.clearProperty(SingleInstanceManager.MODE_PROPERTY);
    }

    /// Disabled mode leaves arguments untouched.
    @Test
    public void offModeKeepsArguments() {
        System.setProperty(SingleInstanceManager.MODE_PROPERTY, "off");

        assertArrayEquals(new String[]{"--foo", "bar"},
                SingleInstanceManager.startup(new String[]{"--foo", "bar"}));
    }

    /// The new-instance flag is removed even when detection is disabled.
    @Test
    public void newInstanceFlagIsStripped() {
        System.setProperty(SingleInstanceManager.MODE_PROPERTY, "off");

        assertArrayEquals(new String[]{"--foo"},
                SingleInstanceManager.startup(new String[]{"--new-instance", "--foo"}));
    }

    /// Internal handovers skip detection without touching arguments.
    @Test
    public void skipModeKeepsArguments() {
        System.setProperty(SingleInstanceManager.MODE_PROPERTY, "skip");

        assertArrayEquals(new String[]{"--flag"},
                SingleInstanceManager.startup(new String[]{"--flag"}));
    }

    /// Update application is exempt even when detection is enabled.
    @Test
    public void applyToIsExempt() {
        System.setProperty(SingleInstanceManager.MODE_PROPERTY, "on");

        assertArrayEquals(new String[]{"--apply-to", "target.jar"},
                SingleInstanceManager.startup(new String[]{"--apply-to", "target.jar"}));
    }
}
