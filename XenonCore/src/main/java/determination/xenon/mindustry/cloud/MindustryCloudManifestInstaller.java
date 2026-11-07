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
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/// Restores a manifest upload whose files are already materialized on disk.
///
/// Unlike [MindustryCloudArchiveInstaller], this path has no zip to extract:
/// the caller downloads every file referenced by the manifest into a staging
/// directory first. The installer validates every expected path and file before
/// it touches the data directory, rejects staged files the manifest does not
/// describe, backs up the old data, and then merges the staged tree with the
/// same rules as the zip restore.
@NotNullByDefault
public final class MindustryCloudManifestInstaller {

    /// Restores a staged manifest tree into a data directory.
    ///
    /// Callers that download the staged files themselves MUST materialize every
    /// path through [#stagingTarget] so traversal is rejected before a file is
    /// written outside the staging tree.
    ///
    /// Validation happens before [MindustryCloudArchiveInstaller#backupExisting]
    /// and [MindustryCloudArchiveInstaller#replaceTrees]: if a path is unsafe,
    /// a file is missing, a size or hash does not match, or the staging tree
    /// contains an undeclared file, the data directory is left untouched.
    ///
    /// @param staging    directory holding the already-downloaded files
    /// @param dataDir    instance data directory
    /// @param backupRoot root under which the pre-restore backup is created
    /// @param expected   manifest file entries the staging tree must match
    /// @param mods       manifest Mod entries used for the restore result
    /// @return the synthesized manifest, the backup directory and missing Mods
    /// @throws IOException when validation, backup or merging fails
    public static MindustryCloudArchiveInstaller.RestoreResult install(
            Path staging, Path dataDir, Path backupRoot, List<FileEntry> expected,
            List<ModEntry> mods) throws IOException {
        List<FileEntry> files = List.copyOf(expected);
        List<ModEntry> entries = List.copyOf(mods);
        if (files.isEmpty()) {
            throw new IOException("cloud manifest has no files");
        }
        if (files.size() > MindustryCloudArchiveInstaller.MAX_ENTRIES) {
            throw new IOException("cloud manifest has too many files");
        }
        Set<String> normalizedPaths = new HashSet<>();
        long total = 0;
        for (FileEntry file : files) {
            Path target = stagingTarget(staging, file.path());
            String normalized = staging.normalize().relativize(target).toString()
                    .replace('\\', '/');
            if (!normalizedPaths.add(normalized)) {
                throw new IOException("cloud manifest has a duplicate path: " + file.path());
            }
            if (file.size() <= 0) {
                throw new IOException("cloud manifest has an invalid size: " + file.path());
            }
            if (file.size() > MindustryCloudArchiveInstaller.MAX_TOTAL_BYTES - total) {
                throw new IOException("cloud manifest is too large");
            }
            total += file.size();
        }
        for (FileEntry file : files) {
            MindustryCloudArchiveInstaller.verifyEntry(staging, file);
        }
        rejectUndeclaredFiles(staging, files);
        Instant now = Instant.now();
        MindustryCloudArchiveManifest manifest = new MindustryCloudArchiveManifest(
                MindustryCloudArchiveManifest.SCHEMA, "", 0, now, entries, files);
        Files.createDirectories(dataDir);
        Path backup = backupRoot.resolve(MindustryCloudArchiveInstaller.backupName(now));
        MindustryCloudArchiveInstaller.backupExisting(dataDir, backup);
        MindustryCloudArchiveInstaller.replaceTrees(staging, dataDir);
        return new MindustryCloudArchiveInstaller.RestoreResult(manifest, backup,
                MindustryCloudArchiveInstaller.missingMods(dataDir, manifest));
    }

    /// Resolves one manifest path inside the staging directory.
    ///
    /// The path must pass the installer entry-name rules and stay inside
    /// `staging` after normalization. Callers use this to materialize downloaded
    /// files, so unsafe paths are rejected before a blob write.
    ///
    /// @param staging root of the staging tree
    /// @param path    manifest path using `/` separators
    /// @return the normalized path inside `staging`
    /// @throws IOException when the path is unsafe or escapes `staging`
    public static Path stagingTarget(Path staging, String path) throws IOException {
        if (!MindustryCloudManifestPlan.isRestorablePath(path)) {
            throw new IOException("cloud manifest entry is not restorable: " + path);
        }
        MindustryCloudArchiveInstaller.validateEntryName(path);
        Path root = staging.normalize();
        Path target = root.resolve(path).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("cloud manifest entry escapes the staging directory: " + path);
        }
        return target;
    }

    /// Rejects regular files that the manifest does not describe.
    ///
    /// Shared with the zip installer so both restore paths reject undeclared
    /// staged content before the data directory is touched.
    ///
    /// @param staging  staging tree to inspect
    /// @param expected manifest entries the tree may contain
    /// @throws IOException when the tree is missing or contains an undeclared file
    static void rejectUndeclaredFiles(Path staging, List<FileEntry> expected)
            throws IOException {
        if (!Files.isDirectory(staging)) {
            if (!expected.isEmpty()) {
                throw new IOException("cloud manifest staging directory is missing");
            }
            return;
        }
        Path root = staging.normalize();
        Set<String> allowed = new HashSet<>();
        for (FileEntry file : expected) {
            Path target = stagingTarget(staging, file.path());
            allowed.add(root.relativize(target).toString().replace('\\', '/'));
        }
        try (Stream<Path> stream = Files.walk(staging)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                String relative = staging.relativize(path).normalize()
                        .toString().replace('\\', '/');
                if (!allowed.contains(relative)) {
                    throw new IOException("cloud manifest staging contains an undeclared file: "
                            + relative);
                }
            }
        }
    }

    private MindustryCloudManifestInstaller() {
    }
}
