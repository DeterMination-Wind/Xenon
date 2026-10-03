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

import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Capabilities;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Joined;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Peer;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.RelayAllocation;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Session;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/// Session-signalling operations required by the multiplayer orchestrator.
///
/// Extracted so the orchestrator can be tested against a fake without a live
/// MDTBBS session; [MdtbbsMultiplayerClient] is the production implementation.
/// The record types stay nested in the concrete client so the UI keeps its
/// single import surface.
@NotNullByDefault
public interface MdtbbsMultiplayerApi {

    /// Reads the multiplayer capability switches.
    ///
    /// @return parsed capabilities
    /// @throws IOException when the request fails
    Capabilities capabilities() throws IOException;

    /// Creates a session owned by this client.
    ///
    /// @param visibility   `private`, `friends` or `unlisted`
    /// @param joinPolicy   `open`, `friends`, `request` or `invite_only`
    /// @param maxPlayers   player limit
    /// @param activityName activity text shown to friends
    /// @param gameVersion  game version shown to friends, or empty
    /// @return the created session, owner peer and optional join code
    /// @throws IOException when the request fails
    Joined createSession(String visibility, String joinPolicy, int maxPlayers,
                         String activityName, String gameVersion) throws IOException;

    /// Resolves an unlisted join code to its session.
    ///
    /// @param code join code
    /// @return the resolved session
    /// @throws IOException when the code is invalid or expired
    Session resolveCode(String code) throws IOException;

    /// Joins a session as a new peer.
    ///
    /// @param sessionId session id
    /// @param joinCode  unlisted join code, or `null`
    /// @return this client's peer and resume token
    /// @throws IOException when the join is refused
    Joined join(String sessionId, @Nullable String joinCode) throws IOException;

    /// Restores a previous peer using its resume token.
    ///
    /// @param sessionId   session id
    /// @param resumeToken resume token from the previous join
    /// @return the restored peer and a rotated resume token
    /// @throws IOException when the resume window expired
    Joined resume(String sessionId, String resumeToken) throws IOException;

    /// Leaves a session.
    ///
    /// @param sessionId session id
    /// @throws IOException when the request fails
    void leave(String sessionId) throws IOException;

    /// Lists the peers that currently occupy session slots.
    ///
    /// @param sessionId session id
    /// @return peer entries
    /// @throws IOException when the request fails
    List<Peer> peers(String sessionId) throws IOException;

    /// Sends one peer heartbeat.
    ///
    /// @param sessionId session id
    /// @param peerId    peer id
    /// @throws IOException when the request fails
    void peerHeartbeat(String sessionId, String peerId) throws IOException;

    /// Allocates an official relay credential for this peer.
    ///
    /// @param sessionId session id
    /// @return the allocation
    /// @throws IOException when the relay is unavailable
    RelayAllocation allocateRelay(String sessionId) throws IOException;

    /// Consumes a Join Intent, becoming a session peer.
    ///
    /// @param intentId intent id
    /// @return the session, this client's peer and resume token
    /// @throws IOException when the intent is invalid or expired
    Joined consumeJoinIntent(String intentId) throws IOException;

    /// Creates an invite for one friend.
    ///
    /// @param sessionId    session owned by this peer
    /// @param targetUserId forum user id of the friend
    /// @return the invite id
    /// @throws IOException when the request fails
    String createInvite(String sessionId, long targetUserId) throws IOException;
}
