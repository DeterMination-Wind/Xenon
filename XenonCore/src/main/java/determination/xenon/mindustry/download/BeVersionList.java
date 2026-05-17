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
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bleeding-edge {@link VersionVariant#BE} list, backed by
 * {@code Anuken/MindustryBuilds}'s GitHub release feed.
 *
 * <p>Asset matching prefers the canonical
 * {@code Mindustry-BE-Desktop-*.jar} pattern. When upstream renames the
 * file (it has churned a few times historically), falls back to the
 * looser {@code Mindustry-BE-*Desktop*.jar} so the list still populates.</p>
 *
 * <p>Build numbers are parsed from the release tag, which is normally
 * {@code Bleeding-Edge-Build-XXXXX}. The build extractor is intentionally
 * forgiving and accepts {@code build-XXXXX}, {@code build_XXXXX} and
 * {@code build XXXXX} variations.</p>
 */
public final class BeVersionList extends MindustryVersionList {

    /** Number of recent BE releases to surface; BE updates several times a day. */
    private static final int RELEASE_LIMIT = 50;

    /** Preferred desktop asset shape used by current MindustryBuilds publishes. */
    private static final Pattern PRIMARY_DESKTOP_PATTERN =
            Pattern.compile("(?i)Mindustry-BE-Desktop-.*\\.jar");

    /** Looser fallback that catches older / reordered asset names. */
    private static final Pattern FALLBACK_DESKTOP_PATTERN =
            Pattern.compile("(?i)Mindustry-BE-.*Desktop.*\\.jar");

    /** Pulls {@code 12345} out of {@code Bleeding-Edge-Build-12345} et al. */
    private static final Pattern BUILD_FROM_TAG =
            Pattern.compile("(?i)build[-_ ]?(\\d+)");

    public BeVersionList(GitHubReleaseClient client) {
        super(VersionVariant.BE, client);
    }

    @Override
    public List<MindustryRemoteVersion> refresh() throws IOException {
        try {
            List<GitHubRelease> releases = client.listReleases(variant.getUpstreamRepo(), RELEASE_LIMIT);
            List<MindustryRemoteVersion> out = new ArrayList<>(releases.size());
            for (GitHubRelease release : releases) {
                GitHubAsset asset = pickDesktopAsset(release);
                if (asset == null) {
                    Logger.LOG.info("BE release " + release.getTagName() + " has no desktop jar asset; skipped");
                    continue;
                }
                int build = parseBuild(release.getTagName());
                out.add(new MindustryRemoteVersion(
                        build,
                        "be",
                        variant,
                        asset.getDownloadUrl(),
                        release.getPublishedAt(),
                        asset.getSize(),
                        release.getTagName(),
                        asset.getName()));
            }
            if (!out.isEmpty()) {
                out.sort(Comparator.comparingInt(MindustryRemoteVersion::getBuild).reversed());
                return out;
            }
            Logger.LOG.warning("GitHub BE feed returned no usable rows; falling back to mindustry.top");
        } catch (IOException e) {
            Logger.LOG.warning("GitHub BE feed failed (" + e.getMessage()
                    + "); falling back to mindustry.top");
        }
        try {
            List<MindustryRemoteVersion> scraped = new MindustryTopScraper().fetchVariant(variant);
            scraped.sort(Comparator.comparingInt(MindustryRemoteVersion::getBuild).reversed());
            return scraped;
        } catch (IOException e) {
            throw new IOException("Both GitHub and mindustry.top failed for BE: " + e.getMessage(), e);
        }
    }

    private static GitHubAsset pickDesktopAsset(GitHubRelease release) {
        GitHubAsset primary = null;
        GitHubAsset fallback = null;
        for (GitHubAsset asset : release.getAssets()) {
            String name = asset.getName();
            if (name == null || name.isEmpty()) continue;
            if (primary == null && PRIMARY_DESKTOP_PATTERN.matcher(name).matches()) {
                primary = asset;
                // keep scanning to honour primary > fallback ordering, but
                // we can short-circuit since primary always wins.
                return primary;
            }
            if (fallback == null && FALLBACK_DESKTOP_PATTERN.matcher(name).matches()) {
                fallback = asset;
            }
        }
        return fallback;
    }

    private static int parseBuild(String tag) {
        if (tag == null || tag.isEmpty()) return 0;
        Matcher m = BUILD_FROM_TAG.matcher(tag);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                // fall through to bare-digit fallback
            }
        }
        // Bare-digit fallback in case upstream ever drops the "build" prefix.
        Matcher digits = Pattern.compile("(\\d+)").matcher(tag);
        if (digits.find()) {
            try {
                return Integer.parseInt(digits.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }
}
