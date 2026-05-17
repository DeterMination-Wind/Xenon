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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback version source backed by
 * <a href="https://www.mindustry.top/download">mindustry.top/download</a>.
 *
 * <p>The site is a SPA but renders the full release matrix server-side as
 * plain HTML, every download line shaped like:
 * <pre>{@code
 * <strong>Mindustry.jar</strong>
 * ... "https://gh.tinylake.top/https://github.com/Anuken/Mindustry/releases/download/v157.4/Mindustry.jar"
 * }</pre>
 * That's all the structure we need: a regex pull yields
 * {@code (repo, tag, filename)} tuples per row, which is a strict subset
 * of what the GitHub Releases API returns.</p>
 *
 * <p>Used as a fallback when the GitHub feed for Vanilla / BE / MindustryX
 * is unreachable or rate-limited (HTTP 403). The site only mirrors those
 * three repos — CN-ARC and Foo are not covered and keep their direct-only
 * paths.</p>
 */
public final class MindustryTopScraper {

    /** Mindustry.top download page (SR-rendered HTML). */
    public static final String DOWNLOAD_URL = "https://www.mindustry.top/download";

    /** {@code .top} proxy prefix the page renders into every URL. */
    public static final String PROXY_PREFIX = "https://gh.tinylake.top/";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Capture filename + matching gh.tinylake.top proxy URL, in the order
     * they appear in the SR HTML. The two are emitted within ~150 chars of
     * each other in the actual payload, so we capture them independently
     * and pair-up by sequence.
     */
    private static final Pattern STRONG_NAME = Pattern.compile(
            "<strong>([^<]+\\.(?:jar|apk))</strong>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROXY_URL = Pattern.compile(
            "https://gh\\.tinylake\\.top/https://github\\.com/([A-Za-z0-9._-]+/[A-Za-z0-9._-]+)/releases/download/([^/\"\\s]+)/([^\"\\s]+)");

    private final HttpClient http;

    public MindustryTopScraper() {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * Hit {@link #DOWNLOAD_URL} and return every download row, keyed by the
     * repo it belongs to. Order within each list mirrors the page (newest
     * first, since the page is sorted that way).
     */
    public Map<String, List<Row>> fetch() throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(DOWNLOAD_URL))
                .GET()
                .timeout(READ_TIMEOUT)
                .header("User-Agent", "Xenon-Launcher")
                .header("Accept", "text/html")
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted contacting " + DOWNLOAD_URL, e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " from " + DOWNLOAD_URL);
        }
        return parse(resp.body());
    }

    /** Visible for tests. */
    static Map<String, List<Row>> parse(String html) {
        Map<String, List<Row>> grouped = new HashMap<>();
        Matcher m = PROXY_URL.matcher(html);
        while (m.find()) {
            String repo = m.group(1);
            String tag = m.group(2);
            String filename = m.group(3);
            String url = m.group(0);
            Row row = new Row(repo, tag, filename, url);
            grouped.computeIfAbsent(repo, k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    /**
     * Variant-aware convenience over {@link #fetch()}: returns
     * {@link MindustryRemoteVersion} rows already filtered to the variant's
     * desktop jar pattern. Returns an empty list if the variant has no
     * matching repo on the page (CN-ARC, Foo).
     */
    public List<MindustryRemoteVersion> fetchVariant(VersionVariant variant) throws IOException {
        Objects.requireNonNull(variant, "variant");
        String repo = variant.getUpstreamRepo();
        if (repo == null) return List.of();
        Map<String, List<Row>> rows = fetch();
        List<Row> hits = rows.getOrDefault(repo, List.of());
        if (hits.isEmpty()) {
            Logger.LOG.debug("mindustry.top: no rows for repo " + repo);
            return List.of();
        }
        // Group by tag, picking the desktop jar per tag.
        Map<String, Row> byTag = new java.util.LinkedHashMap<>();
        for (Row row : hits) {
            if (!isDesktopJar(variant, row.filename)) continue;
            // First match per tag wins (page is newest-first).
            byTag.putIfAbsent(row.tag, row);
        }
        List<MindustryRemoteVersion> out = new ArrayList<>(byTag.size());
        for (Row row : byTag.values()) {
            int build = extractBuild(variant, row.tag, row.filename);
            String type = variant == VersionVariant.BE ? "be" : "stable";
            // Prefer the upstream GitHub URL — MirrorSelector will route via
            // its own probe later, and the proxy URL is just one mirror's
            // opinion. Strip the gh.tinylake.top prefix to recover the
            // canonical download URL.
            String canonical = row.url.startsWith(PROXY_PREFIX)
                    ? row.url.substring(PROXY_PREFIX.length())
                    : row.url;
            out.add(new MindustryRemoteVersion(
                    build, type, variant, canonical, null, 0L, row.tag, row.filename));
        }
        return out;
    }

    private static boolean isDesktopJar(VersionVariant variant, String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".jar")) return false;
        if (lower.contains("server") || lower.contains("dependencies") || lower.contains("dexed"))
            return false;
        return switch (variant) {
            case VANILLA -> lower.equals("mindustry.jar");
            case BE -> lower.contains("desktop");
            case MINDUSTRY_X -> lower.contains("desktop");
            default -> false;
        };
    }

    private static int extractBuild(VersionVariant variant, String tag, String filename) {
        if (variant == VersionVariant.BE) {
            // BE tag = bare integer (e.g. "27085").
            return largestInt(tag);
        }
        if (variant == VersionVariant.VANILLA) {
            // Vanilla tag = "v157" / "v157.4" — first integer is the build.
            Matcher m = Pattern.compile("(\\d+)").matcher(tag);
            if (m.find()) return parseSafe(m.group(1));
            return 0;
        }
        // MindustryX: pull the largest integer (matches MindustryXVersionList).
        int t = largestInt(tag);
        if (t > 0) return t;
        return largestInt(filename);
    }

    private static int largestInt(String s) {
        Matcher m = Pattern.compile("(\\d+)").matcher(s);
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

    /** Single download row pulled from the SR HTML. */
    public record Row(String repo, String tag, String filename, String url) {
        public Row {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(tag, "tag");
            Objects.requireNonNull(filename, "filename");
            Objects.requireNonNull(url, "url");
        }
    }
}
