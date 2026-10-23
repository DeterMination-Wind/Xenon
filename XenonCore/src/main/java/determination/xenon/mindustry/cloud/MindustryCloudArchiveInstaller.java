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

import determination.xenon.mindustry.cloud.MindustryCloudArchiveManifest.ModEntry;
import determination.xenon.mindustry.mod.MindustryLocalMod;
import determination.xenon.mindustry.mod.MindustryModManager;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// Restores a whole-instance cloud archive into an instance data directory.
///
/// Every entry is validated before anything is written: absolute paths, `..`
/// segments, drive letters and backslashes are rejected, the entry count and
/// total uncompressed size are bounded, and only the documented top-level
/// entries are accepted. Existing data is copied into a timestamped backup
/// directory first; a failed restore therefore never destroys the old state.
@NotNullByDefault
public final class MindustryCloudArchiveInstaller {

    /// Maximum number of zip entries accepted.
    public static final int MAX_ENTRIES = 20_000;

    /// Maximum total uncompressed size accepted (4 GiB).
    public static final long MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024;

    /// Top-level directories that may be restored.
    private static final Set<String> ALLOWED_DIRECTORIES =
            Set.of("saves", "maps", "schematics", "mods");

    /// Timestamp format for backup directories.
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    /// Result of one restore.
    ///
    /// @param manifest    parsed archive manifest
    /// @param backupDir   directory holding the pre-restore data
    /// @param missingMods Mod ids the archive did not contain and that are not installed
    public record RestoreResult(MindustryCloudArchiveManifest manifest, Path backupDir,
                                List<String> missingMods) {
        /// Copies the Mod list.
        public RestoreResult {
            missingMods = List.copyOf(missingMods);
        }
    }

    /// Restores an archive.
    ///
    /// @param archive    downloaded archive
    /// @param dataDir    instance data directory
    /// @param backupRoot root under which the pre-restore backup is created
    /// @return restore details
    /// @throws IOException when validation, backup or extraction fails
    public static RestoreResult install(Path archive, Path dataDir, Path backupRoot)
            throws IOException {
        MindustryCloudArchiveManifest manifest = readManifest(archive);
        Files.createDirectories(dataDir);
        Path staging = dataDir.resolve(".xenon-cloud-staging-" + System.nanoTime());
        Path backup = backupRoot.resolve(STAMP.format(Instant.now()));
        try {
            Files.createDirectories(staging);
            extract(archive, staging);
            backupExisting(dataDir, backup);
            replaceTrees(staging, dataDir);
            return new RestoreResult(manifest, backup, missingMods(dataDir, manifest));
        } finally {
            deleteTree(staging);
        }
    }

