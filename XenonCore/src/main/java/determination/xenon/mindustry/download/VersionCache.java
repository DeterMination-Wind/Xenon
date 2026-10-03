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
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.reflect.TypeToken;
import determination.xenon.mindustry.VersionVariant;
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Disk cache for {@link MindustryRemoteVersion} feeds, keyed by
 * {@link VersionVariant}.
 *
 * <p>Files live under {@code <caches>/mindustry/versions/<variant>.json}
 * as a Gson-encoded array. The cache is intentionally TTL-free: the UI
 * always primes the picker from cache for instant display, then kicks
 * off an async refresh that overwrites the file. So a stale cache is at
 * worst a one-frame staleness, never a stuck-forever staleness.</p>
 */
public final class VersionCache {

    private static final Type LIST_TYPE = new TypeToken<List<MindustryRemoteVersion>>() {}.getType();

    /// Parsed bundled snapshots, keyed by variant; parsed at most once.
    private static final Map<VersionVariant, List<MindustryRemoteVersion>> BUNDLED =
            new ConcurrentHashMap<>();

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(Instant.class,
                    (JsonSerializer<Instant>) (src, t, ctx) ->
                            src == null ? null : new JsonPrimitive(src.toString()))
            .registerTypeAdapter(Instant.class,
                    (JsonDeserializer<Instant>) (json, t, ctx) -> {
                        if (json == null || json.isJsonNull()) return null;
                        return Instant.parse(json.getAsString());
                    })
            .setPrettyPrinting()
            .create();

    private VersionCache() {
    }

    /**
     * @return the cached version list for {@code variant}, or an empty
     *         list when the cache is missing / unreadable / corrupt.
     */
    public static List<MindustryRemoteVersion> load(VersionVariant variant, Path cacheRoot) {
        Path file = fileFor(variant, cacheRoot);
        if (file == null || !Files.isRegularFile(file)) return Collections.emptyList();
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            List<MindustryRemoteVersion> parsed = GSON.fromJson(text, LIST_TYPE);
            return parsed != null ? parsed : Collections.emptyList();
        } catch (Exception e) {
            Logger.LOG.warning("VersionCache: failed to read " + file + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Atomically rewrite the cache file with {@code list}. Best-effort —
     * IO failures are logged and swallowed since the cache is just a
     * latency optimisation.
     */
    public static void save(VersionVariant variant, Path cacheRoot,
                            List<MindustryRemoteVersion> list) {
        Path file = fileFor(variant, cacheRoot);
        if (file == null || list == null) return;
        try {
            Files.createDirectories(file.getParent());
            String json = GSON.toJson(list, LIST_TYPE);
            Files.writeString(file, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            Logger.LOG.warning("VersionCache: failed to write " + file + ": " + e.getMessage());
        }
    }

    private static Path fileFor(VersionVariant variant, Path cacheRoot) {
        if (variant == null || cacheRoot == null) return null;
        return cacheRoot.resolve("mindustry").resolve("versions")
                .resolve(variant.name().toLowerCase(Locale.ROOT) + ".json");
    }

    /// Reads the version-list snapshot bundled with the launcher.
    ///
    /// Used when the network is unreachable and no on-disk cache exists yet,
    /// so a first offline start still shows a usable list of versions instead
    /// of an empty picker. Results are parsed once and memoised.
    ///
    /// @param variant release variant to load the snapshot for
    /// @return the bundled rows, or an empty list when no snapshot is shipped
    public static List<MindustryRemoteVersion> loadBundled(VersionVariant variant) {
        if (variant == null) {
            return Collections.emptyList();
        }
        return BUNDLED.computeIfAbsent(variant, key -> {
            String resource = "/assets/mindustry/versions-"
                    + key.name().toLowerCase(Locale.ROOT) + "-snapshot.json";
            try (InputStream in = VersionCache.class.getResourceAsStream(resource)) {
                if (in == null) {
                    return Collections.emptyList();
                }
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                List<MindustryRemoteVersion> parsed = GSON.fromJson(text, LIST_TYPE);
                return parsed != null ? List.copyOf(parsed) : Collections.emptyList();
            } catch (Exception e) {
                Logger.LOG.warning("VersionCache: failed to read bundled " + resource
                        + ": " + e.getMessage());
                return Collections.emptyList();
            }
        });
    }
}
