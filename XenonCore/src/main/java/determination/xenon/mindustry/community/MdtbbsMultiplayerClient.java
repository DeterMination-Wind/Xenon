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
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

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

    /// Server multiplayer capabilities for the authenticated client.
    ///
    /// @param sessionsV1        whether session APIs are enabled
    /// @param invitesV1         whether invites are enabled
    /// @param relayV1           whether the official relay is enabled
    /// @param thirdParty        whether this OAuth client is approved for third-party multiplayer
    /// @param relayCredentialTtlSeconds advertised relay credential lifetime
    public record Capabilities(boolean sessionsV1, boolean invitesV1, boolean relayV1,
                               boolean thirdParty, long relayCredentialTtlSeconds) {
    }

    /// One multiplayer session.
    ///
    /// @param id             opaque session id
    /// @param ownerUserId    id of the owning user
    /// @param visibility     `private`, `friends` or `unlisted`
    /// @param joinPolicy     `open`, `friends`, `request` or `invite_only`
    /// @param gameVersion    game version recorded by the host, or empty
    /// @param activityName   activity text shown to friends
    /// @param currentPlayers occupied player count
    /// @param maxPlayers     player limit
    /// @param status         server status such as `active`
    public record Session(String id, long ownerUserId, String visibility, String joinPolicy,
                          String gameVersion, String activityName,
                          int currentPlayers, int maxPlayers, String status) {
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

    /// Reads the multiplayer capability switches.
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
                MdtbbsJson.longOf(source, "relay_credential_ttl_seconds", 120));
    }

    /// Creates a session owned by this client.
    ///
    /// @param visibility   `private`, `friends` or `unlisted`
    /// @param joinPolicy   `open`, `friends`, `request` or `invite_only`
    /// @param maxPlayers   player limit, clamped to 2..64
    /// @param activityName activity text shown to friends
    /// @param gameVersion  game version shown to friends, or empty
    /// @return the created session, owner peer and optional join code
    /// @throws IOException when the request fails
    public Joined createSession(String visibility, String joinPolicy, int maxPlayers,
                                String activityName, String gameVersion) throws IOException {
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
        body.addProperty("max_players", Math.max(2, Math.min(64, maxPlayers)));
        JsonObject data = MdtbbsJson.dataObject(api.post("/multiplayer/sessions", body));
        return parseJoined(data, sessionFrom(data));
    }

    /// Resolves an unlisted join code to its session.
    ///
    /// @param code ten-character join code, case-insensitive
    /// @return the resolved session
    /// @throws IOException when the code is invalid or expired
    public Session resolveCode(String code) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("code", code == null ? "" : code.trim().toUpperCase(Locale.ROOT));
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
    public void createJoinRequest(String sessionId) throws IOException {
        api.post(path("/multiplayer/sessions/" + sessionId + "/join-requests"), new JsonObject());
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
        return new Session(
                id,
                MdtbbsJson.longOf(source, "owner_user_id", 0),
                MdtbbsJson.stringOf(source, "visibility"),
                MdtbbsJson.stringOf(source, "join_policy"),
                game == null ? "" : MdtbbsJson.stringOf(game, "version"),
                MdtbbsJson.stringOf(source, "activity_name"),
                (int) MdtbbsJson.longOf(source, "current_players", 0),
                (int) MdtbbsJson.longOf(source, "max_players", 0),
                MdtbbsJson.stringOf(source, "status"));
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
