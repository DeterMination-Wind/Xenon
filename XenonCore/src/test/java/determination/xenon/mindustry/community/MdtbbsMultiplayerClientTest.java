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
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.JoinRequestPage;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Joined;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Limits;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Peer;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.RelayAllocation;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Session;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.SessionPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
            assertEquals(List.of("private", "friends", "public"), capabilities.visibility());
            assertEquals(List.of("invite_only", "friends", "public"), capabilities.joinPolicies());
            assertTrue(capabilities.requireApprovalSupported());
            assertEquals(9, capabilities.combinations().size());
            assertTrue(capabilities.combinations().contains("private+public"));
        }
    }

    /// Old capability responses without the 1.7.0 enum lists keep working.
    @Test
    public void capabilitiesFallBackToDocumentedDefaults() throws Exception {
        try (Fixture server = Fixture.start()) {
            server.legacyCapabilities = true;
            Capabilities capabilities = server.client().capabilities();
            assertEquals(List.of("private", "friends", "public"), capabilities.visibility());
            assertEquals(List.of("invite_only", "friends", "public"), capabilities.joinPolicies());
            assertTrue(capabilities.requireApprovalSupported());
            assertEquals(9, capabilities.combinations().size());
        }
    }

    /// Advertised multiplayer limits parse field by field.
    @Test
    public void parsesCapabilityLimits() throws Exception {
        try (Fixture server = Fixture.start()) {
            Limits limits = server.client().capabilities().limits();
            assertEquals(1L, limits.maxActiveSessionsPerUser());
            assertEquals(8L, limits.maxPlayersPerSession());
            assertEquals(20L, limits.maxPendingInvitesPerSender());
            assertEquals(3L, limits.maxPendingInvitesPerSessionTarget());
            assertEquals(2L, limits.maxPresenceConnectionsPerUser());
            assertEquals(30L, limits.maxLobbyPageSize());
            assertEquals(5L, limits.maxRealtimeSessionSubscriptions());
        }
    }

    /// A deployment without a `limits` object reports unknown limits.
    @Test
    public void missingLimitsDefaultsToNone() throws Exception {
        try (Fixture server = Fixture.start()) {
            server.legacyCapabilities = true;
            assertEquals(Limits.NONE, server.client().capabilities().limits());
        }
    }

    /// Session creation sends `require_approval` and echoes it back.
    @Test
    public void createsSessionWithApproval() throws Exception {
        try (Fixture server = Fixture.start()) {
            Joined joined = server.client().createSession("private", "public", 8,
                    "Mindustry", "build 160", true);

            assertEquals("ses_1", joined.session().id());
            assertTrue(joined.session().requireApproval());
            assertEquals("A1B2C3D4E5", joined.joinCode());

            String body = server.lastBody();
            assertTrue(body.contains("\"visibility\":\"private\""));
            assertTrue(body.contains("\"join_policy\":\"public\""));
            assertTrue(body.contains("\"require_approval\":true"));
        }
    }

    /// The public lobby exposes paging metadata and session approval state.
    @Test
    public void listsLobbySessions() throws Exception {
        try (Fixture server = Fixture.start()) {
            SessionPage page = server.client().sessions(2, 10);
            assertEquals(1, page.sessions().size());
            assertEquals("ses_1", page.sessions().get(0).id());
            assertTrue(page.sessions().get(0).requireApproval());
            assertEquals(2, page.page());
            assertEquals(10, page.limit());
            assertTrue(page.hasMore());
            assertTrue(server.sawQuery("page=2"), () -> "query was " + server.lastQuery());
            assertTrue(server.sawQuery("limit=10"));
        }
    }

    /// Pending join requests parse with pagination, id and requester fallbacks.
    @Test
    public void listsJoinRequestsWithPagination() throws Exception {
        try (Fixture server = Fixture.start()) {
            JoinRequestPage page = server.client().joinRequests("ses_1", 1, 10);

            assertEquals(1, page.requests().size());
            assertEquals("jr_1", page.requests().get(0).id());
            assertEquals(Instant.parse("2026-11-08T10:00:00Z"),
                    page.requests().get(0).createdAt());
            assertEquals(Instant.parse("2026-11-08T10:05:00Z"),
                    page.requests().get(0).expiresAt());
            assertEquals(9L, page.requests().get(0).userId());
            assertEquals("alice", page.requests().get(0).username());
            assertEquals(1, page.page());
            assertEquals(2, page.totalPages());
            assertTrue(page.hasMore());
            assertTrue(server.sawQuery("page=1"), () -> "query was " + server.lastQuery());

            JoinRequestPage second = server.client().joinRequests("ses_1", 2, 10);
            assertEquals(1, second.requests().size());
            assertEquals("jr_2", second.requests().get(0).id());
            assertEquals(10L, second.requests().get(0).userId());
            assertEquals("bob", second.requests().get(0).username());
            assertEquals(2, second.page());
            assertEquals(2, second.totalPages());
            assertFalse(second.hasMore());
            assertTrue(server.sawQuery("page=2"));
            assertTrue(server.sawQuery("limit=10"));
            assertTrue(server.sawPath("/multiplayer/sessions/ses_1/join-requests"));
        }
    }

    /// An envelope without a `data` array or `data.requests` is rejected.
    @Test
    public void rejectsUnexpectedJoinRequestEnvelope() throws Exception {
        try (Fixture server = Fixture.start()) {
            server.joinRequestsBody = "{\"data\":{}}";
            assertThrows(IOException.class,
                    () -> server.client().joinRequests("ses_1", 1, 10));
        }
    }

    /// Multiplayer preferences expose eligible clients and can be cleared.
    @Test
    public void readsAndClearsDefaultClient() throws Exception {
        try (Fixture server = Fixture.start()) {
            MdtbbsMultiplayerClient.Preferences preferences = server.client().preferences();
            assertEquals("desktop-launcher", preferences.defaultClientId());
            assertEquals(1, preferences.clients().size());
            assertEquals("Xenon Launcher", preferences.clients().get(0).name());
            assertTrue(preferences.clients().get(0).supportsJoinIntent());

            server.client().setDefaultClient(null);
            assertTrue(server.lastBody().contains("{}"), server.lastBody());
        }
    }

    /// Session creation returns the session, owner peer, resume token and join code.
    @Test
    public void createsSession() throws Exception {
        try (Fixture server = Fixture.start()) {
            Joined joined = server.client().createSession("private", "public", 8, "Mindustry", "build 160");

            assertEquals("ses_1", joined.session().id());
            assertEquals(1, joined.session().currentPlayers());
            assertEquals("peer_owner", joined.peer().peerId());
            assertTrue(joined.peer().owner());
            assertEquals("rt_owner", joined.resumeToken());
            assertEquals("A1B2C3D4E5", joined.joinCode());

            String body = server.lastBody();
            assertTrue(body.contains("\"visibility\":\"private\""));
            assertTrue(body.contains("\"join_policy\":\"public\""));
            assertTrue(body.contains("\"max_players\":8"));
            assertTrue(body.contains("\"require_approval\":false"));
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

    /// The 1.8.0 lobby snapshot carries join eligibility and its reason.
    @Test
    public void parsesLobbyJoinEligibility() throws Exception {
        try (Fixture server = Fixture.start()) {
            server.lobbyBody = Fixture.blockedLobbyJson();
            SessionPage page = server.client().sessions(1, 10);

            assertEquals(1, page.sessions().size());
            Session session = page.sessions().get(0);
            assertFalse(session.joinable());
            assertFalse(session.joinableDirectly());
            assertFalse(session.canRequestJoin());
            assertTrue(session.blocked());
            assertEquals("SESSION_FULL", session.blockedReason());
        }
    }

    /// Sessions without the 1.8.0 fields keep the documented 1.7.0 join rules.
    @Test
    public void infersLobbyJoinEligibilityForLegacySessions() throws Exception {
        try (Fixture server = Fixture.start()) {
            server.lobbyBody = Fixture.legacyLobbyJson();
            SessionPage page = server.client().sessions(1, 10);

            assertEquals(4, page.sessions().size());

            Session direct = page.sessions().get(0);
            assertEquals("active", direct.status());
            assertFalse(direct.requireApproval());
            assertTrue(direct.joinable());
            assertTrue(direct.joinableDirectly());
            assertFalse(direct.canRequestJoin());
            assertNull(direct.blockedReason());
            assertFalse(direct.blocked());

            Session approval = page.sessions().get(1);
            assertTrue(approval.requireApproval());
            assertFalse(approval.joinable());
            assertFalse(approval.joinableDirectly());
            assertTrue(approval.canRequestJoin());
            assertNull(approval.blockedReason());
            assertFalse(approval.blocked());

            Session inactive = page.sessions().get(2);
            assertFalse(inactive.joinable());
            assertFalse(inactive.canRequestJoin());
            assertTrue(inactive.blocked());

            Session noStatus = page.sessions().get(3);
            assertTrue(noStatus.status().isBlank());
            assertFalse(noStatus.requireApproval());
            assertTrue(noStatus.joinable());
            assertFalse(noStatus.canRequestJoin());
            assertFalse(noStatus.blocked());
        }
    }

    /// Local fixture server serving the multiplayer routes.
    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> lastBody = new AtomicReference<>("");
        private final AtomicReference<String> lastQuery = new AtomicReference<>("");
        private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
        private volatile boolean legacyCapabilities;
        private volatile String lobbyBody = lobbyJson();
        private volatile String joinRequestsBody = "";

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

        String lastQuery() {
            return lastQuery.get();
        }

        boolean sawQuery(String fragment) {
            return lastQuery.get().contains(fragment);
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
            lastQuery.set(exchange.getRequestURI().getRawQuery() == null
                    ? "" : exchange.getRequestURI().getRawQuery());

            String response = switch (path) {
                case "/api/v1/multiplayer/capabilities" ->
                    legacyCapabilities ? legacyCapabilitiesJson() : capabilitiesJson();
                case "/api/v1/multiplayer/sessions" -> "GET".equals(method)
                    ? lobbyBody : createdSessionJson();
                case "/api/v1/multiplayer/preferences" -> preferencesJson();
                case "/api/v1/multiplayer/join-intents/intent_1/consume" -> consumedIntentJson();
                case "/api/v1/multiplayer/sessions/resolve-code" -> resolvedSessionJson();
                case "/api/v1/multiplayer/sessions/ses_1/join" -> joinedMemberJson();
                case "/api/v1/multiplayer/sessions/ses_1" -> "{\"data\":{\"session\":" + sessionJson() + "}}";
                case "/api/v1/multiplayer/sessions/ses_1/peers" -> peersJson();
                case "/api/v1/multiplayer/sessions/ses_1/join-requests" ->
                    joinRequestsBody.isEmpty()
                            ? (sawQuery("page=2") ? joinRequestsPage2Json() : joinRequestsJson())
                            : joinRequestsBody;
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

        /// Capability response fixture for the 1.8.0 contract.
        private static String capabilitiesJson() {
            return "{\"data\":{\"sessions_v1\":true,\"invites_v1\":true,\"relay_v1\":true,"
                    + "\"third_party_multiplayer\":true,\"relay_credential_ttl_seconds\":120,"
                    + "\"visibility\":[\"private\",\"friends\",\"public\"],"
                    + "\"join_policies\":[\"invite_only\",\"friends\",\"public\"],"
                    + "\"visibility_join_policy_combinations\":["
                    + "\"private+invite_only\",\"private+friends\",\"private+public\","
                    + "\"friends+invite_only\",\"friends+friends\",\"friends+public\","
                    + "\"public+invite_only\",\"public+friends\",\"public+public\"],"
                    + "\"require_approval\":true,\"transports\":[\"udp\",\"tcp\",\"quic\",\"custom\"],"
                    + "\"limits\":{\"max_active_sessions_per_user\":1,"
                    + "\"max_players_per_session\":8,\"max_pending_invites_per_sender\":20,"
                    + "\"max_pending_invites_per_session_target\":3,"
                    + "\"max_presence_connections_per_user\":2,"
                    + "\"max_lobby_page_size\":30,"
                    + "\"max_realtime_session_subscriptions\":5}}}";
        }

        /// Capability response fixture of an older deployment.
        private static String legacyCapabilitiesJson() {
            return "{\"data\":{\"sessions_v1\":true,\"invites_v1\":true,\"relay_v1\":true,"
                    + "\"third_party_multiplayer\":true,\"relay_credential_ttl_seconds\":120}}";
        }

        /// Multiplayer preferences response fixture.
        private static String preferencesJson() {
            return "{\"data\":{\"default_client_id\":\"desktop-launcher\",\"clients\":["
                    + "{\"client_id\":\"desktop-launcher\",\"name\":\"Xenon Launcher\","
                    + "\"supports_presence\":true,\"supports_multiplayer\":true,"
                    + "\"supports_join_intent\":true,\"launch_uri_template\":\"xenon://join?intent={intent_id}\"}]}}";
        }

        /// Public lobby response fixture.
        private static String lobbyJson() {
            return "{\"data\":[{\"id\":\"ses_1\",\"owner_user_id\":7,\"visibility\":\"public\","
                    + "\"join_policy\":\"public\",\"require_approval\":true,"
                    + "\"game\":{\"id\":\"mindustry\",\"version\":\"build 160\"},"
                    + "\"activity_name\":\"Mindustry\",\"current_players\":2,\"max_players\":8,"
                    + "\"status\":\"active\"}],"
                    + "\"meta\":{\"pagination\":{\"page\":2,\"limit\":10,\"total\":11,"
                    + "\"total_pages\":2,\"has_more\":true}}}";
        }

        /// Lobby response fixture carrying the 1.8.0 eligibility fields.
        private static String blockedLobbyJson() {
            return "{\"data\":[{\"id\":\"ses_blocked\",\"owner_user_id\":7,\"visibility\":\"friends\","
                    + "\"join_policy\":\"public\",\"require_approval\":false,"
                    + "\"game\":{\"id\":\"mindustry\",\"version\":\"build 160\"},"
                    + "\"activity_name\":\"Mindustry\",\"current_players\":8,\"max_players\":8,"
                    + "\"status\":\"active\",\"joinable\":false,"
                    + "\"blocked_reason\":\"SESSION_FULL\",\"can_request_join\":false}],"
                    + "\"meta\":{\"pagination\":{\"page\":1,\"limit\":10,\"total\":1,"
                    + "\"total_pages\":1,\"has_more\":false}}}";
        }

        /// Lobby response fixture of a deployment without the 1.8.0 fields.
        private static String legacyLobbyJson() {
            return "{\"data\":["
                    + "{\"id\":\"ses_direct\",\"owner_user_id\":7,\"visibility\":\"public\","
                    + "\"join_policy\":\"public\",\"require_approval\":false,"
                    + "\"activity_name\":\"Direct\",\"current_players\":1,\"max_players\":8,"
                    + "\"status\":\"active\"},"
                    + "{\"id\":\"ses_approval\",\"owner_user_id\":7,\"visibility\":\"friends\","
                    + "\"join_policy\":\"friends\",\"require_approval\":true,"
                    + "\"activity_name\":\"Approval\",\"current_players\":1,\"max_players\":8,"
                    + "\"status\":\"active\"},"
                    + "{\"id\":\"ses_closed\",\"owner_user_id\":7,\"visibility\":\"friends\","
                    + "\"join_policy\":\"friends\",\"require_approval\":true,"
                    + "\"activity_name\":\"Closed\",\"current_players\":1,\"max_players\":8,"
                    + "\"status\":\"expired\"},"
                    + "{\"id\":\"ses_nostatus\",\"owner_user_id\":7,\"visibility\":\"public\","
                    + "\"join_policy\":\"public\",\"require_approval\":false,"
                    + "\"activity_name\":\"NoStatus\",\"current_players\":1,\"max_players\":8}"
                    + "],\"meta\":{\"pagination\":{\"page\":1,\"limit\":10,\"total\":4,"
                    + "\"total_pages\":1,\"has_more\":false}}}";
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

        /// First page of the host-side pending join-request fixture.
        private static String joinRequestsJson() {
            return "{\"data\":["
                    + "{\"id\":\"jr_1\",\"created_at\":\"2026-11-08T10:00:00Z\","
                    + "\"expires_at\":\"2026-11-08T10:05:00Z\","
                    + "\"user\":{\"id\":9,\"username\":\"alice\","
                    + "\"avatar_url\":\"https://example.invalid/a.png\"}}],"
                    + "\"meta\":{\"pagination\":{\"page\":1,\"total_pages\":2,\"has_more\":true}}}";
        }

        /// Second page of the host-side pending join-request fixture.
        private static String joinRequestsPage2Json() {
            return "{\"data\":["
                    + "{\"join_request_id\":\"jr_2\",\"created_at\":\"2026-11-08T10:01:00Z\","
                    + "\"expires_at\":\"2026-11-08T10:06:00Z\","
                    + "\"requester\":{\"id\":10,\"username\":\"bob\"}}],"
                    + "\"meta\":{\"pagination\":{\"page\":2,\"total_pages\":2,\"has_more\":false}}}";
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
                    + "\"require_approval\":true,"
                    + "\"game\":{\"id\":\"mindustry\",\"version\":\"build 160\"},\"activity_name\":\"Mindustry\","
                    + "\"current_players\":1,\"max_players\":8,\"status\":\"active\"}";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
