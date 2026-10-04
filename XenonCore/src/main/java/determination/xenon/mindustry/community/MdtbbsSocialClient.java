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
import java.util.Map;

/// Reader and writer for the MDTBBS social and multiplayer signalling surface.
///
/// Covers presence connections with heartbeats, Rich Activity publishing,
/// friend presence listings, multiplayer invites and Join Intent consumption.
/// Joining the actual game session remains a future in-game integration; this
/// client only performs the server-side signalling steps.
@NotNullByDefault
public final class MdtbbsSocialClient {
    /// REST transport shared with the account coordinator.
    private final MdtbbsApiClient api;

    /// Creates a social client over the given transport.
    ///
    /// @param api API transport carrying the bearer token
    public MdtbbsSocialClient(MdtbbsApiClient api) {
        this.api = api;
    }

    /// One active presence connection.
    ///
    /// @param connectionId       connection id used by heartbeat and activity calls
    /// @param heartbeatSeconds   server-requested heartbeat interval
    /// @param expiresInSeconds   seconds without heartbeat before the connection expires
    public record PresenceConnection(String connectionId, long heartbeatSeconds, long expiresInSeconds) {
    }

    /// One friend presence entry.
    ///
    /// @param userId       friend user id
    /// @param username     display name
    /// @param status       presence status such as `online` or `idle`
    /// @param activityName current Rich Activity name, or empty
    public record FriendPresence(long userId, String username, String status, String activityName) {
    }

    /// One pending multiplayer invite.
    ///
    /// @param id        invite id
    /// @param sessionId session id
    /// @param inviter   inviter name, or empty
    /// @param expiresAt expiry time
    public record Invite(String id, String sessionId, String inviter, Instant expiresAt) {
    }

