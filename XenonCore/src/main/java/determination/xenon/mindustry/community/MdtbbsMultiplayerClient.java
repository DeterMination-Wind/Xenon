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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// MDTBBS multiplayer session, candidate and relay API surface.
///
/// Covers the signalling half of MDTBBS multiplayer: capability probing,
/// session creation / resolution / join / leave, peer heartbeats, connectivity
/// candidate exchange, join requests and official Relay credentials. The game
/// data plane itself is implemented separately by the relay tunnel.
@NotNullByDefault
public final class MdtbbsMultiplayerClient implements MdtbbsMultiplayerApi {
    /// REST transport carrying the bearer token.
    private final MdtbbsApiClient api;

    /// Creates a multiplayer client over the given transport.
    ///
    /// @param api API transport carrying the bearer token
    public MdtbbsMultiplayerClient(MdtbbsApiClient api) {
        this.api = api;
    }

    /// Server-advertised multiplayer limits.
    ///
    /// A zero field means the server did not advertise that limit; callers
    /// treat zero as "unknown" and keep their own default.
    ///
    /// @param maxActiveSessionsPerUser        active sessions allowed per account
    /// @param maxPlayersPerSession            player limit of one session
    /// @param maxPendingInvitesPerSender      pending invites one sender may have
    /// @param maxPendingInvitesPerSessionTarget pending invites targeting one session
    /// @param maxPresenceConnectionsPerUser   concurrent presence connections
    /// @param maxLobbyPageSize                largest accepted lobby page size
    /// @param maxRealtimeSessionSubscriptions realtime session subscriptions
    public record Limits(long maxActiveSessionsPerUser, long maxPlayersPerSession,
                         long maxPendingInvitesPerSender, long maxPendingInvitesPerSessionTarget,
                         long maxPresenceConnectionsPerUser, long maxLobbyPageSize,
                         long maxRealtimeSessionSubscriptions) {

        /// Unknown limits: every field is zero.
        public static final Limits NONE = new Limits(0, 0, 0, 0, 0, 0, 0);
    }

    /// Server multiplayer capabilities for the authenticated client.
    ///
    /// The enum lists and the approval flag come from the 1.7.0 contract. When
    /// an older deployment omits them the documented 1.7.0 defaults are used so
    /// the UI always has a complete choice set.
    ///
    /// @param sessionsV1            whether session APIs are enabled
    /// @param invitesV1             whether invites are enabled
    /// @param relayV1               whether the official relay is enabled
    /// @param thirdParty            whether this OAuth client is approved for third-party multiplayer
    /// @param relayCredentialTtlSeconds advertised relay credential lifetime
    /// @param visibility            allowed `visibility` values
    /// @param joinPolicies          allowed `join_policy` values
    /// @param requireApprovalSupported whether the server accepts `require_approval`
    /// @param combinations          all legal `visibility+join_policy` pairs
    /// @param transports            supported candidate transports
    /// @param limits                advertised server limits, zero when unknown
    public record Capabilities(boolean sessionsV1, boolean invitesV1, boolean relayV1,
                               boolean thirdParty, long relayCredentialTtlSeconds,
                               List<String> visibility, List<String> joinPolicies,
                               boolean requireApprovalSupported, List<String> combinations,
                               List<String> transports, Limits limits) {

        /// Documented 1.7.0 visibility values.
        public static final List<String> DEFAULT_VISIBILITY = List.of("private", "friends", "public");

        /// Documented 1.7.0 join-policy values.
        public static final List<String> DEFAULT_JOIN_POLICIES =
                List.of("invite_only", "friends", "public");

        /// Documented 1.7.0 candidate transports.
        public static final List<String> DEFAULT_TRANSPORTS = List.of("udp", "tcp", "quic", "custom");

        /// The nine legal visibility/join-policy pairs.
        public static final List<String> DEFAULT_COMBINATIONS = combinationsOf(
                DEFAULT_VISIBILITY, DEFAULT_JOIN_POLICIES);

        /// Copies the enum lists defensively and fills empty lists with defaults.
        public Capabilities {
            visibility = List.copyOf(visibility.isEmpty() ? DEFAULT_VISIBILITY : visibility);
            joinPolicies = List.copyOf(joinPolicies.isEmpty() ? DEFAULT_JOIN_POLICIES : joinPolicies);
            combinations = List.copyOf(combinations.isEmpty()
                    ? combinationsOf(visibility, joinPolicies) : combinations);
            transports = List.copyOf(transports.isEmpty() ? DEFAULT_TRANSPORTS : transports);
        }

        /// Builds capabilities without advertised limits.
        ///
        /// @param sessionsV1            whether session APIs are enabled
        /// @param invitesV1             whether invites are enabled
        /// @param relayV1               whether the official relay is enabled
        /// @param thirdParty            whether this OAuth client is approved
        /// @param relayCredentialTtlSeconds advertised relay credential lifetime
        /// @param visibility            allowed `visibility` values
        /// @param joinPolicies          allowed `join_policy` values
        /// @param requireApprovalSupported whether the server accepts `require_approval`
        /// @param combinations          all legal `visibility+join_policy` pairs
        /// @param transports            supported candidate transports
        public Capabilities(boolean sessionsV1, boolean invitesV1, boolean relayV1,
                            boolean thirdParty, long relayCredentialTtlSeconds,
                            List<String> visibility, List<String> joinPolicies,
                            boolean requireApprovalSupported, List<String> combinations,
                            List<String> transports) {
            this(sessionsV1, invitesV1, relayV1, thirdParty, relayCredentialTtlSeconds,
                    visibility, joinPolicies, requireApprovalSupported, combinations,
                    transports, Limits.NONE);
        }

        /// Builds capabilities from the pre-1.7.0 response shape.
        ///
        /// @param sessionsV1            whether session APIs are enabled
        /// @param invitesV1             whether invites are enabled
        /// @param relayV1               whether the official relay is enabled
        /// @param thirdParty            whether this OAuth client is approved
        /// @param relayCredentialTtlSeconds advertised relay credential lifetime
        public Capabilities(boolean sessionsV1, boolean invitesV1, boolean relayV1,
                            boolean thirdParty, long relayCredentialTtlSeconds) {
            this(sessionsV1, invitesV1, relayV1, thirdParty, relayCredentialTtlSeconds,
                    List.of(), List.of(), true, List.of(), List.of());
        }

        /// Whether the given visibility/join-policy pair is advertised as legal.
        ///
        /// @param visibility desired visibility
        /// @param joinPolicy desired join policy
        /// @return whether the pair is legal
        public boolean allows(String visibility, String joinPolicy) {
            return combinations.contains(visibility + "+" + joinPolicy);
        }

        /// Builds the cartesian product of two value lists.
        private static List<String> combinationsOf(List<String> visibility, List<String> joinPolicies) {
            List<String> result = new ArrayList<>(visibility.size() * joinPolicies.size());
            for (String visible : visibility) {
                for (String join : joinPolicies) {
                    result.add(visible + "+" + join);
                }
            }
            return List.copyOf(result);
        }
    }

