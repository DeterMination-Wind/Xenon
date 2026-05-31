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

import determination.xenon.util.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Picks the fastest reachable GitHub mirror for the current network and
 * rewrites direct GitHub URLs onto it.
 *
 * <p>Probes all candidates in parallel with HEAD requests (3 s per
 * candidate). The first candidate to answer with any HTTP status wins
 * and is cached for {@link #CACHE_TTL}; subsequent {@link #wrap} calls
 * within that window skip probing.</p>
 *
 * <p>Singleton — call {@link #getInstance()}. The candidate list is
 * fixed; thread-safe by virtue of {@code volatile} state.</p>
 */
public final class MirrorSelector {
    /** How long a successful probe result is reused before re-probing. */
    public static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /** Per-candidate HEAD probe timeout. */
    public static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    /** How long a mirror stays blacklisted after a confirmed failure. */
    public static final Duration BLACKLIST_TTL = Duration.ofMinutes(5);

    private static final String GITHUB = "https://github.com/";
    private static final String API = "https://api.github.com/";
    private static final String RAW = "https://raw.githubusercontent.com/";
    private static final String CODELOAD = "https://codeload.github.com/";

    /** Sentinel for "no rewrite" (the upstream GitHub origin). */
    private static final Mirror DIRECT = new Mirror("direct", API, Strategy.DIRECT, null);

    /**
     * Candidates probed in parallel. Order is not significant — fastest wins.
     * The DIRECT entry is included so an unrestricted network never pays the
     * mirror penalty.
     */
    private static final List<Mirror> CANDIDATES = List.of(
            // Domestic cache proxy for Chinese users — faster than direct GitHub access
            new Mirror("cache-121.199.60.4",
                    "http://121.199.60.4/github/", Strategy.PREFIX_FULL_URL, "http://121.199.60.4/github/"),
            // gh.tinylake.top first — TinyLake's own proxy, the same one
            // mindustry.top/download links to. Empirically the most
            // reliable mirror for users in mainland China.
            new Mirror("gh.tinylake.top",
                    "https://gh.tinylake.top/", Strategy.PREFIX_FULL_URL, "https://gh.tinylake.top/"),
            DIRECT,
            new Mirror("ghproxy.com",
                    "https://ghproxy.com/", Strategy.PREFIX_FULL_URL, "https://ghproxy.com/"),
            new Mirror("hub.gitmirror.com",
                    "https://hub.gitmirror.com/", Strategy.PREFIX_FULL_URL, "https://hub.gitmirror.com/"),
            new Mirror("kgithub.com",
                    "https://kgithub.com/", Strategy.HOST_REPLACE, "https://kgithub.com/"),
            new Mirror("gh.api.99866.xyz",
                    "https://gh.api.99866.xyz/", Strategy.PREFIX_FULL_URL, "https://gh.api.99866.xyz/")
    );

    private static final MirrorSelector INSTANCE = new MirrorSelector();

    public static MirrorSelector getInstance() {
        return INSTANCE;
    }

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(PROBE_TIMEOUT)
            .build();

    private volatile Mirror cached;
    private volatile Instant cachedAt;
    /** Mirror name → time it should stop being skipped. */
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> blacklist =
            new java.util.concurrent.ConcurrentHashMap<>();

    private MirrorSelector() {
    }

    /**
     * Mark {@code mirrorName} as failing so subsequent probe races skip it
     * for {@link #BLACKLIST_TTL}. Called by {@code GitHubReleaseClient}
     * after a mirror returns a TLS error / 4xx / 0-byte body.
     */
    public void blacklist(String mirrorName) {
        if (mirrorName == null || mirrorName.isEmpty()) return;
        blacklist.put(mirrorName, Instant.now().plus(BLACKLIST_TTL));
        // If the currently cached mirror is the one being blacklisted,
        // drop the cache so the next call picks a fresh one.
        Mirror c = cached;
        if (c != null && c.name.equals(mirrorName)) {
            cached = null;
            cachedAt = null;
        }
        Logger.LOG.warning("Blacklisted GitHub mirror " + mirrorName + " for " + BLACKLIST_TTL);
    }

    private boolean isBlacklisted(Mirror m) {
        Instant until = blacklist.get(m.name);
        if (until == null) return false;
        if (Instant.now().isAfter(until)) {
            blacklist.remove(m.name);
            return false;
        }
        return true;
    }

    /**
     * Rewrite a direct GitHub URL to go through the currently preferred
     * mirror. URLs that don't match a known GitHub origin (or that the
     * preferred mirror can't proxy) are returned unchanged.
     *
     * <p>If no mirror has been chosen yet, this triggers a synchronous
     * probe ({@link #PROBE_TIMEOUT} per candidate). If every probe times
     * out, the original URL is returned.</p>
     */
    public String wrap(String githubUrl) {
        if (githubUrl == null || githubUrl.isEmpty()) return githubUrl;
        Mirror m = pickMirror();
        return m.wrap(githubUrl);
    }

    /**
     * Force a re-probe on the next {@link #wrap} call. Useful after a
     * confirmed network change or when the cached mirror starts failing.
     */
    public void invalidate() {
        cached = null;
        cachedAt = null;
    }

    /** Mirror name currently in use, for diagnostics. Never returns null. */
    public String currentMirrorName() {
        Mirror m = cached;
        return m == null ? "unknown" : m.name;
    }

    /** Pick the cached mirror or run a fresh probe race. */
    private Mirror pickMirror() {
        Mirror c = cached;
        Instant at = cachedAt;
        if (c != null && at != null && Duration.between(at, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return c;
        }
        Mirror chosen = race();
        if (chosen != null) {
            cached = chosen;
            cachedAt = Instant.now();
            Logger.LOG.info("Selected GitHub mirror: " + chosen.name);
            return chosen;
        }
        Logger.LOG.warning("All GitHub mirrors unreachable, falling back to direct");
        return DIRECT;
    }

    /** Send HEAD probes in parallel, return the first successful candidate. */
    private Mirror race() {
        List<Mirror> candidates = new ArrayList<>(CANDIDATES.size());
        for (Mirror m : CANDIDATES) {
            if (!isBlacklisted(m)) candidates.add(m);
        }
        if (candidates.isEmpty()) {
            // Everything is blacklisted — clear the list and try again rather
            // than locking the user out completely.
            blacklist.clear();
            candidates = new ArrayList<>(CANDIDATES);
        }
        List<CompletableFuture<Mirror>> futures = new ArrayList<>(candidates.size());
        for (Mirror m : candidates) {
            futures.add(probe(m));
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        CompletableFuture<Object> any = CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]));
        try {
            // Outer timeout: longest single probe + small slack.
            Object first = any.get(PROBE_TIMEOUT.toMillis() + 500, TimeUnit.MILLISECONDS);
            if (first instanceof Mirror) return (Mirror) first;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
        }
        // Fallback: return any future that already completed successfully.
        for (CompletableFuture<Mirror> f : futures) {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                try { return f.getNow(null); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private CompletableFuture<Mirror> probe(Mirror m) {
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(m.probeUrl))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(PROBE_TIMEOUT)
                    .build();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        return http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .thenApply(resp -> {
                    // Any HTTP response means the host is reachable. Status
                    // code is irrelevant — many mirrors answer 403/405 to HEAD.
                    return m;
                });
    }

    // ---------- internal model ----------

    private enum Strategy {
        /** Don't rewrite. */
        DIRECT,
        /**
         * Prepend {@code prefix} to the entire original URL, e.g.
         * {@code https://ghproxy.com/} + {@code https://github.com/...}.
         */
        PREFIX_FULL_URL,
        /**
         * Substitute {@code github.com} with the mirror host
         * (and {@code raw.githubusercontent.com} with {@code raw.<host>/}).
         */
        HOST_REPLACE
    }

    private static final class Mirror {
        final String name;
        final String probeUrl;
        final Strategy strategy;
        final String prefix;

        Mirror(String name, String probeUrl, Strategy strategy, String prefix) {
            this.name = Objects.requireNonNull(name);
            this.probeUrl = Objects.requireNonNull(probeUrl);
            this.strategy = strategy;
            this.prefix = prefix;
        }

        String wrap(String url) {
            switch (strategy) {
                case DIRECT:
                    return url;
                case PREFIX_FULL_URL:
                    if (isGithubOrigin(url)) return prefix + url;
                    return url;
                case HOST_REPLACE:
                    if (url.startsWith(GITHUB)) {
                        return prefix + url.substring(GITHUB.length());
                    }
                    if (url.startsWith(RAW)) {
                        // kgithub.com hosts raw blobs at raw.kgithub.com
                        String host = prefix.substring("https://".length(), prefix.length() - 1);
                        return "https://raw." + host + "/" + url.substring(RAW.length());
                    }
                    // api.github.com / codeload not handled by host-replace mirrors
                    return url;
                default:
                    return url;
            }
        }
    }

    private static boolean isGithubOrigin(String url) {
        return url.startsWith(GITHUB)
                || url.startsWith(API)
                || url.startsWith(RAW)
                || url.startsWith(CODELOAD);
    }

    /** Visible for callers that want to enumerate mirror names (UI). */
    public List<String> candidateNames() {
        List<String> names = new ArrayList<>(CANDIDATES.size());
        for (Mirror m : CANDIDATES) names.add(m.name);
        return Collections.unmodifiableList(names);
    }
}
