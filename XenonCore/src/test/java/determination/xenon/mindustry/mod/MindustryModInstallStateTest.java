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
package determination.xenon.mindustry.mod;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the four install states of a remote mod row.
@NotNullByDefault
public final class MindustryModInstallStateTest {

    /// No matching archive means the mod can be installed.
    @Test
    public void absentArchiveIsNotInstalled() {
        MindustryModInstallState state = MindustryModInstallState.resolve("v2.0", List.of());

        assertEquals(MindustryModInstallState.State.NOT_INSTALLED, state.state());
        assertFalse(state.isInstalled());
    }

    /// The same version means installed.
    @Test
    public void sameVersionIsInstalled() {
        MindustryModInstallState state = MindustryModInstallState.resolve(
                "v1.2", List.of(mod("m.zip", "1.2", true)));

        assertEquals(MindustryModInstallState.State.INSTALLED, state.state());
        assertEquals("1.2", state.installedVersion());
        assertFalse(state.isUpdateAvailable());
    }

    /// A newer remote tag means an update is available.
    @Test
    public void newerRemoteIsUpdateAvailable() {
        MindustryModInstallState state = MindustryModInstallState.resolve(
                "v1.3", List.of(mod("m.zip", "1.2", true)));

        assertEquals(MindustryModInstallState.State.UPDATE_AVAILABLE, state.state());
        assertEquals("1.2", state.installedVersion());
        assertTrue(state.isUpdateAvailable());
    }

    /// A newer local build is not downgraded.
    @Test
    public void newerLocalIsLocalNewer() {
        MindustryModInstallState state = MindustryModInstallState.resolve(
                "v1.3", List.of(mod("m.zip", "1.4", true)));

        assertEquals(MindustryModInstallState.State.LOCAL_NEWER, state.state());
        assertEquals("1.4", state.installedVersion());
        assertFalse(state.isUpdateAvailable());
    }

    /// Without a remote tag the mod is simply installed.
    @Test
    public void missingRemoteTagIsInstalled() {
        MindustryModInstallState state = MindustryModInstallState.resolve(
                null, List.of(mod("m.zip", "1.4", true)));

        assertEquals(MindustryModInstallState.State.INSTALLED, state.state());
        assertEquals("1.4", state.installedVersion());
    }

    /// The highest local version wins even when a lower copy is enabled.
    @Test
    public void picksHighestLocalVersion() {
        MindustryModInstallState state = MindustryModInstallState.resolve(
                "v1.3", List.of(
                        mod("old.zip", "1.0", true),
                        mod("new.zip", "1.2", false)));

        assertEquals(MindustryModInstallState.State.UPDATE_AVAILABLE, state.state());
        assertEquals("1.2", state.installedVersion());
    }

    /// Broken archives do not make a mod look installed.
    @Test
    public void ignoresUnparsableArchives() {
        MindustryLocalMod broken = MindustryLocalMod.unparsable(Path.of("broken.zip"), "bad descriptor");
        MindustryModInstallState state = MindustryModInstallState.resolve("v1.0", List.of(broken));

        assertEquals(MindustryModInstallState.State.NOT_INSTALLED, state.state());
    }

    /// Builds one local mod archive descriptor.
    private static MindustryLocalMod mod(String fileName, String version, boolean enabled) {
        Path path = Path.of(enabled ? fileName : fileName + ".disabled");
        return new MindustryLocalMod(path, "Test Mod", "Test Mod", "Xenon",
                version, "", "", 0, false, List.of());
    }
}
