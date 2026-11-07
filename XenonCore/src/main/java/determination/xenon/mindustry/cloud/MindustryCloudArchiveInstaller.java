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
import java.util.concurrent.atomic.AtomicLong;
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

    /// Windows device names that can never be used as file or directory names.
    private static final Set<String> WINDOWS_DEVICE_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /// Timestamp format for backup directories.
    static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    /// Running suffix that keeps backup names unique within one process.
    private static final AtomicLong BACKUP_SEQUENCE = new AtomicLong();

    /// Builds a unique backup directory name with the `yyyyMMdd-HHmmss` prefix.
    static String backupName(Instant now) {
        return STAMP.format(now) + "-" + Long.toHexString(System.nanoTime()) + "-"
                + Long.toHexString(BACKUP_SEQUENCE.incrementAndGet());
    }

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
        Path backup = backupRoot.resolve(backupName(Instant.now()));
        try {
            Files.createDirectories(staging);
            extract(archive, staging);
            verifyHashes(staging, manifest);
            MindustryCloudManifestInstaller.rejectUndeclaredFiles(staging, manifest.files());
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
    ///
    /// The declared entry size is a cheap early reject; the bytes actually
    /// copied are counted separately so a lying header cannot bypass the cap.
    private static void extract(Path archive, Path staging) throws IOException {
        int entries = 0;
        long declaredTotal = 0;
        long copiedTotal = 0;
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
                long declaredSize = Math.max(0, entry.getSize());
                if (declaredSize > MAX_TOTAL_BYTES - declaredTotal) {
                    throw new IOException("cloud archive is too large");
                }
                declaredTotal += declaredSize;
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
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        if (read > MAX_TOTAL_BYTES - copiedTotal) {
                            throw new IOException("cloud archive is too large");
                        }
                        copiedTotal += read;
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    /// Rejects absolute, traversing and non-whitelisted entry names.
    ///
    /// Windows-reserved device names and path segments ending in `.` or a space
    /// are rejected as well, because the restore target cannot represent them.
    static void validateEntryName(String name) throws IOException {
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.contains("\\") || name.contains(":")
                || name.indexOf('\0') >= 0) {
            throw new IOException("cloud archive entry is unsafe: " + name);
        }
        validateSegments(name);
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

    /// Rejects Windows-reserved device names, parent segments and trailing dots/spaces.
    private static void validateSegments(String name) throws IOException {
        int start = 0;
        while (start < name.length()) {
            int slash = name.indexOf('/', start);
            int end = slash < 0 ? name.length() : slash;
            if (end > start) {
                String segment = name.substring(start, end);
                if ("..".equals(segment)) {
                    throw new IOException("cloud archive entry is unsafe: " + name);
                }
                validateSegment(segment, name);
            }
            if (slash < 0) {
                return;
            }
            start = slash + 1;
        }
    }

    /// Rejects one unsafe path segment.
    private static void validateSegment(String segment, String name) throws IOException {
        char last = segment.charAt(segment.length() - 1);
        if (last == '.' || last == ' ') {
            throw new IOException("cloud archive entry is unsafe: " + name);
        }
        int dot = segment.indexOf('.');
        String stem = (dot < 0 ? segment : segment.substring(0, dot))
                .toUpperCase(Locale.ROOT);
        if (WINDOWS_DEVICE_NAMES.contains(stem)) {
            throw new IOException("cloud archive entry is unsafe: " + name);
        }
    }

    /// Verifies the extracted files against the manifest hashes.
    private static void verifyHashes(Path staging, MindustryCloudArchiveManifest manifest)
            throws IOException {
        for (MindustryCloudArchiveManifest.FileEntry expected : manifest.files()) {
            verifyEntry(staging, expected);
        }
    }

    /// Verifies one staged file against its manifest entry.
    ///
    /// The file must exist inside `staging`; a positive expected size and a
    /// non-blank expected hash are enforced, while zero/blank values mean
    /// "not recorded".
    ///
    /// @param staging  directory holding the extracted files
    /// @param expected manifest entry to check
    /// @throws IOException when the path escapes `staging`, the file is missing
    ///                     or does not match
    static void verifyEntry(Path staging, MindustryCloudArchiveManifest.FileEntry expected)
            throws IOException {
        Path root = staging.normalize();
        Path file = root.resolve(expected.path()).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("cloud manifest entry escapes the staging directory: "
                    + expected.path());
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("cloud archive is missing " + expected.path());
        }
        long size = Files.size(file);
        if (expected.size() > 0 && size != expected.size()) {
            throw new IOException("cloud archive size mismatch for " + expected.path());
        }
        if (!expected.sha256().isBlank()
                && !expected.sha256().equalsIgnoreCase(sha256(file))) {
            throw new IOException("cloud archive hash mismatch for " + expected.path());
        }
    }

    /// Computes the lowercase hex SHA-256 of one file.
    static String sha256(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            java.security.MessageDigest digest;
            try {
                digest = java.security.MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 is not available", e);
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        }
    }

    /// Copies existing top-level data into the backup directory.
    static void backupExisting(Path dataDir, Path backup) throws IOException {
        boolean copied = false;
        for (String directory : ALLOWED_DIRECTORIES) {
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
    static void replaceTrees(Path staging, Path dataDir) throws IOException {
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
    static List<String> missingMods(Path dataDir, MindustryCloudArchiveManifest manifest) {
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
    static void copyTree(Path source, Path target) throws IOException {
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
    static void deleteTree(Path root) throws IOException {
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
