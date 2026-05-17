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
 * Version list for {@code TinyLake/MindustryX} — TinyLake's
 * MindustryX patched desktop builds.
 *
 * <p>Asset selection is best-effort: TinyLake has shipped the desktop jar
 * under several names over the years (see fallback chain below). The build
 * number lives in the tag (e.g. {@code v149.x},
 * {@code MindustryX-149-build149}); when the tag is unhelpful we fall back
 * to the largest plain integer in the asset filename. Any candidate jar
 * smaller than 5 MiB is treated as a sidecar (sources, mappings, ...) and
 * skipped.</p>
 */
public final class MindustryXVersionList extends MindustryVersionList {

    private static final int RELEASE_LIMIT = 30;
    private static final long MIN_DESKTOP_SIZE = 5L * 1024 * 1024;

    /** Highest priority — TinyLake's modern naming, e.g. {@code MindustryX-desktop-149.jar}. */
    private static final Pattern P_DESKTOP_TIGHT = Pattern.compile("(?i)MindustryX-desktop[-_].*\\.jar");
    /** Older naming with the {@code desktop} token somewhere in the middle. */
    private static final Pattern P_DESKTOP_LOOSE = Pattern.compile("(?i)MindustryX.*desktop.*\\.jar");
    /** Last-resort: any jar with {@code desktop} in its name. */
    private static final Pattern P_DESKTOP_FALLBACK = Pattern.compile("(?i)desktop.*\\.jar");

    private static final Pattern BUILD_IN_TAG = Pattern.compile("(?i)build[-_ ]?(\\d+)");
    /**
     * MindustryX modern tags look like {@code v2026.05.X34} (stable),
     * {@code v2026.04.X33} or {@code prerelease-2026.04.26.B454} — the
     * trailing {@code X##} / {@code B##} token is the actual version
     * counter and should be treated as the build number. The leading year
     * (2026) and date components are ignored, since picking the largest
     * integer would otherwise display every release as "build 2026".
     */
    private static final Pattern X_OR_B_SUFFIX = Pattern.compile("(?i)[XB](\\d+)\\b");
    private static final Pattern ANY_NUMBER = Pattern.compile("(\\d+)");

    public MindustryXVersionList(GitHubReleaseClient client) {
        super(VersionVariant.MINDUSTRY_X, client);
    }

    @Override
    public List<MindustryRemoteVersion> refresh() throws IOException {
        // Priority 1: alist mirror at 47.238.248.194:5244 — empirically
        // fastest source for users in mainland China, updates within
        // minutes of a TinyLake release. We try this first and only fall
        // back to GitHub when it's unreachable.
        try {
            List<MindustryRemoteVersion> alist = new AlistMindustryXVersionList().refresh();
            if (!alist.isEmpty()) {
                Logger.LOG.info("MindustryX: alist returned " + alist.size() + " releases");
                return alist;
            }
        } catch (IOException e) {
            Logger.LOG.warning("MindustryX alist source failed (" + e.getMessage()
                    + "); falling back to GitHub");
        }

        // Priority 2: GitHub Releases API for TinyLake/MindustryX.
        try {
            List<GitHubRelease> releases = client.listReleases(variant.getUpstreamRepo(), RELEASE_LIMIT);
            List<MindustryRemoteVersion> out = new ArrayList<>(releases.size());
            for (GitHubRelease r : releases) {
                if (r == null || r.getAssets().isEmpty()) continue;
                GitHubAsset asset = pickAsset(r);
                if (asset == null) {
                    Logger.LOG.debug("MindustryX: no desktop jar in release " + r.getTagName());
                    continue;
                }
                int build = extractBuild(r.getTagName(), asset.getName());
                // MindustryX channel: look at the trailing X##/B## token in
                // the tag — X = stable ("正式版"), B = preview ("预览版").
                // Fall back to GitHub's `prerelease` flag if neither marker
                // is present so the column is never empty.
                String channel = detectChannel(r.getTagName(), asset.getName(), r.isPrerelease());
                Logger.LOG.debug("MindustryX: tag=" + r.getTagName()
                        + " asset=" + asset.getName() + " build=" + build
                        + " channel=" + channel + " size=" + asset.getSize());
                out.add(new MindustryRemoteVersion(
                        build,
                        channel,
                        VersionVariant.MINDUSTRY_X,
                        asset.getDownloadUrl(),
                        r.getPublishedAt(),
                        asset.getSize(),
                        r.getTagName(),
                        asset.getName()));
            }
            if (!out.isEmpty()) return out;
            Logger.LOG.warning("GitHub MindustryX feed returned no usable rows; falling back to mindustry.top");
        } catch (IOException e) {
            Logger.LOG.warning("GitHub MindustryX feed failed (" + e.getMessage()
                    + "); falling back to mindustry.top");
        }

        // Priority 3: mindustry.top scraper (last-resort).
        try {
            return new MindustryTopScraper().fetchVariant(variant);
        } catch (IOException e) {
            throw new IOException("All MindustryX sources failed (alist, GitHub, mindustry.top): "
                    + e.getMessage(), e);
        }
    }

