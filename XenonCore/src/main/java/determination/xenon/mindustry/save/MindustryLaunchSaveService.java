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
package determination.xenon.mindustry.save;

import kala.compress.archivers.zip.ZipArchiveEntry;
import kala.compress.archivers.zip.ZipArchiveReader;
import determination.xenon.util.io.CompressingUtils;
import determination.xenon.util.io.FileUtils;
import determination.xenon.util.io.Unzipper;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/// Manages launcher-selected Mindustry data archive saves.
///
/// In Mindustry/MindustryX, `.msav` files are map/sector files and `.mrep`
/// files are replays. Xenon's per-instance "save" selector therefore works
/// with complete `.zip` data archives: the archive root is expected to look
/// like a Mindustry data directory, for example `settings.bin`, `maps/`,
/// `saves/`, `mods/`, and `schematics/`.
@NotNullByDefault
public final class MindustryLaunchSaveService {

    /// Directory under the version root that stores imported save archives.
    public static final String ARCHIVES_DIR = "save-archives";

    /// Directory under the version root that stores extracted runtime copies.
    public static final String RUNTIMES_DIR = ".save-runtimes";

    /// Marker written into extracted runtime directories.
    private static final String MARKER_FILE = ".xenon-save-archive.properties";

    /// Legacy helper mod from the earlier `.msav` based implementation.
    private static final String LEGACY_HELPER_MOD_DIR = "xenon-launch-save-loader";

    /// Legacy temporary `.msav` slot from the earlier implementation.
    private static final String LEGACY_ACTIVE_SAVE_FILE = "xenon-selected.msav";

    /// Returns the archive storage directory for a version root.
    public static Path archivesDir(Path versionRoot) {
        return versionRoot.resolve(ARCHIVES_DIR).toAbsolutePath().normalize();
    }

    /// Returns the extracted runtime directory root for a version root.
    public static Path runtimesDir(Path versionRoot) {
        return versionRoot.resolve(RUNTIMES_DIR).toAbsolutePath().normalize();
    }

