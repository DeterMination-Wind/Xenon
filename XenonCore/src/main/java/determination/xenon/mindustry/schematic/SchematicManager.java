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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Filesystem-level orchestration of a Mindustry schematics directory.
 *
 * <p>Owns a single {@link Path} (typically {@code <data>/schematics}) and
 * exposes the small set of operations the launcher UI needs: list, import
 * (file or base64), export (file or base64), delete. Parsing is delegated to
 * {@link SchematicReader#readHeader(Path)} so the manager stays a thin layer
 * over the filesystem.</p>
 *
 * <p>Imports run through {@link SchematicReader} as a validity gate: if the
 * candidate file does not have the {@code "msch"} magic / inflatable header,
 * the import is rejected and no file is left in the directory. New filenames
 * are produced by {@link #findFreeName(String)} which appends {@code -1},
 * {@code -2}, &hellip; until a free slot is found, mirroring the dedup style
 * used elsewhere in Mindustry but with hyphens instead of underscores per the
 * task brief.</p>
 */
public final class SchematicManager {

    /** Canonical file extension (without dot) for Mindustry schematic files. */
    public static final String EXTENSION = "msch";

    /** Base name used when a file is imported via base64 / has no usable source name. */
    private static final String DEFAULT_BASE_NAME = "schematic";

    private final Path schematicsDir;

    /**
     * @param schematicsDir directory where schematics live; created on demand by
     *                      every mutating operation that needs it
     * @throws NullPointerException if {@code schematicsDir} is {@code null}
     */
    public SchematicManager(Path schematicsDir) {
        this.schematicsDir = Objects.requireNonNull(schematicsDir, "schematicsDir");
    }

    /**
     * @return the directory this manager operates on; never {@code null}
     */
    public Path directory() {
        return schematicsDir;
    }

    /**
     * Scan {@link #directory()} non-recursively for {@code *.msch} files and
     * return their parsed headers.
     *
     * <p>Files that fail to parse are logged as warnings and skipped — a single
     * corrupt schematic must not prevent the rest of the list from showing up
     * in the UI.</p>
     *
     * @return list of summaries, in directory iteration order; empty when the
     *         directory does not exist
     */
    public List<SchematicSummary> list() throws IOException {
        if (!Files.isDirectory(schematicsDir)) {
            return List.of();
        }
        List<SchematicSummary> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(schematicsDir, "*." + EXTENSION)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    result.add(SchematicReader.readHeader(file));
                } catch (IOException e) {
                    Logger.LOG.warning("Failed to read schematic header: " + file, e);
                }
            }
        }
        return result;
    }

    /**
     * Copy {@code src} into {@link #directory()}.
     *
     * <p>The source file is validated with {@link SchematicReader#readHeader(Path)}
     * before any filesystem mutation; an invalid schematic raises
     * {@link IOException} and leaves the directory untouched. The destination
     * file name is the source file's base name (without extension), de-duped
     * via {@link #findFreeName(String)}.</p>
     *
     * @param src path to a {@code .msch} file outside the directory
     * @return the path the schematic was written to; always inside {@link #directory()}
     */
    public Path importFile(Path src) throws IOException {
        Objects.requireNonNull(src, "src");
        // Validate up front so we never copy garbage into the schematics dir.
        SchematicReader.readHeader(src);

        Files.createDirectories(schematicsDir);
        String baseName = stripExtension(src.getFileName().toString());
        if (baseName.isEmpty()) {
            baseName = DEFAULT_BASE_NAME;
        }
        Path dst = findFreeName(baseName);
        Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
        Logger.LOG.info("Imported schematic " + src + " -> " + dst);
        return dst;
    }

    /**
     * Decode {@code b64} (the format produced by Mindustry's "copy schematic to
     * clipboard" command), validate that it is a real schematic, and persist it
     * under {@link #directory()} with a {@code schematic.msch} (or
     * {@code schematic-N.msch}) filename.
     *
     * @param b64 base64-encoded {@code .msch} payload, leading/trailing
     *            whitespace tolerated
     * @return the path the schematic was written to
     */
    public Path importBase64(String b64) throws IOException {
        Objects.requireNonNull(b64, "b64");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(b64.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid base64 schematic payload", e);
        }
        if (bytes.length < SchematicReader.MAGIC.length + 1) {
            throw new IOException("Decoded payload is too short to be a .msch file ("
                    + bytes.length + " bytes)");
        }
        for (int i = 0; i < SchematicReader.MAGIC.length; i++) {
            if (bytes[i] != SchematicReader.MAGIC[i]) {
                throw new IOException("Decoded payload is not a .msch file: bad magic at byte " + i);
            }
        }

        Files.createDirectories(schematicsDir);
        Path tmp = Files.createTempFile(schematicsDir, "schematic-import-", "." + EXTENSION);
        try {
            Files.write(tmp, bytes);
            // Full structural validation, not just the 4-byte magic check above.
            SchematicReader.readHeader(tmp);

            Path dst = findFreeName(DEFAULT_BASE_NAME);
            Files.move(tmp, dst, StandardCopyOption.ATOMIC_MOVE);
            Logger.LOG.info("Imported base64 schematic (" + bytes.length + " bytes) -> " + dst);
            return dst;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * Read {@code msch} and return its raw bytes encoded as a standard base64
     * string (no MIME line breaks). Useful for "share schematic" UI flows.
     *
     * @param msch path to a {@code .msch} file (does not need to live under
     *             {@link #directory()})
     */
    public String exportBase64(Path msch) throws IOException {
        Objects.requireNonNull(msch, "msch");
        byte[] bytes = Files.readAllBytes(msch);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Copy {@code msch} to {@code dst}, creating any missing parent
     * directories. The destination is overwritten if it already exists.
     */
    public Path exportFile(Path msch, Path dst) throws IOException {
        Objects.requireNonNull(msch, "msch");
        Objects.requireNonNull(dst, "dst");
        Path parent = dst.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(msch, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Logger.LOG.info("Exported schematic " + msch + " -> " + dst);
        return dst;
    }

    /**
     * Delete {@code msch} from the filesystem. No-op if the file is already
     * gone.
     */
    public void delete(Path msch) throws IOException {
        Objects.requireNonNull(msch, "msch");
        if (Files.deleteIfExists(msch)) {
            Logger.LOG.info("Deleted schematic " + msch);
        }
    }

    /**
     * Resolve a free filename inside {@link #directory()} of the form
     * {@code <baseName>.msch}, {@code <baseName>-1.msch}, {@code <baseName>-2.msch},
     * &hellip; — the first one that does not currently exist on disk.
     *
     * <p>{@code baseName} is sanitised so that the result never escapes the
     * schematics directory: path separators are stripped and an empty result
     * falls back to {@link #DEFAULT_BASE_NAME}.</p>
     */
    Path findFreeName(String baseName) {
        String sanitized = sanitizeBaseName(baseName);
        Path candidate = schematicsDir.resolve(sanitized + "." + EXTENSION);
        for (int n = 1; Files.exists(candidate); n++) {
            candidate = schematicsDir.resolve(sanitized + "-" + n + "." + EXTENSION);
        }
        return candidate;
    }

    private static String sanitizeBaseName(String baseName) {
        if (baseName == null) {
            return DEFAULT_BASE_NAME;
        }
        // Strip any path-separator characters and trim whitespace; we don't
        // want a malicious tag-derived name to escape the schematics directory.
        String cleaned = baseName.replace('/', '_').replace('\\', '_').trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return DEFAULT_BASE_NAME;
        }
        return cleaned;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return fileName;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (ext.equals(EXTENSION)) {
            return fileName.substring(0, dot);
        }
        return fileName;
    }
}
