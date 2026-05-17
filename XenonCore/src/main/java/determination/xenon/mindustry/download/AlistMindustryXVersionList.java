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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MindustryX version list backed by the community alist instance at
 * {@link AlistClient#DEFAULT_BASE} (path {@code /Github/MindustryX}).
 *
 * <p>Each top-level directory is one release (tag); we list its files,
 * pick {@code MindustryX-*-Desktop.jar}, and emit one
 * {@link MindustryRemoteVersion} per release.</p>
 *
 * <p>Empirically this source updates within minutes of a TinyLake
 * release and has dramatically lower latency than every GitHub mirror
 * for users in mainland China — that's why it's the highest-priority
 * source in {@link MindustryXVersionList}.</p>
 */
public final class AlistMindustryXVersionList {

    private static final String LIST_PATH = "/Github/MindustryX";
    private static final int RELEASE_LIMIT = 30;

    /** Trailing X##/B## counter on the directory name (same shape used by GitHub tags). */
    private static final Pattern X_OR_B_SUFFIX = Pattern.compile("(?i)[XB](\\d+)\\b");

    private final AlistClient client;

    public AlistMindustryXVersionList() {
        this(new AlistClient());
    }

    public AlistMindustryXVersionList(AlistClient client) {
        this.client = client;
    }

    public List<MindustryRemoteVersion> refresh() throws IOException {
        List<AlistClient.Entry> top = client.list(LIST_PATH);
        // Newest first — alist returns directories without a guaranteed
        // order so re-sort by modified time (falling back to lexical, X
        // numbers ascend with calendar order).
        top.sort(Comparator.comparing((AlistClient.Entry e) ->
                        e.modified == null ? Instant.EPOCH : e.modified)
                .reversed());

        List<MindustryRemoteVersion> out = new ArrayList<>();
        int seen = 0;
        for (AlistClient.Entry dir : top) {
            if (!dir.isDir) continue;
            if (seen++ >= RELEASE_LIMIT) break;
            try {
                AlistClient.Entry desktop = pickDesktopJar(dir.name);
                if (desktop == null) continue;
                String fullPath = LIST_PATH + "/" + dir.name + "/" + desktop.name;
                String url = client.directUrl(fullPath);
                int build = extractBuild(dir.name, desktop.name);
                String channel = detectChannel(dir.name, desktop.name);
                out.add(new MindustryRemoteVersion(
                        build,
                        channel,
                        VersionVariant.MINDUSTRY_X,
                        url,
                        desktop.modified != null ? desktop.modified : dir.modified,
                        desktop.size,
                        dir.name,           // tag = directory name
                        desktop.name));
            } catch (IOException ex) {
                Logger.LOG.warning("alist: failed to list " + dir.name + ": " + ex.getMessage());
            }
        }
        if (out.isEmpty()) {
            throw new IOException("alist returned no usable MindustryX releases");
        }
        return out;
    }

    private AlistClient.Entry pickDesktopJar(String dirName) throws IOException {
        List<AlistClient.Entry> files = client.list(LIST_PATH + "/" + dirName);
        AlistClient.Entry tightMatch = null;
        AlistClient.Entry looseMatch = null;
        for (AlistClient.Entry f : files) {
            if (f.isDir) continue;
            if (f.name == null) continue;
            String lower = f.name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".jar")) continue;
            // Skip server / loader / sources sidecars.
            if (lower.contains("server")) continue;
            if (lower.contains("loader")) continue;
            if (lower.contains("source")) continue;
            // Tight match: explicit "Desktop" token.
            if (lower.contains("desktop") && tightMatch == null) tightMatch = f;
            // Loose fallback: any non-server jar over 5 MiB.
            if (f.size > 5L * 1024 * 1024 && looseMatch == null) looseMatch = f;
        }
        if (tightMatch != null) return tightMatch;
        return looseMatch;
    }

    /** X = release ("正式版"), B = preview ("预览版"). Default to X. */
    private static String detectChannel(String tag, String assetName) {
        Character m = lastChannelMarker(tag);
        if (m == null) m = lastChannelMarker(assetName);
        if (m != null) {
            if (m == 'X' || m == 'x') return "X";
            if (m == 'B' || m == 'b') return "B";
        }
        // Directory naming convention: prerelease-* is preview, everything else release.
        if (tag != null && tag.toLowerCase(Locale.ROOT).startsWith("prerelease")) return "B";
        return "X";
    }

    private static Character lastChannelMarker(String s) {
        if (s == null) return null;
        Matcher m = X_OR_B_SUFFIX.matcher(s);
        Character last = null;
        while (m.find()) last = m.group().charAt(0);
        return last;
    }

    private static int extractBuild(String tag, String assetName) {
        Matcher m = X_OR_B_SUFFIX.matcher(tag == null ? "" : tag);
        int last = 0;
        while (m.find()) {
            try { last = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        if (last > 0) return last;
        m = X_OR_B_SUFFIX.matcher(assetName == null ? "" : assetName);
        while (m.find()) {
            try { last = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return last;
    }

    @SuppressWarnings("unused")
    private static List<MindustryRemoteVersion> empty() {
        return Collections.emptyList();
    }
}
