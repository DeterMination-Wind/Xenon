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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the tile-level `.msch` parser and the offline thumbnail renderer.
@NotNullByDefault
public final class SchematicBlueprintTest {

    /// A hand-built schematic parses back into its dictionary and tile positions.
    @Test
    public void parsesDictionaryAndTiles(@TempDir Path tempDir) throws IOException {
        Path msch = writeSchematic(tempDir, 8, 8, new String[]{"conveyor", "duo", "core"},
                new int[][]{{0, 0, 0}, {1, 0, 0}, {3, 3, 1}, {7, 7, 2}});

        SchematicBlueprint blueprint = SchematicReader.readBlueprint(msch);

        assertEquals(8, blueprint.width());
        assertEquals(8, blueprint.height());
        assertEquals(3, blueprint.blockNames().length);
        assertEquals("duo", blueprint.blockNames()[1]);
        assertEquals(4, blueprint.tiles().size());
        assertEquals(3, blueprint.tiles().get(2).x());
        assertEquals(3, blueprint.tiles().get(2).y());
        assertEquals("core", blueprint.blockName(blueprint.tiles().get(3)));
        assertEquals("Approximate", blueprint.tags().get("description").substring(0, 11));
    }

    /// Configs of every shape are skipped without desynchronising the stream.
    @Test
    public void skipsConfiguredBlocks(@TempDir Path tempDir) throws IOException {
        Path msch = writeSchematicWithConfigs(tempDir);

        SchematicBlueprint blueprint = SchematicReader.readBlueprint(msch);

        assertEquals(3, blueprint.tiles().size());
        assertEquals("sorter", blueprint.blockName(blueprint.tiles().get(0)));
        assertEquals("message", blueprint.blockName(blueprint.tiles().get(1)));
        assertEquals("sorter", blueprint.blockName(blueprint.tiles().get(2)));
        assertEquals(5, blueprint.tiles().get(2).x());
    }

    /// Rendering scales up to the requested size and fills every placed tile.
    @Test
    public void rendersScaledThumbnail(@TempDir Path tempDir) throws IOException {
        Path msch = writeSchematic(tempDir, 8, 8, new String[]{"conveyor", "core"},
                new int[][]{{0, 0, 1}, {4, 4, 0}});
        SchematicBlueprint blueprint = SchematicReader.readBlueprint(msch);

        SchematicPreview.Image image = SchematicPreview.render(blueprint, 128);

        assertEquals(128, image.width());
        assertEquals(128, image.height());
        // 8 tiles at scale 16: tile (0,0) is the core colour in the top-left corner.
        assertEquals(SchematicPreview.colourOf("core"), image.argb(1, 1));
        assertEquals(SchematicPreview.colourOf("conveyor"), image.argb(4 * 16 + 1, 4 * 16 + 1));
        assertEquals(SchematicPreview.EMPTY_TILE, image.argb(100, 100));
        assertEquals(SchematicPreview.BORDER, image.argb(0, 0));
    }

    /// Unknown blocks still get a distinct, stable colour.
    @Test
    public void unknownBlocksGetStableColours() {
        int first = SchematicPreview.colourOf("my-mod-block");
        assertNotEquals(first, SchematicPreview.colourOf("another-mod-block"));
        assertEquals(first, SchematicPreview.colourOf("my-mod-block"));
        assertEquals(SchematicPreview.colourOf("duo"), SchematicPreview.colourOf("scatter"));
    }

    /// A schematic without tiles renders as an empty canvas.
    @Test
    public void rendersEmptySchematic(@TempDir Path tempDir) throws IOException {
        Path msch = writeSchematic(tempDir, 2, 2, new String[0], new int[0][]);

        SchematicBlueprint blueprint = SchematicReader.readBlueprint(msch);
        SchematicPreview.Image image = SchematicPreview.render(blueprint, 64);

        assertTrue(blueprint.tiles().isEmpty());
        assertEquals(32, image.width());
        assertEquals(SchematicPreview.EMPTY_TILE, image.argb(16, 16));
    }

