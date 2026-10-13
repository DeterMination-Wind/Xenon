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

import determination.xenon.util.io.CompressingUtils;
import determination.xenon.util.logging.Logger;
import kala.compress.archivers.zip.ZipArchiveEntry;
import kala.compress.archivers.zip.ZipArchiveReader;
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
import java.util.Locale;

/// Manifest-driven download policy: verify, retry, then switch source families.
///
/// When the source feed published a size or a hash, the finished file is
/// checked against it before the download is reported as successful. A
/// mismatch is not a transfer failure — the bytes are all there but wrong — so
/// the artifact is deleted, the same source is retried once, and the other
/// source family is tried last. A pause or cancel never switches sides.
///
/// Kept separate from [MindustryDownloadTask] on purpose: the task only adds
/// HMCL's progress reporting and task state, while every decision here follows
/// from the source tier and the manifest metadata alone. That makes the whole
/// policy exercisable against a local HTTP server, with no JavaFX toolkit — the
/// CI runner is headless, so a policy living inside a `Task` could not be
/// covered there at all.
@NotNullByDefault
public final class MindustryDownloadPolicy {

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

    /// One download attempt in the retry chain.
    ///
    /// @param url      URL to fetch
    /// @param failover whether this attempt switched to the other source family
    private record Attempt(String url, boolean failover) {
    }

