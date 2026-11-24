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

import java.util.Locale;
import java.util.Objects;

/// Renders a schematic thumbnail without the game.
///
/// A `.msch` file carries block names, not sprites, so a real render would need
/// the game's texture atlas and block atlas indices. MindustryX/Copper solve
/// that by running a tool that boots the game; Xenon cannot, because the
/// launcher must not load game classes into its own JVM.
///
/// This renderer draws an **approximate** top-down map instead: every tile gets
/// a colour chosen from the block name by category (transport, liquid, power,
/// production, defence, storage, wall, core, …), with a deterministic hue for
/// anything unrecognised. The result is a readable footprint at a glance —
/// symmetric defence rings, conveyor buses and factory rows are obvious — while
/// the UI labels it as approximate.
///
/// Rendering is plain Java (no JavaFX, no AWT) so it is unit-testable and can
/// later be reused for the server map list.
@NotNullByDefault
public final class SchematicPreview {

    /// Background colour of a tile that holds no block (ARGB).
    public static final int EMPTY_TILE = 0xFF12161C;

    /// Grid colour drawn on the canvas border (ARGB).
    public static final int BORDER = 0xFF2A313C;

    /// Prevents instantiation of this utility class.
    private SchematicPreview() {}

    /// One rendered thumbnail.
    ///
    /// @param width  pixel width
    /// @param height pixel height
    /// @param pixels row-major ARGB pixels, `width * height` long
    public record Image(int width, int height, int @Unmodifiable [] pixels) {

        /// Defensive copy so callers cannot mutate the rendered image.
        public Image {
            pixels = pixels.clone();
        }

        /// Reads one pixel.
        ///
        /// @param x column
        /// @param y row
        /// @return ARGB value
        public int argb(int x, int y) {
            return pixels[y * width + x];
        }
    }

    /// Renders `blueprint` into a square canvas of at most `maxSize` pixels.
    ///
    /// Tiles are drawn at an integer scale so a 32×32 schematic becomes a crisp
    /// 128×128 thumbnail instead of a blurry resample. A one-pixel border is
    /// drawn around the footprint so the extent is visible on dark themes.
    ///
    /// @param blueprint parsed schematic
    /// @param maxSize   maximum width/height of the produced image, in pixels
    /// @return rendered image, never `null`
    public static Image render(SchematicBlueprint blueprint, int maxSize) {
        Objects.requireNonNull(blueprint, "blueprint");
        int width = Math.max(1, blueprint.width());
        int height = Math.max(1, blueprint.height());
        int limit = Math.max(8, maxSize);
        int scale = Math.max(1, Math.min(limit / Math.max(width, height), 16));
        int imageWidth = width * scale;
        int imageHeight = height * scale;
        int[] pixels = new int[imageWidth * imageHeight];
        java.util.Arrays.fill(pixels, EMPTY_TILE);

        for (SchematicBlueprint.Tile tile : blueprint.tiles()) {
            if (tile.x() < 0 || tile.y() < 0 || tile.x() >= width || tile.y() >= height) {
                continue;
            }
            int colour = colourOf(blueprint.blockName(tile));
            int x0 = tile.x() * scale;
            int y0 = tile.y() * scale;
            for (int dy = 0; dy < scale; dy++) {
                int row = (y0 + dy) * imageWidth;
                for (int dx = 0; dx < scale; dx++) {
                    pixels[row + x0 + dx] = colour;
                }
            }
        }

        drawBorder(pixels, imageWidth, imageHeight);
        return new Image(imageWidth, imageHeight, pixels);
    }

