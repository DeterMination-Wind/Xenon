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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// Parsed snapshot of the Xenon recommendation ranking.
///
/// The ranking is generated from `MDTCopper/mindustry-data-index` by
/// `scripts/mod-ranking/generate.mjs` and served as a small JSON document.
/// Every field is parsed defensively: a malformed entry is skipped instead of
/// failing the whole snapshot.
///
/// Entries with `rank == 0` are present for the community-heat tab but are not
/// eligible for the recommendation list. The map is keyed by the lower-cased
/// `owner/repo` slug.
@NotNullByDefault
public final class MindustryModRanking {

    /// Format version this parser understands. Newer files are still parsed
    /// best-effort so an older launcher does not lose the whole list.
    public static final int FORMAT_VERSION = 1;

    private static final MindustryModRanking EMPTY = new MindustryModRanking(
            0, null, new Methodology(0, 0, 0, 0, null, null), List.of());

    private final int formatVersion;
    private final @Nullable Instant generatedAt;
    private final Methodology methodology;
    private final List<Entry> entries;
    private final Map<String, Entry> byRepo;

    /// Builds an immutable snapshot.
    ///
    /// @param formatVersion document format version, or `0` when absent
    /// @param generatedAt   generation timestamp, or `null`
    /// @param methodology   weight/source description, never `null`
    /// @param entries       parsed entries, already sorted and deduplicated
    private MindustryModRanking(int formatVersion,
                                @Nullable Instant generatedAt,
                                Methodology methodology,
                                List<Entry> entries) {
        this.formatVersion = formatVersion;
        this.generatedAt = generatedAt;
        this.methodology = Objects.requireNonNull(methodology, "methodology");
        this.entries = List.copyOf(entries);
        Map<String, Entry> map = new LinkedHashMap<>(entries.size() * 2);
        for (Entry entry : entries) {
            map.putIfAbsent(entry.repo().toLowerCase(Locale.ROOT), entry);
        }
        this.byRepo = Collections.unmodifiableMap(map);
    }

    /// Returns the empty snapshot used before the first successful load.
    public static MindustryModRanking empty() {
        return EMPTY;
    }

    /// Parses one ranking document.
    ///
    /// @param json raw JSON body
    /// @return a parsed snapshot; never `null`
    /// @throws com.google.gson.JsonSyntaxException when the body is not JSON
    public static MindustryModRanking parse(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            return EMPTY;
        }
        JsonObject object = root.getAsJsonObject();
        int formatVersion = intValue(object, "formatVersion", 0);
        Instant generatedAt = instant(object, "generatedAt");
        Methodology methodology = parseMethodology(object.get("methodology"));

        Map<String, Entry> parsed = new LinkedHashMap<>();
        JsonElement entriesElement = object.get("entries");
        if (entriesElement != null && entriesElement.isJsonArray()) {
            JsonArray array = entriesElement.getAsJsonArray();
            for (JsonElement element : array) {
                Entry entry = parseEntry(element);
                if (entry == null) continue;
                String key = entry.repo().toLowerCase(Locale.ROOT);
                Entry existing = parsed.get(key);
                if (existing == null || isBetter(entry, existing)) {
                    parsed.put(key, entry);
                }
            }
        }

