/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package determination.xenon.mindustry.community;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import determination.xenon.mindustry.community.MdtbbsGameContentClient.Blueprint;
import determination.xenon.mindustry.community.MdtbbsGameContentClient.ContentPage;
import determination.xenon.mindustry.community.MdtbbsGameContentClient.MapItem;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests game-content cursor pagination, string ids and preview URLs.
@NotNullByDefault
public final class MdtbbsGameContentClientTest {
    @Test
    public void parsesBlueprintCursorPageWithStringIds() throws Exception {
        try (ContentServer server = ContentServer.start()) {
            ContentPage<Blueprint> page = server.client().blueprints("建筑", null, 20);

            assertEquals(1, page.items().size());
            assertEquals("cursor-1", page.nextCursor());
            Blueprint blueprint = page.items().get(0);
            assertEquals("bp_test", blueprint.id());
            assertEquals("新手建筑布局", blueprint.title());
            assertEquals("builder", blueprint.author());
            assertEquals(45, blueprint.authorId());
            assertEquals(120, blueprint.downloads());
            assertEquals(3, blueprint.previewWidth());
            assertEquals(4, blueprint.previewHeight());
            assertTrue(blueprint.previewUrl().contains("/api/v1/game-content/blueprints/bp_test/preview"));
            assertTrue(blueprint.previewUrl().startsWith("http://127.0.0.1:"));
        }
    }

    @Test
    public void parsesMapDetailFileMetadata() throws Exception {
        try (ContentServer server = ContentServer.start()) {
            MapItem map = server.client().mapDetail("map_test");

            assertEquals("map_test", map.id());
            assertEquals("沙漠基地", map.title());
            assertEquals("mapper", map.author());
            assertEquals(7, map.authorId());
            assertEquals("Survive", map.mode());
            assertEquals(1234, map.size());
            assertEquals("abc123", map.sha256());
            assertTrue(map.previewUrl().contains("/api/v1/game-content/maps/map_test/preview"));
        }
    }

    @Test
    public void readsBlueprintCode() throws Exception {
        try (ContentServer server = ContentServer.start()) {
            String code = server.client().blueprintCode("bp_test");
            assertEquals("bXNjaA==", code);
            assertFalse(code.isBlank());
        }
    }

    private static final class ContentServer implements AutoCloseable {
        private final HttpServer server;

        static ContentServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ContentServer fixture = new ContentServer(server);
            server.createContext("/api/v1", fixture::handle);
            server.start();
            return fixture;
        }

        private ContentServer(HttpServer server) {
            this.server = server;
        }

        MdtbbsGameContentClient client() {
            MdtbbsApiClient api = new MdtbbsApiClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                    () -> "token");
            return new MdtbbsGameContentClient(api);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String body = switch (path) {
                case "/api/v1/game-content/blueprints" -> BLUEPRINTS_JSON;
                case "/api/v1/game-content/blueprints/bp_test/code" -> {
                    yield "{\"data\":{\"id\":\"bp_test\",\"code\":\"bXNjaA==\"}}";
                }
                case "/api/v1/game-content/maps/map_test" -> MAP_JSON;
                default -> "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"missing\"}}";
            };
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(body.contains("\"error\"") ? 404 : 200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } finally {
                exchange.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final String BLUEPRINTS_JSON = """
            {"data":{"data":[{
              "id":"bp_test","title":"新手建筑布局","summary":"分享布局",
              "author":{"id":45,"username":"builder","avatar_url":null},
              "game":{"version":"v160.5"},"tags":["建筑"],
              "preview":{"thumbnail":"/api/v1/game-content/blueprints/bp_test/preview",
                         "width":3,"height":4},
              "stats":{"downloads":120,"likes":3}
            }],"pagination":{"nextCursor":"cursor-1","hasMore":true}}}
            """;

    private static final String MAP_JSON = """
            {"data":{
              "id":"map_test","title":"沙漠基地","summary":"","author":{"id":7,"username":"mapper"},
              "map":{"mode":"Survive","players":2},
              "preview":{"thumbnail":"/api/v1/game-content/maps/map_test/preview",
                         "width":246,"height":200},
              "file":{"size":1234,"sha256":"abc123"},
              "stats":{"downloads":5,"likes":1}
            }}
            """;
}
