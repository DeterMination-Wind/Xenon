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
package determination.xenon.mindustry.mod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import determination.xenon.mindustry.download.MirrorSelector;
import determination.xenon.util.logging.Logger;

import java.io.IOException;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link MindustryModRepository} backed by the community-maintained
 * {@code Anuken/mindustry-mods} index. The index is a single JSON array
 * served from {@code raw.githubusercontent.com}; every entry roughly
 * follows the shape
 * <pre>
 * {
 *   "repo":"Anuken/ExampleJavaMod",
 *   "name":"example",
 *   "displayName":"Example Mod",
 *   "stars":12,
 *   "lastUpdated":"2026-01-02T03:04:05Z",
 *   "issues":3,
 *   "description":"...",
 *   "author":"Anuken",
 *   "dependencies":["..."],
 *   "icon":"https://..."
 * }
 * </pre>
 * but the schema drifts without warning — every field is parsed
 * defensively and missing or malformed fields fall back to defaults
 * rather than aborting the whole listing.
 *
 * <p>The raw response body is funnelled through {@link MirrorSelector}
 * so the request honours whichever GitHub mirror the launcher already
 * picked. Successful refreshes are persisted to
 * {@code <cacheRoot>/mods/community-index.json}; if the next refresh
 * fails the on-disk copy is replayed.</p>
 */
public final class MindustryModsIndexRepository extends MindustryModRepository {

    /** Upstream URL of the community index (Anuken/MindustryMods, master branch). */
    public static final String INDEX_URL =
            "https://raw.githubusercontent.com/Anuken/MindustryMods/master/mods.json";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final MirrorSelector mirror;
    private final Path cacheFile;

    /** Snapshot of the most recent successful refresh; never {@code null}. */
    private volatile List<MindustryRemoteMod> snapshot = Collections.emptyList();
    /** Lower-cased {@code name} -&gt; entry, kept in sync with {@link #snapshot}. */
    private volatile Map<String, MindustryRemoteMod> byName = Collections.emptyMap();

    /**
     * @param cacheRoot launcher caches root (e.g. {@code Metadata.getCachesDirectory()});
     *                  may be {@code null} to disable on-disk caching
     */
    public MindustryModsIndexRepository(Path cacheRoot) {
        this(cacheRoot, MirrorSelector.getInstance());
    }

