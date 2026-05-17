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
package determination.xenon.mindustry.schematic;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only summary of a Mindustry {@code .msch} schematic file.
 *
 * <p>Returned by {@link SchematicReader#readHeader(java.nio.file.Path)}. By design only
 * the small, cheap-to-parse header is exposed: 4-byte magic + version byte + the
 * zlib-compressed {@code (width, height, tags)} prefix. The
 * &laquo;tile-block&raquo; section is never decoded — listing schematics in a
 * file browser does not need per-tile information, and skipping it keeps the
 * scan O(tags) instead of O(width * height) per file.</p>
 *
 * <p>{@link #name()} and {@link #description()} are convenience extracts of the
 * {@code "name"} and {@code "description"} entries from {@link #tags()}. They
 * fall back to an empty string when the schematic was authored without those
 * tags. Callers that need other meta keys (e.g. {@code "labels"} or
 * {@code "contentMap"}) should read them straight off {@link #tags()}.</p>
 *
 * @param file        absolute or relative path to the underlying {@code .msch} file
 * @param name        value of the {@code "name"} tag, never {@code null} (empty when absent)
 * @param description value of the {@code "description"} tag, never {@code null} (empty when absent)
 * @param width       schematic width in tiles, as written in the compressed header
 * @param height      schematic height in tiles, as written in the compressed header
 * @param tags        immutable snapshot of every key/value pair in the tag region
 */
public record SchematicSummary(
        Path file,
        String name,
        String description,
        int width,
        int height,
        Map<String, String> tags
) {
    public SchematicSummary {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(tags, "tags");
        // Defensive immutable copy so callers cannot mutate our state.
        tags = Map.copyOf(tags);
    }
}
