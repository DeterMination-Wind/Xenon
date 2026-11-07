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
import org.jetbrains.annotations.Unmodifiable;

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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
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
///
/// [#plan] predicts the archive content for the upload-format decision, while
/// [#pack] hashes the bytes it actually writes and records those values in the
/// manifest, so a file changing mid-pack cannot make the manifest lie.
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
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    /// Result of one pack operation.
    ///
    /// @param archive finished archive path
    /// @param manifest manifest written into the archive
    /// @param size     archive size in bytes
    public record Result(Path archive, MindustryCloudArchiveManifest manifest, long size) {
    }

    /// One file selected for inclusion in the archive.
    ///
    /// @param archivePath archive-relative path with `/` separators
    /// @param source      file the bytes are read from
    /// @param size        uncompressed size in bytes
    /// @param sha256      lowercase hex SHA-256 of the file
    public record PlannedFile(String archivePath, Path source, long size, String sha256) {
    }

    /// A prediction of the archive content, computed before the archive is opened.
    ///
    /// [#pack] uses [#files] to choose what to write, but records the size and
    /// hash of the bytes it actually writes instead of these predicted values.
    ///
    /// @param files selected files in archive order
    /// @param mods  Mods recorded in the manifest
    public record Plan(@Unmodifiable List<PlannedFile> files,
                       @Unmodifiable List<ModEntry> mods) {

        /// Copies both lists defensively.
        public Plan {
            files = List.copyOf(files);
            mods = List.copyOf(mods);
        }
    }

    /// Selects every file and Mod the archive will contain.
    ///
    /// The walk order is the archive order: `saves/`, `maps/`, `schematics/`
    /// sorted by path, then `settings.bin`, then the private Mods that fit the
    /// byte budget. Hashes are computed here as a prediction; [#pack] re-hashes
    /// the bytes it writes and records those actual values in the manifest.
    ///
    /// @param dataDir instance data directory
    /// @param options pack configuration
    /// @return the immutable plan
    /// @throws IOException when a directory walk or file read fails
    public static Plan plan(Path dataDir, Options options) throws IOException {
        return plan(dataDir, options, null);
    }

    /// Selects every file and Mod the archive will contain, with cancellation.
    ///
    /// Cancellation is honoured while walking and hashing, before any archive
    /// is opened.
    ///
    /// @param dataDir   instance data directory
    /// @param options   pack configuration
    /// @param cancelled cancellation flag, or `null`
    /// @return the immutable plan
    /// @throws IOException when a directory walk or file read fails, or the
    ///                     caller cancels
    public static Plan plan(Path dataDir, Options options,
                            @Nullable AtomicBoolean cancelled) throws IOException {
        checkCancelled(cancelled);
        List<PlannedFile> files = new ArrayList<>();
        for (String directory : DIRECTORIES) {
            files.addAll(planDirectory(dataDir.resolve(directory), directory, cancelled));
        }
        Path settings = dataDir.resolve("settings.bin");
        if (Files.isRegularFile(settings)) {
            files.add(plannedFile(settings, "settings.bin", cancelled));
        }
        List<ModEntry> mods = new ArrayList<>();
        long modBudget = options.maxModBytes();
        for (MindustryLocalMod mod : scanMods(dataDir)) {
            checkCancelled(cancelled);
            String key = key(mod.getInternalName().isBlank()
                    ? mod.getName() : mod.getInternalName());
            boolean indexed = options.indexedMods().contains(key);
            boolean packed = false;
            String fileName = "";
            @Nullable String packedDigest = null;
            if (!indexed && mod.isEnabled() && mod.getFile() != null
                    && Files.isRegularFile(mod.getFile())) {
                long size = Files.size(mod.getFile());
                if (size <= modBudget) {
                    String entryName = "mods/" + mod.getFile().getFileName();
                    packedDigest = fileDigest(mod.getFile(), cancelled);
                    files.add(new PlannedFile(entryName, mod.getFile(), size, packedDigest));
                    modBudget -= size;
                    packed = true;
                    fileName = mod.getFile().getFileName().toString();
                }
            }
            String modSha = packedDigest != null ? packedDigest
                    : mod.getFile() == null ? "" : sha256(mod.getFile(), cancelled);
            mods.add(new ModEntry(mod.getInternalName(), mod.getDisplayName(),
                    mod.getVersion(), modSha, packed, fileName, mod.isEnabled()));
        }
        validatePlan(files);
        return new Plan(files, mods);
    }

    /// Validates planned entries against the installer's restore rules and caps.
    ///
    /// @param files planned files in archive order
    /// @throws IOException when an entry is not restorable or a cap is exceeded
    private static void validatePlan(List<PlannedFile> files) throws IOException {
        if (files.size() > MindustryCloudArchiveInstaller.MAX_ENTRIES) {
            throw new IOException("cloud archive has too many entries");
        }
        long total = 0;
        for (PlannedFile file : files) {
            if (!MindustryCloudManifestPlan.isRestorablePath(file.archivePath())) {
                throw new IOException("cloud archive entry is not restorable: "
                        + file.archivePath());
            }
            MindustryCloudArchiveInstaller.validateEntryName(file.archivePath());
            if (file.size() > MindustryCloudArchiveInstaller.MAX_TOTAL_BYTES - total) {
                throw new IOException("cloud archive is too large");
            }
            total += file.size();
        }
    }

    /// Packs one instance.
    ///
    /// The manifest records the size and SHA-256 of the bytes actually written,
    /// not the values predicted by [#plan]. When a written file differs from its
    /// planned entry, the divergence is logged and packing continues.
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
        return pack(dataDir, target, options, plan(dataDir, options, cancelled), progress,
                cancelled);
    }

    /// Packs one instance from an existing plan.
    ///
    /// The manifest records the size and SHA-256 of the bytes actually written,
    /// not the values predicted by [#plan]; packed Mod entries are rebuilt from
    /// those written digests so `mods[]` and `files[]` cannot disagree. When a
    /// written file differs from its planned entry, the divergence is logged and
    /// packing continues.
    ///
    /// @param dataDir   instance data directory
    /// @param target    archive destination
    /// @param options   pack configuration
    /// @param plan      precomputed plan to write
    /// @param progress  optional byte progress receiver
    /// @param cancelled cancellation flag, or `null`
    /// @return the finished archive and its manifest
    /// @throws IOException when reading, writing or cancellation fails
    public static Result pack(Path dataDir, Path target, Options options, Plan plan,
                              @Nullable ProgressCallback progress,
                              @Nullable AtomicBoolean cancelled) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        checkCancelled(cancelled);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp-"
                + Long.toHexString(System.nanoTime()));
        long written = 0;
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temp))) {
                List<FileEntry> files = new ArrayList<>(plan.files().size());
                for (PlannedFile file : plan.files()) {
                    WrittenFile packed = writePlannedFile(zip, file, written, options,
                            progress, cancelled);
                    written = packed.written();
                    if (packed.entry().size() != file.size()
                            || !packed.entry().sha256().equalsIgnoreCase(file.sha256())) {
                        Logger.LOG.warning("Cloud archive content changed while packing "
                                + file.archivePath());
                    }
                    files.add(packed.entry());
                }
                List<ModEntry> mods = writtenModEntries(plan.mods(), files);
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

    /// Plans one directory tree, sorted for deterministic archives.
    private static List<PlannedFile> planDirectory(Path root, String prefix,
                                                   @Nullable AtomicBoolean cancelled)
            throws IOException {
        checkCancelled(cancelled);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(root)) {
            paths = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        List<PlannedFile> files = new ArrayList<>(paths.size());
        for (Path path : paths) {
            checkCancelled(cancelled);
            String relative = root.relativize(path).toString().replace('\\', '/');
            String entryName = prefix + "/" + relative;
            if ("saves".equals(prefix) && relative.startsWith("backups/")) {
                continue;
            }
            files.add(plannedFile(path, entryName, cancelled));
        }
        return files;
    }

    /// Plans one file and records its size and digest.
    private static PlannedFile plannedFile(Path file, String entryName,
                                           @Nullable AtomicBoolean cancelled) throws IOException {
        checkCancelled(cancelled);
        return new PlannedFile(entryName, file, Files.size(file),
                fileDigest(file, cancelled));
    }

    /// One file as it was actually written into the archive.
    ///
    /// @param entry   manifest entry derived from the written bytes
    /// @param written running total of archive bytes written
    private record WrittenFile(FileEntry entry, long written) {
    }

    /// Writes one planned file, hashing the bytes that are actually copied.
    private static WrittenFile writePlannedFile(ZipOutputStream zip, PlannedFile file, long written,
                                                Options options,
                                                @Nullable ProgressCallback progress,
                                                @Nullable AtomicBoolean cancelled)
            throws IOException {
        checkCancelled(cancelled);
        zip.putNextEntry(new ZipEntry(file.archivePath()));
        MessageDigest digest = digest();
        long total = written;
        long size = 0;
        try (InputStream input = Files.newInputStream(file.source())) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                checkCancelled(cancelled);
                zip.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                size += read;
                total += read;
                if (progress != null) progress.onProgress(total, 0);
                if (options.maxTotalBytes() > 0 && total > options.maxTotalBytes()) {
                    throw new IOException("archive exceeds the configured size limit");
                }
            }
        }
        zip.closeEntry();
        return new WrittenFile(new FileEntry(file.archivePath(), size,
                HexFormat.of().formatHex(digest.digest())), total);
    }

    /// Rebuilds Mod entries with the digests of the files actually written.
    ///
    /// @param planned planned Mod entries
    /// @param files   written file entries
    /// @return Mod entries whose packed hashes match `files[]`
    private static List<ModEntry> writtenModEntries(List<ModEntry> planned,
                                                    List<FileEntry> files) {
        Map<String, String> digests = new HashMap<>();
        for (FileEntry file : files) {
            digests.put(file.path(), file.sha256());
        }
        List<ModEntry> mods = new ArrayList<>(planned.size());
        for (ModEntry mod : planned) {
            String sha256 = mod.sha256();
            if (mod.packed() && !mod.fileName().isBlank()) {
                @Nullable String written = digests.get("mods/" + mod.fileName());
                if (written != null) sha256 = written;
            }
            mods.add(new ModEntry(mod.id(), mod.name(), mod.version(), sha256,
                    mod.packed(), mod.fileName(), mod.enabled()));
        }
        return mods;
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

    /// Computes a file digest, returning an empty string when it cannot be read.
    ///
    /// A cancellation interrupt is propagated instead of being swallowed.
    private static String sha256(Path file, @Nullable AtomicBoolean cancelled) throws IOException {
        try {
            return fileDigest(file, cancelled);
        } catch (InterruptedIOException e) {
            throw e;
        } catch (IOException e) {
            return "";
        }
    }

    /// Computes the lowercase hex SHA-256 of one readable file.
    private static String fileDigest(Path file, @Nullable AtomicBoolean cancelled)
            throws IOException {
        checkCancelled(cancelled);
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                checkCancelled(cancelled);
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private MindustryCloudArchivePacker() {
    }
}
