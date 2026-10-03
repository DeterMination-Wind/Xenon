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
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Candidate;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Capabilities;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Joined;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Peer;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.RelayAllocation;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Session;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the MDTBBS multiplayer REST surface against a fixture server.
@NotNullByDefault
public final class MdtbbsMultiplayerClientTest {

    /// Capability switches parse from the documented response shape.
    @Test
    public void parsesCapabilities() throws Exception {
        try (Fixture server = Fixture.start()) {
            Capabilities capabilities = server.client().capabilities();
            assertTrue(capabilities.sessionsV1());
            assertTrue(capabilities.invitesV1());
            assertTrue(capabilities.relayV1());
            assertTrue(capabilities.thirdParty());
            assertEquals(120, capabilities.relayCredentialTtlSeconds());
        }
    }

    /// Session creation returns the session, owner peer, resume token and join code.
    @Test
    public void createsSession() throws Exception {
        try (Fixture server = Fixture.start()) {
            Joined joined = server.client().createSession("unlisted", "open", 8, "Mindustry", "build 160");

            assertEquals("ses_1", joined.session().id());
            assertEquals(1, joined.session().currentPlayers());
            assertEquals("peer_owner", joined.peer().peerId());
            assertTrue(joined.peer().owner());
            assertEquals("rt_owner", joined.resumeToken());
            assertEquals("A1B2C3D4E5", joined.joinCode());

            String body = server.lastBody();
            assertTrue(body.contains("\"visibility\":\"unlisted\""));
            assertTrue(body.contains("\"join_policy\":\"open\""));
            assertTrue(body.contains("\"max_players\":8"));
        }
    }

    /// Join responses without a session object trigger a follow-up session read.
    @Test
    public void joinsSessionAndFetchesSession() throws Exception {
        try (Fixture server = Fixture.start()) {
            Joined joined = server.client().join("ses_1", "A1B2C3D4E5");

            assertEquals("ses_1", joined.session().id());
            assertEquals("peer_member", joined.peer().peerId());
            assertFalse(joined.peer().owner());
            assertEquals("rt_member", joined.resumeToken());
            assertTrue(server.sawPath("/multiplayer/sessions/ses_1/join"));
            assertTrue(server.sawPath("/multiplayer/sessions/ses_1"));
        }
    }

    /// Peers, candidates and relay allocation parse into typed records.
    @Test
    public void parsesPeersCandidatesAndRelay() throws Exception {
        try (Fixture server = Fixture.start()) {
            List<Peer> peers = server.client().peers("ses_1");
            assertEquals(2, peers.size());
            assertTrue(peers.get(0).owner());

            String candidateId = server.client().addCandidate("ses_1", "public", "udp", "203.0.113.10", 6567, 100);
            assertEquals("cand_1", candidateId);

            List<Candidate> candidates = server.client().peerCandidates("ses_1", "peer_owner");
            assertEquals(1, candidates.size());
            assertEquals("203.0.113.10", candidates.get(0).address());
            assertEquals(6567, candidates.get(0).port());

            RelayAllocation relay = server.client().allocateRelay("ses_1");
            assertEquals("rly_1", relay.allocationId());
            assertEquals("official-eu-1", relay.agentId());
            assertEquals("wss://relay.example/relay/v1", relay.endpoint());
            assertEquals("credential_1", relay.credential());
            assertEquals(120, relay.expiresInSeconds());
        }
    }

    /// Join intents are consumed with the join capabilities body.
    @Test
    public void consumesJoinIntent() throws Exception {
        try (Fixture server = Fixture.start()) {
            Joined joined = server.client().consumeJoinIntent("intent_1");
            assertEquals("ses_1", joined.session().id());
            assertEquals("peer_member", joined.peer().peerId());
            assertEquals("rt_member", joined.resumeToken());
            assertTrue(server.lastBody().contains("\"relay\":true"));
        }
    }

    /// Resolving an unlisted code returns the session directly.
    @Test
    public void resolvesJoinCode() throws Exception {
        try (Fixture server = Fixture.start()) {
            Session session = server.client().resolveCode("a1b2c3d4e5");
            assertEquals("ses_2", session.id());
            assertTrue(server.lastBody().contains("\"code\":\"A1B2C3D4E5\""));
        }
    }

    /// Local fixture server serving the multiplayer routes.
    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> lastBody = new AtomicReference<>("");
        private final List<String> paths = Collections.synchronizedList(new ArrayList<>());

        private Fixture(HttpServer server) {
            this.server = server;
        }