    /// One multiplayer session.
    ///
    /// @param id             opaque session id
    /// @param ownerUserId    id of the owning user
    /// @param visibility     `private`, `friends` or `public`
    /// @param joinPolicy     `invite_only`, `friends` or `public`
    /// @param gameVersion    game version recorded by the host, or empty
    /// @param activityName   activity text shown to friends
    /// @param currentPlayers occupied player count
    /// @param maxPlayers     player limit
    /// @param status         server status such as `active`
    /// @param requireApproval whether the host must approve join requests
    /// @param expiresAt      hard session expiry, or {`Instant#EPOCH`} when omitted
    /// @param joinable       whether the server allows a direct join
    /// @param blockedReason  stable reason code when the session is not joinable, or `null`
    /// @param canRequestJoin whether the server accepts a join request for this session
    public record Session(String id, long ownerUserId, String visibility, String joinPolicy,
                          String gameVersion, String activityName,
                          int currentPlayers, int maxPlayers, String status,
                          boolean requireApproval, Instant expiresAt,
                          boolean joinable, @Nullable String blockedReason,
                          boolean canRequestJoin) {

        /// Builds a session from the pre-1.7.0 response shape.
        ///
        /// @param id             opaque session id
        /// @param ownerUserId    id of the owning user
        /// @param visibility     visibility value
        /// @param joinPolicy     join-policy value
        /// @param gameVersion    game version recorded by the host, or empty
        /// @param activityName   activity text
        /// @param currentPlayers occupied player count
        /// @param maxPlayers     player limit
        /// @param status         server status
        public Session(String id, long ownerUserId, String visibility, String joinPolicy,
                       String gameVersion, String activityName,
                       int currentPlayers, int maxPlayers, String status) {
            this(id, ownerUserId, visibility, joinPolicy, gameVersion, activityName,
                    currentPlayers, maxPlayers, status, false, Instant.EPOCH);
        }

        /// Builds a session without a known expiry.
        ///
        /// @param id             opaque session id
        /// @param ownerUserId    id of the owning user
        /// @param visibility     visibility value
        /// @param joinPolicy     join-policy value
        /// @param gameVersion    game version recorded by the host, or empty
        /// @param activityName   activity text
        /// @param currentPlayers occupied player count
        /// @param maxPlayers     player limit
        /// @param status         server status
        /// @param requireApproval whether the host must approve join requests
        public Session(String id, long ownerUserId, String visibility, String joinPolicy,
                       String gameVersion, String activityName,
                       int currentPlayers, int maxPlayers, String status,
                       boolean requireApproval) {
            this(id, ownerUserId, visibility, joinPolicy, gameVersion, activityName,
                    currentPlayers, maxPlayers, status, requireApproval, Instant.EPOCH);
        }

        /// Builds a session from the 1.7.0 response shape, inferring lobby eligibility.
        ///
        /// The inferred values reproduce the pre-1.8.0 behaviour: an `active`
        /// session accepts a direct join unless it requires approval, and an
        /// approval session accepts a join request.
        ///
        /// @param id             opaque session id
        /// @param ownerUserId    id of the owning user
        /// @param visibility     visibility value
        /// @param joinPolicy     join-policy value
        /// @param gameVersion    game version recorded by the host, or empty
        /// @param activityName   activity text
        /// @param currentPlayers occupied player count
        /// @param maxPlayers     player limit
        /// @param status         server status
        /// @param requireApproval whether the host must approve join requests
        /// @param expiresAt      hard session expiry
        public Session(String id, long ownerUserId, String visibility, String joinPolicy,
                       String gameVersion, String activityName,
                       int currentPlayers, int maxPlayers, String status,
                       boolean requireApproval, Instant expiresAt) {
            this(id, ownerUserId, visibility, joinPolicy, gameVersion, activityName,
                    currentPlayers, maxPlayers, status, requireApproval, expiresAt,
                    activeStatus(status) && !requireApproval, null,
                    activeStatus(status) && requireApproval);
        }

        /// Whether a status should be treated as an active session.
        ///
        /// Older deployments omit `status` entirely; those sessions behave like
        /// `active` ones instead of being rendered as permanently unavailable.
        ///
        /// @param status server status, or `null`
        /// @return whether the session is active
        static boolean activeStatus(@Nullable String status) {
            return status == null || status.isBlank() || "active".equals(status);
        }

        /// Whether the session currently accepts new peers without an approval step.
        ///
        /// @return whether a direct join is possible
        public boolean joinableDirectly() {
            return joinable;
        }

        /// Whether the session refuses both a direct join and a join request.
        ///
        /// @return whether no lobby action can succeed
        public boolean blocked() {
            return !joinable && !canRequestJoin;
        }
    }

