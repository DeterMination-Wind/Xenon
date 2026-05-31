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

import determination.xenon.mindustry.MindustryVersionDisplay;
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
 * MindustryX server feed pulled from
 * {@code TinyLake/MindustryX/releases}.
 *
 * <p>The MindustryX release pipeline produces an
 * {@code MindustryX-server*.jar} per build. The fallback widens to any
 * {@code *server*.jar} since TinyLake has reshuffled asset names a few
 * times across the patch series.</p>
 */
public final class MindustryXServerVersionList extends ServerVersionList {

    private static final String OWNER_REPO = "TinyLake/MindustryX";

    private static final Pattern PRIMARY = Pattern.compile("(?i)MindustryX-server.*\\.jar$");
    private static final Pattern FALLBACK = Pattern.compile("(?i)server.*\\.jar$");

    public MindustryXServerVersionList(GitHubReleaseClient client) {
        super(VersionVariant.MINDUSTRY_X, client);
    }

    @Override
    public List<MindustryRemoteVersion> refreshServer() throws IOException {
        List<GitHubRelease> releases = client.listReleases(OWNER_REPO, DEFAULT_LIMIT);
        List<MindustryRemoteVersion> out = new ArrayList<>(releases.size());
        for (GitHubRelease r : releases) {
            GitHubAsset asset = pickAsset(r, PRIMARY, FALLBACK);
            if (asset == null) {
                Logger.LOG.debug("MindustryX release " + r.getTagName() + " has no server jar, skipping");
                continue;
            }
            int build = MindustryVersionDisplay.extractMindustryXBuild(r.getTagName(), asset.getName());
            if (build <= 0) {
                build = extractBuild(r.getTagName());
            }
            String channel = MindustryVersionDisplay.detectMindustryXChannel(null, r.getTagName(), asset.getName());
            if (channel == null) {
                channel = r.isPrerelease() ? "B" : "X";
            }
            out.add(new MindustryRemoteVersion(
                    build,
                    channel,
                    variant,
                    asset.getDownloadUrl(),
                    r.getPublishedAt(),
                    asset.getSize(),
                    r.getTagName(),
                    asset.getName()));
        }
        return out;
    }
}
