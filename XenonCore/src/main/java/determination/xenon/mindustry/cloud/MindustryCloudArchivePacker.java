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
package determination.xenon.mindustry.cloud;

import determination.xenon.mindustry.cloud.MindustryCloudArchiveManifest.FileEntry;
import determination.xenon.mindustry.cloud.MindustryCloudArchiveManifest.ModEntry;
import determination.xenon.mindustry.download.ProgressCallback;
import determination.xenon.mindustry.mod.MindustryLocalMod;
import determination.xenon.mindustry.mod.MindustryModManager;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/// Packs one Mindustry instance into a restorable cloud archive.
///
/// The archive contains `saves/`, `maps/`, `schematics/`, `settings.bin` and a
/// [MindustryCloudArchiveManifest]. Mods known to the public index are listed
/// but not packed (the restore side re-downloads them); private Mods are packed
/// while the configured byte budget allows and are otherwise listed as missing.
///
/// The archive is written to a sibling temp file and moved into place only when
/// it is complete, so a cancellation or failure never leaves a half archive.
@NotNullByDefault
public final class MindustryCloudArchivePacker {

    /// Includable top-level directories, in archive order.
    private static final List<String> DIRECTORIES = List.of("saves", "maps", "schematics");

    /// One pack configuration.
    ///
    /// @param gameVersion  game version such as `v157`, or empty
    /// @param build        game build number, or 0
    /// @param indexedMods  internal names the public index can restore
    /// @param maxModBytes  byte budget for private Mod archives
    /// @param maxTotalBytes hard upper bound for the finished archive, or 0
    public record Options(String gameVersion, int build, Set<String> indexedMods,
                          long maxModBytes, long maxTotalBytes) {

        /// Builds options with a 64 MiB private-Mod budget.
        ///
        /// @param gameVersion game version
        /// @param build       game build
        /// @param indexedMods public-indexed internal names
        public Options(String gameVersion, int build, Set<String> indexedMods) {
            this(gameVersion, build, indexedMods, 64L * 1024 * 1024, 0);
        }

        /// Copies the indexed set and normalises names for lookup.
        public Options {
            indexedMods = indexedMods.stream()
                    .map(value -> value.toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    /// Result of one pack operation.
    ///
    /// @param archive finished archive path
    /// @param manifest manifest written into the archive
    /// @param size     archive size in bytes
    public record Result(Path archive, MindustryCloudArchiveManifest manifest, long size) {
    }

    /// Packs one instance.
    ///
    /// @param dataDir   instance data directory
    /// @param target    archive destination
    /// @param options   pack configuration
    /// @param progress  optional byte progress receiver
    /// @param cancelled cancellation flag, or `null`
    /// @return the finished archive and its manifest
    /// @throws IOException when reading, writing or cancellation fails
    public static Result pack(Path dataDir, Path target, Options options,
                              @Nullable ProgressCallback progress,
                              @Nullable AtomicBoolean cancelled) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-"
                + Long.toHexString(System.nanoTime()));
        List<FileEntry> files = new ArrayList<>();
        List<ModEntry> mods = new ArrayList<>();
        long written = 0;
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
                for (String directory : DIRECTORIES) {
                    written = addDirectory(zip, dataDir.resolve(directory), directory,
                            files, written, options, progress, cancelled);
                }
                Path settings = dataDir.resolve("settings.bin");
                if (Files.isRegularFile(settings)) {
                    written = addFile(zip, dataDir, settings, "settings.bin",
                            files, written, options, progress, cancelled);
                }
                long modBudget = options.maxModBytes();
                for (MindustryLocalMod mod : scanMods(dataDir)) {
                    String key = key(mod.getInternalName().isBlank()
                            ? mod.getName() : mod.getInternalName());
                    boolean indexed = options.indexedMods().contains(key);
                    boolean packed = false;
                    String fileName = "";
                    if (!indexed && mod.isEnabled() && mod.getFile() != null
                            && Files.isRegularFile(mod.getFile())) {
                        long size = Files.size(mod.getFile());
                        if (size <= modBudget) {
                            String entryName = "mods/" + mod.getFile().getFileName();
                            written = addFile(zip, dataDir, mod.getFile(), entryName,
                                    files, written, options, progress, cancelled);
                            modBudget -= size;
                            packed = true;
                            fileName = mod.getFile().getFileName().toString();
                        }
                    }
                    mods.add(new ModEntry(mod.getInternalName(), mod.getDisplayName(),
                            mod.getVersion(), mod.getFile() == null ? ""
                            : MindustryCloudArchivePacker.sha256(mod.getFile()),
                            packed, fileName, mod.isEnabled()));
                }
                MindustryCloudArchiveManifest manifest = new MindustryCloudArchiveManifest(
                        MindustryCloudArchiveManifest.SCHEMA, options.gameVersion(),
                        options.build(), Instant.now(), mods, files);
                byte[] manifestBytes = manifest.toJson().toString()
                        .getBytes(StandardCharsets.UTF_8);
                zip.putNextEntry(new ZipEntry(MindustryCloudArchiveManifest.ENTRY_NAME));
                zip.write(manifestBytes);
                zip.closeEntry();
                written += manifestBytes.length;
                if (options.maxTotalBytes() > 0 && written > options.maxTotalBytes()) {
                    throw new IOException("archive exceeds the configured size limit");
                }
                if (progress != null) progress.onProgress(written, 0);
                zip.finish();
                long size = Files.size(temp);
                moveIntoPlace(temp, target);
                return new Result(target, manifest, size);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /// Adds one directory tree, sorted for deterministic archives.
    private static long addDirectory(ZipOutputStream zip, Path root, String prefix,
                                     List<FileEntry> files, long written, Options options,
                                     @Nullable ProgressCallback progress,
                                     @Nullable AtomicBoolean cancelled) throws IOException {
        if (!Files.isDirectory(root)) {
            return written;
        }
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(root)) {
            paths = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        long total = written;
        for (Path path : paths) {
            String relative = root.relativize(path).toString().replace('\\', '/');
            total = addFile(zip, root, path, prefix + "/" + relative,
                    files, total, options, progress, cancelled);
        }
        return total;
    }

    /// Adds one file and records its digest.
    private static long addFile(ZipOutputStream zip, Path root, Path file, String entryName,
                                List<FileEntry> files, long written, Options options,
                                @Nullable ProgressCallback progress,
                                @Nullable AtomicBoolean cancelled) throws IOException {
        checkCancelled(cancelled);
        long size = Files.size(file);
        zip.putNextEntry(new ZipEntry(entryName));
        MessageDigest digest = digest();
        long total = written;
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                checkCancelled(cancelled);
                zip.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                total += read;
                if (progress != null) progress.onProgress(total, 0);
                if (options.maxTotalBytes() > 0 && total > options.maxTotalBytes()) {
                    throw new IOException("archive exceeds the configured size limit");
                }
            }
        }
        zip.closeEntry();
        files.add(new FileEntry(entryName, size, HexFormat.of().formatHex(digest.digest())));
        return total;
    }

    /// Scans the instance Mods, tolerating a missing directory.
    private static List<MindustryLocalMod> scanMods(Path dataDir) {
        Path modsDir = dataDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            return List.of();
        }
        try {
            return new MindustryModManager(modsDir, dataDir).scanAll();
        } catch (RuntimeException e) {
            Logger.LOG.info("Cannot scan Mods for the cloud archive: " + e.getMessage());
            return List.of();
        }
    }

    /// Normalises a Mod key for the public-index lookup.
    private static String key(@Nullable String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    /// Throws when the caller cancelled the pack.
    private static void checkCancelled(@Nullable AtomicBoolean cancelled) throws IOException {
        if (cancelled != null && cancelled.get()) {
            throw new InterruptedIOException("cloud archive creation cancelled");
        }
    }

    /// Moves the finished temp archive into place.
    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /// Creates a SHA-256 digest.
    private static MessageDigest digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
    }

    /// Computes a file digest.
    private static String sha256(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            return "";
        }
    }

    private MindustryCloudArchivePacker() {
    }
}