    /// One OAuth client that can act as the default join-intent launcher.
    ///
    /// @param clientId          opaque client id accepted by the preference API
    /// @param name              display name
    /// @param iconUrl           application icon, or empty
    /// @param developerName     developer name, or empty
    /// @param supportsPresence  whether the client publishes presence
    /// @param supportsMultiplayer whether the client supports multiplayer
    /// @param supportsJoinIntent whether the client can consume join intents
    /// @param launchUriTemplate registered launch URI, or empty
    public record ClientApp(String clientId, String name, String iconUrl,
                            String developerName, boolean supportsPresence,
                            boolean supportsMultiplayer, boolean supportsJoinIntent,
                            String launchUriTemplate) {
    }

    /// Multiplayer preferences of the current user.
    ///
    /// @param defaultClientId default join-intent client, or empty when unset
    /// @param clients         clients eligible to become the default
    public record Preferences(String defaultClientId, List<ClientApp> clients) {
        /// Copies the client list defensively.
        public Preferences {
            clients = List.copyOf(clients);
        }
    }

    /// One page of discoverable lobby sessions.
    ///
    /// @param sessions   sessions on this page
    /// @param page       1-based page number
    /// @param limit      requested page size
    /// @param total      total session count, or `-1` when omitted
    /// @param totalPages total page count, or `-1` when omitted
    /// @param hasMore    whether another page exists
    public record SessionPage(List<Session> sessions, int page, int limit, int total,
                              int totalPages, boolean hasMore) {
        /// Copies the session list defensively.
        public SessionPage {
            sessions = List.copyOf(sessions);
        }
    }

    /// One session peer.
    ///
    /// @param peerId opaque peer id
    /// @param userId owning user id
    /// @param role   `owner` or `member`
    /// @param status server status such as `active`
    public record Peer(String peerId, long userId, String role, String status) {
        /// Whether this peer is the session host.
        public boolean owner() {
            return "owner".equals(role);
        }
    }

    /// Result of creating or joining a session.
    ///
    /// @param session     session description
    /// @param peer        this client's peer entry
    /// @param resumeToken one-shot resume token, or empty when the server omitted it
    /// @param joinCode    unlisted join code, or empty
    public record Joined(Session session, Peer peer, String resumeToken, String joinCode) {
    }

    /// One connectivity candidate published by a peer.
    ///
    /// @param candidateId opaque candidate id
    /// @param peerId      publishing peer
    /// @param kind        `local`, `public` or `relay`
    /// @param transport   `udp`, `tcp`, `quic` or `custom`
    /// @param address     IP or host
    /// @param port        port
    /// @param priority    higher is preferred
    public record Candidate(String candidateId, String peerId, String kind, String transport,
                            String address, int port, int priority) {
    }

    /// One official Relay allocation.
    ///
    /// @param allocationId opaque allocation id
    /// @param agentId      relay agent id
    /// @param endpoint     `wss://` endpoint ending in `/relay/v1`
    /// @param credential   short-lived credential used in the first auth frame
    /// @param expiresInSeconds credential lifetime
    public record RelayAllocation(String allocationId, String agentId, String endpoint,
                                  String credential, long expiresInSeconds) {
    }

    /// One pending join request of a hosted session.
    ///
    /// @param id        join request id
    /// @param createdAt creation instant, or {`Instant#EPOCH`} when omitted
    /// @param expiresAt expiry instant, or {`Instant#EPOCH`} when omitted
    /// @param userId    requesting user id, or `0` when unknown
    /// @param username  requesting user name, or empty
    public record JoinRequestEntry(String id, Instant createdAt, Instant expiresAt,
                                   long userId, String username) {
    }

