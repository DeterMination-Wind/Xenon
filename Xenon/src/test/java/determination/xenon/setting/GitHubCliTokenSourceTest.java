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
package determination.xenon.setting;

import determination.xenon.mindustry.download.GitHubAuth;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests which credential {@link GitHubCliTokenSource} installs into XenonCore.
@NotNullByDefault
public final class GitHubCliTokenSourceTest {

    /// Clears the shared token so tests cannot leak state into each other.
    @AfterEach
    public void resetGitHubAuth() {
        GitHubAuth.setToken("");
    }

    /// A manual token always wins, even when the CLI fallback is enabled.
    @Test
    public void manualTokenTakesPriority() {
        GitHubCliTokenSource.apply("  ghp_manual  ", true);

        assertEquals("ghp_manual", GitHubAuth.token());
        assertEquals(GitHubCliTokenSource.Source.MANUAL, GitHubCliTokenSource.resolved().source());
    }

    /// Clearing the manual token with the CLI fallback disabled returns to anonymous access.
    @Test
    public void disabledCliFallsBackToAnonymous() {
        GitHubCliTokenSource.apply("ghp_manual", true);
        GitHubCliTokenSource.apply("", false);

        assertFalse(GitHubAuth.hasToken());
        assertEquals("", GitHubAuth.token());
        assertEquals(GitHubCliTokenSource.Source.ANONYMOUS, GitHubCliTokenSource.resolved().source());
    }

    /// The CLI toggle defaults to on and survives a config round-trip.
    @Test
    public void ghCliAuthRoundTripsThroughConfig() {
        Config config = new Config();
        assertTrue(config.isGhCliAuth());

        config.setGhCliAuth(false);
        String json = config.toJson();
        Config restored = Config.fromJson(json);

        assertNotNull(restored);
        assertFalse(restored.isGhCliAuth());
        assertTrue(json.contains("ghCliAuth"), "the toggle must be serialized");
        assertFalse(json.contains("gho_"), "config.json must never contain a gh CLI token");
    }
}
