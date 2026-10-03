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

import determination.xenon.task.FetchTask;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/// Resumable single-stream downloader.
///
/// The payload is written to `<target>.part` accompanied by a
/// `<target>.part.meta` sidecar recording the source URL and the entity
/// validators (ETag / Last-Modified). A later download of the same target
/// resumes from the existing byte count with a `Range` request when the
/// server still serves the same entity, so interrupted downloads survive
/// retries and launcher restarts.
///
/// Cancelling deletes the partial files ("cancel means cleanup"); pausing
/// parks the streaming thread at a chunk boundary and keeps the partial
/// file for the resume.
@NotNullByDefault
public final class ResumableDownloader {

    /// Suffix of the partial payload next to the destination file.
    public static final String PART_SUFFIX = ".part";

    /// Suffix of the partial-file metadata sidecar.
    public static final String META_SUFFIX = ".part.meta";

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(30);

    private final HttpClient http;

    /// Creates a downloader with the production HTTP client.
    public ResumableDownloader() {
        this(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(ProxySelector.getDefault())
                .build());
    }

    /// Creates a downloader with an injected client (tests).
    ResumableDownloader(HttpClient http) {
        this.http = Objects.requireNonNull(http, "http");
    }

    /// Thrown when the transfer was cancelled by the user.
    ///
    /// Partial files were removed before this exception was raised, so
    /// callers must not treat it as a retryable failure.
    public static final class DownloadCancelledException extends IOException {
        /// Creates the exception with the given detail message.
        public DownloadCancelledException(String message) {
            super(message);
        }
    }

    /// The partial payload path for `target`.
    public static Path partPath(Path target) {
        return target.resolveSibling(target.getFileName() + PART_SUFFIX);
    }

    /// The metadata sidecar path for `target`.
    public static Path metaPath(Path target) {
        return target.resolveSibling(target.getFileName() + META_SUFFIX);
    }

    /// Whether a resumable partial file exists for `target`.
    public static boolean hasPartial(Path target) {
        return Files.isRegularFile(partPath(target));
    }

    /// Removes the partial payload and metadata for `target`, if present.
    public static void discardPartial(Path target) throws IOException {
        Files.deleteIfExists(partPath(target));
        Files.deleteIfExists(metaPath(target));
    }

