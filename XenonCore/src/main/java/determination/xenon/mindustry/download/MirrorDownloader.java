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
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

/**
 * Parallel-mirror downloader, port of the {@code github-mirror-downloader}
 * (CainBot) strategy:
 *
 * <ol>
 *   <li>{@code Range: bytes=0-0} probe every mirror in parallel (8s timeout).</li>
 *   <li>Keep mirrors whose latency is {@code < 1500ms} as the startup pool.</li>
 *   <li>Start parallel downloads on every survivor, each into its own temp file.</li>
 *   <li>After a 3s speed-test window, sample bytes/sec per stream.</li>
 *   <li>Keep the fastest 2, abort the rest, and let the first to
 *       finish win. We never abort solely because of low speed —
 *       a slow mirror is still better than no download.</li>
 *   <li>Cache the winning mirror (8h TTL) so the next download can skip
 *       most of the probing.</li>
 * </ol>
 *
 * <p>The class is self-contained — it does not delegate to
 * {@link MirrorSelector}, since that picks a single mirror and that
 * approach has empirically produced "0 B/s" stalls (one slow but
 * reachable mirror would still win the probe).</p>
 */
public final class MirrorDownloader {

    /**
     * Mirror prefix list (URL gets concatenated as
     * {@code <base>/<github-url>}, the same shape gh.tinylake.top accepts).
     * Order is informational only — the probe race decides who actually
     * gets used.
     */
    public static final List<String> MIRRORS = List.of(
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

    private final HttpClient http;
    private final Path cacheFile;

    public MirrorDownloader(Path cachesRoot) {
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(8))
                .build();
        this.cacheFile = cachesRoot == null
                ? null
                : cachesRoot.resolve("github").resolve("preferred-mirror.json");
    }

    /**
     * Download {@code githubUrl} (a canonical {@code https://github.com/...}
     * URL) into {@code target}, racing the mirror pool.
     *
     * @param expectedSize size hint for progress, or 0 if unknown
     * @param progress     {@code (read, total)} callback; may be null
     */
    public void download(String githubUrl, Path target, long expectedSize,
                         ProgressCallback progress) throws IOException {
        if (githubUrl == null || githubUrl.isEmpty()) {
            throw new IOException("Missing source URL");
        }
        Path parent = target.getParent();
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
                Files.deleteIfExists(target);
                Files.move(tmp, target);
                Logger.LOG.info("MirrorDownloader: direct (non-GitHub) won "
                        + target.getFileName());
                return;
            } finally {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }

        // 1. Probe + filter.
        List<Probe> survivors = probeAll(githubUrl);
        // Add direct origin so unrestricted networks aren't forced through a mirror.
        Probe direct = probeOnce("direct", githubUrl);
        if (direct != null) survivors.add(direct);
        survivors.sort(Comparator.comparingLong(p -> p.latencyMs));

        // Promote the cached preferred mirror to the front, if still valid.
        String preferred = readPreferredMirror();
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
        // Trim to the lowest-latency MAX_RACE_PARALLELISM and require <1500ms.
        List<Probe> startup = new ArrayList<>();
        for (Probe p : survivors) {
            if (p.latencyMs < MAX_STARTUP_LATENCY_MS) startup.add(p);
            if (startup.size() >= MAX_RACE_PARALLELISM) break;
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
            Files.deleteIfExists(target);
            Files.move(race.winnerTemp, target);
            // 3. Remember the winner so the next call gets a head start.
            if (race.winnerLabel.startsWith("mirror:")) {
                writePreferredMirror(race.winnerLabel.substring("mirror:".length()));
            }
            Logger.LOG.info("MirrorDownloader: " + race.winnerLabel + " won "
                    + target.getFileName());
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
    }

    // ------------------------------------------------------------------
    // Probing
    // ------------------------------------------------------------------