    /// One page of pending join requests.
    ///
    /// @param requests   join requests on this page
    /// @param page       1-based page number
    /// @param totalPages total page count, or `-1` when omitted
    /// @param hasMore    whether another page exists
    public record JoinRequestPage(List<JoinRequestEntry> requests, int page, int totalPages,
                                  boolean hasMore) {
        /// Copies the request list defensively.
        public JoinRequestPage {
            requests = List.copyOf(requests);
        }
    }

    /// Reads the multiplayer capability switches.
    ///
    /// The response may be flat (documented) or nested under `multiplayer`
    /// (older gateways); both are accepted. Unknown fields are ignored.
    ///
    /// @return parsed capabilities
    /// @throws IOException when the request fails
    public Capabilities capabilities() throws IOException {
        JsonObject data = MdtbbsJson.dataObject(api.getAuth("/multiplayer/capabilities", null));
        JsonObject nested = MdtbbsJson.objectOf(data, "multiplayer");
        JsonObject source = nested == null ? data : nested;
        return new Capabilities(
                MdtbbsJson.boolOf(source, "sessions_v1", false),
                MdtbbsJson.boolOf(source, "invites_v1", false),
                MdtbbsJson.boolOf(source, "relay_v1", false),
                MdtbbsJson.boolOf(source, "third_party_multiplayer", false),
                MdtbbsJson.longOf(source, "relay_credential_ttl_seconds", 120),
                MdtbbsJson.stringListOf(source, "visibility"),
                MdtbbsJson.stringListOf(source, "join_policies"),
                MdtbbsJson.boolOf(source, "require_approval", true),
                MdtbbsJson.stringListOf(source, "visibility_join_policy_combinations"),
                MdtbbsJson.stringListOf(source, "transports"),
                parseLimits(source));
    }

    /// Reads the advertised multiplayer limits, defaulting every field to zero.
    ///
    /// A missing or malformed `limits` object yields {`Limits#NONE`}.
    private static Limits parseLimits(JsonObject source) {
        JsonObject limits = MdtbbsJson.objectOf(source, "limits");
        if (limits == null) {
            return Limits.NONE;
        }
        return new Limits(
                MdtbbsJson.longOf(limits, "max_active_sessions_per_user", 0),
                MdtbbsJson.longOf(limits, "max_players_per_session", 0),
                MdtbbsJson.longOf(limits, "max_pending_invites_per_sender", 0),
                MdtbbsJson.longOf(limits, "max_pending_invites_per_session_target", 0),
                MdtbbsJson.longOf(limits, "max_presence_connections_per_user", 0),
                MdtbbsJson.longOf(limits, "max_lobby_page_size", 0),
                MdtbbsJson.longOf(limits, "max_realtime_session_subscriptions", 0));
    }

