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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.reflect.TypeToken;
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Cached, mirror-aware client for GitHub's Releases REST API.
 *
 * <p>All outbound URLs are funnelled through {@link MirrorSelector#wrap}.
 * Release-list responses are persisted under
 * {@code <cacheRoot>/github/<owner_repo>.json} together with a
 * {@code .meta} sidecar holding the previous {@code ETag} and
 * {@code Last-Modified}; the next request issues conditional headers and
 * reuses the cached body on {@code HTTP 304}.</p>
 *
 * <p>Stateless beyond the cache directory — safe to share across threads.</p>
 */
public final class GitHubReleaseClient {

    private static final String API_BASE = "https://api.github.com/";
    /** Cache proxy server — returns GitHub API v3 compatible responses. */
    private static final String CACHE_API_BASE = "http://121.199.60.4/github/";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    /** Cap per-mirror retry budget to avoid stacking 5s × N mirrors of latency. */
    private static final int MAX_MIRROR_RETRIES = 3;
    private static final int DOWNLOAD_BUFFER = 64 * 1024;
    private static final Type RELEASE_LIST_TYPE = new TypeToken<List<GitHubRelease>>() {}.getType();

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Instant.class,
                    (JsonDeserializer<Instant>) (json, t, ctx) -> Instant.parse(json.getAsString()))
            .create();

    private final HttpClient http;
    private final Path cacheDir;
    private final Path cacheRoot;
    private final MirrorSelector mirror;

    /**
     * @param cacheRoot the launcher caches directory (e.g. {@code Metadata.getCachesDirectory()});
     *                  may be {@code null} to disable on-disk caching.
     */
    public GitHubReleaseClient(Path cacheRoot) {
        this(cacheRoot, MirrorSelector.getInstance());
    }

    /** Visible for callers that want to inject a custom selector (tests, mocks). */
    public GitHubReleaseClient(Path cacheRoot, MirrorSelector mirror) {
        this.mirror = Objects.requireNonNull(mirror, "mirror");
        this.cacheRoot = cacheRoot;
        this.cacheDir = cacheRoot == null ? null : cacheRoot.resolve("github");
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    /**
     * Fetch the most recent {@code limit} releases of {@code owner/repo},
     * newest first. Uses the on-disk ETag cache when present.
     *
     * @param ownerRepo {@code owner/repo}, e.g. {@code Anuken/Mindustry}
     * @param limit     max entries (GitHub's {@code per_page}, capped at 100)
     */
    public List<GitHubRelease> listReleases(String ownerRepo, int limit) throws IOException {
        Objects.requireNonNull(ownerRepo, "ownerRepo");
        if (limit <= 0) return Collections.emptyList();
        int perPage = Math.min(100, limit);

        String relativePath = "repos/" + ownerRepo + "/releases?per_page=" + perPage;
        String body = fetchWithFallback(relativePath, ownerRepo);
        List<GitHubRelease> all = GSON.fromJson(body, RELEASE_LIST_TYPE);
        if (all == null) return Collections.emptyList();
        if (all.size() > limit) return new ArrayList<>(all.subList(0, limit));
        return all;
    }

    /** Convenience: the newest non-draft release, or {@code null} if none. */
    public GitHubRelease getLatestRelease(String ownerRepo) throws IOException {
        Objects.requireNonNull(ownerRepo, "ownerRepo");
        // /releases/latest skips drafts and pre-releases on GitHub's side.
        String relativePath = "repos/" + ownerRepo + "/releases/latest";
        String body = fetchWithFallback(relativePath, ownerRepo + "_latest");
        if (body == null || body.isEmpty()) return null;
        return GSON.fromJson(body, GitHubRelease.class);
    }

    /**
     * Stream {@code asset} to {@code target}. Parent directories are
     * created on demand; an existing file is overwritten.
     *
     * @param cb optional progress callback, may be {@code null}
     * @return {@code target} for fluent chaining
     */
    public Path downloadAsset(GitHubAsset asset, Path target, ProgressCallback cb) throws IOException {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(target, "target");
        if (asset.getDownloadUrl() == null || asset.getDownloadUrl().isEmpty()) {
            throw new IOException("Asset has no download URL: " + asset.getName());
        }
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);

        // Mirror-racing downloader (CainBot strategy): probe every mirror,
        // race the survivors, keep the fastest 2, cache the winner. Falls
        // through to the legacy single-mirror path on outright failure.
        try {
            new MirrorDownloader(cacheRoot)
                    .download(asset.getDownloadUrl(), target, asset.getSize(), cb);
            return target;
        } catch (IOException e) {
            Logger.LOG.warning("MirrorDownloader race failed for "
                    + asset.getName() + ": " + e.getMessage()
                    + " — falling back to single-mirror path");
        }

        IOException lastError = null;
        for (int attempt = 0; attempt < MAX_MIRROR_RETRIES; attempt++) {
            String url = mirror.wrap(asset.getDownloadUrl());
            try {
                doDownload(url, target, asset.getSize(), cb);
                return target;
            } catch (IOException e) {
                lastError = e;
                // Read the mirror name AFTER the failed call so we
                // blacklist the actually-broken mirror, not "unknown" left
                // over by a previous invalidate(). Same bug shape as
                // fetchWithCache before its fix.
                String failedMirror = mirror.currentMirrorName();
                Logger.LOG.warning("Download attempt " + (attempt + 1)
                        + " via mirror " + failedMirror
                        + " failed: " + e.getMessage());
                mirror.blacklist(failedMirror);
                mirror.invalidate();
            }
        }
        throw lastError != null ? lastError
                : new IOException("Download failed for " + asset.getName());
    }

    private void doDownload(String url, Path target, long expectedSize, ProgressCallback cb)
            throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .GET()
                // Connect-phase timeout. Body read uses the buffer loop below
                // to detect stalls; an idle stream past 30s is treated as dead.
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "Xenon-Launcher")
                .build();

        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted: " + url, e);
        }
        if (resp.statusCode() / 100 != 2) {
            try (InputStream ignored = resp.body()) {
                ignored.transferTo(OutputStream.nullOutputStream());
            }
            throw new IOException("HTTP " + resp.statusCode() + " downloading " + url);
        }
        long total = resp.headers().firstValueAsLong("Content-Length")
                .orElse(expectedSize > 0 ? expectedSize : -1);

        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(target,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            byte[] buf = new byte[DOWNLOAD_BUFFER];
            long read = 0;
            long lastProgressNs = System.nanoTime();
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                read += n;
                lastProgressNs = System.nanoTime();
                // Feed the global speed counter so the install dialog and
                // the top-left download indicator show real throughput.
                determination.xenon.task.FetchTask.recordDownloadedBytes(n);
                if (cb != null) cb.onProgress(read, total);
            }
            // No bytes ever read — server accepted then closed; treat as failure
            // so the caller picks a different mirror.
            if (read == 0) {
                throw new IOException("Empty response body from " + url);
            }
            if (cb != null) cb.onProgress(read, total);
        }
    }

    // ---------- cache-with-fallback ----------

    /**
     * Try the cache proxy server first, then fall back to the original
     * GitHub API (with ETag caching and mirror support).
     *
     * <p>The cache server at {@link #CACHE_API_BASE} returns GitHub API v3
     * compatible responses. If it is unreachable or returns a non-2xx
     * status, the request is transparently retried against
     * {@link #API_BASE} via {@link #fetchWithCache}.</p>
     *
     * @param relativePath path relative to the base URL,
     *                     e.g. {@code repos/owner/repo/releases}
     * @param cacheKey     on-disk cache key passed to {@link #fetchWithCache}
     */
    private String fetchWithFallback(String relativePath, String cacheKey) throws IOException {
        // 1. Try cache proxy
        String cacheUrl = CACHE_API_BASE + relativePath;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cacheUrl))
                    .GET()
                    .timeout(READ_TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Xenon-Launcher")
                    .build();
            HttpResponse<String> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 == 2) {
                Logger.LOG.info("Fetched " + relativePath + " from cache server");
                return resp.body();
            }
            Logger.LOG.warning("Cache server returned HTTP " + resp.statusCode()
                    + " for " + relativePath + ", falling back to GitHub API");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Logger.LOG.warning("Cache server failed for " + relativePath
                    + ": " + e.getMessage() + ", falling back to GitHub API");
        }

        // 2. Fall back to GitHub API (with ETag caching and mirror support)
        String apiUrl = API_BASE + relativePath;
        String body = fetchWithCache(apiUrl, cacheKey);
        Logger.LOG.info("Fetched " + relativePath + " from GitHub API");
        return body;
    }

    // ---------- caching ----------

    /**
     * GET {@code apiUrl} with conditional headers from the on-disk meta
     * sidecar; on 304 returns the cached body, on 200 rewrites cache.
     * On network failure with a usable cache, returns the cached body.
     *
     * <p>If the request times out (5s) or returns 5xx/403/404, force a
     * mirror re-probe and retry up to {@link #MAX_MIRROR_RETRIES} times so
     * one slow / dead mirror doesn't lock us out.</p>
     */
    private String fetchWithCache(String apiUrl, String cacheKey) throws IOException {
        String safeKey = cacheKey.replace('/', '_');
        Path bodyFile = cacheDir == null ? null : cacheDir.resolve(safeKey + ".json");
        Path metaFile = cacheDir == null ? null : cacheDir.resolve(safeKey + ".json.meta");

        IOException lastError = null;
        for (int attempt = 0; attempt < MAX_MIRROR_RETRIES; attempt++) {
            try {
                String body = tryOnce(apiUrl, bodyFile, metaFile);
                if (body != null) return body;
            } catch (IOException e) {
                lastError = e;
                // Read the mirror name *after* the failed call. tryOnce
                // populates the cached mirror via wrap(); reading the name
                // before would yield "unknown" on retries (cache was just
                // invalidated by the previous loop iteration), and the
                // actual broken mirror would never get blacklisted.
                String failedMirror = mirror.currentMirrorName();
                Logger.LOG.warning("GitHub fetch attempt " + (attempt + 1) + " failed via mirror "
                        + failedMirror + ": " + e.getMessage());
                // Blacklist + invalidate so the next attempt picks a fresh
                // mirror instead of looping back to the same broken one.
                mirror.blacklist(failedMirror);
                mirror.invalidate();
            }
        }
        // Out of retries — fall back to cached copy if any.
        String fallback = readCachedBody(bodyFile);
        if (fallback != null) {
            Logger.LOG.warning("All mirrors failed for " + apiUrl + ", serving cached " + safeKey);
            return fallback;
        }
        if (lastError != null) throw lastError;
        throw new IOException("GitHub unreachable for " + apiUrl);
    }

    /** Single-attempt variant of fetchWithCache. Returns null on 304-no-cache. */
    private String tryOnce(String apiUrl, Path bodyFile, Path metaFile) throws IOException {
        Properties meta = readMeta(metaFile);
        String etag = meta.getProperty("etag");
        String lastModified = meta.getProperty("lastModified");

        String mirroredUrl = mirror.wrap(apiUrl);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(mirroredUrl))
                .GET()
                .timeout(READ_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Xenon-Launcher");
        if (bodyFile != null && Files.isRegularFile(bodyFile)) {
            if (etag != null) rb.header("If-None-Match", etag);
            if (lastModified != null) rb.header("If-Modified-Since", lastModified);
        }

        HttpResponse<String> resp;
        try {
            resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted contacting " + mirroredUrl, e);
        }

        int code = resp.statusCode();
        if (code == 304) {
            String cached = readCachedBody(bodyFile);
            return cached; // null is caller's signal to retry
        }
        if (code / 100 == 2) {
            String body = resp.body();
            if (bodyFile != null) {
                Files.createDirectories(bodyFile.getParent());
                Files.writeString(bodyFile, body, StandardCharsets.UTF_8);
                Properties out = new Properties();
                resp.headers().firstValue("ETag").ifPresent(v -> out.setProperty("etag", v));
                resp.headers().firstValue("Last-Modified").ifPresent(v -> out.setProperty("lastModified", v));
                writeMeta(metaFile, out);
            }
            return body;
        }
        // Treat 4xx/5xx as a mirror failure so the caller picks another one.
        throw new IOException("GitHub HTTP " + code + " for " + mirroredUrl);
    }

    private static String readCachedBody(Path bodyFile) {
        if (bodyFile == null || !Files.isRegularFile(bodyFile)) return null;
        try {
            return Files.readString(bodyFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static Properties readMeta(Path metaFile) {
        Properties p = new Properties();
        if (metaFile == null || !Files.isRegularFile(metaFile)) return p;
        try (InputStream in = Files.newInputStream(metaFile)) {
            p.load(in);
        } catch (IOException ignored) {
        }
        return p;
    }

    private static void writeMeta(Path metaFile, Properties props) throws IOException {
        if (metaFile == null) return;
        Path parent = metaFile.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (OutputStream out = Files.newOutputStream(metaFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            props.store(out, "Xenon GitHub release cache metadata");
        }
    }

    /** Resolve the cache file location for a given repo (for debugging). */
    public Path cacheFileFor(String ownerRepo) {
        if (cacheDir == null) return null;
        return cacheDir.resolve(ownerRepo.replace('/', '_') + ".json");
    }

    @SuppressWarnings("unused")
    private static Path resolvePath(String s) {
        return Paths.get(s);
    }
}
