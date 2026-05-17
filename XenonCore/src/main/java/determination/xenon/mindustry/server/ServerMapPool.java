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
package determination.xenon.mindustry.server;

import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Manages the {@code maps/} folder of one Mindustry dedicated-server
 * data directory: lists existing custom maps, imports new {@code .msav}
 * archives, and deletes them.
 *
 * <p>Bound to a single {@link ServerInstance} on construction; the pool
 * directory is resolved as {@code <dataDir>/maps} where {@code <dataDir>}
 * comes from {@link ServerInstance#resolveDataDir(Path)}.</p>
 */
public final class ServerMapPool {

    private static final String MSAV = ".msav";

    private final Path mapsDir;

    public ServerMapPool(ServerInstance inst, ServerInstanceManager mgr) {
        Objects.requireNonNull(inst, "inst");
        Objects.requireNonNull(mgr, "mgr");
        this.mapsDir = inst.resolveDataDir(mgr.getServerRoot(inst.getId()))
                .resolve("maps");
    }

    /** Absolute path to the {@code maps/} folder this pool wraps. */
    public Path getMapsDir() {
        return mapsDir;
    }

    /**
     * Snapshot of every {@code .msav} living directly under
     * {@link #getMapsDir()}, sorted by filename (case-insensitive).
     * Files that disappear or fail to {@code stat} are skipped with a
     * warning rather than aborting the whole listing.
     */
    public List<MapEntry> list() {
        List<MapEntry> out = new ArrayList<>();
        if (!Files.isDirectory(mapsDir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mapsDir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;
                String fname = p.getFileName().toString();
                if (!fname.toLowerCase(Locale.ROOT).endsWith(MSAV)) continue;
                try {
                    BasicFileAttributes attrs =
                            Files.readAttributes(p, BasicFileAttributes.class);
                    String stem = fname.substring(0, fname.length() - MSAV.length());
                    out.add(new MapEntry(p, stem, attrs.size(),
                            attrs.lastModifiedTime().toInstant()));
                } catch (IOException ex) {
                    Logger.LOG.warning("Failed to stat Mindustry map "
                            + p + ": " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            Logger.LOG.warning("Failed to list Mindustry maps dir "
                    + mapsDir + ": " + ex.getMessage());
        }
        out.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
        return out;
    }

    /**
     * Copy {@code src} into {@link #getMapsDir()}, creating the dir if
     * needed. Existing files with the same name are overwritten. Returns
     * the destination path inside the pool.
     */
    public Path importMap(Path src) throws IOException {
        Objects.requireNonNull(src, "src");
        if (!Files.isRegularFile(src)) {
            throw new IOException("Not a regular file: " + src);
        }
        String name = src.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(MSAV)) {
            throw new IOException("Not a Mindustry .msav archive: " + src);
        }
        Files.createDirectories(mapsDir);
        Path dst = mapsDir.resolve(name);
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        return dst;
    }

    /** Permanently delete one map archive. No-op if it's already gone. */
    public void delete(Path map) throws IOException {
        Objects.requireNonNull(map, "map");
        Files.deleteIfExists(map);
    }

    /**
     * Snapshot of one custom map archive in the pool.
     *
     * @param file         absolute path to the {@code .msav}
     * @param name         filename without the {@code .msav} extension
     * @param size         file size in bytes
     * @param lastModified last-modified instant from the filesystem
     */
    public record MapEntry(Path file, String name, long size, Instant lastModified) {
        public MapEntry {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(lastModified, "lastModified");
        }
    }
}
