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
package determination.xenon.mindustry.download;

import kala.compress.archivers.zip.ZipArchiveEntry;
import kala.compress.archivers.zip.ZipArchiveReader;
import determination.xenon.task.Task;
import determination.xenon.util.io.CompressingUtils;
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * HMCL-{@link Task} adapter around {@link MirrorDownloader}.
 *
 * <p>Reports {@code (read, total)} into {@link Task#updateProgress} so
 * {@code TaskListPane} draws the per-subtask bar, and inherits the
 * global download-speed counter for free since {@link MirrorDownloader}
 * already feeds {@link determination.xenon.task.FetchTask#recordDownloadedBytes}.</p>
 */
public final class MindustryDownloadTask extends Task<Void> {
    private final String sourceUrl;
    private final Path target;
    private final long expectedSize;
    private final Path cachesRoot;
    private final boolean archive;
    private final @org.jetbrains.annotations.Nullable String fallbackUrl;

    /**
     * @param sourceUrl    direct jar URL, or a platform zip URL when {@code archive} is true
     * @param target       destination file
     * @param expectedSize size hint for progress, or 0 if unknown
     * @param cachesRoot   launcher caches dir (used for the preferred-mirror cache)
     */
    public MindustryDownloadTask(String sourceUrl, Path target, long expectedSize, Path cachesRoot) {
        this(sourceUrl, target, expectedSize, cachesRoot, false, null);
    }

    /** Creates a download task, optionally extracting {@code Mindustry.jar} from a zip. */
    public MindustryDownloadTask(String sourceUrl, Path target, long expectedSize,
                                 Path cachesRoot, boolean archive) {
        this(sourceUrl, target, expectedSize, cachesRoot, archive, null);
    }

    /**
     * @param fallbackUrl tried after the primary URL fails; typically the GitHub release asset
     */
    public MindustryDownloadTask(String sourceUrl, Path target, long expectedSize,
                                 Path cachesRoot, boolean archive,
                                 @org.jetbrains.annotations.Nullable String fallbackUrl) {
        this.sourceUrl = sourceUrl;
        this.target = target;
        this.expectedSize = expectedSize;
        this.cachesRoot = cachesRoot;
        this.archive = archive;
        this.fallbackUrl = fallbackUrl == null || fallbackUrl.isBlank() ? null : fallbackUrl;
        setName(target.getFileName().toString());
    }

    @Override
    public void execute() throws Exception {
        if (!archive) {
            try {
                new MirrorDownloader(cachesRoot).download(sourceUrl, target, expectedSize, this::updateDownloadProgress);
            } catch (IOException primary) {
                if (fallbackUrl == null || fallbackUrl.equals(sourceUrl)) throw primary;
                Logger.LOG.warning("Primary Mindustry download failed (" + primary.getMessage()
                        + "); trying " + fallbackUrl);
                new MirrorDownloader(cachesRoot).download(fallbackUrl, target, expectedSize, this::updateDownloadProgress);
            }
            return;
        }

        Path parent = target.getParent();
        if (parent == null) throw new IOException("Mindustry target has no parent: " + target);
        Path staging = parent.resolve("_xenon_mindustry_archive");
        Files.createDirectories(staging);
        Path zip = staging.resolve(target.getFileName() + ".zip");
        try {
            new MdtbbsSegmentedDownloader().download(sourceUrl, zip, expectedSize,
                    this::updateDownloadProgress);
            extractMindustryJar(zip, target);
            Files.createDirectories(target.getParent());
            Logger.LOG.info("Extracted Mindustry.jar from " + sourceUrl);
        } finally {
            Files.deleteIfExists(zip);
            Files.deleteIfExists(staging);
        }
    }

    private void updateDownloadProgress(long read, long total) {
        // updateProgress requires read <= total > 0; bail if total is unknown.
        if (total > 0 && read >= 0) {
            long capped = Math.min(read, total);
            updateProgress(capped, total);
        }
    }

    private static void extractMindustryJar(Path zip, Path target) throws IOException {
        try (ZipArchiveReader reader = CompressingUtils.openZipFileWithPossibleEncoding(
                zip, java.nio.charset.StandardCharsets.UTF_8)) {
            for (ZipArchiveEntry entry : reader.getEntries()) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                int slash = name.lastIndexOf('/');
                if (!"mindustry.jar".equalsIgnoreCase(slash >= 0
                        ? name.substring(slash + 1) : name)) continue;
                Files.createDirectories(target.getParent());
                try (var input = reader.getInputStream(entry)) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            }
        }
        throw new IOException("MDTbbs archive does not contain Mindustry.jar");
    }
}
