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
import determination.xenon.mindustry.community.MdtbbsForumClient.Notification;
import determination.xenon.mindustry.community.MdtbbsForumClient.Page;
import determination.xenon.mindustry.community.MdtbbsForumClient.ThreadDetail;
import determination.xenon.mindustry.community.MdtbbsForumClient.ThreadSummary;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests forum list, detail and reply parsing against a fixture server.
@NotNullByDefault
public final class MdtbbsForumClientTest {
    @Test
    public void parsesThreadListWithPagination() throws Exception {
        try (ForumServer server = ForumServer.start()) {
            Page<ThreadSummary> page = server.client().threads(2L, 1, 20, "建筑");

            assertEquals(1, page.items().size());
            assertEquals(2, page.total());
            ThreadSummary thread = page.items().get(0);
            assertEquals(7, thread.id());
            assertEquals("新手建筑布局", thread.title());
            assertTrue(thread.pinned());
            assertEquals(8, thread.replyCount());
            assertNotNull(thread.author());
            assertEquals("builder", thread.author().username());
            assertNotNull(thread.category());
            assertEquals("交流", thread.category().name());
        }
    }

    @Test
    public void parsesThreadDetailWithReplies() throws Exception {
        try (ForumServer server = ForumServer.start()) {
            ThreadDetail detail = server.client().thread(7);

            assertEquals("新手建筑布局", detail.title());
            assertTrue(detail.liked());
            assertTrue(detail.contentJson().contains("paragraph"));
            assertEquals(1, detail.replies().size());
            assertEquals("可以试试把电力区放在地图中央。", detail.replies().get(0).contentText());
            assertEquals("MannerDoor23", detail.replies().get(0).author().username());
        }
    }

    @Test
    public void writesThreadAndReply() throws Exception {
        try (ForumServer server = ForumServer.start()) {
            assertEquals(99, server.client().createThread("标题", 2, "正文"));
            assertEquals(5, server.client().createReply(7, "回复", null));
        }
    }

    @Test
    public void buildsProfileUrl() {
        assertEquals("https://mdtbbs.cn/users/92", MdtbbsForumClient.profileUrl(92));
    }

    @Test
    public void parsesNotificationsFromDataItems() throws Exception {
        try (ForumServer server = ForumServer.start()) {
            Page<Notification> page = server.client().notifications(1, 20);

            assertEquals(1, page.items().size());
            assertEquals(1, page.page());
            assertEquals(7, page.total());
            assertTrue(page.hasMore());
            assertEquals("reply", page.items().get(0).type());
            assertEquals("有人回复了你", page.items().get(0).title());
            assertFalse(page.items().get(0).read());
        }
    }

    private static final class ForumServer implements AutoCloseable {
        private final HttpServer server;

        static ForumServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ForumServer fixture = new ForumServer(server);
            server.createContext("/api/v1", fixture::handle);
            server.start();
            return fixture;
        }

        private ForumServer(HttpServer server) {
            this.server = server;
        }

        MdtbbsForumClient client() {
            MdtbbsApiClient api = new MdtbbsApiClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                    () -> "token");
            return new MdtbbsForumClient(api);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body;
            if (path.equals("/api/v1/threads") && method.equals("GET")) {
                body = LIST_JSON;
            } else if (path.equals("/api/v1/threads/7") && method.equals("GET")) {
                body = DETAIL_JSON;
            } else if (path.equals("/api/v1/threads") && method.equals("POST")) {
                body = "{\"data\":{\"id\":99}}";
            } else if (path.equals("/api/v1/threads/7/replies") && method.equals("POST")) {
                body = "{\"data\":{\"id\":5}}";
            } else if (path.equals("/api/v1/notifications") && method.equals("GET")) {
                body = NOTIFICATIONS_JSON;
            } else {
                body = "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"missing\"}}";
            }
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

    private static final String LIST_JSON = """
            {"data":[{
              "id":7,"title":"新手建筑布局","is_pinned":true,"is_locked":false,
              "view_count":245,"reply_count":8,"created_at":"2026-09-30T12:00:00.000Z",
              "author":{"id":45,"username":"builder","avatar_url":null},
              "category":{"id":2,"name":"交流","slug":"talk"},
              "excerpt":"分享一套适合新手的建筑布局"
            }],
            "meta":{"request_id":"req-1","pagination":{"page":1,"limit":20,"total":2,"has_more":false}}}
            """;

    private static final String NOTIFICATIONS_JSON = """
            {"data":{"items":[{
              "id":11,"type":"reply","title":"有人回复了你","content":"看看新回复",
              "is_read":false,"created_at":"2026-09-30T13:00:00.000Z"
            }],"pagination":{"page":1,"limit":20,"total":7,"total_pages":1}},
            "meta":{"request_id":"req-3","pagination":{"page":1,"limit":20,"total":7,"has_more":true}}}
            """;

    private static final String DETAIL_JSON = """
            {"data":{
              "id":7,"title":"新手建筑布局","is_pinned":true,"is_locked":false,
              "view_count":245,"reply_count":1,"created_at":"2026-09-30T12:00:00.000Z",
              "author":{"id":45,"username":"builder","avatar_url":null},
              "category":{"id":2,"name":"交流","slug":"talk"},
              "like_count":3,"is_owner":false,
              "viewer":{"liked":true,"bookmarked":false},
              "content_format":"tiptap_json",
              "content_json":{"type":"doc","content":[{"type":"paragraph",
                "content":[{"type":"text","text":"分享布局"}]}]},
              "content_text":"分享布局",
              "replies":[{
                "id":31,"post_id":7,"user_id":2,"parent_reply_id":null,
                "author_mindauth_id":9,"author_role":"user",
                "author_name":"MannerDoor23","author_avatar_url":null,
                "content_text":"可以试试把电力区放在地图中央。",
                "like_count":1,"is_owner":true,"created_at":"2026-09-30T12:30:00.000Z"
              }],
              "reply_pagination":{"page":1,"limit":20,"total":1,"has_more":false}
            },
            "meta":{"request_id":"req-2"}}
            """;
}
