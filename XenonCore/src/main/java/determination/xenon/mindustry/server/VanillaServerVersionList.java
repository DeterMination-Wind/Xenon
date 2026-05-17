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
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Vanilla server feed pulled from {@code Anuken/Mindustry/releases}.
 *
 * <p>Asset matching prefers the canonical {@code server-release.jar} name
 * (used since build 140), with a regex fallback to legacy/renamed
 * {@code Mindustry-server*.jar} assets.</p>
 */
public final class VanillaServerVersionList extends ServerVersionList {

    private static final String OWNER_REPO = "Anuken/Mindustry";

    /** Canonical server jar name used by recent vanilla releases. */
    private static final Pattern PRIMARY = Pattern.compile("(?i)^server-release\\.jar$");
    /** Legacy/alternate naming used by older vanilla releases. */
    private static final Pattern FALLBACK = Pattern.compile("(?i)Mindustry-server.*\\.jar$");

    public VanillaServerVersionList(GitHubReleaseClient client) {
        super(VersionVariant.VANILLA, client);
    }

    @Override
    public List<MindustryRemoteVersion> refreshServer() throws IOException {
        List<GitHubRelease> releases = client.listReleases(OWNER_REPO, DEFAULT_LIMIT);
        List<MindustryRemoteVersion> out = new ArrayList<>(releases.size());
        for (GitHubRelease r : releases) {
            GitHubAsset asset = pickAsset(r, PRIMARY, FALLBACK);
            if (asset == null) {
                Logger.LOG.debug("Vanilla release " + r.getTagName() + " has no server jar, skipping");
                continue;
            }
            int build = extractBuild(r.getTagName());
            out.add(new MindustryRemoteVersion(
                    build,
                    "stable",
                    variant,
                    asset.getDownloadUrl(),
                    r.getPublishedAt(),
                    asset.getSize()));
        }
        return out;
    }
}
