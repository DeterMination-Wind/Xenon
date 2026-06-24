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
package determination.xenon.mindustry.download;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the 121 cache URL rewrite and eligibility threshold for popular mods.
@NotNullByDefault
public final class HighStarModCacheTest {
    /// Canonical GitHub release asset URLs are rewritten into the public 121 cache prefix.
    @Test
    public void rewritesGitHubReleaseAssetUrl() {
        assertEquals(
                "http://121.199.60.4/https://github.com/owner/repo/releases/download/v1/mod.jar",
                HighStarModCache.toCacheUrl(
                        "https://github.com/owner/repo/releases/download/v1/mod.jar"));
    }

    /// Non-release URLs are ignored so callers can keep the normal downloader path.
    @Test
    public void ignoresUnsupportedUrls() {
        assertNull(HighStarModCache.toCacheUrl("https://api.github.com/repos/owner/repo/releases/latest"));
        assertNull(HighStarModCache.toCacheUrl("https://example.com/mod.jar"));
    }

    /// Only mods strictly above the star threshold are eligible for the cache-first download path.
    @Test
    public void appliesStrictHighStarThreshold() {
        String url = "https://github.com/owner/repo/releases/download/v1/mod.jar";

        assertTrue(HighStarModCache.shouldTryCacheFirst(6, url));
        assertFalse(HighStarModCache.shouldTryCacheFirst(5, url));
        assertFalse(HighStarModCache.shouldTryCacheFirst(6,
                "https://api.github.com/repos/owner/repo/releases/latest"));
    }
}
