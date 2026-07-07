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
import determination.xenon.task.FetchTask;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/// Parallel-mirror downloader, port of the `github-mirror-downloader`
/// (CainBot) strategy.
///
/// The downloader probes mirrors with `Range: bytes=0-0`, races the
/// responsive candidates, keeps the fastest finalists, and moves the first
/// complete temp file into place. It reports progress as final-file progress,
/// while global speed accounting uses every HTTP body byte actually read from
/// every active racing stream.
@NotNullByDefault
public final class MirrorDownloader {

    /// Mirror prefix list. Each request URL is built as `<base>/<github-url>`.
    public static final @Unmodifiable List<String> MIRRORS = List.of(
            "https://gh.tinylake.top",
            "https://github.chenc.dev",
            "https://ghproxy.cfd",
            "https://github.tbedu.top",
            "https://ghproxy.cc",
            "https://gh.monlor.com",
            "https://cdn.akaere.online",
            "https://gh.idayer.com",
            "https://gh.llkk.cc",
            "https://ghpxy.hwinzniej.top",
            "https://github-proxy.memory-echoes.cn",
            "https://git.yylx.win",
            "https://gitproxy.mrhjx.cn",
            "https://gh.fhjhy.top",
            "https://gp.zkitefly.eu.org",
            "https://gh-proxy.com",
            "https://ghfile.geekertao.top",
            "https://ghproxy.imciel.com",
            "https://github-proxy.teach-english.tech",
            "https://gh.927223.xyz",
            "https://github.ednovas.xyz",
            "https://gh.dpik.top",
            "https://gh.jasonzeng.dev",
            "https://gh.xxooo.cf",
            "https://ghm.078465.xyz",
            "https://tvv.tw",
            "https://gitproxy.127731.xyz",
            "https://ghproxy.cxkpro.top",
            "https://gh.sixyin.com",
            "https://github.geekery.cn",
            "https://git.669966.xyz",
            "https://gh.5050net.cn",
            "https://gh.felicity.ac.cn",
            "https://github.dpik.top",
            "https://ghp.keleyaa.com",
            "https://ghproxy.monkeyray.net",
            "https://fastgit.cc",
            "https://gh.catmak.name"
    );

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(8);
    private static final long MAX_STARTUP_LATENCY_MS = 1500;
    private static final long SPEED_TEST_WINDOW_MS = 3000;
    private static final int KEEP_FASTEST = 2;
    private static final long PREFERRED_MIRROR_TTL_MS = 8L * 60 * 60 * 1000;
    /** Limit how many simultaneous downloads we race so we don't murder the user's NIC. */
    private static final int MAX_RACE_PARALLELISM = 6;
    /** Maximum number of retry attempts after a failed download race. */
    private static final int MAX_RETRIES = 3;

    /// HTTP client used for probe and payload requests.
    private final HttpClient http;
    /// Preferred-mirror cache file, or `null` when cache persistence is disabled.
    private final @Nullable Path cacheFile;
    /// Mirror bases used by this downloader instance.
    private final @Unmodifiable List<String> mirrors;
    /// Whether direct `github.com` should be added to each race.
    private final boolean includeDirectOrigin;
    /// Duration of the initial mirror speed-test race.
    private final long speedTestWindowMs;

    /// Creates a downloader that uses the production mirror pool.
    public MirrorDownloader(@Nullable Path cachesRoot) {
        this(cachesRoot, createHttpClient(), MIRRORS, true, SPEED_TEST_WINDOW_MS);
    }

    /// Creates a downloader with an injected mirror pool for local HTTP tests.
    MirrorDownloader(@Nullable Path cachesRoot,
                     @Unmodifiable List<String> mirrors,
                     boolean includeDirectOrigin,
                     long speedTestWindowMs) {
        this(cachesRoot, createHttpClient(), mirrors, includeDirectOrigin, speedTestWindowMs);
    }