        static Fixture start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            Fixture fixture = new Fixture(server);
            server.createContext("/api/v1", fixture::handle);
            server.start();
            return fixture;
        }

        MdtbbsMultiplayerClient client() {
            MdtbbsApiClient api = new MdtbbsApiClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                    HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
                    () -> "token");
            return new MdtbbsMultiplayerClient(api);
        }

        String lastBody() {
            return lastBody.get();
        }

        boolean sawPath(String suffix) {
            return paths.stream().anyMatch(path -> path.endsWith(suffix));
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            byte[] request = exchange.getRequestBody().readAllBytes();
            paths.add(path);
            lastBody.set(new String(request, StandardCharsets.UTF_8));

            String response = switch (path) {
                case "/api/v1/multiplayer/capabilities" -> capabilitiesJson();
                case "/api/v1/multiplayer/sessions" -> createdSessionJson();
                case "/api/v1/multiplayer/join-intents/intent_1/consume" -> consumedIntentJson();
                case "/api/v1/multiplayer/sessions/resolve-code" -> resolvedSessionJson();
                case "/api/v1/multiplayer/sessions/ses_1/join" -> joinedMemberJson();
                case "/api/v1/multiplayer/sessions/ses_1" -> "{\"data\":{\"session\":" + sessionJson() + "}}";
                case "/api/v1/multiplayer/sessions/ses_1/peers" -> peersJson();
                case "/api/v1/multiplayer/sessions/ses_1/candidates" -> candidateCreatedJson();
                case "/api/v1/multiplayer/sessions/ses_1/peers/peer_owner/candidates" -> candidatesJson();
                case "/api/v1/multiplayer/sessions/ses_1/relay" -> relayJson();
                default -> "{\"data\":{}}";
            };
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        /// Capability response fixture.
        private static String capabilitiesJson() {
            return "{\"data\":{\"sessions_v1\":true,\"invites_v1\":true,\"relay_v1\":true,"
                    + "\"third_party_multiplayer\":true,\"relay_credential_ttl_seconds\":120}}";
        }

        /// Session creation response fixture.
        private static String createdSessionJson() {
            return "{\"data\":{\"session\":" + sessionJson() + ","
                    + "\"peer\":{\"peer_id\":\"peer_owner\",\"user_id\":7,\"role\":\"owner\",\"status\":\"active\"},"
                    + "\"resume_token\":\"rt_owner\",\"join_code\":\"A1B2C3D4E5\"}}";
        }

        /// Join-intent consumption response fixture.
        private static String consumedIntentJson() {
            return "{\"data\":{\"session\":" + sessionJson() + ","
                    + "\"peer\":{\"peer_id\":\"peer_member\",\"user_id\":9,\"role\":\"member\",\"status\":\"active\"},"
                    + "\"resume_token\":\"rt_member\"}}";
        }

        /// Join-code resolution response fixture.
        private static String resolvedSessionJson() {
            return "{\"data\":{\"id\":\"ses_2\",\"owner_user_id\":7,\"visibility\":\"unlisted\","
                    + "\"join_policy\":\"open\",\"current_players\":1,\"max_players\":8,"
                    + "\"status\":\"active\"}}";
        }

        /// Member join response fixture (no session object).
        private static String joinedMemberJson() {
            return "{\"data\":{\"peer\":{\"peer_id\":\"peer_member\",\"user_id\":9,"
                    + "\"role\":\"member\",\"status\":\"active\"},\"resume_token\":\"rt_member\"}}";
        }

        /// Peer list response fixture.
        private static String peersJson() {
            return "{\"data\":[{\"peer_id\":\"peer_owner\",\"user_id\":7,\"role\":\"owner\",\"status\":\"active\"},"
                    + "{\"peer_id\":\"peer_member\",\"user_id\":9,\"role\":\"member\",\"status\":\"active\"}]}";
        }

        /// Candidate creation response fixture.
        private static String candidateCreatedJson() {
            return "{\"data\":{\"candidate_id\":\"cand_1\",\"expires_in\":90}}";
        }

        /// Peer candidate list response fixture.
        private static String candidatesJson() {
            return "{\"data\":[{\"candidate_id\":\"cand_1\",\"peer_id\":\"peer_owner\",\"kind\":\"public\","
                    + "\"transport\":\"udp\",\"address\":\"203.0.113.10\",\"port\":6567,\"priority\":100}]}";
        }

        /// Relay allocation response fixture.
        private static String relayJson() {
            return "{\"data\":{\"allocation_id\":\"rly_1\",\"agent_id\":\"official-eu-1\","
                    + "\"endpoint\":\"wss://relay.example/relay/v1\",\"credential\":\"credential_1\","
                    + "\"expires_in\":120}}";
        }

        private static String sessionJson() {
            return "{\"id\":\"ses_1\",\"owner_user_id\":7,\"visibility\":\"friends\",\"join_policy\":\"friends\","
                    + "\"game\":{\"id\":\"mindustry\",\"version\":\"build 160\"},\"activity_name\":\"Mindustry\","
                    + "\"current_players\":1,\"max_players\":8,\"status\":\"active\"}";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
