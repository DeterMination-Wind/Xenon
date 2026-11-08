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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the defensive parser behind the Xenon recommendation tab.
@NotNullByDefault
public final class MindustryModRankingTest {

    /// Parses weights, entries and the recommended order.
    @Test
    public void parsesRankedEntriesAndMethodology() {
        MindustryModRanking ranking = MindustryModRanking.parse("""
                {
                  "formatVersion": 1,
                  "generatedAt": "2026-11-08T04:00:00Z",
                  "methodology": {
                    "latestGameVersion": "160.7",
                    "weights": {
                      "popularity": 0.35,
                      "activity": 0.35,
                      "freshness": 0.20,
                      "compatibility": 0.10
                    },
                    "source": { "index": "https://example.test/index-full.json" }
                  },
                  "entries": [
                    {
                      "repo": "owner/second",
                      "rank": 2,
                      "score": 0.8,
                      "popularRank": 9,
                      "downloads": 2000,
                      "stars": 20,
                      "components": {
                        "popularity": 0.7,
                        "activity": 0.8,
                        "freshness": 0.9,
                        "compatibility": 1.0
                      },
                      "badges": ["official", "official", "latest-compatible"]
                    },
                    {
                      "repo": "owner/first",
                      "rank": 1,
                      "score": 0.9,
                      "popularRank": 3,
                      "downloads": 3000,
                      "stars": 30,
                      "components": {
                        "popularity": 0.8,
                        "activity": 0.9,
                        "freshness": 1.0,
                        "compatibility": 1.0
                      },
                      "badges": ["bundle"]
                    },
                    {
                      "repo": "owner/unranked",
                      "rank": 0,
                      "score": 0.6,
                      "popularRank": 12
                    }
                  ]
                }
                """);

        assertEquals(1, ranking.formatVersion());
        assertNotNull(ranking.generatedAt());
        assertEquals("160.7", ranking.methodology().latestGameVersion());
        assertEquals(0.35, ranking.methodology().popularityWeight(), 0.0001);
        assertEquals("https://example.test/index-full.json", ranking.methodology().source());
        assertTrue(ranking.hasRankedEntries());

        List<MindustryModRanking.Entry> entries = ranking.entries();
        assertEquals(3, entries.size());
        assertEquals("owner/first", entries.get(0).repo());
        assertEquals("owner/second", entries.get(1).repo());
        assertEquals("owner/unranked", entries.get(2).repo());
        assertEquals(0, entries.get(2).rank());

        MindustryModRanking.Entry first = entries.get(0);
        assertEquals(1, first.rank());
        assertEquals(0.9, first.score(), 0.0001);
        assertEquals(3, first.popularRank());
        assertEquals(3000, first.downloads());
        assertEquals(30, first.stars());
        assertEquals(0.8, first.components().popularity(), 0.0001);
        assertEquals(0.9, first.components().activity(), 0.0001);
        assertEquals(List.of("bundle"), first.badges());
    }

    /// Repo lookup is case-insensitive and ignores unknown or blank slugs.
    @Test
    public void looksUpReposCaseInsensitively() {
        MindustryModRanking ranking = MindustryModRanking.parse("""
                {
                  "entries": [
                    { "repo": "Owner/Mod", "rank": 1, "score": 1.0 }
                  ]
                }
                """);

        Optional<MindustryModRanking.Entry> hit = ranking.findByRepo("owner/mod");
        assertTrue(hit.isPresent());
        assertEquals(1, hit.orElseThrow().rank());
        assertFalse(ranking.findByRepo("owner/other").isPresent());
        assertFalse(ranking.findByRepo(null).isPresent());
        assertFalse(ranking.findByRepo("   ").isPresent());
    }

    /// Duplicate repos keep the better rank instead of the later duplicate.
    @Test
    public void keepsTheBetterDuplicate() {
        MindustryModRanking ranking = MindustryModRanking.parse("""
                {
                  "entries": [
                    { "repo": "owner/mod", "rank": 0, "score": 0.9 },
                    { "repo": "OWNER/MOD", "rank": 4, "score": 0.5 },
                    { "repo": "owner/mod", "rank": 7, "score": 0.4 }
                  ]
                }
                """);

        assertEquals(1, ranking.entries().size());
        assertEquals(4, ranking.findByRepo("owner/mod").orElseThrow().rank());
    }

    /// A non-object root and malformed entries degrade to an empty list.
    @Test
    public void ignoresMalformedInput() {
        assertEquals(0, MindustryModRanking.parse("[]").entries().size());
        MindustryModRanking parsed = MindustryModRanking.parse("""
                {
                  "entries": [
                    null,
                    "not-an-entry",
                    { "rank": 1 },
                    { "repo": "   ", "rank": 1 },
                    { "repo": "owner/mod", "rank": "bad", "score": "bad" }
                  ]
                }
                """);
        assertEquals(1, parsed.entries().size());
        assertEquals(0, parsed.entries().get(0).rank());
        assertEquals(0, parsed.entries().get(0).score(), 0.0001);
    }

    /// A syntactically invalid document is rejected by the JSON parser.
    @Test
    public void rejectsInvalidJson() {
        assertThrows(RuntimeException.class, () -> MindustryModRanking.parse("{"));
    }
}
