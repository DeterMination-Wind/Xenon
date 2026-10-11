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
package determination.xenon.util;

import determination.xenon.JavaFXLauncher;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Unit tests for the bounded image LRU cache.
///
/// A 10x10 image is estimated at exactly 400 bytes, which lets the tests
/// express budgets and evictions with readable numbers.
@NotNullByDefault
public final class ImageMemoryCacheTest {

    /// Starts the JavaFX toolkit once for the test class.
    @BeforeAll
    public static void startToolkit() {
        JavaFXLauncher.start();
        Assumptions.assumeTrue(JavaFXLauncher.isStarted(), "JavaFX toolkit unavailable");
    }

    /// Creates a blank image with the given pixel dimensions.
    private static Image image(int width, int height) {
        return new WritableImage(width, height);
    }

    /// A stored image is returned by its key; unknown keys miss.
    @Test
    public void hitAndMiss() {
        ImageMemoryCache cache = new ImageMemoryCache(1_000_000L, 8);
        assertNull(cache.get("missing"));

        Image stored = image(10, 10);
        cache.put("key", stored);

        assertSame(stored, cache.get("key"));
        assertNull(cache.get("other"));
        assertEquals(1, cache.size());
        assertEquals(400L, cache.estimatedBytes());
    }

    /// The least recently used entry is evicted when the byte budget overflows.
    @Test
    public void evictsLeastRecentlyUsed() {
        ImageMemoryCache cache = new ImageMemoryCache(800L, 8);
        Image first = image(10, 10);
        Image second = image(10, 10);
        Image third = image(10, 10);

        cache.put("first", first);
        cache.put("second", second);
        // Touch "first" so "second" becomes the least recently used entry.
        assertSame(first, cache.get("first"));
        cache.put("third", third);

        assertNull(cache.get("second"));
        assertSame(first, cache.get("first"));
        assertSame(third, cache.get("third"));
        assertEquals(2, cache.size());
        assertEquals(800L, cache.estimatedBytes());
    }

    /// The entry limit evicts even when the byte budget would allow more.
    @Test
    public void enforcesEntryLimit() {
        ImageMemoryCache cache = new ImageMemoryCache(1_000_000L, 2);
        Image first = image(10, 10);

        cache.put("first", first);
        cache.put("second", image(10, 10));
        cache.put("third", image(10, 10));

        assertNull(cache.get("first"));
        assertNotNull(cache.get("second"));
        assertNotNull(cache.get("third"));
        assertEquals(2, cache.size());
    }

    /// Replacing an entry keeps the entry count stable and updates the budget.
    @Test
    public void replacesExistingEntry() {
        ImageMemoryCache cache = new ImageMemoryCache(1_000_000L, 8);
        cache.put("key", image(10, 10));
        cache.put("key", image(20, 20));

        assertEquals(1, cache.size());
        assertEquals(1600L, cache.estimatedBytes());
    }

    /// Clearing drops every entry and resets the byte accounting.
    @Test
    public void clearDropsEverything() {
        ImageMemoryCache cache = new ImageMemoryCache(1_000_000L, 8);
        cache.put("first", image(10, 10));
        cache.put("second", image(10, 10));

        cache.clear();

        assertEquals(0, cache.size());
        assertEquals(0L, cache.estimatedBytes());
        assertNull(cache.get("first"));
        assertNull(cache.get("second"));
    }

    /// An entry larger than the whole budget is not retained.
    @Test
    public void oversizedEntryIsNotRetained() {
        ImageMemoryCache cache = new ImageMemoryCache(400L, 8);
        Image small = image(10, 10);
        Image oversized = image(20, 20);

        cache.put("small", small);
        cache.put("oversized", oversized);

        assertNull(cache.get("small"));
        assertNull(cache.get("oversized"));
        assertEquals(0, cache.size());
        assertEquals(0L, cache.estimatedBytes());
    }

    /// Invalid constructor limits are rejected.
    @Test
    public void rejectsInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> new ImageMemoryCache(0L, 8));
        assertThrows(IllegalArgumentException.class, () -> new ImageMemoryCache(1024L, 0));
    }
}