    /// Runs the whole attempt chain for one artifact.
    ///
    /// @param sourceUrl    direct jar URL, or a platform zip URL when {@code archive} is true
    /// @param target       destination file
    /// @param expectedSize expected size in bytes from the manifest, or 0 when unknown
    /// @param cachesRoot   launcher caches dir (used for the preferred-mirror cache)
    /// @param archive      whether {@code sourceUrl} is a zip that must be unpacked
    /// @param fallbackUrl  alternate URL on the other source family, or `null`
    /// @param expectedHash expected lowercase-hex SHA-256, or `null` when the feed omitted it
    /// @param control      pause/cancel state, or `null` for a plain download
    /// @param progress     optional progress callback
    /// @throws IOException when every allowed attempt failed
    public static void download(String sourceUrl, Path target, long expectedSize,
                                Path cachesRoot, boolean archive,
                                @Nullable String fallbackUrl, @Nullable String expectedHash,
                                @Nullable DownloadControl control,
                                @Nullable ProgressCallback progress) throws IOException {
        @Nullable String fallback = fallbackUrl == null || fallbackUrl.isBlank()
                ? null : fallbackUrl;
        @Nullable String hash = expectedHash == null || expectedHash.isBlank()
                ? null : expectedHash.toLowerCase(Locale.ROOT);

        MindustryDownloadSource source = MindustryDownloadSource.current();
        List<Attempt> attempts = buildAttempts(sourceUrl, fallback, source);
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
                attemptDownload(attempt.url(), target, expectedSize, cachesRoot, archive,
                        hash, control, progress);
                return;
            } catch (ResumableDownloader.DownloadCancelledException cancelled) {
                // A pause or cancel is the user's decision, never a route problem.
                throw cancelled;
            } catch (IOException e) {
                lastError = e;
                if (e instanceof ArtifactIntegrityException) {
                    // Resuming from a corrupt artifact would reproduce it, so
                    // drop it before the retry.
                    discardArtifact(target);
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

    /// Builds the ordered attempt chain for the tier in effect.
    ///
    /// The primary URL is tried twice: a truncated transfer is usually a flaky
    /// connection or a stale mirror cache, and the second attempt resumes the
    /// partial file. The other source family is appended only when the tier
    /// allows switching sides and the fallback URL belongs to an allowed route.
    ///
    /// @param sourceUrl primary URL
    /// @param fallback  alternate URL, or `null`
    /// @param source    tier captured for the whole download
    /// @return the attempts to run, empty when no route is allowed
    private static List<Attempt> buildAttempts(String sourceUrl, @Nullable String fallback,
                                              MindustryDownloadSource source) {
        List<Attempt> attempts = new ArrayList<>(3);
        if (source.allowsArtifactUrl(sourceUrl)) {
            attempts.add(new Attempt(sourceUrl, false));
            attempts.add(new Attempt(sourceUrl, false));
        }
        if (fallback != null
                && !fallback.equals(sourceUrl)
                && source.shouldFailover(MindustryDownloadSource.AttemptOutcome.TRANSFER_FAILED)
                && source.allowsArtifactUrl(fallback)) {
            attempts.add(new Attempt(fallback, true));
        }
        return attempts;
    }

    /// Downloads one URL and verifies the resulting artifact.
    ///
    /// @param url          URL to fetch
    /// @param target       destination file
    /// @param expectedSize expected size in bytes, or 0 when unknown
    /// @param cachesRoot   launcher caches dir
    /// @param archive      whether {@code url} is a zip that must be unpacked
    /// @param hash         expected SHA-256, or `null`
    /// @param control      pause/cancel state, or `null`
    /// @param progress     optional progress callback
    /// @throws IOException when the transfer or the integrity check fails
    private static void attemptDownload(String url, Path target, long expectedSize,
                                        Path cachesRoot, boolean archive,
                                        @Nullable String hash,
                                        @Nullable DownloadControl control,
                                        @Nullable ProgressCallback progress) throws IOException {
        if (!archive) {
            new MirrorDownloader(cachesRoot).download(url, target, expectedSize, progress, control);
            verify(target, url, expectedSize, hash);
            return;
        }

        Path parent = target.getParent();
        if (parent == null) throw new IOException("Mindustry target has no parent: " + target);
        Path staging = parent.resolve("_xenon_mindustry_archive");
        Files.createDirectories(staging);
        Path zip = staging.resolve(target.getFileName() + ".zip");
        boolean completed = false;
        try {
            new ResumableDownloader().download(url, zip, expectedSize, progress, control);
            // Archive mode checks the zip itself: the manifest describes the
            // archive, and the extracted jar is derived from it.
            verify(zip, url, expectedSize, hash);
            extractMindustryJar(zip, target);
            Files.createDirectories(target.getParent());
            completed = true;
            Logger.LOG.info("Extracted Mindustry.jar from " + url);
        } finally {
            // Keep the staging archive (and its `.part` resume data) after a
            // failure so the next attempt continues from the last byte; clean
            // up on success or when the user cancelled the download.
            if (completed || (control != null && control.isCancelled())) {
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
    /// @param file         downloaded artifact
    /// @param url          URL it came from, used in the error message
    /// @param expectedSize expected size in bytes, or 0 when unknown
    /// @param hash         expected SHA-256, or `null` when the feed omitted it
    /// @throws IOException when the size or the hash does not match
    private static void verify(Path file, String url, long expectedSize,
                               @Nullable String hash) throws IOException {
        long actualSize = Files.size(file);
        if (actualSize <= 0L) {
            throw new ArtifactIntegrityException("Downloaded artifact is empty: " + url);
        }
        if (expectedSize > 0L && actualSize != expectedSize) {
            throw new ArtifactIntegrityException(
                    "Downloaded file size does not match the source manifest: got "
                            + actualSize + " bytes, expected " + expectedSize + " for " + url);
        }
        if (hash == null) {
            // The GitHub release API publishes no hash; a matching size is all
            // that can be checked for those rows.
            return;
        }
        String actualHash = sha256Hex(file);
        if (!hash.equals(actualHash)) {
            throw new ArtifactIntegrityException(
                    "Downloaded file hash does not match the source manifest: got "
                            + actualHash + ", expected " + hash + " for " + url);
        }
    }

    /// Deletes a completed artifact that failed verification.
    ///
    /// Also drops any `.part`/`.part.meta` siblings so the next attempt starts
    /// from zero instead of resuming bytes that are known to be wrong.
    ///
    /// @param target artifact to remove
    private static void discardArtifact(Path target) {
        try {
            ResumableDownloader.discardPartial(target);
            Files.deleteIfExists(target);
        } catch (IOException e) {
            Logger.LOG.warning("Cannot clean up the rejected artifact " + target
                    + ": " + e.getMessage());
        }
    }

    /// Unpacks `Mindustry.jar` from a downloaded platform archive.
    ///
    /// @param zip    downloaded archive
    /// @param target destination jar
    /// @throws IOException when the archive cannot be read or holds no jar
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

    private MindustryDownloadPolicy() {
    }
}
