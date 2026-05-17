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
 * Vanilla {@link VersionVariant#VANILLA} list, backed by
 * {@code Anuken/Mindustry}'s GitHub release feed.
 *
 * <p>Asset matching prefers an exact {@code Mindustry.jar} (the canonical
 * desktop client jar). When the upstream pipeline has not produced that
 * exact name, falls back to a case-insensitive {@code mindustry*.jar}
 * match while excluding obvious non-desktop variants
 * ({@code server}, {@code -be-}, {@code linux}, {@code mac}).</p>
 *
 * <p>Build numbers are parsed from the release tag — Anuken's vanilla
 * tags look like {@code v149} (occasionally a bare {@code 149}). Anything
 * that does not yield a positive integer is reported as build {@code 0}
 * and still surfaces, so the UI never silently swallows a release.</p>
 */
public final class VanillaVersionList extends MindustryVersionList {

    /** GitHub {@code per_page} ceiling we ask for; covers years of stable history. */
    private static final int RELEASE_LIMIT = 30;

    /** {@code Mindustry.jar} is the canonical desktop asset name. */
    private static final String CANONICAL_ASSET = "Mindustry.jar";

    /** Lenient fallback for the desktop jar when the canonical name is absent. */
    private static final Pattern DESKTOP_JAR_PATTERN =
            Pattern.compile("(?i)mindustry.*\\.jar");

    /**
     * Tag formats observed: {@code v149}, {@code 149}, occasionally
     * {@code v149-rc1}. The first run of digits is the build number.
     */
    private static final Pattern BUILD_FROM_TAG = Pattern.compile("(\\d+)");

    public VanillaVersionList(GitHubReleaseClient client) {
        super(VersionVariant.VANILLA, client);
    }

    @Override
    public List<MindustryRemoteVersion> refresh() throws IOException {
        try {
            List<GitHubRelease> releases = client.listReleases(variant.getUpstreamRepo(), RELEASE_LIMIT);
            List<MindustryRemoteVersion> out = new ArrayList<>(releases.size());
            for (GitHubRelease release : releases) {
                GitHubAsset asset = pickDesktopAsset(release);
                if (asset == null) {
                    Logger.LOG.info("Vanilla release " + release.getTagName() + " has no desktop jar asset; skipped");
                    continue;
                }
                int build = parseBuild(release.getTagName());
                out.add(new MindustryRemoteVersion(
                        build,
                        "stable",
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
            Logger.LOG.warning("GitHub Vanilla feed returned no usable rows; falling back to mindustry.top");
        } catch (IOException e) {
            Logger.LOG.warning("GitHub Vanilla feed failed (" + e.getMessage()
                    + "); falling back to mindustry.top");
        }
        // Fallback: mindustry.top/download
        try {
            List<MindustryRemoteVersion> scraped = new MindustryTopScraper().fetchVariant(variant);
            scraped.sort(Comparator.comparingInt(MindustryRemoteVersion::getBuild).reversed());
            return scraped;
        } catch (IOException e) {
            throw new IOException("Both GitHub and mindustry.top failed for Vanilla: " + e.getMessage(), e);
        }
    }

    private static GitHubAsset pickDesktopAsset(GitHubRelease release) {
        GitHubAsset fallback = null;
        for (GitHubAsset asset : release.getAssets()) {
            String name = asset.getName();
            if (name == null || name.isEmpty()) continue;

            if (CANONICAL_ASSET.equals(name)) {
                return asset; // exact match wins immediately
            }
            if (fallback != null) continue; // already have a fallback candidate
            if (!DESKTOP_JAR_PATTERN.matcher(name).matches()) continue;

            String lower = name.toLowerCase();
            if (lower.contains("server")) continue;
            if (lower.contains("-be-")) continue;
            if (lower.contains("linux")) continue;
            if (lower.contains("mac")) continue;
            fallback = asset;
        }
        return fallback;
    }

    private static int parseBuild(String tag) {
        if (tag == null || tag.isEmpty()) return 0;
        Matcher m = BUILD_FROM_TAG.matcher(tag);
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