    /// Downloads `url` to `target`, resuming an existing partial file.
    ///
    /// @param url          source URL; may point at a mirror
    /// @param target       destination file, overwritten on success
    /// @param expectedSize size hint for the progress bar, or `0` when unknown
    /// @param progress     optional progress callback
    /// @param control      optional pause/cancel state
    /// @return the number of bytes transferred by this call
    /// @throws DownloadCancelledException when the control was cancelled
    /// @throws IOException when the transfer failed; partial files are kept
    ///                     unless the failure was a cancellation
    public long download(String url, Path target, long expectedSize,
                         @Nullable ProgressCallback progress,
                         @Nullable DownloadControl control) throws IOException {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(target, "target");
        @Nullable Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path part = partPath(target);
        Path meta = metaPath(target);
        Properties metaProps = readMeta(meta);
        long offset = Files.isRegularFile(part) ? Files.size(part) : 0L;

        for (int attempt = 0; attempt < 2; attempt++) {
            boolean resuming = offset > 0 && attempt == 0;
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(READ_TIMEOUT)
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "Xenon-Launcher");
            if (resuming) {
                builder.header("Range", "bytes=" + offset + "-");
                String validator = metaProps.getProperty("etag",
                        metaProps.getProperty("lastModified", ""));
                if (!validator.isBlank()) {
                    builder.header("If-Range", validator);
                }
            }

            HttpResponse<InputStream> response = send(builder, url, target, control);
            int code = response.statusCode();

            if (resuming && code == 416) {
                // The server considers the range unsatisfiable: either the
                // file is already complete, or the entity changed.
                long knownTotal = parseLong(metaProps.getProperty("total"), 0L);
                try (InputStream ignored = response.body()) {
                    ignored.transferTo(OutputStream.nullOutputStream());
                }
                if (knownTotal > 0 && offset == knownTotal) {
                    complete(part, meta, target);
                    report(progress, offset, knownTotal);
                    return 0;
                }
                Logger.LOG.warning("ResumableDownloader: " + url
                        + " rejected range " + offset + "; restarting from scratch");
                discardPartial(target);
                offset = 0;
                continue;
            }

            if (resuming && code == 206) {
                String contentRange = response.headers().firstValue("Content-Range").orElse("");
                long start = parseContentRangeStart(contentRange);
                if (start != offset) {
                    Logger.LOG.warning("ResumableDownloader: " + url
                            + " answered range start " + start + " instead of " + offset
                            + "; restarting from scratch");
                    closeQuietly(response);
                    discardPartial(target);
                    offset = 0;
                    continue;
                }
                long total = parseContentRangeTotal(contentRange, expectedSize);
                writeMeta(meta, url, response, total);
                long written = stream(response, part, offset, total, progress, control, target);
                verifyComplete(target, part, meta, offset + written, total);
                return written;
            }

            if (code / 100 == 2) {
                // 200 (or a resuming server that ignored Range): full entity.
                if (resuming) {
                    Logger.LOG.info("ResumableDownloader: " + url
                            + " ignored the range request; restarting from scratch");
                }
                long total = totalOf(response, expectedSize);
                offset = 0;
                writeMeta(meta, url, response, total);
                long written = stream(response, part, offset, total, progress, control, target);
                verifyComplete(target, part, meta, written, total);
                return written;
            }

            try (InputStream ignored = response.body()) {
                ignored.transferTo(OutputStream.nullOutputStream());
            }
            throw new IOException("HTTP " + code + " downloading " + url);
        }
        throw new IOException("Unable to download " + url);
    }

    /// Sends one request, translating interrupts and cancellation.
    private HttpResponse<InputStream> send(HttpRequest.Builder builder, String url, Path target,
                                           @Nullable DownloadControl control) throws IOException {
        if (control != null && control.isCancelled()) {
            // Cancel means cleanup, even before the first byte was read.
            discardPartialQuietly(target);
            throw new DownloadCancelledException("Cancelled before starting " + url);
        }
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownloadCancelledException("Interrupted downloading " + url);
        }
    }

    /// Streams the response body into `part`, honouring pause/cancel.
    private long stream(HttpResponse<InputStream> response, Path part, long offset,
                        long total, @Nullable ProgressCallback progress,
                        @Nullable DownloadControl control, Path target) throws IOException {
        List<StandardOpenOption> options;
        if (offset > 0) {
            options = List.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } else {
            options = List.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        }
        long written = 0;
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(part,
                     options.toArray(new StandardOpenOption[0]))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (true) {
                if (control != null) {
                    if (control.isCancelled()) {
                        throw new DownloadCancelledException("Cancelled downloading " + target);
                    }
                    if (control.isPaused()) {
                        try {
                            control.awaitResume();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new DownloadCancelledException("Interrupted downloading " + target);
                        }
                    }
                }
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                out.write(buffer, 0, read);
                written += read;
                // Feed the launcher-wide speed counter so the task list shows
                // real throughput, exactly like the racing downloader does.
                FetchTask.recordDownloadedBytes(read);
                report(progress, offset + written, total);
            }
        } catch (DownloadCancelledException e) {
            // Cancel means cleanup: remove the partial payload and metadata.
            discardPartialQuietly(target);
            throw e;
        } catch (IOException e) {
            // A cancellation can surface as a plain IO error when the
            // underlying read is interrupted; still honour the cleanup
            // contract in that case.
            if (control != null && control.isCancelled()) {
                discardPartialQuietly(target);
                throw new DownloadCancelledException("Cancelled downloading " + target);
            }
            throw e;
        }
        return written;
    }

    /// Validates the byte count and moves the part file into place.
    private void verifyComplete(Path target, Path part, Path meta, long size, long total)
            throws IOException {
        if (total > 0 && size != total) {
            throw new IOException("Truncated download for " + target + ": got " + size
                    + " of " + total + " bytes");
        }
        if (size == 0) {
            throw new IOException("Empty response body for " + target);
        }
        complete(part, meta, target);
    }

    /// Moves the completed partial file over the destination.
    private static void complete(Path part, Path meta, Path target) throws IOException {
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.deleteIfExists(meta);
    }

    /// Writes the entity validators for a partial file.
    private static void writeMeta(Path meta, String url, HttpResponse<?> response, long total) {
        try {
            Properties props = new Properties();
            props.setProperty("url", url);
            response.headers().firstValue("ETag").ifPresent(v -> props.setProperty("etag", v));
            response.headers().firstValue("Last-Modified")
                    .ifPresent(v -> props.setProperty("lastModified", v));
            if (total > 0) {
                props.setProperty("total", Long.toString(total));
            }
            Path parent = meta.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream out = Files.newOutputStream(meta,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                props.store(out, "Xenon resumable download metadata");
            }
        } catch (IOException e) {
            Logger.LOG.warning("ResumableDownloader: cannot write " + meta + ": " + e.getMessage());
        }
    }

    /// Reads the metadata sidecar, returning an empty object when missing.
    private static Properties readMeta(Path meta) {
        Properties props = new Properties();
        if (!Files.isRegularFile(meta)) {
            return props;
        }
        try (InputStream in = Files.newInputStream(meta)) {
            props.load(in);
        } catch (IOException e) {
            Logger.LOG.warning("ResumableDownloader: cannot read " + meta + ": " + e.getMessage());
        }
        return props;
    }

    /// Drains and closes a response that is about to be abandoned.
    private static void closeQuietly(HttpResponse<InputStream> response) {
        try (InputStream in = response.body()) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
        }
    }

    /// Deletes partial files without propagating IO errors.
    private static void discardPartialQuietly(Path target) {
        try {
            discardPartial(target);
        } catch (IOException e) {
            Logger.LOG.warning("ResumableDownloader: cannot clean up " + target + ": " + e.getMessage());
        }
    }

    /// Reports progress when a callback is present.
    private static void report(@Nullable ProgressCallback progress, long read, long total) {
        if (progress != null) {
            progress.onProgress(read, total);
        }
    }

    /// Content-Length of a full response, falling back to the size hint.
    private static long totalOf(HttpResponse<?> response, long expectedSize) {
        long header = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (header > 0) {
            return header;
        }
        return Math.max(expectedSize, 0L);
    }

    /// Parses the start offset of a `bytes start-end/total` header.
    private static long parseContentRangeStart(String contentRange) {
        if (contentRange == null || !contentRange.startsWith("bytes ")) {
            return -1L;
        }
        int dash = contentRange.indexOf('-');
        if (dash < 0) {
            return -1L;
        }
        return parseLong(contentRange.substring("bytes ".length(), dash), -1L);
    }

    /// Parses the total size of a `bytes start-end/total` header.
    private static long parseContentRangeTotal(String contentRange, long fallback) {
        if (contentRange == null) {
            return fallback;
        }
        int slash = contentRange.indexOf('/');
        if (slash < 0) {
            return fallback;
        }
        String total = contentRange.substring(slash + 1).trim();
        if (total.isEmpty() || "*".equals(total)) {
            return fallback;
        }
        return parseLong(total, fallback);
    }

    /// Parses a decimal long, returning `fallback` on failure.
    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