        List<Entry> entries = new ArrayList<>(parsed.values());
        entries.sort(MindustryModRanking::compareEntries);
        return new MindustryModRanking(formatVersion, generatedAt, methodology, entries);
    }

    /// Document format version, or `0` when the field was missing.
    public int formatVersion() {
        return formatVersion;
    }

    /// Generation timestamp, or `null` when absent.
    public @Nullable Instant generatedAt() {
        return generatedAt;
    }

    /// Weight and source description used by the methodology dialog.
    public Methodology methodology() {
        return methodology;
    }

    /// Entries sorted by recommendation rank; unranked entries come last.
    public List<Entry> entries() {
        return entries;
    }

    /// Looks up one mod by its `owner/repo` slug.
    ///
    /// @param repo repository slug, possibly `null`
    /// @return the matching entry, or empty when the mod is not ranked
    public Optional<Entry> findByRepo(@Nullable String repo) {
        if (repo == null || repo.isBlank()) return Optional.empty();
        return Optional.ofNullable(byRepo.get(repo.toLowerCase(Locale.ROOT)));
    }

    /// Returns whether at least one entry has a recommendation rank.
    public boolean hasRankedEntries() {
        for (Entry entry : entries) {
            if (entry.rank() > 0) return true;
        }
        return false;
    }

    /// Ranks one entry before another: ranked first, then score, then repo.
    private static int compareEntries(Entry left, Entry right) {
        int leftRank = left.rank() > 0 ? left.rank() : Integer.MAX_VALUE;
        int rightRank = right.rank() > 0 ? right.rank() : Integer.MAX_VALUE;
        if (leftRank != rightRank) return Integer.compare(leftRank, rightRank);
        int score = Double.compare(right.score(), left.score());
        if (score != 0) return score;
        return left.repo().compareToIgnoreCase(right.repo());
    }

    /// Chooses the better duplicate: a real rank beats unranked, a lower rank
    /// wins, and a higher score breaks an unranked tie.
    private static boolean isBetter(Entry candidate, Entry current) {
        if (candidate.rank() > 0 && current.rank() == 0) return true;
        if (candidate.rank() == 0 && current.rank() > 0) return false;
        if (candidate.rank() > 0 && current.rank() > 0) {
            return candidate.rank() < current.rank();
        }
        return candidate.score() > current.score();
    }

    /// Parses one entry, returning `null` when the repo slug is unusable.
    private static @Nullable Entry parseEntry(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();
        String repo = string(object, "repo");
        if (repo == null || repo.isBlank()) return null;
        int rank = Math.max(0, intValue(object, "rank", 0));
        int popularRank = Math.max(0, intValue(object, "popularRank", 0));
        int downloads = Math.max(0, intValue(object, "downloads", 0));
        int stars = Math.max(0, intValue(object, "stars", 0));
        double score = doubleValue(object, "score", 0);
        Components components = parseComponents(object.get("components"));
        List<String> badges = stringArray(object, "badges");
        return new Entry(repo, rank, score, popularRank, downloads, stars, components, badges);
    }

    /// Parses the methodology object, falling back to zero weights.
    private static Methodology parseMethodology(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return new Methodology(0, 0, 0, 0, null, null);
        }
        JsonObject object = element.getAsJsonObject();
        JsonObject weights = object.has("weights") && object.get("weights").isJsonObject()
                ? object.getAsJsonObject("weights")
                : new JsonObject();
        String source = null;
        JsonElement sourceElement = object.get("source");
        if (sourceElement != null && sourceElement.isJsonObject()) {
            source = string(sourceElement.getAsJsonObject(), "index");
        } else if (sourceElement != null && sourceElement.isJsonPrimitive()) {
            source = string(object, "source");
        }
        return new Methodology(
                doubleValue(weights, "popularity", 0),
                doubleValue(weights, "activity", 0),
                doubleValue(weights, "freshness", 0),
                doubleValue(weights, "compatibility", 0),
                string(object, "latestGameVersion"),
                source);
    }

    /// Parses the four component scores; missing components become `0`.
    private static Components parseComponents(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return new Components(0, 0, 0, 0);
        }
        JsonObject object = element.getAsJsonObject();
        return new Components(
                doubleValue(object, "popularity", 0),
                doubleValue(object, "activity", 0),
                doubleValue(object, "freshness", 0),
                doubleValue(object, "compatibility", 0));
    }

    /// Reads a nullable string field.
    private static @Nullable String string(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return null;
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive()) return null;
        try {
            String value = element.getAsString();
            return value == null || value.isBlank() ? null : value;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /// Reads an integer field with a default for missing or malformed values.
    private static int intValue(JsonObject object, String key, int fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive()) return fallback;
        try {
            if (element.getAsJsonPrimitive().isNumber()) return element.getAsInt();
            return Integer.parseInt(element.getAsString().trim());
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /// Reads a double field with a default for missing or malformed values.
    private static double doubleValue(JsonObject object, String key, double fallback) {
        if (!object.has(key) || object.get(key).isJsonNull()) return fallback;
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive()) return fallback;
        try {
            double value = element.getAsDouble();
            return Double.isFinite(value) ? value : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /// Reads an ISO-8601 timestamp, returning `null` when unparsable.
    private static @Nullable Instant instant(JsonObject object, String key) {
        String raw = string(object, key);
        if (raw == null) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /// Reads a string array, dropping blanks and non-primitive values.
    private static List<String> stringArray(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) return List.of();
        JsonElement element = object.get(key);
        if (!element.isJsonArray()) return List.of();
        JsonArray array = element.getAsJsonArray();
        List<String> result = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item == null || !item.isJsonPrimitive()) continue;
            try {
                String value = item.getAsString();
                if (value != null && !value.isBlank()) result.add(value);
            } catch (RuntimeException ignored) {
                // Skip values that are not representable as strings.
            }
        }
        return List.copyOf(result);
    }

    /// One ranked mod.
    ///
    /// @param repo         `owner/repo` slug
    /// @param rank         recommendation rank, or `0` when ineligible
    /// @param score        weighted score in `[0, 1]`
    /// @param popularRank  community-heat rank, or `0` when unknown
    /// @param downloads    lifetime preferred-asset downloads
    /// @param stars        GitHub stars snapshot
    /// @param components   component scores used by the methodology dialog
    /// @param badges       stable badge keys (`official`, `bundle`, ...)
    @NotNullByDefault
    public record Entry(String repo,
                        int rank,
                        double score,
                        int popularRank,
                        int downloads,
                        int stars,
                        Components components,
                        List<String> badges) {

        /// Normalizes the repo slug and freezes the badge list.
        public Entry {
            repo = Objects.requireNonNull(repo, "repo").trim();
            if (repo.isEmpty()) throw new IllegalArgumentException("repo");
            components = components == null ? new Components(0, 0, 0, 0) : components;
            badges = badges == null ? List.of() : List.copyOf(badges);
        }
    }

    /// Component scores behind one entry.
    ///
    /// @param popularity    percentile blend of downloads and stars
    /// @param activity      percentile blend of release rate and commits
    /// @param freshness     release-recency percentile
    /// @param compatibility `1` when the mod covers the latest stable game
    @NotNullByDefault
    public record Components(double popularity,
                             double activity,
                             double freshness,
                             double compatibility) {
    }

    /// Human-readable description of the ranking weights and source.
    ///
    /// @param popularityWeight    weight of the popularity component
    /// @param activityWeight      weight of the maintenance component
    /// @param freshnessWeight     weight of the recency component
    /// @param compatibilityWeight weight of the compatibility component
    /// @param latestGameVersion   latest stable Mindustry version, or `null`
    /// @param source              upstream data index URL, or `null`
    @NotNullByDefault
    public record Methodology(double popularityWeight,
                              double activityWeight,
                              double freshnessWeight,
                              double compatibilityWeight,
                              @Nullable String latestGameVersion,
                              @Nullable String source) {
    }
}