    /// Creates a downloader with injected transport and mirror configuration.
    MirrorDownloader(@Nullable Path cachesRoot,
                     HttpClient http,
                     @Unmodifiable List<String> mirrors,
                     boolean includeDirectOrigin,
                     long speedTestWindowMs) {
        this.http = http;
        this.cacheFile = cachesRoot == null
                ? null
                : cachesRoot.resolve("github").resolve("preferred-mirror.json");
        this.mirrors = List.copyOf(mirrors);
        this.includeDirectOrigin = includeDirectOrigin;
        this.speedTestWindowMs = speedTestWindowMs;
    }

    /// Builds the default HTTP client for mirror downloads.
    private static HttpClient createHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(8))
                .proxy(ProxySelector.getDefault())
                .build();
    }

    /// Downloads a canonical GitHub URL into `target`, racing the mirror pool.
    ///
    /// `expectedSize` is a size hint for progress, or `0` when unknown.
    /// `progress` receives `(read, total)` and may be `null`.
    public void download(String githubUrl, Path target, long expectedSize,
                         @Nullable ProgressCallback progress) throws IOException {
        if (githubUrl == null || githubUrl.isEmpty()) {
            throw new IOException("Missing source URL");
        }
        @Nullable Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);

        // Non-GitHub origins (alist mirror, mindustry.top static asset, …)
        // can't be wrapped with the mirror prefix list. Hit them directly.
        if (!isGithubOrigin(githubUrl)) {
            Path tmp = target.getParent() == null
                    ? Path.of(System.getProperty("java.io.tmpdir", "."))
                            .resolve(target.getFileName().toString() + ".part")
                    : target.getParent().resolve("_xenon_dl")
                            .resolve(target.getFileName().toString() + ".part");
            Files.createDirectories(tmp.getParent());
            try {
                downloadStream(githubUrl, tmp, expectedSize, progress);
                deleteWithRetry(target);
                Files.move(tmp, target);
                Logger.LOG.info("MirrorDownloader: direct (non-GitHub) won "
                        + target.getFileName());
                return;
            } finally {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }

        // Retry loop: re-probe + re-race on failure, with exponential backoff.
        @Nullable IOException lastError = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                long backoffMs = 1000L << (attempt - 1); // 1s, 2s
                Logger.LOG.info("MirrorDownloader: retrying download (attempt "
                        + (attempt + 1) + "/" + MAX_RETRIES
                        + ") after " + backoffMs + "ms backoff");
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Download interrupted during retry backoff", ie);
                }
            }

            try {
                // 1. Probe + filter.
                List<Probe> survivors = probeAll(githubUrl);
                // Add direct origin so unrestricted networks aren't forced through a mirror.
                if (includeDirectOrigin) {
                    @Nullable Probe direct = probeOnce("direct", githubUrl);
                    if (direct != null) survivors.add(direct);
                }
                survivors.sort(Comparator.comparingLong(p -> p.latencyMs));

                // Promote the cached preferred mirror to the front, if still valid.
                @Nullable String preferred = readPreferredMirror();
                if (preferred != null) {
                    for (int i = 0; i < survivors.size(); i++) {
                        if (survivors.get(i).label.equals("mirror:" + preferred)) {
                            Probe p = survivors.remove(i);
                            survivors.add(0, p);
                            Logger.LOG.info("MirrorDownloader: cached preferred mirror "
                                    + preferred + " (latency=" + p.latencyMs + "ms) reused");
                            break;
                        }
                    }
                }

                if (survivors.isEmpty()) {
                    throw new IOException("No reachable mirrors for " + githubUrl);
                }
                // Trim to the configured download-thread budget and require <1500ms.
                int raceParallelism = Math.max(1, Math.min(MAX_RACE_PARALLELISM,
                        FetchTask.getDownloadExecutorConcurrency()));
                List<Probe> startup = new ArrayList<>();
                for (Probe p : survivors) {
                    if (p.latencyMs < MAX_STARTUP_LATENCY_MS) startup.add(p);
                    if (startup.size() >= raceParallelism) break;
                }
                if (startup.isEmpty()) {
                    // Fall back to the single fastest one even if it's slow — better
                    // than failing outright.
                    startup.add(survivors.get(0));
                }
                Logger.LOG.info("MirrorDownloader: racing " + startup.size()
                        + " mirrors for " + githubUrl + " — "
                        + summary(startup));

                // 2. Race them.
                Path tmpDir = target.getParent() == null
                        ? Path.of(System.getProperty("java.io.tmpdir", "."))
                        : target.getParent().resolve("_xenon_dl");
                Files.createDirectories(tmpDir);

                RaceResult race = doRace(startup, tmpDir, expectedSize, progress);
                try {
                    if (!race.success) {
                        throw new IOException(race.error == null ? "All mirrors failed"
                                : race.error.getMessage());
                    }
                    // Move the winning temp file to the final target.
                    // On Windows, file may be briefly locked by antivirus — retry.
                    deleteWithRetry(target);
                    Files.move(java.util.Objects.requireNonNull(race.winnerTemp), target);
                    // 3. Remember the winner so the next call gets a head start.
                    if (race.winnerLabel.startsWith("mirror:")) {
                        writePreferredMirror(race.winnerLabel.substring("mirror:".length()));
                    }
                    Logger.LOG.info("MirrorDownloader: " + race.winnerLabel + " won "
                            + target.getFileName());
                    return; // success — exit retry loop
                } finally {
                    // Best-effort cleanup of leftover temp files.
                    for (RaceState s : race.allStates) {
                        try {
                            if (race.winnerTemp == null || !s.temp.equals(race.winnerTemp)) {
                                Files.deleteIfExists(s.temp);
                            }
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (IOException e) {
                lastError = e;
                Logger.LOG.warning("MirrorDownloader: download attempt "
                        + (attempt + 1) + "/" + MAX_RETRIES
                        + " failed: " + e.getMessage());
            }
        }
        // All retries exhausted — throw with a user-friendly message.
        throw new IOException(friendlyMessage(lastError), lastError);
    }

    // ------------------------------------------------------------------
    // Probing
    // ------------------------------------------------------------------

    /// Probes all configured mirror bases for one GitHub URL.
    private List<Probe> probeAll(String githubUrl) {
        List<CompletableFuture<@Nullable Probe>> futures = new ArrayList<>(mirrors.size());
        for (String base : mirrors) {
            String trimmed = base.replaceAll("/+$", "");
            String url = trimmed + "/" + githubUrl;
            futures.add(CompletableFuture.supplyAsync(() -> probeOnce("mirror:" + trimmed, url)));
        }
        List<Probe> out = new ArrayList<>();
        for (CompletableFuture<@Nullable Probe> f : futures) {
            try {
                @Nullable Probe p = f.get(PROBE_TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
                if (p != null) out.add(p);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException ignored) {
                // probe failed/timed out, skip
            }
        }
        return out;
    }

    /// Probes one concrete URL and returns its latency when it is usable.
    private @Nullable Probe probeOnce(String label, String url) {
        long start = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(PROBE_TIMEOUT)
                    .header("Range", "bytes=0-0")
                    .header("User-Agent", "Xenon-Launcher")
                    .build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                in.transferTo(OutputStream.nullOutputStream());
            }
            int code = resp.statusCode();
            // 200 or 206 (partial content) are both fine.
            if (code != 200 && code != 206) {
                return null;
            }
            return new Probe(label, url, System.currentTimeMillis() - start);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Race
    // ------------------------------------------------------------------

    /// Races startup mirror streams and returns the first complete winner.
    private RaceResult doRace(List<Probe> startup, Path tmpDir, long expectedSize,
                              @Nullable ProgressCallback progress) throws IOException {
        List<RaceState> states = new ArrayList<>();
        long base = System.nanoTime();
        for (int i = 0; i < startup.size(); i++) {
            Probe p = startup.get(i);
            Path temp = tmpDir.resolve("part-" + i + "-"
                    + Long.toHexString(base ^ (i * 0x9E3779B97F4A7C15L)) + ".tmp");
            states.add(new RaceState(p, temp));
        }
        ProgressTracker tracker = new ProgressTracker(expectedSize, progress);

        ConcurrentHashMap<RaceState, CompletableFuture<Void>> tasks = new ConcurrentHashMap<>();
        for (RaceState s : states) {
            tasks.put(s, CompletableFuture.runAsync(() -> downloadOne(s)));
        }

        long raceStart = System.currentTimeMillis();
        // Speed-test window: report combined progress, then prune to top-K.
        try {
            while (System.currentTimeMillis() - raceStart < speedTestWindowMs) {
                if (states.stream().anyMatch(s -> s.done)) break;
                if (states.stream().allMatch(s -> s.failed || s.aborted)) break;
                tracker.publish(states);
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // If something already finished, ship it.
        @Nullable RaceState earlyWinner = states.stream()
                .filter(s -> s.done)
                .min(Comparator.comparingLong(s -> s.finishedAt))
                .orElse(null);
        if (earlyWinner != null) {
            abortLosers(states, earlyWinner);
            waitAll(tasks);
            tracker.finish(earlyWinner);
            return new RaceResult(true, earlyWinner.probe.label, earlyWinner.temp,
                    null, states);
        }

        // Compute speeds. We no longer fail if all mirrors are "slow" —
        // a 200 KiB/s download still beats throwing the user back to a
        // dialog. Just rank by current bytes/sec and keep the top K.
        states.sort(Comparator.comparingLong(this::speedBps).reversed());

        // Keep the fastest K, abort the rest.
        List<RaceState> keep = new ArrayList<>(states.subList(0, Math.min(KEEP_FASTEST, states.size())));
        Logger.LOG.info("MirrorDownloader: speed-test passed, keeping "
                + keep.stream().map(s -> s.probe.label + "=" + (speedBps(s) / 1024) + "KiB/s")
                        .reduce((a, b) -> a + " | " + b).orElse(""));
        for (RaceState s : states) {
            if (!keep.contains(s) && !s.done) {
                s.aborted = true;
                closeQuietly(s);
            }
        }

        // Wait for the first survivor to finish.
        try {
            while (true) {
                @Nullable RaceState winner = keep.stream()
                        .filter(s -> s.done)
                        .min(Comparator.comparingLong(x -> x.finishedAt))
                        .orElse(null);
                if (winner != null) {
                    abortLosers(states, winner);
                    waitAll(tasks);
                    tracker.finish(winner);
                    return new RaceResult(true, winner.probe.label, winner.temp,
                            null, states);
                }
                if (keep.stream().allMatch(s -> s.failed || s.aborted)) {
                    waitAll(tasks);
                    @Nullable Throwable err = keep.stream()
                            .map(s -> s.error)
                            .filter(java.util.Objects::nonNull)
                            .findFirst().orElse(null);
                    return new RaceResult(false, "", null,
                            err == null ? new IOException("Both finalists failed")
                                    : new IOException(err.getMessage(), err),
                            states);
                }
                tracker.publish(states);
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abortAll(states);
            waitAll(tasks);
            return new RaceResult(false, "", null,
                    new IOException("Interrupted"), states);
        }
    }

    /// Downloads one racing candidate into its temp file.
    private void downloadOne(RaceState s) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(s.probe.url))
                    .GET()
                    .timeout(Duration.ofMinutes(10))
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "Xenon-Launcher")
                    .build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                try (InputStream drain = resp.body()) { drain.transferTo(OutputStream.nullOutputStream()); }
                throw new IOException("HTTP " + resp.statusCode());
            }
            s.in = resp.body();
            try (InputStream in = s.in;
                 OutputStream out = Files.newOutputStream(s.temp,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    FetchTask.recordDownloadedBytes(n);
                    if (s.aborted) throw new IOException("aborted");
                    out.write(buf, 0, n);
                    s.bytes.addAndGet(n);
                }
            }
            s.finishedAt = System.currentTimeMillis();
            s.done = true;
        } catch (Exception e) {
            if (!s.aborted) {
                s.failed = true;
                s.error = e;
                Logger.LOG.warning("MirrorDownloader: " + s.probe.label
                        + " failed (" + e.getMessage() + ")");
            }
            try { Files.deleteIfExists(s.temp); } catch (IOException ignored) {}
        }
    }

    /// Returns the largest final-file progress across all racing streams.
    private static long maxBytes(List<RaceState> states) {
        long max = 0;
        for (RaceState s : states) {
            long b = s.bytes.get();
            if (b > max) max = b;
        }
        return max;
    }

    /// Estimates one racing stream's average bytes per second.
    private long speedBps(RaceState s) {
        long elapsedMs = Math.max(1, System.currentTimeMillis() - s.startedAt);
        return s.bytes.get() * 1000 / elapsedMs;
    }

    /// Aborts every racing stream except the winner.
    private void abortLosers(List<RaceState> states, RaceState winner) {
        for (RaceState s : states) {
            if (s == winner) continue;
            s.aborted = true;
            closeQuietly(s);
        }
    }

    /// Aborts every racing stream.
    private void abortAll(List<RaceState> states) {
        for (RaceState s : states) {
            s.aborted = true;
            closeQuietly(s);
        }
    }

    /// Closes the active response body for one racing stream if present.
    private void closeQuietly(RaceState s) {
        @Nullable InputStream in = s.in;
        if (in != null) {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

    /// Waits briefly for racing tasks to observe aborts and close temp files.
    private void waitAll(Map<RaceState, CompletableFuture<Void>> tasks) {
        try {
            CompletableFuture.allOf(tasks.values().toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Cache
    // ------------------------------------------------------------------

    /// Reads the preferred mirror from disk if the cache entry is still valid.
    private @Nullable String readPreferredMirror() {
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) return null;
        try {
            String text = Files.readString(cacheFile, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = new Gson().fromJson(text, Map.class);
            @Nullable Object base = obj == null ? null : obj.get("base");
            @Nullable Object exp = obj == null ? null : obj.get("expiresAt");
            if (!(base instanceof String) || !(exp instanceof Number)) return null;
            long expMs = ((Number) exp).longValue();
            if (expMs < Instant.now().toEpochMilli()) return null;
            return ((String) base).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /// Persists the winning mirror for later downloads.
    private void writePreferredMirror(String base) {
        if (cacheFile == null || base == null || base.isEmpty()) return;
        try {
            Files.createDirectories(cacheFile.getParent());
            Map<String, Object> obj = Map.of(
                    "base", base,
                    "expiresAt", Instant.now().toEpochMilli() + PREFERRED_MIRROR_TTL_MS);
            Files.writeString(cacheFile, new Gson().toJson(obj), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            Logger.LOG.warning("MirrorDownloader: failed to cache preferred mirror: " + e.getMessage());
        }
    }

    /// Deletes a file with retry logic for brief Windows file-locking issues.
    private static void deleteWithRetry(Path file) throws IOException {
        for (int i = 0; i < 5; i++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (java.nio.file.FileSystemException e) {
                if (i < 4) {
                    try { Thread.sleep(200 * (i + 1)); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during file delete retry", ie);
                    }
                } else {
                    throw e;
                }
            }
        }
    }

    /// Formats probe latency results for logging.
    private static String summary(List<Probe> probes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < probes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(probes.get(i).label).append("=").append(probes.get(i).latencyMs).append("ms");
        }
        return sb.toString();
    }

    /// Translates a technical exception into a user-facing Chinese message.
    private static String friendlyMessage(@Nullable IOException e) {
        if (e == null) {
            return "所有镜像源不可用，请稍后重试";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "下载超时，请稍后重试";
        }
        if (e instanceof java.net.ConnectException) {
            return "网络连接失败，请检查网络设置";
        }
        @Nullable String msg = e.getMessage();
        if (msg != null && msg.contains("No reachable mirrors")) {
            return "所有镜像源不可用，请稍后重试";
        }
        return "下载失败: " + msg;
    }

    // ------------------------------------------------------------------
    // Internal types
    // ------------------------------------------------------------------

    /// Probe result for one concrete download URL.
    private static final class Probe {
        /// Human-readable source label used in logs.
        final String label;
        /// Concrete URL that should be downloaded if this probe survives.
        final String url;
        /// Probe latency in milliseconds.
        final long latencyMs;
        /// Creates a probe result.
        Probe(String label, String url, long latencyMs) {
            this.label = label;
            this.url = url;
            this.latencyMs = latencyMs;
        }
    }

    /// Mutable state for one active racing download.
    private static final class RaceState {
        /// Probe metadata for the stream.
        final Probe probe;
        /// Temporary file written by this stream.
        final Path temp;
        /// Bytes written to the temp file and eligible for final-file progress.
        final AtomicLong bytes = new AtomicLong();
        /// Stream start time for speed ranking.
        final long startedAt = System.currentTimeMillis();
        /// Active response body, if the request has reached body streaming.
        volatile @Nullable InputStream in;
        /// Whether this stream completed successfully.
        volatile boolean done;
        /// Whether this stream failed without being intentionally aborted.
        volatile boolean failed;
        /// Whether this stream should stop as soon as possible.
        volatile boolean aborted;
        /// Completion timestamp used to pick the earliest winner.
        volatile long finishedAt;
        /// Failure cause when `failed` is true.
        volatile @Nullable Throwable error;
        /// Creates racing state for one probe.
        RaceState(Probe probe, Path temp) {
            this.probe = probe;
            this.temp = temp;
        }
    }

    /// Result returned after a mirror race ends.
    private static final class RaceResult {
        /// Whether a complete winner was produced.
        final boolean success;
        /// Label of the winning mirror or source.
        final String winnerLabel;
        /// Temporary file containing the winner payload, or `null` on failure.
        final @Nullable Path winnerTemp;
        /// Failure cause when no stream completed.
        final @Nullable Throwable error;
        /// All stream states for cleanup and diagnostics.
        final List<RaceState> allStates;
        /// Creates a race result.
        RaceResult(boolean success, String winnerLabel, @Nullable Path winnerTemp,
                   @Nullable Throwable error, List<RaceState> allStates) {
            this.success = success;
            this.winnerLabel = winnerLabel;
            this.winnerTemp = winnerTemp;
            this.error = error;
            this.allStates = allStates;
        }
    }

    /// Publishes final-file progress without counting duplicate racing bytes.
    private static final class ProgressTracker {
        /// Expected final file size, or `0` when unknown.
        final long expectedSize;
        /// Optional progress callback.
        final @Nullable ProgressCallback progress;

        /// Creates a tracker for one download race.
        ProgressTracker(long expectedSize, @Nullable ProgressCallback progress) {
            this.expectedSize = expectedSize;
            this.progress = progress;
        }

        /// Publishes the largest final-file progress currently available.
        void publish(List<RaceState> states) {
            report(maxBytes(states));
        }

        /// Publishes final progress from the winning stream.
        void finish(RaceState winner) {
            report(winner.bytes.get());
        }

        /// Sends a bounded progress callback.
        private void report(long bytes) {
            long effective = expectedSize > 0 ? Math.min(bytes, expectedSize) : bytes;
            if (progress != null) {
                progress.onProgress(effective, expectedSize > 0 ? expectedSize : -1);
            }
        }
    }

    // ------------------------------------------------------------------
    // Direct (non-GitHub) path
    // ------------------------------------------------------------------

    /// Returns whether a URL can be wrapped by the GitHub mirror prefix list.
    private static boolean isGithubOrigin(String url) {
        return url.startsWith("https://github.com/")
                || url.startsWith("http://github.com/")
                || url.startsWith("https://api.github.com/")
                || url.startsWith("https://raw.githubusercontent.com/")
                || url.startsWith("https://codeload.github.com/");
    }

    /// Streams a non-GitHub URL directly to a temp file.
    private void downloadStream(String url, Path tmp, long expectedSize,
                                @Nullable ProgressCallback progress) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofMinutes(10))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "Xenon-Launcher")
                .build();
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading " + url, e);
        }
        if (resp.statusCode() / 100 != 2) {
            try (InputStream drain = resp.body()) {
                drain.transferTo(OutputStream.nullOutputStream());
            }
            throw new IOException("HTTP " + resp.statusCode() + " from " + url);
        }
        long total = resp.headers().firstValueAsLong("Content-Length")
                .orElse(expectedSize > 0 ? expectedSize : -1);
        try (InputStream in = resp.body();
             OutputStream out = Files.newOutputStream(tmp,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            byte[] buf = new byte[64 * 1024];
            long read = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                FetchTask.recordDownloadedBytes(n);
                out.write(buf, 0, n);
                read += n;
                if (progress != null) {
                    progress.onProgress(read, total);
                }
            }
            if (read == 0) {
                throw new IOException("Empty response body from " + url);
            }
        }
    }
}
