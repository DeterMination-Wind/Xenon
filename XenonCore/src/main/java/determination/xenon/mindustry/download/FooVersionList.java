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

import determination.xenon.mindustry.VersionVariant;
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version list for {@code mindustry-antigrief/mindustry-client} — the
 * Foo Client.
 *
 * <p>Foo's desktop artifact is published as {@code client-release.jar} or
 * {@code client-vN.jar}; we exclude server / headless siblings. Foo's
 * own version number is independent of Mindustry's build, so we read the
 * release body for a {@code build: NNN} or {@code Mindustry build: NNN}
 * line and use that as the canonical build number stored on the
 * {@link MindustryRemoteVersion} — matching how the rest of the launcher
 * resolves Java requirements.</p>
 */
public final class FooVersionList extends MindustryVersionList {

    private static final int RELEASE_LIMIT = 30;
    private static final long MIN_DESKTOP_SIZE = 5L * 1024 * 1024;

    private static final Pattern P_CLIENT_RELEASE = Pattern.compile("(?i)client[-_]release.*\\.jar");
    private static final Pattern P_CLIENT_LOOSE = Pattern.compile("(?i)client[-_].*\\.jar");
    private static final Pattern EXCLUDE_TOKENS =
            Pattern.compile("(?i)(server|headless|sources|javadoc)");

    private static final Pattern BODY_BUILD_LONG = Pattern.compile("(?i)mindustry build[ :]+(\\d+)");
    private static final Pattern BODY_BUILD_SHORT = Pattern.compile("(?i)build[ :]+(\\d+)");

    public FooVersionList(GitHubReleaseClient client) {
        super(VersionVariant.FOO, client);
    }

    @Override
    public List<MindustryRemoteVersion> refresh() throws IOException {
        List<GitHubRelease> releases = client.listReleases(variant.getUpstreamRepo(), RELEASE_LIMIT);
        List<MindustryRemoteVersion> out = new ArrayList<>(releases.size());
        for (GitHubRelease r : releases) {
            if (r == null || r.getAssets().isEmpty()) continue;
            GitHubAsset asset = pickAsset(r);
            if (asset == null) {
                Logger.LOG.debug("Foo: no client jar in release " + r.getTagName());
                continue;
            }
            int build = extractBuildFromBody(r.getBody());
            Logger.LOG.debug("Foo: tag=" + r.getTagName()
                    + " asset=" + asset.getName()
                    + " mindustryBuild=" + build
                    + " size=" + asset.getSize());
            out.add(new MindustryRemoteVersion(
                    build,
                    "stable",
                    VersionVariant.FOO,
                    asset.getDownloadUrl(),
                    r.getPublishedAt(),
                    asset.getSize(),
                    r.getTagName(),
                    asset.getName()));
        }
        return out;
    }

    private static GitHubAsset pickAsset(GitHubRelease release) {
        GitHubAsset hit = matchFirst(release, P_CLIENT_RELEASE);
        if (hit != null) return hit;
        return matchFirst(release, P_CLIENT_LOOSE);
    }

    private static GitHubAsset matchFirst(GitHubRelease release, Pattern pattern) {
        for (GitHubAsset a : release.getAssets()) {
            if (a == null || a.getName() == null || a.getDownloadUrl() == null) continue;
            String name = a.getName();
            if (EXCLUDE_TOKENS.matcher(name).find()) continue;
            if (a.getSize() > 0 && a.getSize() < MIN_DESKTOP_SIZE) continue;
            if (pattern.matcher(name).find()) return a;
        }
        return null;
    }

    private static int extractBuildFromBody(String body) {
        if (body == null || body.isEmpty()) return 0;
        Matcher m = BODY_BUILD_LONG.matcher(body);
        if (m.find()) return parseSafe(m.group(1));
        m = BODY_BUILD_SHORT.matcher(body);
        if (m.find()) return parseSafe(m.group(1));
        return 0;
    }

    private static int parseSafe(String s) {
        try {
            long v = Long.parseLong(s);
            if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return (int) v;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
