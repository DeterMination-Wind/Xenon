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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests MDTbbs Range downloads against a deterministic local server. */
@NotNullByDefault
public final class MdtbbsSegmentedDownloaderTest {
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d+)");

    @Test
    public void downloadsAndMergesRanges(@TempDir Path tempDir) throws Exception {
        byte[] data = new byte[10 * 1024 * 1024 + 123];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31);

        try (RangeServer server = RangeServer.start(data)) {
            Path target = tempDir.resolve("mindustry.zip");
            new MdtbbsSegmentedDownloader().download(server.url(), target, data.length,
                    (read, total) -> assertEquals(data.length, total));
            assertArrayEquals(data, Files.readAllBytes(target));
        }
    }

    private static final class RangeServer implements AutoCloseable {
        private final HttpServer server;
        private final byte[] data;

        static RangeServer start(byte[] data) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            RangeServer result = new RangeServer(server, data);
            server.createContext("/file.zip", result::handle);
            server.start();
            return result;
        }

        private RangeServer(HttpServer server, byte[] data) {
            this.server = server;
            this.data = data;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/file.zip";
        }

        private void handle(HttpExchange exchange) throws IOException {
            try {
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().set("Content-Length", Long.toString(data.length));
                if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }
                String range = exchange.getRequestHeaders().getFirst("Range");
                long start = 0;
                long end = data.length - 1;
                int status = 200;
                if (range != null) {
                    Matcher matcher = RANGE.matcher(range);
                    if (!matcher.matches()) {
                        exchange.sendResponseHeaders(416, -1);
                        return;
                    }
                    start = Long.parseLong(matcher.group(1));
                    end = Math.min(end, Long.parseLong(matcher.group(2)));
                    status = 206;
                    exchange.getResponseHeaders().set("Content-Range",
                            "bytes " + start + "-" + end + "/" + data.length);
                    exchange.getResponseHeaders().set("Content-Length",
                            Long.toString(end - start + 1));
                }
                exchange.sendResponseHeaders(status, end - start + 1);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(data, (int) start, (int) (end - start + 1));
                }
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
