/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package determination.xenon.mindustry.download;

import determination.xenon.task.FetchTask;
import determination.xenon.util.io.NetworkUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/** Range-based downloader for the MDTbbs file host. */
public final class MdtbbsSegmentedDownloader {
    private static final long SEGMENT_SIZE = 4L * 1024 * 1024;
    private static final int MAX_SEGMENTS = 16;
    private static final int RETRIES = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .proxy(ProxySelector.getDefault())
            .build();

    /** Downloads a file, using Range segments when the server advertises a length. */
    public void download(String url, Path target, long expectedSize,
                         @Nullable ProgressCallback progress) throws IOException {
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);

        long total = contentLength(url);
        if (total <= 0) {
            downloadSingle(url, target, expectedSize, progress);
            return;
        }
        final long downloadTotal = total;
        int segments = segmentCount(downloadTotal);
        if (segments <= 1) {
            downloadSingle(url, target, downloadTotal, progress);
            return;
        }

        Path tempDir = parent == null ? Path.of(System.getProperty("java.io.tmpdir", "."))
                : parent.resolve("_xenon_dl");
        Files.createDirectories(tempDir);
        Path part = tempDir.resolve(target.getFileName() + ".ranges.part");
        Files.deleteIfExists(part);

        AtomicLong downloaded = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(segments, runnable -> {
            Thread thread = new Thread(runnable, "MDTbbs-range-download");
            thread.setDaemon(true);
            return thread;
        });
        List<Future<?>> futures = new ArrayList<>();
        boolean completed = false;
        try (FileChannel output = FileChannel.open(part,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            output.position(downloadTotal - 1);
            output.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
            for (int i = 0; i < segments; i++) {
                long start = downloadTotal * i / segments;
                long end = downloadTotal * (i + 1) / segments - 1;
                futures.add(executor.submit(() -> {
                    try {
                        downloadRange(url, part, start, end, downloadTotal,
                                downloaded, progress);
                    } catch (IOException e) {
                        throw new RangeDownloadFailure(e);
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted downloading " + url, e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RangeDownloadFailure failure) {
                        throw failure.cause;
                    }
                    throw cause instanceof IOException ? (IOException) cause
                            : new IOException("Failed downloading " + url, cause);
                }
            }
            completed = true;
        } finally {
            for (Future<?> future : futures) future.cancel(true);
            executor.shutdownNow();
            if (!completed) Files.deleteIfExists(part);
        }

        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (progress != null) progress.onProgress(total, total);
    }

    private static final class RangeDownloadFailure extends RuntimeException {
        private final IOException cause;

        private RangeDownloadFailure(IOException cause) {
            this.cause = cause;
        }
    }

    private long contentLength(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Xenon-Launcher")
                    .build();
            HttpResponse<Void> response = http.send(request,
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2
                    || !response.headers().firstValue("Accept-Ranges")
                    .orElse("").equalsIgnoreCase("bytes")) return -1;
            return response.headers().firstValueAsLong("Content-Length").orElse(-1);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static int segmentCount(long total) {
        if (total <= 0) return 1;
        long bySize = (total + SEGMENT_SIZE - 1) / SEGMENT_SIZE;
        return (int) Math.max(1, Math.min(MAX_SEGMENTS,
                Math.min(FetchTask.getDownloadExecutorConcurrency(), bySize)));
    }

    private void downloadRange(String url, Path part, long start, long end, long total,
                               AtomicLong downloaded, @Nullable ProgressCallback progress)
            throws IOException {
        long expected = end - start + 1;
        IOException last = null;
        for (int attempt = 0; attempt < RETRIES; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(NetworkUtils.resolvePlayMirrorIp(URI.create(url)))
                        .GET()
                        .timeout(Duration.ofMinutes(10))
                        .header("Range", "bytes=" + start + "-" + end)
                        .header("Accept", "application/octet-stream")
                        .header("User-Agent", "Xenon-Launcher")
                        .build();
                HttpResponse<InputStream> response = http.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 206) {
                    try (InputStream ignored = response.body()) {
                        ignored.transferTo(OutputStream.nullOutputStream());
                    }
                    throw new IOException("MDTbbs range request returned HTTP "
                            + response.statusCode());
                }
                try (InputStream input = response.body();
                     FileChannel output = FileChannel.open(part, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    long position = start;
                    long readTotal = 0;
                    int read;
                    while ((read = input.read(buffer)) > 0) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IOException("Range download interrupted");
                        }
                        java.nio.ByteBuffer chunk = java.nio.ByteBuffer.wrap(buffer, 0, read);
                        while (chunk.hasRemaining()) {
                            int written = output.write(chunk, position);
                            if (written <= 0) throw new IOException("Unable to write range data");
                            position += written;
                        }
                        readTotal += read;
                        FetchTask.recordDownloadedBytes(read);
                        long current = downloaded.addAndGet(read);
                        if (progress != null) progress.onProgress(current, total);
                    }
                    if (readTotal != expected) {
                        throw new IOException("Unexpected range size: " + readTotal
                                + ", expected " + expected);
                    }
                }
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted downloading range", e);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("Range download failed") : last;
    }

    private void downloadSingle(String url, Path target, long total,
                                @Nullable ProgressCallback progress) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".single.part");
        try {
            HttpRequest request = HttpRequest.newBuilder(NetworkUtils.resolvePlayMirrorIp(URI.create(url)))
                    .GET()
                    .timeout(Duration.ofMinutes(10))
                    .header("Accept", "application/octet-stream")
                    .header("User-Agent", "Xenon-Launcher")
                    .build();
            HttpResponse<InputStream> response = http.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                try (InputStream ignored = response.body()) {
                    ignored.transferTo(OutputStream.nullOutputStream());
                }
                throw new IOException("MDTbbs returned HTTP " + response.statusCode());
            }
            long actualTotal = response.headers().firstValueAsLong("Content-Length")
                    .orElse(total);
            try (InputStream input = response.body();
                 OutputStream output = Files.newOutputStream(temp,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[64 * 1024];
                long readTotal = 0;
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                    readTotal += read;
                    FetchTask.recordDownloadedBytes(read);
                    if (progress != null) progress.onProgress(readTotal, actualTotal);
                }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading " + url, e);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
