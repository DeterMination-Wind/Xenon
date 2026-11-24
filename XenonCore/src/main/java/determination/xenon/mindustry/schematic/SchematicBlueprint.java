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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/// Full tile-level contents of one Mindustry `.msch` schematic.
///
/// [SchematicReader#readHeader(java.nio.file.Path)] stops after the tag map
/// because listing a folder does not need tiles. A preview does: the archive
/// stores block names in a dictionary inside the compressed payload, and every
/// tile is `(dictionary index, packed position)`. This record keeps exactly the
/// information a thumbnail needs and drops configs, rotations and labels.
///
/// Block names come from the schematic's own dictionary, so a preview never
/// needs the game jar and never guesses ids across game versions.
///
/// @param width      schematic width in tiles
/// @param height     schematic height in tiles
/// @param blockNames dictionary of block names, indexed by [Tile#blockIndex()]
/// @param tiles      placed blocks, in file order
/// @param tags       immutable snapshot of the tag region
@NotNullByDefault
public record SchematicBlueprint(
        int width,
        int height,
        String @Unmodifiable [] blockNames,
        java.util.@Unmodifiable List<Tile> tiles,
        Map<String, String> tags
) {

    /// Defensive copies so callers cannot mutate the parsed result.
    public SchematicBlueprint {
        blockNames = blockNames.clone();
        tiles = List.copyOf(tiles);
        tags = Map.copyOf(tags);
    }

    /// One placed block.
    ///
    /// @param x          tile x, 0-based from the schematic origin
    /// @param y          tile y, 0-based from the schematic origin
    /// @param blockIndex index into [SchematicBlueprint#blockNames()]
    public record Tile(int x, int y, int blockIndex) {
    }

    /// Name of the block placed by one tile.
    ///
    /// @param tile tile under inspection
    /// @return the block name, or `"air"` when the index is out of range
    public String blockName(Tile tile) {
        Objects.requireNonNull(tile, "tile");
        int index = tile.blockIndex();
        return index >= 0 && index < blockNames.length ? blockNames[index] : "air";
    }
}