    /// A schematic declaring an enormous size renders inside the requested edge
    /// length instead of allocating a canvas from the untrusted header.
    @Test
    public void clampsOversizedSchematic(@TempDir Path tempDir) throws IOException {
        Path msch = writeSchematic(tempDir, 4000, 4000, new String[]{"core"},
                new int[][]{{2, 2, 0}});

        SchematicBlueprint blueprint = SchematicReader.readBlueprint(msch);
        SchematicPreview.Image image = SchematicPreview.render(blueprint, 64);

        assertEquals(64, image.width());
        assertEquals(64, image.height());
        assertEquals(SchematicPreview.colourOf("core"), image.argb(2, 2));
    }

    /// Writes a schematic with one tile per `{x, y, blockIndex}` entry and no configs.
    ///
    /// @param dir       temporary folder
    /// @param width     schematic width
    /// @param height    schematic height
    /// @param blocks    block dictionary
    /// @param tiles     `{x, y, blockIndex}` entries
    /// @return path of the written file
    private static Path writeSchematic(Path dir, int width, int height, String[] blocks,
                                       int[][] tiles) throws IOException {
        return write(dir, width, height, blocks, tiles);
    }

    /// Writes a schematic whose tiles carry configs (string, int and boolean).
    ///
    /// @param dir temporary folder
    /// @return path of the written file
    private static Path writeSchematicWithConfigs(Path dir) throws IOException {
        Path file = dir.resolve("configs.msch");
        try (OutputStream raw = Files.newOutputStream(file);
             DataOutputStream out = new DataOutputStream(raw)) {
            out.write(SchematicReader.MAGIC);
            out.writeByte(SchematicReader.CURRENT_VERSION);
            try (DataOutputStream stream = new DataOutputStream(new DeflaterOutputStream(out))) {
                stream.writeShort(8);
                stream.writeShort(8);
                stream.writeByte(1);
                stream.writeUTF("description");
                stream.writeUTF("Approximate preview");
                stream.writeByte(2);
                stream.writeUTF("sorter");
                stream.writeUTF("message");
                stream.writeInt(3);
                writeTileWithStringConfig(stream, 0, 0, 0, "item-copper");
                writeTileWithIntConfig(stream, 1, 0, 1);
                writeTileWithStringConfig(stream, 5, 5, 0, "note");
            }
        }
        return file;
    }

    /// Writes one tile whose config is a string.
    private static void writeTileWithStringConfig(DataOutputStream stream, int x, int y,
                                                 int blockIndex, String text) throws IOException {
        stream.writeByte(blockIndex);
        stream.writeInt((x & 0xFFFF) | ((y & 0xFFFF) << 16));
        stream.writeByte(4);
        stream.writeByte(1);
        stream.writeUTF(text);
        stream.writeByte(0);
    }

    /// Writes one tile whose config is an int.
    private static void writeTileWithIntConfig(DataOutputStream stream, int x, int y,
                                               int blockIndex) throws IOException {
        stream.writeByte(blockIndex);
        stream.writeInt((x & 0xFFFF) | ((y & 0xFFFF) << 16));
        stream.writeByte(1);
        stream.writeInt(42);
        stream.writeByte(0);
    }

    /// Writes a schematic with the shared header/tag/dictionary layout.
    private static Path write(Path dir, int width, int height, String[] blocks,
                              int[][] tiles) throws IOException {
        Path file = dir.resolve("test.msch");
        try (OutputStream raw = Files.newOutputStream(file);
             DataOutputStream out = new DataOutputStream(raw)) {
            out.write(SchematicReader.MAGIC);
            out.writeByte(SchematicReader.CURRENT_VERSION);
            try (DataOutputStream stream = new DataOutputStream(new DeflaterOutputStream(out))) {
                stream.writeShort(width);
                stream.writeShort(height);
                stream.writeByte(1);
                stream.writeUTF("description");
                stream.writeUTF("Approximate preview");
                stream.writeByte(blocks.length);
                for (String block : blocks) {
                    stream.writeUTF(block);
                }
                stream.writeInt(tiles.length);
                for (int[] tile : tiles) {
                    stream.writeByte(tile[2]);
                    stream.writeInt((tile[0] & 0xFFFF) | ((tile[1] & 0xFFFF) << 16));
                    stream.writeByte(0);
                    stream.writeByte(0);
                }
            }
        }
        return file;
    }
}
