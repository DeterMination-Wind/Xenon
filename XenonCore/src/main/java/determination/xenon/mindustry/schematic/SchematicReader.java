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

import determination.xenon.util.logging.Logger;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * Header-only parser for Mindustry {@code .msch} schematic files.
 *
 * <p>Mirrors the prefix of {@code mindustry.game.Schematics#read(InputStream)}
 * but stops as soon as the tag StringMap has been consumed; the per-tile
 * payload (block dictionary + tile records) is intentionally skipped so a
 * directory full of schematics can be listed without paying the full inflate
 * + decode cost.</p>
 *
 * <p>On-disk layout of a {@code .msch} file:</p>
 * <pre>
 *   byte[4] magic   = 'm','s','c','h'         (== big-endian int 0x6D736368)
 *   byte    version                            (currently 1)
 *   --- zlib (DEFLATE wrapped) stream begins here ---
 *     short  width
 *     short  height
 *     ubyte  tagCount
 *     repeated tagCount times:
 *       UTF   key      (DataInput.writeUTF: u16 length + modified UTF-8)
 *       UTF   value
 *     ubyte  blockDictionaryLength       (NOT read here)
 *     ...    blockNames + tile records   (NOT read here)
 * </pre>
 *
 * <p>This class never instantiates Mindustry blocks or constructs a
 * {@link Tile}-equivalent list — it has zero dependency on the Mindustry
 * runtime, only on the JDK ({@link InflaterInputStream},
 * {@link DataInputStream}).</p>
 */
public final class SchematicReader {

    /** The 4 bytes that every {@code .msch} file starts with: {@code 'm','s','c','h'}. */
    public static final byte[] MAGIC = {'m', 's', 'c', 'h'};

    /** Big-endian {@code int} form of {@link #MAGIC}, i.e. {@code 0x6D736368}. */
    public static final int MAGIC_INT = 0x6D736368;

    /** Latest schematic format version this reader is willing to parse. */
    public static final int CURRENT_VERSION = 1;

    private static final int BUFFER_SIZE = 8 * 1024;

    private SchematicReader() {
        // utility class
    }

    /**
     * Parse only the magic, version and tag StringMap of {@code msch}.
     *
     * <p>The tile-block region is not consumed — the underlying stream is
     * closed as soon as the tag map has been read, so this method is cheap
     * even on large schematics.</p>
     *
     * @param msch path to a Mindustry schematic file
     * @return non-null summary built from the parsed header
     * @throws IOException if the file is missing/unreadable, the magic is
     *                     wrong, the version is from a newer game build, the
     *                     stream is truncated, or the zlib payload is invalid
     */
    public static SchematicSummary readHeader(Path msch) throws IOException {
        Objects.requireNonNull(msch, "msch");

        try (InputStream raw = Files.newInputStream(msch);
             InputStream buffered = new BufferedInputStream(raw, BUFFER_SIZE)) {

            // Magic + version are stored OUTSIDE the zlib stream, mirroring
            // mindustry.game.Schematics.read(InputStream).
            for (int i = 0; i < MAGIC.length; i++) {
                int b = buffered.read();
                if (b < 0) {
                    throw new IOException("Truncated .msch header (magic byte " + i + ") for " + msch);
                }
                if ((byte) b != MAGIC[i]) {
                    throw new IOException(String.format(
                            "Invalid .msch magic for %s: expected 0x%08X (\"msch\") but byte %d was 0x%02X",
                            msch, MAGIC_INT, i, b & 0xFF));
                }
            }

            int version = buffered.read();
            if (version < 0) {
                throw new IOException("Truncated .msch header (version byte) for " + msch);
            }
            if (version > CURRENT_VERSION) {
                throw new IOException("Unknown .msch version " + version + " for " + msch
                        + " (this build supports up to " + CURRENT_VERSION + ")");
            }

            try (DataInputStream stream = new DataInputStream(new InflaterInputStream(buffered))) {
                short width;
                short height;
                int tagCount;
                try {
                    width = stream.readShort();
                    height = stream.readShort();
                    tagCount = stream.readUnsignedByte();
                } catch (EOFException e) {
                    throw new IOException("Truncated .msch (size/tag-count) for " + msch, e);
                }

                Map<String, String> tags = new LinkedHashMap<>(Math.max(8, tagCount * 2));
                for (int i = 0; i < tagCount; i++) {
                    String key;
                    String value;
                    try {
                        key = stream.readUTF();
                        value = stream.readUTF();
                    } catch (EOFException e) {
                        throw new IOException("Truncated .msch (tag entry " + i + " of " + tagCount
                                + ") for " + msch, e);
                    }
                    tags.put(key, value);
                }

                String name = tags.getOrDefault("name", "");
                String description = tags.getOrDefault("description", "");

                Logger.LOG.debug("Parsed .msch " + msch.getFileName()
                        + " version=" + version
                        + " size=" + width + "x" + height
                        + " tags=" + tags.size());

                return new SchematicSummary(msch, name, description, width, height, tags);
            }
        } catch (ZipException e) {
            throw new IOException("Failed to inflate .msch payload (not a valid zlib stream?): " + msch, e);
        }
    }
}
