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

import java.nio.file.Path;

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

    /**
     * @param sourceUrl    canonical {@code https://github.com/.../releases/download/...} URL
     * @param target       destination file
     * @param expectedSize size hint for progress, or 0 if unknown
     * @param cachesRoot   launcher caches dir (used for the preferred-mirror cache)
     */
    public MindustryDownloadTask(String sourceUrl, Path target, long expectedSize, Path cachesRoot) {
        this.sourceUrl = sourceUrl;
        this.target = target;
        this.expectedSize = expectedSize;
        this.cachesRoot = cachesRoot;
        setName(target.getFileName().toString());
    }

    @Override
    public void execute() throws Exception {
        new MirrorDownloader(cachesRoot).download(sourceUrl, target, expectedSize, (read, total) -> {
            // updateProgress requires read <= total > 0; bail if total is unknown.
            if (total > 0 && read >= 0) {
                long capped = Math.min(read, total);
                updateProgress(capped, total);
            }
        });
    }
}
