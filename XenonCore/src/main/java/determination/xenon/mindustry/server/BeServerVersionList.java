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
 * Bleeding-Edge server feed pulled from
 * {@code Anuken/MindustryBuilds/releases}.
 *
 * <p>BE releases attach one server jar per build named
 * {@code Mindustry-BE-Server-<build>.jar}. The primary regex matches that
 * exact convention; the fallback widens to any {@code *server*.jar} so a
 * mid-cycle rename does not break installation.</p>
 */
public final class BeServerVersionList extends ServerVersionList {

    private static final String OWNER_REPO = "Anuken/MindustryBuilds";

    private static final Pattern PRIMARY = Pattern.compile("(?i)Mindustry-BE-Server-.*\\.jar$");
    private static final Pattern FALLBACK = Pattern.compile("(?i)server.*\\.jar$");

    public BeServerVersionList(GitHubReleaseClient client) {
        super(VersionVariant.BE, client);
    }

    @Override
    public List<MindustryRemoteVersion> refreshServer() throws IOException {
        List<GitHubRelease> releases = client.listReleases(OWNER_REPO, DEFAULT_LIMIT);
        List<MindustryRemoteVersion> out = new ArrayList<>(releases.size());
        for (GitHubRelease r : releases) {
            GitHubAsset asset = pickAsset(r, PRIMARY, FALLBACK);
            if (asset == null) {
                Logger.LOG.debug("BE release " + r.getTagName() + " has no server jar, skipping");
                continue;
            }
            int build = extractBuild(r.getTagName());
            out.add(new MindustryRemoteVersion(
                    build,
                    "be",
                    variant,
                    asset.getDownloadUrl(),
                    r.getPublishedAt(),
                    asset.getSize()));
        }
        return out;
    }
}
