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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/// HMCL-{@link Task} adapter around {@link MirrorDownloader}.
///
/// <p>Reports {@code (read, total)} into {@link Task#updateProgress} so
/// {@code TaskListPane} draws the per-subtask bar, and inherits the
/// global download-speed counter for free since the downloaders already
/// feed {@link determination.xenon.task.FetchTask#recordDownloadedBytes}.</p>
///
/// <p>Every task owns a {@link DownloadControl} published under
/// {@link #CONTROL_PROPERTY}; the wizard dialog reads it to offer a
/// pause/resume button. Interrupted transfers keep a `.part` file, so
/// retries and restarted launchers resume instead of starting over.</p>
///
/// <p>Verification and the retry / failover chain live in
/// [MindustryDownloadPolicy] so they can be tested without a JavaFX toolkit;
/// this task only adds the progress reporting and the pause/resume button.</p>
@NotNullByDefault
public final class MindustryDownloadTask extends Task<Void> {

    /// Task property key carrying the {@link DownloadControl} instance.
    public static final String CONTROL_PROPERTY = "xenon.download.control";

    private final String sourceUrl;
    private final Path target;
    private final long expectedSize;
    private final Path cachesRoot;
    private final boolean archive;
    private final @org.jetbrains.annotations.Nullable String fallbackUrl;
    private final @Nullable String expectedHash;

    /// Pause/resume/cancel state shared with the progress dialog.
    private final DownloadControl control = new DownloadControl();

    /**
     * @param sourceUrl    direct jar URL, or a platform zip URL when {@code archive} is true
     * @param target       destination file
     * @param expectedSize size hint for progress, or 0 if unknown
     * @param cachesRoot   launcher caches dir (used for the preferred-mirror cache)
     */
    public MindustryDownloadTask(String sourceUrl, Path target, long expectedSize, Path cachesRoot) {
        this(sourceUrl, target, expectedSize, cachesRoot, false, null, null);
    }

    /** Creates a download task, optionally extracting {@code Mindustry.jar} from a zip. */
    public MindustryDownloadTask(String sourceUrl, Path target, long expectedSize,
                                 Path cachesRoot, boolean archive) {
        this(sourceUrl, target, expectedSize, cachesRoot, archive, null, null);
    }

    /**
     * @param fallbackUrl tried after the primary URL fails; typically the GitHub release asset
     */
    public MindustryDownloadTask(String sourceUrl, Path target, long expectedSize,
                                 Path cachesRoot, boolean archive,
                                 @org.jetbrains.annotations.Nullable String fallbackUrl) {
        this(sourceUrl, target, expectedSize, cachesRoot, archive, fallbackUrl, null);
    }

    /// Creates a download task with the integrity metadata of the source manifest.
    ///
    /// @param sourceUrl    direct jar URL, or a platform zip URL when {@code archive} is true
    /// @param target       destination file
    /// @param expectedSize expected size in bytes from the manifest, or 0 when unknown
    /// @param cachesRoot   launcher caches dir (used for the preferred-mirror cache)
    /// @param archive      whether {@code sourceUrl} is a zip that must be unpacked
    /// @param fallbackUrl  alternate URL on the other source family, or {@code null}
    /// @param expectedHash expected lowercase-hex SHA-256, or `null` when the feed omitted it
    public MindustryDownloadTask(String sourceUrl, Path target, long expectedSize,
                                 Path cachesRoot, boolean archive,
                                 @org.jetbrains.annotations.Nullable String fallbackUrl,
                                 @Nullable String expectedHash) {
        this.sourceUrl = sourceUrl;
        this.target = target;
        this.expectedSize = expectedSize;
        this.cachesRoot = cachesRoot;
        this.archive = archive;
        this.fallbackUrl = fallbackUrl == null || fallbackUrl.isBlank() ? null : fallbackUrl;
        this.expectedHash = expectedHash == null || expectedHash.isBlank()
                ? null : expectedHash.toLowerCase(java.util.Locale.ROOT);
        setName(target.getFileName().toString());
        getProperties().put(CONTROL_PROPERTY, control);
    }

    /// The pause/resume/cancel state of this download.
    public DownloadControl getDownloadControl() {
        return control;
    }

    @Override
    public void execute() throws Exception {
        MindustryDownloadPolicy.download(sourceUrl, target, expectedSize, cachesRoot,
                archive, fallbackUrl, expectedHash, control, this::updateDownloadProgress);
    }

    private void updateDownloadProgress(long read, long total) {
        // Propagate HMCL's task cancellation into the download control so the
        // cancel contract (stop + remove partial files) also applies when the
        // executor interrupts the worker.
        if (isCancelled()) {
            control.cancel();
        }
        // updateProgress requires read <= total > 0; bail if total is unknown.
        if (total > 0 && read >= 0) {
            long capped = Math.min(read, total);
            updateProgress(capped, total);
        }
    }
}
