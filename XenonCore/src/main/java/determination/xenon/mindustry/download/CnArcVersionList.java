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
 * Version list for {@code BlueWolf3434/Mindustry-CN-ARC} — the CN-ARC
 * variant.
 *
 * <p>The repo only ships a single desktop jar per release with no fixed
 * naming convention, so we accept any {@code .jar} that is not obviously
 * a server / headless / platform-specific artifact. The build number is
 * extracted from the tag (the tag is typically a 3- or 4-digit Mindustry
 * build, e.g. {@code 146} or {@code v146}).</p>
 */
public final class CnArcVersionList extends MindustryVersionList {

    private static final int RELEASE_LIMIT = 30;
    private static final long MIN_DESKTOP_SIZE = 5L * 1024 * 1024;

    private static final Pattern P_JAR = Pattern.compile("(?i).*\\.jar");
    private static final Pattern EXCLUDE_TOKENS =
            Pattern.compile("(?i)(server|headless|linux|mac|macos|osx|aarch64|arm64|sources|javadoc)");

    private static final Pattern BUILD_IN_TAG = Pattern.compile("(?i)(\\d{2,4})");

    public CnArcVersionList(GitHubReleaseClient client) {
        super(VersionVariant.CN_ARC, client);
    }

    @Override
    public List<MindustryRemoteVersion> refresh() throws IOException {
        List<GitHubRelease> releases = client.listReleases(variant.getUpstreamRepo(), RELEASE_LIMIT);
        List<MindustryRemoteVersion> out = new ArrayList<>(releases.size());
        for (GitHubRelease r : releases) {
            if (r == null || r.getAssets().isEmpty()) continue;
            GitHubAsset asset = pickAsset(r);
            if (asset == null) {
                Logger.LOG.debug("CN-ARC: no desktop jar in release " + r.getTagName());
                continue;
            }
            int build = extractBuild(r.getTagName());
            Logger.LOG.debug("CN-ARC: tag=" + r.getTagName()
                    + " asset=" + asset.getName() + " build=" + build
                    + " size=" + asset.getSize());
            out.add(new MindustryRemoteVersion(
                    build,
                    "stable",
                    VersionVariant.CN_ARC,
                    asset.getDownloadUrl(),
                    r.getPublishedAt(),
                    asset.getSize(),
                    r.getTagName(),
                    asset.getName()));
        }
        return out;
    }

    private static GitHubAsset pickAsset(GitHubRelease release) {
        for (GitHubAsset a : release.getAssets()) {
            if (a == null || a.getName() == null || a.getDownloadUrl() == null) continue;
            String name = a.getName();
            if (!P_JAR.matcher(name).matches()) continue;
            if (EXCLUDE_TOKENS.matcher(name).find()) continue;
            if (a.getSize() > 0 && a.getSize() < MIN_DESKTOP_SIZE) continue;
            return a;
        }
        return null;
    }

    private static int extractBuild(String tag) {
        if (tag == null) return 0;
        Matcher m = BUILD_IN_TAG.matcher(tag);
        int best = 0;
        while (m.find()) {
            int v = parseSafe(m.group(1));
            if (v > best) best = v;
        }
        return best;
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
