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
package determination.xenon.mindustry.community;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import determination.xenon.mindustry.community.MdtbbsSocialClient.FriendPresence;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests friend presence parsing against the current MDTBBS social shape.
@NotNullByDefault
public final class MdtbbsSocialClientTest {
    /// The aggregate response nests friends under `data.data` with a nested
    /// `presence` object.
    @Test
    public void parsesFriendsPresenceFromAggregateEnvelope() throws Exception {
        try (SocialServer server = SocialServer.start()) {
            List<FriendPresence> friends = server.client().friendsPresence(1, 50);

            assertEquals(1, friends.size());
            assertEquals(9, friends.get(0).userId());
            assertEquals("friend", friends.get(0).username());
            assertEquals("online", friends.get(0).status());
            assertEquals("Playing", friends.get(0).activityName());
        }
    }

    /// The legacy flat `data` array with a top-level `status` still parses.
    @Test
    public void parsesLegacyFlatPresence() throws Exception {
        try (SocialServer server = SocialServer.start()) {
            server.legacy = true;
            List<FriendPresence> friends = server.client().friendsPresence(1, 50);

            assertEquals(1, friends.size());
            assertEquals(9, friends.get(0).userId());
            assertEquals("idle", friends.get(0).status());
            assertEquals("", friends.get(0).activityName());
        }
    }

    /// Local fixture server serving the social routes.
    private static final class SocialServer implements AutoCloseable {
        private final HttpServer server;
        /// When set, the presence route answers with the legacy flat shape.
        volatile boolean legacy;

        private SocialServer(HttpServer server) {
            this.server = server;
        }

        static SocialServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            SocialServer fixture = new SocialServer(server);
            server.createContext("/api/v1", fixture::handle);
            server.start();
            return fixture;
        }

        MdtbbsSocialClient client() {
            MdtbbsApiClient api = new MdtbbsApiClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                    () -> "token");
            return new MdtbbsSocialClient(api);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            exchange.getRequestBody().readAllBytes();
            String body = path.equals("/api/v1/social/friends/presence")
                    ? (legacy ? LEGACY_JSON : AGGREGATE_JSON)
                    : "{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"missing\"}}";
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

    private static final String AGGREGATE_JSON = """
            {"data":{"data":[{
              "user":{"id":9,"username":"friend"},
              "presence":{"status":"online"},
              "activity":{"name":"Playing"},
              "actions":{"can_join":false}
            }],"pagination":{"page":1,"limit":50,"total":1}},
            "meta":{"request_id":"req-1"}}
            """;

    private static final String LEGACY_JSON = """
            {"data":[{
              "user":{"id":9,"username":"friend"},
              "status":"idle"
            }]}
            """;
}