    /// Creates a session owned by this client.
    ///
    /// @param visibility      `private`, `friends` or `public`
    /// @param joinPolicy      `invite_only`, `friends` or `public`
    /// @param maxPlayers      player limit, clamped to 2..64
    /// @param activityName    activity text shown to friends
    /// @param gameVersion     game version shown to friends, or empty
    /// @param requireApproval whether the host must approve join requests
    /// @return the created session, owner peer and optional join code
    /// @throws IOException when the request fails
    public Joined createSession(String visibility, String joinPolicy, int maxPlayers,
                                String activityName, String gameVersion,
                                boolean requireApproval) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("game_id", "mindustry");
        if (gameVersion != null && !gameVersion.isBlank()) {
            body.addProperty("game_version", gameVersion);
        }
        if (activityName != null && !activityName.isBlank()) {
            body.addProperty("activity_name", activityName);
        }
        body.addProperty("visibility", visibility);
        body.addProperty("join_policy", joinPolicy);
        // `invite_only` has no approval path, so never send a contradictory flag.
        body.addProperty("require_approval", requireApproval && !"invite_only".equals(joinPolicy));
        body.addProperty("max_players", Math.max(2, Math.min(64, maxPlayers)));
        JsonObject data = MdtbbsJson.dataObject(api.post("/multiplayer/sessions", body));
        return parseJoined(data, sessionFrom(data));
    }

    /// Creates a session without an approval step.
    ///
    /// @param visibility      `private`, `friends` or `public`
    /// @param joinPolicy      `invite_only`, `friends` or `public`
    /// @param maxPlayers      player limit, clamped to 2..64
    /// @param activityName    activity text shown to friends
    /// @param gameVersion     game version shown to friends, or empty
    /// @return the created session, owner peer and optional join code
    /// @throws IOException when the request fails
    public Joined createSession(String visibility, String joinPolicy, int maxPlayers,
                                String activityName, String gameVersion) throws IOException {
        return createSession(visibility, joinPolicy, maxPlayers, activityName, gameVersion, false);
    }

    /// Lists the sessions the caller may discover.
    ///
    /// Only `public` sessions, `friends` sessions owned by a friend and
    /// sessions the caller already occupies are returned; `private` sessions
    /// never appear.
    ///
    /// @param page  1-based page number
    /// @param limit page size, clamped to 1..50
    /// @return one page of discoverable sessions
    /// @throws IOException when the request fails
    public SessionPage sessions(int page, int limit) throws IOException {
        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, Math.min(50, limit));
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("page", Integer.toString(safePage));
        parameters.put("limit", Integer.toString(safeLimit));
        JsonObject root = api.getAuth("/multiplayer/sessions", parameters);
        JsonArray array = MdtbbsJson.dataArray(root);
        if (array.isEmpty()) {
            array = MdtbbsJson.arrayOf(MdtbbsJson.dataObject(root), "sessions");
        }
        List<Session> sessions = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject object = MdtbbsJson.asObject(element);
            Session session = object == null ? null : sessionFrom(object);
            if (session != null) sessions.add(session);
        }
        JsonObject pagination = MdtbbsJson.objectOf(root, "meta");
        pagination = pagination == null ? null : MdtbbsJson.objectOf(pagination, "pagination");
        if (pagination == null) {
            pagination = MdtbbsJson.objectOf(MdtbbsJson.dataObject(root), "pagination");
        }
        int total = pagination == null ? -1 : (int) MdtbbsJson.longOf(pagination, "total", -1);
        int totalPages = pagination == null ? -1 : (int) MdtbbsJson.longOf(pagination, "total_pages", -1);
        if (pagination != null && totalPages < 0) {
            totalPages = (int) MdtbbsJson.longOf(pagination, "totalPages", -1);
        }
        boolean hasMore = pagination != null && MdtbbsJson.boolOf(pagination, "has_more", false);
        if (pagination != null && !hasMore && totalPages > 0) {
            hasMore = safePage < totalPages;
        }
        return new SessionPage(sessions, safePage, safeLimit, total, totalPages, hasMore);
    }

    /// Reads the user's multiplayer preferences and eligible clients.
    ///
    /// @return the preferences
    /// @throws IOException when the request fails
    public Preferences preferences() throws IOException {
        JsonObject data = MdtbbsJson.dataObject(api.getAuth("/multiplayer/preferences", null));
        List<ClientApp> clients = new ArrayList<>();
        for (JsonElement element : MdtbbsJson.arrayOf(data, "clients")) {
            JsonObject object = MdtbbsJson.asObject(element);
            if (object == null) continue;
            clients.add(new ClientApp(
                    MdtbbsJson.stringOf(object, "client_id"),
                    MdtbbsJson.stringOf(object, "name"),
                    MdtbbsJson.stringOf(object, "application_icon_url"),
                    MdtbbsJson.stringOf(object, "developer_name"),
                    MdtbbsJson.boolOf(object, "supports_presence", false),
                    MdtbbsJson.boolOf(object, "supports_multiplayer", false),
                    MdtbbsJson.boolOf(object, "supports_join_intent", false),
                    MdtbbsJson.stringOf(object, "launch_uri_template")));
        }
        return new Preferences(MdtbbsJson.stringOf(data, "default_client_id"), clients);
    }

    /// Sets or clears the default join-intent launcher.
    ///
    /// @param clientId client id from [#preferences()], or `null` to clear
    /// @throws IOException when the request fails
    public void setDefaultClient(@Nullable String clientId) throws IOException {
        JsonObject body = new JsonObject();
        if (clientId != null && !clientId.isBlank()) {
            // Omitting `client_id` clears the default, which is the documented
            // behaviour; sending JSON null is not representable here because
            // the shared Gson instance omits null members.
            body.addProperty("client_id", clientId);
        }
        api.patch("/multiplayer/preferences", body);
    }

    /// Resolves a public join code to its session.
    ///
    /// @param code ten-character join code, case-insensitive
    /// @return the resolved session
    /// @throws IOException when the code is invalid or expired
    public Session resolveCode(String code) throws IOException {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[0-9A-F]{10}")) {
            throw new IllegalArgumentException("MDTBBS join codes are 10 hexadecimal characters");
        }
        JsonObject body = new JsonObject();
        body.addProperty("code", normalized);
        JsonObject data = MdtbbsJson.dataObject(api.post("/multiplayer/sessions/resolve-code", body));
        Session session = sessionFrom(data);
        if (session == null) {
            throw new IOException("MDTBBS did not return a session for join code");
        }
        return session;
    }

    /// Reads one visible session.
    ///
    /// @param sessionId session id
    /// @return the session, or `null` when the server returned none
    /// @throws IOException when the request fails
    public @Nullable Session session(String sessionId) throws IOException {
        JsonObject data = MdtbbsJson.dataObject(api.getAuth(path("/multiplayer/sessions/" + sessionId), null));
        return sessionFrom(data);
    }

    /// Joins a session as a new peer.
    ///
    /// @param sessionId session id
    /// @param joinCode  unlisted join code, or `null`
    /// @return this client's peer and resume token
    /// @throws IOException when the join is refused
    public Joined join(String sessionId, @Nullable String joinCode) throws IOException {
        JsonObject body = new JsonObject();
        if (joinCode != null && !joinCode.isBlank()) {
            body.addProperty("join_code", joinCode.trim().toUpperCase(Locale.ROOT));
        }
        body.add("capabilities", joinCapabilities());
        JsonObject data = MdtbbsJson.dataObject(
                api.post(path("/multiplayer/sessions/" + sessionId + "/join"), body));
        Session session = sessionFrom(data);
        if (session == null) {
            // Join responses may only carry the peer; fetch the session for the
            // caller's status display.
            session = session(sessionId);
        }
        return parseJoined(data, session);
    }

    /// Restores a previous peer using its resume token.
    ///
    /// @param sessionId   session id
    /// @param resumeToken resume token from the previous join
    /// @return the restored peer and a rotated resume token
    /// @throws IOException when the resume window expired
    public Joined resume(String sessionId, String resumeToken) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("resume_token", resumeToken);
        body.add("capabilities", joinCapabilities());
        JsonObject data = MdtbbsJson.dataObject(
                api.post(path("/multiplayer/sessions/" + sessionId + "/join"), body));
        Session session = sessionFrom(data);
        if (session == null) {
            session = session(sessionId);
        }
        return parseJoined(data, session);
    }

    /// Leaves a session.
    ///
    /// @param sessionId session id
    /// @throws IOException when the request fails
    public void leave(String sessionId) throws IOException {
        api.post(path("/multiplayer/sessions/" + sessionId + "/leave"), new JsonObject());
    }

    /// Lists the peers that currently occupy session slots.
    ///
    /// @param sessionId session id
    /// @return peer entries
    /// @throws IOException when the request fails
    public List<Peer> peers(String sessionId) throws IOException {
        JsonObject root = api.getAuth(path("/multiplayer/sessions/" + sessionId + "/peers"), null);
        JsonArray array = MdtbbsJson.dataArray(root);
        if (array.isEmpty()) {
            array = MdtbbsJson.arrayOf(MdtbbsJson.dataObject(root), "peers");
        }
        List<Peer> result = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject object = MdtbbsJson.asObject(element);
            if (object == null) continue;
            result.add(parsePeer(object));
        }
        return result;
    }

    /// Sends one peer heartbeat.
    ///
    /// @param sessionId session id
    /// @param peerId    peer id
    /// @throws IOException when the request fails
    public void peerHeartbeat(String sessionId, String peerId) throws IOException {
        api.post(path("/multiplayer/sessions/" + sessionId + "/peers/" + peerId + "/heartbeat"),
                new JsonObject());
    }

    /// Publishes one connectivity candidate for this peer.
    ///
    /// @param sessionId session id
    /// @param kind      `local`, `public` or `relay`
    /// @param transport `udp`, `tcp`, `quic` or `custom`
    /// @param address   IP or host
    /// @param port      port
    /// @param priority  higher is preferred
    /// @return the candidate id
    /// @throws IOException when the request fails
    public String addCandidate(String sessionId, String kind, String transport,
                               String address, int port, int priority) throws IOException {
        JsonObject candidate = new JsonObject();
        candidate.addProperty("kind", kind);
        candidate.addProperty("transport", transport);
        candidate.addProperty("address", address);
        candidate.addProperty("port", port);
        candidate.addProperty("priority", priority);
        JsonObject body = new JsonObject();
        body.add("candidate", candidate);
        JsonObject data = MdtbbsJson.dataObject(
                api.post(path("/multiplayer/sessions/" + sessionId + "/candidates"), body));
        return MdtbbsJson.stringOf(data, "candidate_id");
    }

    /// Lists the candidates published by one peer.
    ///
    /// @param sessionId session id
    /// @param peerId    peer whose candidates to read
    /// @return candidate entries
    /// @throws IOException when the request fails
    public List<Candidate> peerCandidates(String sessionId, String peerId) throws IOException {
        JsonObject root = api.getAuth(
                path("/multiplayer/sessions/" + sessionId + "/peers/" + peerId + "/candidates"), null);
        JsonArray array = MdtbbsJson.dataArray(root);
        if (array.isEmpty()) {
            array = MdtbbsJson.arrayOf(MdtbbsJson.dataObject(root), "candidates");
        }
        List<Candidate> result = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject object = MdtbbsJson.asObject(element);
            if (object == null) continue;
            result.add(new Candidate(
                    MdtbbsJson.stringOf(object, "candidate_id"),
                    MdtbbsJson.stringOf(object, "peer_id"),
                    MdtbbsJson.stringOf(object, "kind"),
                    MdtbbsJson.stringOf(object, "transport"),
                    MdtbbsJson.stringOf(object, "address"),
                    (int) MdtbbsJson.longOf(object, "port", 0),
                    (int) MdtbbsJson.longOf(object, "priority", 0)));
        }
        return result;
    }

    /// Deletes one of this peer's candidates.
    ///
    /// @param sessionId   session id
    /// @param candidateId candidate id
    /// @throws IOException when the request fails
    public void removeCandidate(String sessionId, String candidateId) throws IOException {
        api.delete(path("/multiplayer/sessions/" + sessionId + "/candidates/" + candidateId), null);
    }

    /// Allocates an official relay credential for this peer.
    ///
    /// @param sessionId session id
    /// @return the allocation
    /// @throws IOException when the relay is unavailable or the client lacks the capability
    public RelayAllocation allocateRelay(String sessionId) throws IOException {
        JsonObject data = MdtbbsJson.dataObject(
                api.post(path("/multiplayer/sessions/" + sessionId + "/relay"), new JsonObject()));
        return new RelayAllocation(
                MdtbbsJson.stringOf(data, "allocation_id"),
                MdtbbsJson.stringOf(data, "agent_id"),
                MdtbbsJson.stringOf(data, "endpoint"),
                MdtbbsJson.stringOf(data, "credential"),
                MdtbbsJson.longOf(data, "expires_in", 120));
    }

    /// Creates an invite for one friend.
    ///
    /// @param sessionId    session owned by this peer
    /// @param targetUserId forum user id of the friend
    /// @return the invite id
    /// @throws IOException when the request fails
    public String createInvite(String sessionId, long targetUserId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("session_id", sessionId);
        body.addProperty("target_user_id", targetUserId);
        JsonObject data = MdtbbsJson.dataObject(api.post("/multiplayer/invites", body));
        String id = MdtbbsJson.stringOf(data, "invite_id");
        if (id.isBlank()) {
            id = MdtbbsJson.stringOf(data, "id");
        }
        return id;
    }

    /// Revokes one invite created by this user.
    ///
    /// @param inviteId invite id
    /// @throws IOException when the request fails
    public void revokeInvite(String inviteId) throws IOException {
        api.post(path("/multiplayer/invites/" + inviteId + "/revoke"), new JsonObject());
    }

    /// Requests to join a session whose policy requires approval.
    ///
    /// @param sessionId session id
    /// @throws IOException when the request fails
    public String createJoinRequest(String sessionId) throws IOException {
        JsonObject data = MdtbbsJson.dataObject(
                api.post(path("/multiplayer/sessions/" + sessionId + "/join-requests"),
                        new JsonObject()));
        String id = MdtbbsJson.stringOf(data, "join_request_id");
        return id.isBlank() ? MdtbbsJson.stringOf(data, "id") : id;
    }

    /// Lists the pending join requests of a hosted session.
    ///
    /// The host view is server-authoritative: entries are ordered by the
    /// server, and the caller decides how long to keep a locally inserted
    /// request that the snapshot has not published yet.
    ///
    /// @param sessionId session owned by this peer
    /// @param page      1-based page number
    /// @param limit     page size, clamped to 1..50
    /// @return one page of pending join requests
    /// @throws IOException when the request fails
    public JoinRequestPage joinRequests(String sessionId, int page, int limit) throws IOException {
        int safePage = Math.max(1, page);
        int safeLimit = Math.max(1, Math.min(50, limit));
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("page", Integer.toString(safePage));
        parameters.put("limit", Integer.toString(safeLimit));
        JsonObject root = api.getAuth(
                path("/multiplayer/sessions/" + sessionId + "/join-requests"), parameters);
        JsonArray array;
        JsonElement dataElement = root.get("data");
        if (dataElement != null && dataElement.isJsonArray()) {
            array = dataElement.getAsJsonArray();
        } else {
            JsonObject data = MdtbbsJson.objectOf(root, "data");
            JsonElement requests = data == null ? null : data.get("requests");
            if (requests == null || !requests.isJsonArray()) {
                throw new IOException("unexpected join-request response");
            }
            array = requests.getAsJsonArray();
        }
        List<JoinRequestEntry> requests = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject object = MdtbbsJson.asObject(element);
            if (object == null) continue;
            String id = MdtbbsJson.stringOf(object, "id");
            if (id.isBlank()) {
                id = MdtbbsJson.stringOf(object, "join_request_id");
            }
            if (id.isBlank()) continue;
            JsonObject requester = MdtbbsJson.objectOf(object, "user");
            if (requester == null) {
                requester = MdtbbsJson.objectOf(object, "requester");
            }
            requests.add(new JoinRequestEntry(
                    id,
                    MdtbbsJson.instantOf(object, "created_at"),
                    MdtbbsJson.instantOf(object, "expires_at"),
                    requester == null ? 0L : MdtbbsJson.longOf(requester, "id", 0L),
                    requester == null ? "" : MdtbbsJson.stringOf(requester, "username")));
        }
        JsonObject pagination = MdtbbsJson.objectOf(root, "meta");
        pagination = pagination == null ? null : MdtbbsJson.objectOf(pagination, "pagination");
        if (pagination == null) {
            pagination = MdtbbsJson.objectOf(MdtbbsJson.dataObject(root), "pagination");
        }
        int responsePage = pagination == null ? safePage
                : (int) MdtbbsJson.longOf(pagination, "page", safePage);
        int totalPages = pagination == null
                ? -1 : (int) MdtbbsJson.longOf(pagination, "total_pages", -1);
        if (pagination != null && totalPages < 0) {
            totalPages = (int) MdtbbsJson.longOf(pagination, "totalPages", -1);
        }
        boolean hasMore = pagination != null && MdtbbsJson.boolOf(pagination, "has_more", false);
        if (pagination != null && !hasMore && totalPages > 0) {
            hasMore = responsePage < totalPages;
        }
        return new JoinRequestPage(requests, responsePage, totalPages, hasMore);
    }

    /// Approves one join request.
    ///
    /// @param requestId join request id
    /// @return the join intent id the requester can consume, or empty
    /// @throws IOException when the request fails
    public String approveJoinRequest(String requestId) throws IOException {
        JsonObject data = MdtbbsJson.dataObject(
                api.post(path("/multiplayer/join-requests/" + requestId + "/approve"), new JsonObject()));
        return MdtbbsJson.stringOf(data, "intent_id");
    }

    /// Rejects one join request.
    ///
    /// @param requestId join request id
    /// @throws IOException when the request fails
    public void rejectJoinRequest(String requestId) throws IOException {
        api.post(path("/multiplayer/join-requests/" + requestId + "/reject"), new JsonObject());
    }

    /// Consumes a Join Intent, becoming a session peer.
    ///
    /// Consuming binds the intent to this OAuth client; the returned peer and
    /// resume token can be used directly by the relay data plane.
    ///
    /// @param intentId intent id carried by the deep link or approval event
    /// @return the session, this client's peer and resume token
    /// @throws IOException when the intent is invalid, expired or already consumed
    public Joined consumeJoinIntent(String intentId) throws IOException {
        JsonObject body = new JsonObject();
        body.add("capabilities", joinCapabilities());
        JsonObject data = MdtbbsJson.dataObject(
                api.post(path("/multiplayer/join-intents/" + intentId + "/consume"), body));
        return parseJoined(data, sessionFrom(data));
    }

    /// Builds the join capabilities body sent to the server.
    private static JsonObject joinCapabilities() {
        JsonObject capabilities = new JsonObject();
        capabilities.addProperty("udp", true);
        capabilities.addProperty("relay", true);
        return capabilities;
    }

    /// Parses a session/peer/resume response.
    private static Joined parseJoined(JsonObject data, @Nullable Session session) {
        JsonObject peerObject = MdtbbsJson.objectOf(data, "peer");
        Peer peer = peerObject == null ? new Peer("", 0, "member", "")
                : parsePeer(peerObject);
        return new Joined(session == null ? new Session("", 0, "", "", "", "", 0, 0, "") : session,
                peer,
                MdtbbsJson.stringOf(data, "resume_token"),
                MdtbbsJson.stringOf(data, "join_code"));
    }

    /// Reads a session object from either the data root or its `session` field.
    ///
    /// The 1.8.0 lobby fields `joinable`, `blocked_reason` and
    /// `can_request_join` are optional; older deployments omit them and the
    /// documented 1.7.0 rules are inferred instead.
    private static @Nullable Session sessionFrom(JsonObject data) {
        JsonObject source = MdtbbsJson.objectOf(data, "session");
        if (source == null) {
            source = data;
        }
        String id = MdtbbsJson.stringOf(source, "id");
        if (id.isBlank()) {
            return null;
        }
        JsonObject game = MdtbbsJson.objectOf(source, "game");
        String status = MdtbbsJson.stringOf(source, "status");
        boolean active = Session.activeStatus(status);
        boolean requireApproval = MdtbbsJson.boolOf(source, "require_approval", false);
        @Nullable String blockedReason = MdtbbsJson.nullableString(source, "blocked_reason");
        if (blockedReason == null) {
            blockedReason = MdtbbsJson.nullableString(source, "blockedReason");
        }
        if (blockedReason != null && blockedReason.isBlank()) {
            blockedReason = null;
        }
        return new Session(
                id,
                MdtbbsJson.longOf(source, "owner_user_id", 0),
                MdtbbsJson.stringOf(source, "visibility"),
                MdtbbsJson.stringOf(source, "join_policy"),
                game == null ? "" : MdtbbsJson.stringOf(game, "version"),
                MdtbbsJson.stringOf(source, "activity_name"),
                (int) MdtbbsJson.longOf(source, "current_players", 0),
                (int) MdtbbsJson.longOf(source, "max_players", 0),
                status,
                requireApproval,
                MdtbbsJson.instantOf(source, "expires_at"),
                MdtbbsJson.boolOf(source, "joinable", active && !requireApproval),
                blockedReason,
                MdtbbsJson.boolOf(source, "can_request_join", active && requireApproval));
    }

    /// Parses one peer object.
    private static Peer parsePeer(JsonObject object) {
        JsonElement userId = object.get("user_id");
        long id = 0;
        if (userId != null && !userId.isJsonNull()) {
            try {
                id = userId.getAsLong();
            } catch (RuntimeException ignored) {
                id = 0;
            }
        }
        return new Peer(
                MdtbbsJson.stringOf(object, "peer_id"),
                id,
                MdtbbsJson.stringOf(object, "role"),
                MdtbbsJson.stringOf(object, "status"));
    }

    /// Validates and returns an opaque id path segment.
    private static String path(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MDTBBS path id is required");
        }
        return value;
    }
}
