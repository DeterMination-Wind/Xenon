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
package determination.xenon.mindustry.save;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only summary of the header / meta region of a Mindustry {@code .msav} save file.
 *
 * <p>This is what {@link SaveFileReader#readHeader(Path)} returns. Only fields that
 * can be parsed cheaply from the meta region without touching map / entity data are
 * exposed. Fields that are not stored by Mindustry in modern save formats are
 * surfaced as {@code null} (notably {@link #playerName()}); fields that are stored
 * inside the JSON-encoded {@code rules} blob are extracted on a best-effort basis
 * (see {@link #mode()}, {@link #difficulty()}).</p>
 *
 * <p>The full {@code key &#8594; value} StringMap that Mindustry serialises in the
 * meta region is preserved verbatim in {@link #tags()} for callers that want
 * access to fields outside the curated set.</p>
 *
 * @param file         absolute or relative path to the underlying {@code .msav} file
 * @param fileSize     size of {@code file} in bytes, as reported by the filesystem
 * @param lastModified filesystem last-modified timestamp of {@code file}
 * @param version      SAVE format version integer (e.g. {@code 7})
 * @param mapName      raw {@code mapname} tag, never {@code null} (empty if absent)
 * @param playerName   not stored in modern Mindustry saves; always {@code null}
 * @param playtime     value of the {@code playtime} tag in milliseconds, or {@code 0} if absent
 * @param build        Mindustry build number that produced this save, or {@code 0} if absent
 * @param wave         current wave number when the save was written, or {@code 0} if absent
 * @param difficulty   best-effort extraction from the {@code rules} JSON, or {@code null} if not present
 * @param mode         best-effort extraction from the {@code rules} JSON, or {@code null} if not present
 * @param savedAt      epoch milliseconds when the save was written ({@code saved} tag), or {@code 0} if absent
 * @param tags         immutable snapshot of every key/value pair in the meta region
 */
public record SaveSummary(
        Path file,
        long fileSize,
        Instant lastModified,
        int version,
        String mapName,
        String playerName,
        long playtime,
        int build,
        int wave,
        String difficulty,
        String mode,
        long savedAt,
        Map<String, String> tags
) {
    public SaveSummary {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(lastModified, "lastModified");
        Objects.requireNonNull(mapName, "mapName");
        Objects.requireNonNull(tags, "tags");
        // Defensive immutable copy so callers cannot mutate our state.
        tags = Map.copyOf(tags);
    }
}
