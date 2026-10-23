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
package determination.xenon.mindustry.trash;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/// Local recycle bin for deleted instances and save trees.
///
/// Deletions move the tree into `<root>/.trash/<kind>-<stamp>/` with a
/// `manifest.json` naming the original path. Same-volume moves are atomic;
/// across volumes the tree is copied and only deleted after the copy finished.
/// Restores refuse to overwrite an existing path, and expired entries are
/// removed by [#purgeOld].
@NotNullByDefault
public final class MindustryTrashService {

    /// Manifest file inside every trash entry.
    public static final String MANIFEST = "manifest.json";

    /// Timestamp format for trash directories.
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    private final Path trashRoot;

    /// Creates a service under one root.
    ///
    /// @param root root that owns `.trash/`
    public MindustryTrashService(Path root) {
        this.trashRoot = root.toAbsolutePath().normalize().resolve(".trash");
    }

    /// One trash entry.
    ///
    /// @param directory  entry directory
    /// @param original   original path
    /// @param deletedAt  deletion time
    /// @param kind       `instance` or `save`
    public record Entry(Path directory, Path original, Instant deletedAt, String kind) {
    }

    /// Moves one tree into the trash.
    ///
    /// @param source tree to delete
    /// @param kind   entry kind
    /// @return the created entry
    /// @throws IOException when the tree cannot be moved or copied
    public Entry trash(Path source, String kind) throws IOException {
        Path absolute = source.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            throw new IOException("path does not exist: " + absolute);
        }
        Files.createDirectories(trashRoot);
        String name = kind + "-" + STAMP.format(Instant.now()) + "-"
                + absolute.getFileName();
        Path directory = trashRoot.resolve(name);
        int suffix = 1;
        while (Files.exists(directory)) {
            directory = trashRoot.resolve(name + "-" + suffix++);
        }
        moveTree(absolute, directory);
        writeManifest(directory, absolute, kind);
        return new Entry(directory, absolute, Instant.now(), kind);
    }

    /// Lists the current entries, newest first.
    ///
    /// @return trash entries
    /// @throws IOException when the trash directory cannot be listed
    public List<Entry> list() throws IOException {
        if (!Files.isDirectory(trashRoot)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(trashRoot)) {
            for (Path path : stream.filter(Files::isDirectory).toList()) {
                Entry entry = readManifest(path);
                if (entry != null) entries.add(entry);
            }
        }
        entries.sort(Comparator.comparing(Entry::deletedAt).reversed());
        return entries;
    }

    /// Restores one entry to its original path.
    ///
    /// @param entry entry to restore
    /// @throws IOException when the destination exists or the move fails
    public void restore(Entry entry) throws IOException {
        if (Files.exists(entry.original())) {
            throw new IOException("destination already exists: " + entry.original());
        }
        Path parent = entry.original().getParent();
        if (parent != null) Files.createDirectories(parent);
        moveTree(entry.directory(), entry.original());
    }

    /// Permanently deletes one entry.
    ///
    /// @param entry entry to delete
    /// @throws IOException when the tree cannot be deleted
    public void deletePermanently(Entry entry) throws IOException {
        deleteTree(entry.directory());
    }

    /// Deletes entries older than the retention period.
    ///
    /// @param retentionDays days to keep, or `0`/negative to keep forever
    /// @return number of removed entries
    /// @throws IOException when the trash cannot be listed
    public int purgeOld(int retentionDays) throws IOException {
        if (retentionDays <= 0) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int removed = 0;
        for (Entry entry : list()) {
            if (entry.deletedAt().isBefore(cutoff)) {
                try {
                    deletePermanently(entry);
                    removed++;
                } catch (IOException e) {
                    Logger.LOG.info("Cannot purge trash entry " + entry.directory() + ": "
                            + e.getMessage());
                }
            }
        }
        return removed;
    }

    /// Moves a tree, falling back to copy+delete across volumes.
    private static void moveTree(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (IOException atomicFailure) {
            try {
                Files.move(source, target);
                return;
            } catch (IOException ignored) {
                // Different volume or a locked file; copy then delete.
            }
        }
        copyTree(source, target);
        deleteTree(source);
    }

    /// Copies a tree recursively.
    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /// Deletes a tree recursively.
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /// Writes the entry manifest.
    private static void writeManifest(Path directory, Path original, String kind)
            throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("original", original.toString());
        root.addProperty("deleted_at", Instant.now().toString());
        root.addProperty("kind", kind);
        Files.writeString(directory.resolve(MANIFEST), root.toString(), StandardCharsets.UTF_8);
    }

    /// Reads one entry manifest.
    private static @Nullable Entry readManifest(Path directory) {
        Path file = directory.resolve(MANIFEST);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            String original = root.get("original") == null
                    ? "" : root.get("original").getAsString();
            if (original.isBlank()) {
                return null;
            }
            Instant deletedAt;
            try {
                deletedAt = Instant.parse(root.get("deleted_at").getAsString());
            } catch (RuntimeException e) {
                deletedAt = Instant.EPOCH;
            }
            String kind = root.get("kind") == null ? "" : root.get("kind").getAsString();
            return new Entry(directory, Path.of(original), deletedAt, kind);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