    /** Visible for callers that want to inject a custom selector (tests, mocks). */
    public MindustryModsIndexRepository(Path cacheRoot, MirrorSelector mirror) {
        this.mirror = Objects.requireNonNull(mirror, "mirror");
        this.cacheFile = cacheRoot == null
                ? null
                : cacheRoot.resolve("mods").resolve("community-index.json");
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    @Override
    public List<MindustryRemoteMod> refresh() throws IOException {
        String body;
        IOException networkError = null;
        try {
            body = fetch();
        } catch (IOException e) {
            networkError = e;
            body = readCachedBody();
            if (body == null) throw e;
            Logger.LOG.warning("Failed to fetch mindustry-mods index, serving cached copy: " + e.getMessage());
        }

        List<MindustryRemoteMod> parsed;
        try {
            parsed = parse(body);
        } catch (JsonSyntaxException e) {
            // Upstream returned non-JSON (e.g. an HTML error page proxied
            // through a misbehaving mirror). Replay the cache if we have one.
            String cached = readCachedBody();
            if (cached != null && !cached.equals(body)) {
                Logger.LOG.warning("mindustry-mods index parse failed, replaying cache: " + e.getMessage());
                parsed = parse(cached);
            } else {
                throw new IOException("Malformed mindustry-mods index: " + e.getMessage(), e);
            }
        }

        // Persist on success — but only when we actually went to the network.
        if (networkError == null) {
            writeCache(body);
        }
        publish(parsed);
        return parsed;
    }

    @Override
    public Optional<MindustryRemoteMod> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String key = name.toLowerCase(Locale.ROOT);
        MindustryRemoteMod direct = byName.get(key);
        if (direct != null) return Optional.of(direct);
        // Fall back to repo match (case-insensitive); cheap because snapshots
        // are typically a few thousand entries.
        for (MindustryRemoteMod m : snapshot) {
            if (m.getRepo() != null && m.getRepo().equalsIgnoreCase(name)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /** Most recent snapshot without triggering a refresh. */
    public List<MindustryRemoteMod> currentSnapshot() {
        return snapshot;
    }

    /**
     * Load the on-disk cache synchronously without touching the network.
     * Returns an empty list if no cache exists. Safe to call from the FX
     * thread — the file is small (a few hundred KB) and reading it is
     * O(milliseconds).
     */
    public List<MindustryRemoteMod> loadCache() {
        String body = readCachedBody();
        if (body == null || body.isEmpty()) return Collections.emptyList();
        try {
            List<MindustryRemoteMod> parsed = parse(body);
            publish(parsed);
            return parsed;
        } catch (RuntimeException e) {
            Logger.LOG.warning("Failed to parse cached mod index: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ------------------------------------------------------------------
    // Networking
    // ------------------------------------------------------------------

    private String fetch() throws IOException {
        String url = mirror.wrap(INDEX_URL);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(READ_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "Xenon-Launcher")
                .build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted contacting " + url, e);
        }
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " from " + url);
        }
        return resp.body();
    }

    private String readCachedBody() {
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) return null;
        try {
            return Files.readString(cacheFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.LOG.warning("Failed to read cached mindustry-mods index: " + e.getMessage());
            return null;
        }
    }

    private void writeCache(String body) {
        if (cacheFile == null) return;
        try {
            Path parent = cacheFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(cacheFile, body, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            Logger.LOG.warning("Failed to persist mindustry-mods index cache: " + e.getMessage());
        }
    }

    private void publish(List<MindustryRemoteMod> parsed) {
        Map<String, MindustryRemoteMod> map = new HashMap<>(parsed.size() * 2);
        for (MindustryRemoteMod m : parsed) {
            if (m.getName() != null && !m.getName().isBlank()) {
                map.putIfAbsent(m.getName().toLowerCase(Locale.ROOT), m);
            }
        }
        this.snapshot = List.copyOf(parsed);
        this.byName = Collections.unmodifiableMap(map);
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /** Visible for tests / fixtures. */
    static List<MindustryRemoteMod> parse(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            // Some forks wrap the array in {"mods":[...]}.
            if (root.isJsonObject() && root.getAsJsonObject().has("mods")
                    && root.getAsJsonObject().get("mods").isJsonArray()) {
                root = root.getAsJsonObject().get("mods");
            } else {
                return Collections.emptyList();
            }
        }
        JsonArray arr = root.getAsJsonArray();
        List<MindustryRemoteMod> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (el == null || !el.isJsonObject()) continue;
            try {
                MindustryRemoteMod mod = parseEntry(el.getAsJsonObject());
                if (mod != null) out.add(mod);
            } catch (RuntimeException e) {
                Logger.LOG.log(System.Logger.Level.DEBUG,
                        "Skipping malformed mindustry-mods entry: " + e.getMessage());
            }
        }
        return out;
    }

    private static MindustryRemoteMod parseEntry(JsonObject obj) {
        String repo = string(obj, "repo");
        if (repo == null || repo.isBlank()) {
            // An entry without a repo is unusable — every install path
            // resolves through GitHub.
            return null;
        }
        String name = string(obj, "name");
        String displayName = string(obj, "displayName");
        String author = string(obj, "author");
        String description = string(obj, "description");
        String latestTag = firstNonBlank(string(obj, "latestTag"), string(obj, "version"));
        String downloadUrl = firstNonBlank(string(obj, "downloadUrl"), string(obj, "download"));
        int stars = intValue(obj, "stars", 0);
        int issues = intValue(obj, "issues", 0);
        String iconUrl = firstNonBlank(string(obj, "icon"), string(obj, "iconUrl"));
        Instant lastUpdated = instant(obj, "lastUpdated");
        List<String> dependencies = stringArray(obj, "dependencies");
        return new MindustryRemoteMod(name, displayName, author, description,
                repo, latestTag, downloadUrl, stars, issues, iconUrl,
                lastUpdated, dependencies);
    }

    private static String string(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        JsonElement el = obj.get(key);
        if (!el.isJsonPrimitive()) return null;
        try {
            String s = el.getAsString();
            return s == null ? null : s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static int intValue(JsonObject obj, String key, int def) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return def;
        JsonElement el = obj.get(key);
        if (!el.isJsonPrimitive()) return def;
        try {
            if (el.getAsJsonPrimitive().isNumber()) return el.getAsInt();
            String s = el.getAsString().trim();
            if (s.isEmpty()) return def;
            return Integer.parseInt(s);
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static Instant instant(JsonObject obj, String key) {
        String raw = string(obj, key);
        if (raw == null || raw.isBlank()) return null;
        // Common shapes: "2026-01-02T03:04:05Z" or "2026-01-02 03:04:05".
        String candidate = raw.contains(" ") && !raw.contains("T")
                ? raw.replace(' ', 'T')
                : raw;
        if (!candidate.endsWith("Z") && candidate.length() == 19) {
            candidate = candidate + "Z";
        }
        try {
            return Instant.parse(candidate);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static List<String> stringArray(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return Collections.emptyList();
        JsonElement el = obj.get(key);
        if (!el.isJsonArray()) return Collections.emptyList();
        JsonArray arr = el.getAsJsonArray();
        List<String> out = new ArrayList<>(arr.size());
        for (JsonElement e : arr) {
            if (e != null && e.isJsonPrimitive()) {
                try {
                    String s = e.getAsString();
                    if (s != null && !s.isBlank()) out.add(s);
                } catch (RuntimeException ignored) {
                }
            }
        }
        return out;
    }
}