    /**
     * MindustryX channel token: {@code X} = release ("正式版"), {@code B}
     * = preview ("预览版"). The token sits at the end of the tag right
     * before the build number, e.g. {@code v2026.05.X34} (release) or
     * {@code prerelease-2026.04.26.B454} (preview). When the marker is
     * missing on both tag and asset name we fall back to GitHub's
     * {@code prerelease} flag.
     */
    private static String detectChannel(String tag, String assetName, boolean prerelease) {
        Character marker = lastChannelMarker(tag);
        if (marker == null) marker = lastChannelMarker(assetName);
        if (marker != null) {
            if (marker == 'X' || marker == 'x') return "X";
            if (marker == 'B' || marker == 'b') return "B";
        }
        return prerelease ? "B" : "X";
    }

    /** Return the channel letter (X / B) of the last X##/B## token, or null. */
    private static Character lastChannelMarker(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = X_OR_B_SUFFIX.matcher(text);
        Character last = null;
        while (m.find()) {
            char c = m.group().charAt(0);
            last = c;
        }
        return last;
    }

    private static GitHubAsset pickAsset(GitHubRelease release) {
        GitHubAsset hit = matchFirst(release, P_DESKTOP_TIGHT);
        if (hit != null) return hit;
        hit = matchFirst(release, P_DESKTOP_LOOSE);
        if (hit != null) return hit;
        return matchFirst(release, P_DESKTOP_FALLBACK);
    }

    private static GitHubAsset matchFirst(GitHubRelease release, Pattern pattern) {
        for (GitHubAsset a : release.getAssets()) {
            if (a == null || a.getName() == null || a.getDownloadUrl() == null) continue;
            if (a.getSize() > 0 && a.getSize() < MIN_DESKTOP_SIZE) continue;
            if (pattern.matcher(a.getName()).find()) return a;
        }
        return null;
    }

    private static int extractBuild(String tag, String assetName) {
        // Modern MindustryX tags carry the build as a trailing X## / B##
        // token (the year-based prefix is calendar metadata). Try that
        // shape first — both on the tag and on the asset name — before
        // falling back to legacy "buildNNN" / "largest integer" heuristics.
        if (tag != null) {
            Matcher x = X_OR_B_SUFFIX.matcher(tag);
            int last = 0;
            while (x.find()) last = parseSafe(x.group(1));
            if (last > 0) return last;
        }
        if (assetName != null) {
            Matcher x = X_OR_B_SUFFIX.matcher(assetName);
            int last = 0;
            while (x.find()) last = parseSafe(x.group(1));
            if (last > 0) return last;
        }
        if (tag != null) {
            Matcher m = BUILD_IN_TAG.matcher(tag);
            if (m.find()) return parseSafe(m.group(1));
        }
        // Last-resort: largest integer in the asset name (skip the tag here
        // because a date-shaped tag like 2026.05.X34 would yield the year).
        if (assetName != null) {
            int max = largestNumber(assetName);
            if (max > 0) return max;
        }
        return 0;
    }

    private static int largestNumber(String text) {
        Matcher m = ANY_NUMBER.matcher(text);
        int max = 0;
        while (m.find()) {
            int v = parseSafe(m.group(1));
            if (v > max) max = v;
        }
        return max;
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
