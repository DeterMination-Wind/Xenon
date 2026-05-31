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

import determination.xenon.task.Task;
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;

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
    private final List<Path> reusableSources;

    /**
     * @param sourceUrl    canonical {@code https://github.com/.../releases/download/...} URL
     * @param target       destination file
     * @param expectedSize size hint for progress, or 0 if unknown
     * @param cachesRoot   launcher caches dir (used for the preferred-mirror cache)
     */
    public MindustryDownloadTask(String sourceUrl, Path target, long expectedSize, Path cachesRoot) {
        this(sourceUrl, target, expectedSize, cachesRoot, List.of());
    }

    /**
     * @param sourceUrl       canonical {@code https://github.com/.../releases/download/...} URL
     * @param target          destination file
     * @param expectedSize    size hint for progress, or 0 if unknown
     * @param cachesRoot      launcher caches dir (used for the preferred-mirror cache)
     * @param reusableSources local jar candidates that represent the same remote build
     */
    public MindustryDownloadTask(String sourceUrl,
                                 Path target,
                                 long expectedSize,
                                 Path cachesRoot,
                                 Collection<Path> reusableSources) {
        this.sourceUrl = sourceUrl;
        this.target = target;
        this.expectedSize = expectedSize;
        this.cachesRoot = cachesRoot;
        this.reusableSources = reusableSources == null ? List.of() : List.copyOf(reusableSources);
        setName(target.getFileName().toString());
    }

    @Override
    public void execute() throws Exception {
        Path reusable = findReusableSource();
        if (reusable != null) {
            if (sameFile(reusable, target)) {
                Logger.LOG.info("MindustryDownloadTask: reusing existing target " + target);
            } else {
                Path parent = target.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.copy(reusable, target, StandardCopyOption.REPLACE_EXISTING);
                Logger.LOG.info("MindustryDownloadTask: copied local jar " + reusable + " -> " + target);
            }
            updateProgress(1.0);
            return;
        }
        new MirrorDownloader(cachesRoot).download(sourceUrl, target, expectedSize, (read, total) -> {
            // updateProgress requires read <= total > 0; bail if total is unknown.
            if (total > 0 && read >= 0) {
                long capped = Math.min(read, total);
                updateProgress(capped, total);
            }
        });
    }

    private Path findReusableSource() throws IOException {
        for (Path candidate : reusableSources) {
            if (candidate == null || !Files.isRegularFile(candidate)) {
                continue;
            }
            if (expectedSize > 0 && Files.size(candidate) != expectedSize) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b);
        } catch (Exception ignored) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }
}