    /// List imported archive saves, newest first.
    public static @Unmodifiable List<MindustrySaveArchive> listArchives(Path versionRoot) throws IOException {
        Path archivesDir = archivesDir(versionRoot);
        if (!Files.isDirectory(archivesDir)) {
            return List.of();
        }

        List<MindustrySaveArchive> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(archivesDir)) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry) || !isZip(entry.getFileName().toString())) {
                    continue;
                }
                result.add(readArchive(entry));
            }
        }
        result.sort(Comparator.comparing(MindustrySaveArchive::lastModified).reversed());
        return List.copyOf(result);
    }

    /// Import a `.zip` data archive into the version's archive storage.
    public static MindustrySaveArchive importArchive(Path versionRoot, Path source) throws IOException {
        if (!Files.isRegularFile(source)) {
            throw new IOException("Save archive does not exist: " + source);
        }
        if (!isZip(source.getFileName().toString())) {
            throw new IOException("Save archive must be a .zip file: " + source);
        }

        MindustrySaveArchive sourceArchive = readArchive(source);
        if (!looksLikeDataArchive(sourceArchive)) {
            throw new IOException("Not a Mindustry data archive: " + source);
        }

        Path archivesDir = archivesDir(versionRoot);
        Files.createDirectories(archivesDir);
        Path target = uniqueArchivePath(archivesDir, source.getFileName().toString());
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        return readArchive(target);
    }

    /// Delete an imported archive and its extracted runtime directory.
    public static void deleteArchive(Path versionRoot, String archiveFileName) throws IOException {
        Path archive = resolveArchive(versionRoot, archiveFileName);
        Files.deleteIfExists(archive);
        FileUtils.deleteDirectory(runtimeDir(versionRoot, archive.getFileName().toString()));
    }

    /// Resolve the effective data directory for launch.
    ///
    /// When no archive is selected, the caller-provided `defaultDataDir` is
    /// returned. When an archive is selected, Xenon extracts it into a managed
    /// runtime directory and returns that directory; the user's global or
    /// isolated data directory is not overwritten.
    public static Path prepare(Path versionRoot,
                               Path defaultDataDir,
                               @Nullable String selectedArchiveFile) throws IOException {
        removeLegacyAutoLoadFiles(defaultDataDir);
        if (selectedArchiveFile == null || selectedArchiveFile.isBlank()) {
            return defaultDataDir;
        }

        Path archive = resolveArchive(versionRoot, selectedArchiveFile);
        MindustrySaveArchive summary = readArchive(archive);
        if (!looksLikeDataArchive(summary)) {
            throw new IOException("Not a Mindustry data archive: " + archive);
        }

        Path runtimeDir = runtimeDir(versionRoot, archive.getFileName().toString());
        if (!isRuntimeCurrent(runtimeDir, archive)) {
            FileUtils.deleteDirectory(runtimeDir);
            Files.createDirectories(runtimeDir);
            new Unzipper(archive, runtimeDir)
                    .setEncoding(StandardCharsets.UTF_8)
                    .setReplaceExistentFile(true)
                    .unzip();
            removeLegacyAutoLoadFiles(runtimeDir);
            writeMarker(runtimeDir, archive);
        }
        return runtimeDir;
    }

    private static MindustrySaveArchive readArchive(Path archive) throws IOException {
        long size = Files.size(archive);
        Instant modified = Files.getLastModifiedTime(archive).toInstant();
        boolean hasSettings = false;
        int maps = 0;
        int sectors = 0;
        int replays = 0;
        int mods = 0;

        try (ZipArchiveReader reader = CompressingUtils.openZipFileWithPossibleEncoding(archive, StandardCharsets.UTF_8)) {
            for (ZipArchiveEntry entry : reader.getEntries()) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = normalizeEntry(entry.getName());
                String lower = name.toLowerCase(Locale.ROOT);
                if ("settings.bin".equals(lower)) {
                    hasSettings = true;
                } else if (lower.startsWith("maps/") && lower.endsWith(".msav")) {
                    maps++;
                } else if (lower.startsWith("saves/") && lower.endsWith(".msav")) {
                    sectors++;
                } else if (lower.startsWith("saves/") && lower.endsWith(".mrep")) {
                    replays++;
                } else if (lower.startsWith("mods/") && (lower.endsWith(".zip") || lower.endsWith(".jar"))) {
                    mods++;
                }
            }
        }

        return new MindustrySaveArchive(archive, size, modified, hasSettings, maps, sectors, replays, mods);
    }

    private static boolean looksLikeDataArchive(MindustrySaveArchive archive) {
        return archive.hasSettings()
                || archive.maps() > 0
                || archive.sectors() > 0
                || archive.replays() > 0
                || archive.mods() > 0;
    }

    private static Path resolveArchive(Path versionRoot, String archiveFileName) throws IOException {
        String simpleName = Path.of(archiveFileName).getFileName().toString();
        if (!simpleName.equals(archiveFileName)
                || archiveFileName.indexOf('/') >= 0
                || archiveFileName.indexOf('\\') >= 0) {
            throw new IOException("Save archive must be a file name, not a path: " + archiveFileName);
        }
        if (!isZip(simpleName)) {
            throw new IOException("Save archive must be a .zip file: " + archiveFileName);
        }
        Path archivesDir = archivesDir(versionRoot);
        Path archive = archivesDir.resolve(simpleName).toAbsolutePath().normalize();
        if (!archive.startsWith(archivesDir)) {
            throw new IOException("Save archive escapes archive directory: " + archiveFileName);
        }
        if (!Files.isRegularFile(archive)) {
            throw new IOException("Selected save archive does not exist: " + archive);
        }
        return archive;
    }

    private static Path uniqueArchivePath(Path archiveDir, String fileName) {
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }

        Path target = archiveDir.resolve(fileName);
        for (int index = 1; Files.exists(target); index++) {
            target = archiveDir.resolve(base + "-" + index + ext);
        }
        return target;
    }

    private static Path runtimeDir(Path versionRoot, String archiveFileName) {
        String safe = archiveFileName.replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_");
        if (safe.length() > 96) {
            safe = safe.substring(0, 96);
        }
        return runtimesDir(versionRoot).resolve(safe).toAbsolutePath().normalize();
    }

    private static boolean isRuntimeCurrent(Path runtimeDir, Path archive) throws IOException {
        Path marker = runtimeDir.resolve(MARKER_FILE);
        if (!Files.isRegularFile(marker)) {
            return false;
        }

        Properties properties = new Properties();
        try (var in = Files.newInputStream(marker)) {
            properties.load(in);
        }
        return archive.getFileName().toString().equals(properties.getProperty("archive"))
                && Long.toString(Files.size(archive)).equals(properties.getProperty("size"))
                && Long.toString(Files.getLastModifiedTime(archive).toMillis())
                .equals(properties.getProperty("modified"));
    }

    private static void writeMarker(Path runtimeDir, Path archive) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("archive", archive.getFileName().toString());
        properties.setProperty("size", Long.toString(Files.size(archive)));
        properties.setProperty("modified", Long.toString(Files.getLastModifiedTime(archive).toMillis()));
        try (var out = Files.newOutputStream(runtimeDir.resolve(MARKER_FILE))) {
            properties.store(out, "Xenon Mindustry save archive runtime");
        }
    }

    private static void removeLegacyAutoLoadFiles(Path dataDir) throws IOException {
        FileUtils.deleteDirectory(dataDir.resolve("mods").resolve(LEGACY_HELPER_MOD_DIR));
        Files.deleteIfExists(dataDir.resolve("saves").resolve(LEGACY_ACTIVE_SAVE_FILE));
    }

    private static String normalizeEntry(String name) {
        return name.replace('\\', '/').replaceFirst("^/+", "");
    }

    private static boolean isZip(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private MindustryLaunchSaveService() {
    }
}