    /// Picks an approximate colour for one block name.
    ///
    /// Matching is by keyword, most specific category first. Unknown blocks get
    /// a stable hue derived from the name so a modded factory still renders as
    /// distinct blocks instead of one grey mass.
    ///
    /// @param blockName block name from the schematic dictionary
    /// @return opaque ARGB colour
    public static int colourOf(String blockName) {
        String name = blockName == null ? "" : blockName.toLowerCase(Locale.ROOT);

        if (name.isEmpty() || "air".equals(name)) return EMPTY_TILE;
        if (name.contains("core")) return 0xFF4C8DFF;
        if (containsAny(name, "wall", "door", "shield", "mender", "projector", "force")) {
            return 0xFF6C7684;
        }
        if (containsAny(name, "conveyor", "junction", "router", "sorter", "unloader", "bridge",
                "stack", "mass-driver", "duct")) {
            return 0xFF8A8F98;
        }
        if (containsAny(name, "conduit", "pipe", "pump", "tank", "liquid", "valve")) {
            return 0xFF2FA8B8;
        }
        if (containsAny(name, "drill", "bore", "extractor", "harvester", "cultivator", "fracker")) {
            return 0xFFB08A4A;
        }
        if (containsAny(name, "battery", "power", "node", "solar", "generator", "reactor",
                "diffuser", "tower", "beam")) {
            return 0xFFE0B93C;
        }
        if (containsAny(name, "turret", "duo", "scatter", "hail", "wave", "lancer", "arc",
                "cyclone", "swarmer", "salvo", "ripple", "fuse", "meltdown", "spectre",
                "foreshadow", "segment", "parallax", "tsunami", "breach", "diffuse",
                "sublimate", "afflict", "disperse", "titan", "mender")) {
            return 0xFFC2453F;
        }
        if (containsAny(name, "factory", "smelter", "crucible", "mixer", "press", "compressor",
                "kiln", "incinerator", "separator", "disassembler", "pulverizer", "centrifuge",
                "synthesizer", "refabricator", "fabricator", "assembler", "foundry")) {
            return 0xFF8E6CC9;
        }
        if (containsAny(name, "container", "vault", "storage", "payload")) {
            return 0xFF3FA98A;
        }
        if (containsAny(name, "unit", "reconstructor", "command", "repair")) {
            return 0xFF7A9CC6;
        }
        if (containsAny(name, "sorter", "switch", "processor", "logic", "message", "display")) {
            return 0xFFB9C24A;
        }
        return stableHue(name);
    }

    /// Whether the lowercase name contains any of the keywords.
    ///
    /// @param name     lowercase block name
    /// @param keywords candidate keywords
    /// @return `true` on the first match
    private static boolean containsAny(String name, String... keywords) {
        for (String keyword : keywords) {
            if (name.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /// Deterministic dark colour for a name the palette does not know.
    ///
    /// @param name lowercase block name
    /// @return opaque ARGB colour with mid-range saturation
    private static int stableHue(String name) {
        int hash = name.hashCode();
        float hue = ((hash & 0x7FFFFFFF) % 360) / 360f;
        return 0xFF000000 | hsbToRgb(hue, 0.45f, 0.62f);
    }

    /// Converts HSB to packed RGB without the AWT toolkit.
    ///
    /// @param hue        hue in the 0..1 range
    /// @param saturation saturation in the 0..1 range
    /// @param brightness brightness in the 0..1 range
    /// @return `0xRRGGBB`
    private static int hsbToRgb(float hue, float saturation, float brightness) {
        int r = 0;
        int g = 0;
        int b = 0;
        if (saturation == 0f) {
            r = g = b = (int) (brightness * 255f + 0.5f);
        } else {
            float h = (hue - (float) Math.floor(hue)) * 6f;
            float f = h - (float) Math.floor(h);
            float p = brightness * (1f - saturation);
            float q = brightness * (1f - saturation * f);
            float t = brightness * (1f - saturation * (1f - f));
            switch ((int) h) {
                case 0 -> { r = (int) (brightness * 255f + 0.5f); g = (int) (t * 255f + 0.5f); b = (int) (p * 255f + 0.5f); }
                case 1 -> { r = (int) (q * 255f + 0.5f); g = (int) (brightness * 255f + 0.5f); b = (int) (p * 255f + 0.5f); }
                case 2 -> { r = (int) (p * 255f + 0.5f); g = (int) (brightness * 255f + 0.5f); b = (int) (t * 255f + 0.5f); }
                case 3 -> { r = (int) (p * 255f + 0.5f); g = (int) (q * 255f + 0.5f); b = (int) (brightness * 255f + 0.5f); }
                case 4 -> { r = (int) (t * 255f + 0.5f); g = (int) (p * 255f + 0.5f); b = (int) (brightness * 255f + 0.5f); }
                default -> { r = (int) (brightness * 255f + 0.5f); g = (int) (p * 255f + 0.5f); b = (int) (q * 255f + 0.5f); }
            }
        }
        return (r << 16) | (g << 8) | b;
    }

    /// Draws a one-pixel border around the canvas edge.
    ///
    /// @param pixels row-major pixels
    /// @param width  canvas width
    /// @param height canvas height
    private static void drawBorder(int[] pixels, int width, int height) {
        for (int x = 0; x < width; x++) {
            pixels[x] = BORDER;
            pixels[(height - 1) * width + x] = BORDER;
        }
        for (int y = 0; y < height; y++) {
            pixels[y * width] = BORDER;
            pixels[y * width + width - 1] = BORDER;
        }
    }
}