    private List<Probe> probeAll(String githubUrl) {
        List<CompletableFuture<Probe>> futures = new ArrayList<>(MIRRORS.size());
        for (String base : MIRRORS) {
            String trimmed = base.replaceAll("/+$", "");
            String url = trimmed + "/" + githubUrl;
            futures.add(CompletableFuture.supplyAsync(() -> probeOnce("mirror:" + trimmed, url)));
        }
        List<Probe> out = new ArrayList<>();
        for (CompletableFuture<Probe> f : futures) {
            try {
                Probe p = f.get(PROBE_TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS);
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

    private Probe probeOnce(String label, String url) {
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

    private RaceResult doRace(List<Probe> startup, Path tmpDir, long expectedSize,
                              ProgressCallback progress) throws IOException {
        List<RaceState> states = new ArrayList<>();
        long base = System.nanoTime();
        for (int i = 0; i < startup.size(); i++) {
            Probe p = startup.get(i);
            Path temp = tmpDir.resolve("part-" + i + "-"
                    + Long.toHexString(base ^ (i * 0x9E3779B97F4A7C15L)) + ".tmp");
            states.add(new RaceState(p, temp));
        }

        ConcurrentHashMap<RaceState, CompletableFuture<Void>> tasks = new ConcurrentHashMap<>();
        for (RaceState s : states) {
            tasks.put(s, CompletableFuture.runAsync(() -> downloadOne(s)));
        }

        long raceStart = System.currentTimeMillis();
        // Speed-test window: report combined progress, then prune to top-K.
        try {
            while (System.currentTimeMillis() - raceStart < SPEED_TEST_WINDOW_MS) {
                if (states.stream().anyMatch(s -> s.done)) break;
                if (states.stream().allMatch(s -> s.failed || s.aborted)) break;
                publishProgress(states, expectedSize, progress);
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // If something already finished, ship it.
        RaceState earlyWinner = states.stream()
                .filter(s -> s.done)
                .min(Comparator.comparingLong(s -> s.finishedAt))
                .orElse(null);
        if (earlyWinner != null) {
            abortLosers(states, earlyWinner);
            waitAll(tasks);
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
                RaceState winner = keep.stream()
                        .filter(s -> s.done)
                        .min(Comparator.comparingLong(x -> x.finishedAt))
                        .orElse(null);
                if (winner != null) {
                    abortLosers(states, winner);
                    waitAll(tasks);
                    return new RaceResult(true, winner.probe.label, winner.temp,
                            null, states);
                }
                if (keep.stream().allMatch(s -> s.failed || s.aborted)) {
                    waitAll(tasks);
                    Throwable err = keep.stream()
                            .map(s -> s.error)
                            .filter(java.util.Objects::nonNull)
                            .findFirst().orElse(null);
                    return new RaceResult(false, "", null,
                            err == null ? new IOException("Both finalists failed")
                                    : new IOException(err.getMessage(), err),
                            states);
                }
                publishProgress(states, expectedSize, progress);
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
                    if (s.aborted) throw new IOException("aborted");
                    out.write(buf, 0, n);
                    s.bytes.addAndGet(n);
                    // Feed the global download-speed counter so the top-left
                    // download indicator shows the real throughput. Only the
                    // winning stream's bytes count toward the user-visible
                    // file, but every byte we pull off the wire is real
                    // bandwidth, so we report all of them.
                    determination.xenon.task.FetchTask.recordDownloadedBytes(n);
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

    private void publishProgress(List<RaceState> states, long expectedSize,
                                 ProgressCallback progress) {
        if (progress == null) return;
        long max = 0;
        for (RaceState s : states) {
            long b = s.bytes.get();
            if (b > max) max = b;
        }
        progress.onProgress(max, expectedSize > 0 ? expectedSize : -1);
    }

    private long speedBps(RaceState s) {
        long elapsedMs = Math.max(1, System.currentTimeMillis() - s.startedAt);
        return s.bytes.get() * 1000 / elapsedMs;
    }

    private void abortLosers(List<RaceState> states, RaceState winner) {
        for (RaceState s : states) {
            if (s == winner) continue;
            s.aborted = true;
            closeQuietly(s);
        }
    }

    private void abortAll(List<RaceState> states) {
        for (RaceState s : states) {
            s.aborted = true;
            closeQuietly(s);
        }
    }

    private void closeQuietly(RaceState s) {
        InputStream in = s.in;
        if (in != null) {
            try { in.close(); } catch (IOException ignored) {}
        }
    }

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

    private String readPreferredMirror() {
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) return null;
        try {
            String text = Files.readString(cacheFile, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = new Gson().fromJson(text, Map.class);
            Object base = obj == null ? null : obj.get("base");
            Object exp = obj == null ? null : obj.get("expiresAt");
            if (!(base instanceof String) || !(exp instanceof Number)) return null;
            long expMs = ((Number) exp).longValue();
            if (expMs < Instant.now().toEpochMilli()) return null;
            return ((String) base).trim();
        } catch (Exception e) {
            return null;
        }
    }

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

    private static String summary(List<Probe> probes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < probes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(probes.get(i).label).append("=").append(probes.get(i).latencyMs).append("ms");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Internal types
    // ------------------------------------------------------------------

    private static final class Probe {
        final String label;
        final String url;
        final long latencyMs;
        Probe(String label, String url, long latencyMs) {
            this.label = label;
            this.url = url;
            this.latencyMs = latencyMs;
        }
    }

    private static final class RaceState {
        final Probe probe;
        final Path temp;
        final AtomicLong bytes = new AtomicLong();
        final long startedAt = System.currentTimeMillis();
        volatile InputStream in;
        volatile boolean done;
        volatile boolean failed;
        volatile boolean aborted;
        volatile long finishedAt;
        volatile Throwable error;
        RaceState(Probe probe, Path temp) {
            this.probe = probe;
            this.temp = temp;
        }
    }

    private static final class RaceResult {
        final boolean success;
        final String winnerLabel;
        final Path winnerTemp;
        final Throwable error;
        final List<RaceState> allStates;
        RaceResult(boolean success, String winnerLabel, Path winnerTemp,
                   Throwable error, List<RaceState> allStates) {
            this.success = success;
            this.winnerLabel = winnerLabel;
            this.winnerTemp = winnerTemp;
            this.error = error;
            this.allStates = allStates;
        }
    }

    // ------------------------------------------------------------------
    // Direct (non-GitHub) path
    // ------------------------------------------------------------------

    /**
     * Heuristic: is {@code url} a github.com / api.github.com /
     * raw.githubusercontent.com / codeload origin? If not, the mirror
     * prefix list can't proxy it and we have to hit it directly.
     */
    private static boolean isGithubOrigin(String url) {
        return url.startsWith("https://github.com/")
                || url.startsWith("http://github.com/")
                || url.startsWith("https://api.github.com/")
                || url.startsWith("https://raw.githubusercontent.com/")
                || url.startsWith("https://codeload.github.com/");
    }

    /** Stream {@code url} to {@code tmp} with progress + global-speed reporting. */
    private void downloadStream(String url, Path tmp, long expectedSize,
                                ProgressCallback progress) throws IOException {
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
                out.write(buf, 0, n);
                read += n;
                determination.xenon.task.FetchTask.recordDownloadedBytes(n);
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