    /// Reads and validates the manifest of an archive.
    ///
    /// @param archive archive to read
    /// @return the parsed manifest
    /// @throws IOException when the archive is unreadable or the manifest is missing
    public static MindustryCloudArchiveManifest readManifest(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry(MindustryCloudArchiveManifest.ENTRY_NAME);
            if (entry == null) {
                throw new IOException("cloud archive has no manifest");
            }
            try (InputStream input = zip.getInputStream(entry)) {
                return MindustryCloudArchiveManifest.parse(
                        new String(input.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                throw new IOException("cloud archive manifest is invalid: " + e.getMessage(), e);
            }
        }
    }

    /// Validates every entry and extracts it into the staging directory.
    private static void extract(Path archive, Path staging) throws IOException {
        int entries = 0;
        long total = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("cloud archive has too many entries");
                }
                String name = entry.getName();
                validateEntryName(name);
                if (entry.isDirectory()) {
                    continue;
                }
                total += Math.max(0, entry.getSize());
                if (total > MAX_TOTAL_BYTES) {
                    throw new IOException("cloud archive is too large");
                }
                if (MindustryCloudArchiveManifest.ENTRY_NAME.equals(name)) {
                    continue;
                }
                Path target = staging.resolve(name).normalize();
                if (!target.startsWith(staging.normalize())) {
                    throw new IOException("cloud archive entry escapes the target: " + name);
                }
                Path parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);
                try (InputStream input = zip.getInputStream(entry);
                     OutputStream output = Files.newOutputStream(target)) {
                    input.transferTo(output);
                }
            }
        }
    }

    /// Rejects absolute, traversing and non-whitelisted entry names.
    private static void validateEntryName(String name) throws IOException {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.contains("..") || name.contains("\\") || name.contains(":")
                || name.indexOf('\0') >= 0) {
            throw new IOException("cloud archive entry is unsafe: " + name);
        }
        if (MindustryCloudArchiveManifest.ENTRY_NAME.equals(name)) {
            return;
        }
        int slash = name.indexOf('/');
        String top = slash < 0 ? name : name.substring(0, slash);
        if (ALLOWED_DIRECTORIES.contains(top)) {
            return;
        }
        if ("settings.bin".equals(name)) {
            return;
        }
        throw new IOException("cloud archive entry is not restorable: " + name);
    }

    /// Copies existing top-level data into the backup directory.
    private static void backupExisting(Path dataDir, Path backup) throws IOException {
        boolean copied = false;
        for (String directory : ALLOWED_DIRECTORIES) {
            if ("mods".equals(directory)) {
                continue;
            }
            Path source = dataDir.resolve(directory);
            if (Files.isDirectory(source)) {
                copyTree(source, backup.resolve(directory));
                copied = true;
            }
        }
        Path settings = dataDir.resolve("settings.bin");
        if (Files.isRegularFile(settings)) {
            Files.createDirectories(backup);
            Files.copy(settings, backup.resolve("settings.bin"),
                    StandardCopyOption.REPLACE_EXISTING);
            copied = true;
        }
        if (!copied) {
            Files.createDirectories(backup);
        }
    }

    /// Replaces the restored trees in the data directory.
    private static void replaceTrees(Path staging, Path dataDir) throws IOException {
        for (String directory : List.of("saves", "maps", "schematics")) {
            Path source = staging.resolve(directory);
            Path target = dataDir.resolve(directory);
            if (!Files.isDirectory(source)) {
                continue;
            }
            deleteTree(target);
            copyTree(source, target);
        }
        Path settings = staging.resolve("settings.bin");
        if (Files.isRegularFile(settings)) {
            Files.copy(settings, dataDir.resolve("settings.bin"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        Path mods = staging.resolve("mods");
        if (Files.isDirectory(mods)) {
            copyTree(mods, dataDir.resolve("mods"));
        }
    }

    /// Lists Mods that the archive did not pack and the instance still lacks.
    private static List<String> missingMods(Path dataDir, MindustryCloudArchiveManifest manifest) {
        Set<String> installed = new HashSet<>();
        Path modsDir = dataDir.resolve("mods");
        if (Files.isDirectory(modsDir)) {
            try {
                for (MindustryLocalMod mod : new MindustryModManager(modsDir, dataDir).scanAll()) {
                    installed.add(mod.getInternalName().toLowerCase(Locale.ROOT));
                }
            } catch (RuntimeException e) {
                Logger.LOG.info("Cannot scan restored Mods: " + e.getMessage());
            }
        }
        List<String> missing = new ArrayList<>();
        for (ModEntry mod : manifest.mods()) {
            if (mod.packed()) {
                continue;
            }
            if (!installed.contains(mod.id().toLowerCase(Locale.ROOT))) {
                missing.add(mod.id());
            }
        }
        return missing;
    }

    /// Copies a tree, replacing files with the same name.
    private static void copyTree(Path source, Path target) throws IOException {
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(source)) {
            paths = stream.sorted(Comparator.comparing(Path::toString)).toList();
        }
        for (Path path : paths) {
            Path relative = source.relativize(path);
            Path destination = target.resolve(relative.toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Path parent = destination.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /// Deletes a tree, ignoring a missing path.
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

    private MindustryCloudArchiveInstaller() {
    }
}
