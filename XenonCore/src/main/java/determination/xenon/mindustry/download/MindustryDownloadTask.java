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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

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
/// <p>When the source feed published a size or a hash, the finished file is
/// checked against it before the task reports success. A mismatch is not a
/// transfer failure — the bytes are all there but wrong — so the artifact is
/// deleted, the same source is retried once, and the other source family is
/// tried last. A pause or cancel never switches sides.</p>
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

    /// Thrown when a completed file does not match the source manifest.
    ///
    /// Distinct from a transfer failure: every byte arrived, but they are not
    /// the bytes the manifest describes, so the partial file is discarded
    /// instead of being resumed from.
    static final class ArtifactIntegrityException extends IOException {
        /// Creates the exception with the given detail message.
        ///
        /// @param message description of the mismatch
        ArtifactIntegrityException(String message) {
            super(message);
        }
    }

    @Override
    public void execute() throws Exception {
        MindustryDownloadSource source = MindustryDownloadSource.current();
        List<Attempt> attempts = buildAttempts(source);
        if (attempts.isEmpty()) {
            throw new IOException("No download route is allowed for " + sourceUrl
                    + " under " + source);
        }
        Logger.LOG.info("Mindustry download " + target.getFileName() + " from " + source
                + ": " + attempts.size() + " allowed attempt(s)");

        @Nullable IOException lastError = null;
        for (int index = 0; index < attempts.size(); index++) {
            Attempt attempt = attempts.get(index);
            if (attempt.failover()) {
                Logger.LOG.warning("Switching to the " + (MindustryDownloadSource
                        .isFileStationUrl(attempt.url()) ? "domestic" : "official")
                        + " source for " + target.getFileName() + " after: " + messageOf(lastError));
            }
            try {
                attemptDownload(attempt.url());
                return;
            } catch (ResumableDownloader.DownloadCancelledException cancelled) {
                // A pause or cancel is the user's decision, never a route problem.
                throw cancelled;
            } catch (IOException e) {
                lastError = e;
                if (e instanceof ArtifactIntegrityException) {
                    // Resuming from a corrupt artifact would reproduce it, so
                    // drop it before the retry.
                    discardArtifact();
                }
                Logger.LOG.warning("Mindustry download attempt " + (index + 1) + "/"
                        + attempts.size() + " failed for " + attempt.url()
                        + ": " + e.getMessage());
            }
        }
        throw new IOException("Mindustry download failed after " + attempts.size()
                + " attempt(s) for " + target.getFileName() + ": " + messageOf(lastError),
                lastError);
    }

    /// One download attempt in the retry chain.
    ///
    /// @param url      URL to fetch
    /// @param failover whether this attempt switched to the other source family
    private record Attempt(String url, boolean failover) {
    }

    /// Builds the ordered attempt chain for the tier in effect.
    ///
    /// The primary URL is tried twice: a truncated transfer is usually a flaky
    /// connection or a stale mirror cache, and the second attempt resumes the
    /// partial file. The other source family is appended only when the tier
    /// allows switching sides and the fallback URL belongs to an allowed route.
    ///
    /// @param source tier captured for the whole task
    /// @return the attempts to run, never empty on a well-formed artifact
    private List<Attempt> buildAttempts(MindustryDownloadSource source) {
        List<Attempt> attempts = new ArrayList<>(3);
        if (source.allowsArtifactUrl(sourceUrl)) {
            attempts.add(new Attempt(sourceUrl, false));
            attempts.add(new Attempt(sourceUrl, false));
        }
        if (fallbackUrl != null
                && !fallbackUrl.equals(sourceUrl)
                && source.shouldFailover(
                        MindustryDownloadSource.AttemptOutcome.TRANSFER_FAILED)
                && source.allowsArtifactUrl(fallbackUrl)) {
            attempts.add(new Attempt(fallbackUrl, true));
        }
        return attempts;
    }

    /// Downloads one URL and verifies the resulting artifact.
    ///
    /// @param url URL to fetch
    /// @throws IOException when the transfer or the integrity check fails
    private void attemptDownload(String url) throws IOException {
        if (!archive) {
            new MirrorDownloader(cachesRoot).download(url, target, expectedSize,
                    this::updateDownloadProgress, control);
            verify(target, url);
            return;
        }

        Path parent = target.getParent();
        if (parent == null) throw new IOException("Mindustry target has no parent: " + target);
        Path staging = parent.resolve("_xenon_mindustry_archive");
        Files.createDirectories(staging);
        Path zip = staging.resolve(target.getFileName() + ".zip");
        boolean completed = false;
        try {
            new ResumableDownloader().download(url, zip, expectedSize,
                    this::updateDownloadProgress, control);
            // Archive mode checks the zip itself: the manifest describes the
            // archive, and the extracted jar is derived from it.
            verify(zip, url);
            extractMindustryJar(zip, target);
            Files.createDirectories(java.util.Objects.requireNonNull(target.getParent()));
            completed = true;
            Logger.LOG.info("Extracted Mindustry.jar from " + url);
        } finally {
            // Keep the staging archive (and its `.part` resume data) after a
            // failure so the next attempt continues from the last byte; clean
            // up on success or when the user cancelled the download.
            if (completed || control.isCancelled()) {
                Files.deleteIfExists(zip);
                try {
                    Files.deleteIfExists(staging);
                } catch (DirectoryNotEmptyException ignored) {
                    // A leftover partial file keeps the staging directory alive.
                }
            }
        }
    }

    /// Verifies a finished file against the source manifest.
    ///
    /// @param file downloaded artifact
    /// @param url  URL it came from, used in the error message
    /// @throws IOException when the size or the hash does not match
    private void verify(Path file, String url) throws IOException {
        long actualSize = Files.size(file);
        if (actualSize <= 0L) {
            throw new ArtifactIntegrityException("Downloaded artifact is empty: " + url);
        }
        if (expectedSize > 0L && actualSize != expectedSize) {
            throw new ArtifactIntegrityException(
                    "Downloaded file size does not match the source manifest: got "
                            + actualSize + " bytes, expected " + expectedSize + " for " + url);
        }
        if (expectedHash == null) {
            // The GitHub release API publishes no hash; a matching size is all
            // that can be checked for those rows.
            return;
        }
        String actualHash = sha256Hex(file);
        if (!expectedHash.equals(actualHash)) {
            throw new ArtifactIntegrityException(
                    "Downloaded file hash does not match the source manifest: got "
                            + actualHash + ", expected " + expectedHash + " for " + url);
        }
    }

    /// Deletes a completed artifact that failed verification.
    ///
    /// Also drops any `.part`/`.part.meta` siblings so the next attempt starts
    /// from zero instead of resuming bytes that are known to be wrong.
    private void discardArtifact() {
        try {
            ResumableDownloader.discardPartial(target);
            Files.deleteIfExists(target);
        } catch (IOException e) {
            Logger.LOG.warning("Cannot clean up the rejected artifact " + target
                    + ": " + e.getMessage());
        }
    }

    /// Computes the lowercase-hex SHA-256 of a file.
    ///
    /// @param file file to hash
    /// @return the digest as lowercase hex
    /// @throws IOException when the file cannot be read or SHA-256 is missing
    private static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Renders an error message for the retry log.
    ///
    /// @param error previous failure, possibly `null`
    /// @return a readable description
    private static String messageOf(@Nullable IOException error) {
        return error == null ? "no previous failure" : error.getMessage();
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
