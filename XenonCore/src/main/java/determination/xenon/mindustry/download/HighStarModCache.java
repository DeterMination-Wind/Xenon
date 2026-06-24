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
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts canonical GitHub release asset URLs into the 121 cache URL used
 * for popular Mindustry mods.
 *
 * <p>The cache is only intended for release assets on {@code github.com};
 * API endpoints, raw files, and non-GitHub URLs are left untouched so the
 * caller can fall back to the normal mirror-racing path.</p>
 */
@NotNullByDefault
public final class HighStarModCache {
    private static final String CACHE_PREFIX = "http://121.199.60.4/";
    private static final Pattern GITHUB_RELEASE_ASSET = Pattern.compile(
            "^https?://github\\.com/([^/]+)/([^/]+)/releases/download/([^/]+)/([^?#]+)$");

    private HighStarModCache() {}

    /// Returns whether a mod with the given star count should try the 121 cache before the normal downloader.
    public static boolean shouldTryCacheFirst(int stars, @Nullable String githubReleaseAssetUrl) {
        return stars > 5 && toCacheUrl(githubReleaseAssetUrl) != null;
    }

    /// Rewrites a canonical GitHub release asset URL into the public 121 cache URL, or returns `null` when unsupported.
    public static @Nullable String toCacheUrl(@Nullable String githubReleaseAssetUrl) {
        if (githubReleaseAssetUrl == null || githubReleaseAssetUrl.isBlank()) {
            return null;
        }
        Matcher matcher = GITHUB_RELEASE_ASSET.matcher(githubReleaseAssetUrl);
        if (!matcher.matches()) {
            return null;
        }
        return CACHE_PREFIX + "https://github.com/"
                + matcher.group(1) + "/"
                + matcher.group(2) + "/releases/download/"
                + matcher.group(3) + "/"
                + matcher.group(4);
    }
}
