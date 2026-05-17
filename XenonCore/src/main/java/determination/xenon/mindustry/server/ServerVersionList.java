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
package determination.xenon.mindustry.server;

import determination.xenon.mindustry.VersionVariant;
import determination.xenon.mindustry.download.GitHubAsset;
import determination.xenon.mindustry.download.GitHubRelease;
import determination.xenon.mindustry.download.GitHubReleaseClient;
import determination.xenon.mindustry.download.MindustryRemoteVersion;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider that pulls a list of installable Mindustry <em>dedicated-server</em>
 * jars from one upstream {@link VersionVariant}.
 *
 * <p>Mirrors {@code MindustryVersionList} on the client side but resolves
 * server-side {@code *-server*.jar} assets instead of the desktop jar.
 * Each concrete subclass encodes one upstream's asset-naming rules and
 * regex fallback chain.</p>
 *
 * <p>Subclasses should be safe to call from background threads; results
 * are not cached at this layer (callers may cache or rely on
 * {@link GitHubReleaseClient}'s ETag cache).</p>
 */
public abstract class ServerVersionList {

    /** Default page size pulled from the GitHub releases endpoint. */
    protected static final int DEFAULT_LIMIT = 50;

    /** Captures the first integer in a release tag (e.g. {@code v146.4} → 146). */
    private static final Pattern FIRST_INT = Pattern.compile("(\\d+)");

    protected final VersionVariant variant;
    protected final GitHubReleaseClient client;

    protected ServerVersionList(VersionVariant variant, GitHubReleaseClient client) {
        this.variant = Objects.requireNonNull(variant, "variant");
        this.client = Objects.requireNonNull(client, "client");
    }

    /** Which upstream this list pulls from. */
    public final VersionVariant getVariant() {
        return variant;
    }

    /**
     * Re-fetch the upstream release feed and convert it into a flat list
     * of installable server builds, newest first.
     *
     * @throws IOException on network failure with no usable cached fallback
     */
    public abstract List<MindustryRemoteVersion> refreshServer() throws IOException;

    // ---------- helpers shared by subclasses ----------

    /**
     * Pick the first asset whose name matches one of {@code patterns}, in
     * order. Earlier patterns are preferred over later ones (the fallback
     * chain). Returns {@code null} if no asset matches and no pattern
     * captures something usable.
     */
    protected static GitHubAsset pickAsset(GitHubRelease release, Pattern... patterns) {
        if (release == null || release.getAssets() == null) return null;
        for (Pattern p : patterns) {
            for (GitHubAsset asset : release.getAssets()) {
                if (asset == null || asset.getName() == null) continue;
                if (p.matcher(asset.getName()).find()) {
                    return asset;
                }
            }
        }
        return null;
    }

    /**
     * Extract the Mindustry build number from a release tag.
     * <p>Returns the first decimal integer found, or {@code 0} when the
     * tag has no digits. Examples:</p>
     * <pre>
     *   "v146"       → 146
     *   "147"        → 147
     *   "v146.4"     → 146
     *   "release-12" → 12
     * </pre>
     */
    protected static int extractBuild(String tag) {
        if (tag == null) return 0;
        Matcher m = FIRST_INT.matcher(tag);
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