    /// Creates a presence connection for this client instance.
    ///
    /// @param platform platform token such as `launcher`
    /// @param status   initial status such as `online`, or `null` for the default
    /// @return the created connection
    /// @throws IOException when the request fails
    public PresenceConnection connect(String platform, @Nullable String status) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("platform", platform);
        if (status != null && !status.isBlank()) body.addProperty("status", status);
        JsonObject data = MdtbbsJson.dataObject(api.post("/presence/connections", body));
        return new PresenceConnection(
                MdtbbsJson.stringOf(data, "connection_id"),
                MdtbbsJson.longOf(data, "heartbeat_interval", 30),
                MdtbbsJson.longOf(data, "expires_in", 90));
    }

    /// Sends one presence heartbeat.
    ///
    /// @param connectionId connection id
    /// @throws IOException when the request fails
    public void heartbeat(String connectionId) throws IOException {
        api.post("/presence/connections/" + connectionId + "/heartbeat", new JsonObject());
    }

    /// Publishes or replaces the Rich Activity of a connection.
    ///
    /// @param connectionId connection id
    /// @param activity     activity body with at least `type` and `name`
    /// @throws IOException when the request fails
    public void updateActivity(String connectionId, JsonObject activity) throws IOException {
        api.put("/presence/connections/" + connectionId + "/activity", activity);
    }

    /// Clears the Rich Activity of a connection.
    ///
    /// @param connectionId connection id
    /// @throws IOException when the request fails
    public void clearActivity(String connectionId) throws IOException {
        api.delete("/presence/connections/" + connectionId + "/activity", null);
    }

    /// Deletes a presence connection.
    ///
    /// @param connectionId connection id
    /// @throws IOException when the request fails
    public void disconnect(String connectionId) throws IOException {
        api.delete("/presence/connections/" + connectionId, null);
    }

    /// Lists friends with their presence and activity.
    ///
    /// @param page  1-based page number
    /// @param limit page size, clamped to 1..50
    /// @return friend presence entries
    /// @throws IOException when the request fails
    public List<FriendPresence> friendsPresence(int page, int limit) throws IOException {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("page", Integer.toString(Math.max(1, page)));
        parameters.put("limit", Integer.toString(Math.max(1, Math.min(50, limit))));
        JsonObject root = api.getAuth("/social/friends/presence", parameters);
        // The aggregate response nests entries under `data.data`; older
        // deployments returned `data` as a plain array.
        JsonObject data = MdtbbsJson.dataObject(root);
        JsonArray entries = MdtbbsJson.arrayOf(data, "data");
        if (entries.isEmpty()) entries = MdtbbsJson.dataArray(root);
        List<FriendPresence> result = new ArrayList<>();
        for (JsonElement element : entries) {
            JsonObject object = MdtbbsJson.asObject(element);
            if (object == null) continue;
            result.add(parsePresence(object));
        }
        return result;
    }

    /// Lists pending multiplayer invites for the current user.
    ///
    /// @return pending invites
    /// @throws IOException when the request fails
    public List<Invite> invites() throws IOException {
        JsonObject root = api.getAuth("/multiplayer/invites", null);
        JsonArray array = MdtbbsJson.dataArray(root);
        if (array.isEmpty()) array = MdtbbsJson.arrayOf(MdtbbsJson.dataObject(root), "invites");
        List<Invite> result = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject object = MdtbbsJson.asObject(element);
            if (object == null) continue;
            result.add(new Invite(
                    firstNonBlank(MdtbbsJson.stringOf(object, "id"),
                            MdtbbsJson.stringOf(object, "invite_id")),
                    firstNonBlank(MdtbbsJson.stringOf(object, "session_id"),
                            MdtbbsJson.stringOf(object, "sessionId")),
                    inviterOf(object),
                    MdtbbsJson.instantOf(object, "expires_at")));
        }
        return result;
    }

    /// Accepts an invite.
    ///
    /// @param inviteId invite id
    /// @return the Join Intent id the accepting client should consume, or an
    ///         empty string when the server omitted it
    /// @throws IOException when the request fails
    public String acceptInvite(String inviteId) throws IOException {
        JsonObject data = MdtbbsJson.dataObject(
                api.post("/multiplayer/invites/" + inviteId + "/accept", new JsonObject()));
        String intent = MdtbbsJson.stringOf(data, "intent_id");
        if (intent.isBlank()) {
            intent = MdtbbsJson.stringOf(data, "join_intent");
        }
        if (intent.isBlank()) {
            JsonObject nested = MdtbbsJson.objectOf(data, "join_intent");
            if (nested != null) {
                intent = MdtbbsJson.stringOf(nested, "intent_id");
            }
        }
        return intent;
    }

    /// Declines an invite.
    ///
    /// @param inviteId invite id
    /// @throws IOException when the request fails
    public void declineInvite(String inviteId) throws IOException {
        api.post("/multiplayer/invites/" + inviteId + "/decline", new JsonObject());
    }

    /// Consumes a Join Intent and becomes a session peer.
    ///
    /// @param intentId intent id carried by the deep link
    /// @return the session data returned by the server
    /// @throws IOException when the intent is invalid, expired or already consumed
    public JsonObject consumeJoinIntent(String intentId) throws IOException {
        return MdtbbsJson.dataObject(
                api.post("/multiplayer/join-intents/" + intentId + "/consume", new JsonObject()));
    }

    /// Parses one friend presence object.
    private static FriendPresence parsePresence(JsonObject object) {
        JsonObject user = MdtbbsJson.objectOf(object, "user");
        if (user == null) user = MdtbbsJson.objectOf(object, "friend");
        long userId = MdtbbsJson.longOf(object, "user_id",
                user == null ? MdtbbsJson.longOf(object, "id", 0) : MdtbbsJson.longOf(user, "id", 0));
        String username = user == null ? MdtbbsJson.stringOf(object, "username")
                : MdtbbsJson.stringOf(user, "username");
        JsonObject activity = MdtbbsJson.objectOf(object, "activity");
        String activityName = activity == null ? "" : MdtbbsJson.stringOf(activity, "name");
        // Presence status moved into the nested `presence` object; keep the
        // flat `status` fallback for older responses.
        JsonObject presence = MdtbbsJson.objectOf(object, "presence");
        String status = presence == null ? MdtbbsJson.stringOf(object, "status")
                : MdtbbsJson.stringOf(presence, "status");
        return new FriendPresence(userId, username, status, activityName);
    }

    /// Reads the inviter name from the various response shapes.
    private static String inviterOf(JsonObject object) {
        JsonObject inviter = MdtbbsJson.objectOf(object, "inviter");
        if (inviter == null) inviter = MdtbbsJson.objectOf(object, "from");
        if (inviter == null) return MdtbbsJson.stringOf(object, "inviter_name");
        return MdtbbsJson.stringOf(inviter, "username");
    }

    /// Returns the first non-blank string.
    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }
}
