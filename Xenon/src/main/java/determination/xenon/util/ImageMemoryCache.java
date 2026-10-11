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

import javafx.scene.image.Image;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// Bounded, thread-safe LRU cache for decoded JavaFX images.
///
/// Every entry is charged against a byte budget using its estimated ARGB
/// pixel footprint (`width * height * 4`), so browsing an endless list can no
/// longer grow the retained image set without bound. Once either the byte
/// budget or the entry limit is exceeded, least-recently-used entries are
/// dropped until the cache fits again.
///
/// The cache is safe to use from background threads; all mutating methods are
/// synchronized. Images that failed to decode or have no pixels yet are
/// rejected instead of cached, because they cannot be accounted for.
@NotNullByDefault
public final class ImageMemoryCache {
    /// Default byte budget (96 MiB) for the shared cache.
    public static final long DEFAULT_MAX_BYTES = 96L * 1024L * 1024L;
    /// Default entry limit for the shared cache.
    public static final int DEFAULT_MAX_ENTRIES = 128;

    /// Shared cache used by the launcher image loaders.
    private static final ImageMemoryCache SHARED =
            new ImageMemoryCache(DEFAULT_MAX_BYTES, DEFAULT_MAX_ENTRIES);

    /// Maximum number of estimated bytes this cache may retain.
    private final long maxBytes;
    /// Maximum number of entries this cache may retain.
    private final int maxEntries;
    /// Access-ordered entry map; iteration starts at the least recently used entry.
    private final LinkedHashMap<String, Image> entries = new LinkedHashMap<>(16, 0.75f, true);
    /// Sum of [#estimateBytes(Image)] over all entries.
    private long bytes;

    /// Creates a cache with explicit limits.
    ///
    /// @param maxBytes   positive byte budget
    /// @param maxEntries positive entry limit
    /// @throws IllegalArgumentException when either limit is not positive
    public ImageMemoryCache(long maxBytes, int maxEntries) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive: " + maxEntries);
        }
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
    }

    /// Returns the process-wide shared cache.
    ///
    /// @return the shared cache instance
    public static ImageMemoryCache shared() {
        return SHARED;
    }

    /// Returns the cached image for `key`, or `null` on a miss.
    ///
    /// A hit also marks the entry as most recently used.
    ///
    /// @param key cache key, usually the image URL or file identity
    /// @return the cached image, or `null` when absent
    public synchronized @Nullable Image get(String key) {
        return entries.get(Objects.requireNonNull(key, "key"));
    }

    /// Stores `image` under `key`, replacing any previous entry.
    ///
    /// Error images and images without pixel dimensions are ignored, so a
    /// failed decode never occupies the budget. Storing may evict older
    /// entries to stay within the configured limits.
    ///
    /// @param key   cache key, usually the image URL or file identity
    /// @param image decoded image to retain
    public synchronized void put(String key, Image image) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(image, "image");
        if (image.isError()) {
            return;
        }
        long size = estimateBytes(image);
        if (size <= 0) {
            // Background-loading images report no dimensions before they
            // finish; caching them would undercount the real footprint.
            return;
        }

        Image previous = entries.remove(key);
        if (previous != null) {
            bytes -= estimateBytes(previous);
        }
        entries.put(key, image);
        bytes += size;
        trim();
    }

    /// Drops every cached entry.
    public synchronized void clear() {
        entries.clear();
        bytes = 0L;
    }

    /// Returns the number of cached entries.
    ///
    /// @return current entry count
    public synchronized int size() {
        return entries.size();
    }

    /// Returns the estimated retained size of all cached entries.
    ///
    /// @return estimated retained bytes
    public synchronized long estimatedBytes() {
        return bytes;
    }

    /// Estimates the ARGB pixel footprint of `image`.
    ///
    /// @param image image to measure
    /// @return estimated bytes, or `0` when the image has no pixels yet
    public static long estimateBytes(Image image) {
        double width = image.getWidth();
        double height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return 0L;
        }
        return (long) width * (long) height * 4L;
    }

    /// Evicts least-recently-used entries until both limits hold.
    ///
    /// Must only be called while holding this cache's monitor.
    private void trim() {
        Iterator<Map.Entry<String, Image>> iterator = entries.entrySet().iterator();
        while ((bytes > maxBytes || entries.size() > maxEntries) && iterator.hasNext()) {
            Map.Entry<String, Image> entry = iterator.next();
            iterator.remove();
            bytes -= estimateBytes(entry.getValue());
        }
    }
}
