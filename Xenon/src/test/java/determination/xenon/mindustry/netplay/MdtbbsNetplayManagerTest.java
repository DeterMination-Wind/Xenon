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
package determination.xenon.mindustry.netplay;

import determination.xenon.mindustry.community.MdtbbsApiException;
import determination.xenon.mindustry.community.MdtbbsMultiplayerApi;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Capabilities;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.JoinRequestEntry;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.JoinRequestPage;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Joined;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Limits;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Peer;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.RelayAllocation;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Session;
import determination.xenon.netplay.mdtbbs.MdtbbsRelayTunnel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the MDTBBS session orchestrator against fake signalling and tunnels.
@NotNullByDefault
public final class MdtbbsNetplayManagerTest {

    /// Creating a room attaches the session and starts the owner tunnel.
    @Test
    public void createRoomStartsOwnerTunnel() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);
        List<String> activity = new CopyOnWriteArrayList<>();
        manager.setActivitySink((sessionId, hosting) -> activity.add(sessionId + ":" + hosting));

        manager.createRoom("friends", "friends", "build 160");

        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));
        assertEquals("ses_1", manager.sessionId());
        assertTrue(manager.hosting());
        assertEquals("friends", manager.visibility());
        assertEquals("friends", manager.joinPolicy());
        assertNotNull(tunnels.lastConfig);
        assertTrue(tunnels.lastConfig.owner());
        assertEquals(MdtbbsNetplayManager.MINDUSTRY_PORT, tunnels.lastConfig.gamePort());
        assertTrue(activity.stream().anyMatch(entry -> entry.equals("ses_1:true")),
                () -> "activity sink calls: " + activity);

        manager.leave();
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.IDLE));
        assertTrue(await(5000, () -> fake.left.contains("ses_1")));
    }

    /// Deprecated enum values are normalised instead of being sent to the server.
    @Test
    public void createRoomNormalisesDeprecatedValues() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.createRoom("unlisted", "open", "");

        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));
        assertEquals("private", fake.createdVisibility);
        assertEquals("public", fake.createdJoinPolicy);
        assertFalse(fake.createdRequireApproval);
        manager.leave();
    }

    /// `invite_only` can never carry the approval flag.
    @Test
    public void inviteOnlyClearsApproval() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.createRoom("friends", "invite_only", true, "");

        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));
        assertEquals("friends", fake.createdVisibility);
        assertEquals("invite_only", fake.createdJoinPolicy);
        assertFalse(fake.createdRequireApproval);
        manager.leave();
    }

    /// The first peer heartbeat is sent immediately after attach.
    @Test
    public void firstHeartbeatIsImmediate() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 60);

        manager.createRoom("friends", "friends", "");

        assertTrue(await(3000, () -> !fake.heartbeatCalls.isEmpty()),
                "the first heartbeat must not wait a whole interval");
        manager.leave();
    }

    /// The capability gate rejects clients without third-party multiplayer.
    @Test
    public void capabilityGateRejectsThirdPartyDisabled() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.capabilities = new Capabilities(true, true, true, false, 120);
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.createRoom("friends", "friends", "");

        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.ERROR));
        assertEquals("CLIENT_CAPABILITY_NOT_APPROVED", manager.errorCode());
        assertNull(tunnels.lastConfig);
    }

    /// Joining finds the owner peer and exposes the guest loopback address.
    @Test
    public void guestJoinExposesLoopbackAddress() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.joined = new Joined(session(), memberPeer(), "rt_member", "");
        fake.peers = List.of(ownerPeer(), memberPeer());
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.joinByCode("ses_1");

        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.GUEST_READY));
        assertEquals("127.0.0.1:6567", manager.localAddress());
        assertNotNull(tunnels.lastConfig);
        assertFalse(tunnels.lastConfig.owner());
        assertEquals("peer_owner", tunnels.lastConfig.ownerPeerId());
        assertEquals(6567, tunnels.lastConfig.preferredLocalPort());
    }

    /// A create result arriving after leave is dropped instead of resurrecting state.
    @Test
    public void staleCreateResultIsDroppedAfterLeave() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        fake.createGate = new CountDownLatch(1);
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.createRoom("friends", "friends", "");
        assertTrue(fake.createStarted.await(5, TimeUnit.SECONDS));
        manager.leave();
        fake.createGate.countDown();

        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.IDLE));
        assertFalse(manager.hasSession());
        assertNull(tunnels.lastConfig);
    }

    /// An expired peer resumes with the stored token instead of failing.
    @Test
    public void peerExpiredTriggersResume() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        fake.heartbeatError = new MdtbbsApiException(200, "PEER_EXPIRED", "expired", false);
        fake.resumed = new Joined(session(),
                new Peer("peer_owner_2", 7, "owner", "active"), "rt_2", "");
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 1);

        manager.createRoom("friends", "friends", "");

        assertTrue(await(8000, () -> fake.resumeCalled.getCount() == 0),
                "peer heartbeat should resume the peer");
        assertTrue(manager.phase() == MdtbbsNetplayManager.Phase.HOSTING);
        manager.leave();
    }

    /// Inviting without a hosted session fails with a stable code.
    @Test
    public void inviteRequiresHostedSession() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.inviteFriend(9);
        assertEquals(MdtbbsNetplayManager.Phase.IDLE, manager.phase());
        assertEquals("SESSION_NOT_FOUND", manager.lastInviteError());
        assertEquals("9", manager.lastInviteTarget());

        fake.created = ownerJoined();
        manager.createRoom("friends", "friends", "");
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));
        assertNull(manager.lastInviteError());
        manager.inviteFriend(9);
        assertTrue(await(5000, () -> fake.invited.contains("ses_1:9")));
        manager.leave();
    }

    /// Starting a new room while hosting leaves the previous session first.
    @Test
    public void createRoomWhileHostingLeavesPreviousSession() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.createRoom("friends", "friends", "");
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));

        fake.created = new Joined(
                new Session("ses_2", 7, "friends", "friends", "build 160", "Mindustry", 1, 8, "active"),
                new Peer("peer_owner_2", 7, "owner", "active"), "rt_2", "");
        manager.createRoom("friends", "friends", "");

        assertTrue(await(5000, () -> "ses_2".equals(manager.sessionId())));
        assertTrue(await(5000, () -> fake.left.contains("ses_1")),
                "the previous session should be left before the new one starts");
        int leaveIndex = fake.calls.indexOf("leave:ses_1");
        int createIndex = fake.calls.lastIndexOf("create");
        assertTrue(leaveIndex >= 0 && createIndex > leaveIndex,
                () -> "leave must precede create on the wire: " + fake.calls);
        manager.leave();
    }

    /// Cancelling while a create is in flight leaves the created session.
    @Test
    public void cancelDuringCreateLeavesCreatedSession() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        fake.createGate = new CountDownLatch(1);
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.createRoom("friends", "friends", "");
        assertTrue(fake.createStarted.await(5, TimeUnit.SECONDS));
        manager.leave();
        fake.createGate.countDown();

        assertTrue(await(5000, () -> fake.left.contains("ses_1")),
                () -> "the created session must be left after a cancel: " + fake.calls);
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.IDLE));
        assertNull(tunnels.lastConfig);
    }

    /// Cancelling while a join is in flight leaves the joined session.
    @Test
    public void cancelDuringJoinLeavesJoinedSession() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.joined = new Joined(session(), memberPeer(), "rt_member", "");
        fake.joinGate = new CountDownLatch(1);
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.joinByCode("ses_1");
        assertTrue(fake.joinStarted.await(5, TimeUnit.SECONDS));
        manager.leave();
        fake.joinGate.countDown();

        assertTrue(await(5000, () -> fake.left.contains("ses_1")),
                () -> "the joined session must be left after a cancel: " + fake.calls);
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.IDLE));
        assertNull(tunnels.lastConfig);
    }

    /// Cancelling while an intent consume is in flight leaves the session.
    @Test
    public void cancelDuringIntentConsumeLeavesSession() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.joined = new Joined(session(), memberPeer(), "rt_member", "");
        fake.consumeGate = new CountDownLatch(1);
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.joinByIntent("intent_1");
        assertTrue(fake.consumeStarted.await(5, TimeUnit.SECONDS));
        manager.leave();
        fake.consumeGate.countDown();

        assertTrue(await(5000, () -> fake.left.contains("ses_1")),
                () -> "the consumed session must be left after a cancel: " + fake.calls);
        assertNull(tunnels.lastConfig);
    }

    /// Cancelling while the owner relay allocation is blocked leaves the session.
    @Test
    public void cancelDuringOwnerRelayAllocationLeavesSession() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        fake.allocateGate = new CountDownLatch(1);
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.createRoom("friends", "friends", "");
        assertTrue(fake.allocateStarted.await(5, TimeUnit.SECONDS));
        manager.leave();
        fake.allocateGate.countDown();

        assertTrue(await(5000, () -> fake.left.contains("ses_1")),
                () -> "the session must be left when the relay allocation is cancelled: " + fake.calls);
        assertNull(tunnels.lastConfig);
    }

    /// Cancelling while the guest relay allocation is blocked leaves the session.
    @Test
    public void cancelDuringGuestRelayAllocationLeavesSession() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.joined = new Joined(session(), memberPeer(), "rt_member", "");
        fake.peers = List.of(ownerPeer(), memberPeer());
        fake.allocateGate = new CountDownLatch(1);
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.joinByCode("ses_1");
        assertTrue(fake.allocateStarted.await(5, TimeUnit.SECONDS));
        manager.leave();
        fake.allocateGate.countDown();

        assertTrue(await(5000, () -> fake.left.contains("ses_1")),
                () -> "the guest session must be left when the relay allocation is cancelled: " + fake.calls);
        assertNull(tunnels.lastConfig);
    }

    /// Requesting an approval room waits instead of joining directly.
    @Test
    public void requestJoinWaitsForApproval() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.requestJoin("ses_1");

        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.GUEST_WAITING
                && manager.awaitingApproval()));
        assertTrue(fake.calls.contains("join-request:ses_1"));
        assertNull(tunnels.lastConfig);
        manager.leave();
    }

    /// A stale failed request cannot clear a newer request's approval state.
    @Test
    public void staleJoinRequestFailureDoesNotClobberNewRequest() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.failFirstJoinRequest = true;
        fake.joinRequestGate = new CountDownLatch(1);
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.requestJoin("ses_1");
        assertTrue(fake.joinRequestStarted.await(5, TimeUnit.SECONDS));
        manager.leave();
        manager.requestJoin("ses_2");
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.GUEST_WAITING
                && manager.awaitingApproval()));
        fake.joinRequestGate.countDown();
        Thread.sleep(300);

        assertTrue(manager.phase() == MdtbbsNetplayManager.Phase.GUEST_WAITING);
        assertTrue(manager.awaitingApproval());
        manager.leave();
    }

    /// A rejection carrying an old request id must not affect the new request.
    @Test
    public void staleJoinRequestRejectionIsIgnored() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.requestJoin("ses_1");
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.GUEST_WAITING));
        manager.handleRealtimeEvent(rejectionEvent("jr_old", "ses_1"));
        assertTrue(manager.phase() == MdtbbsNetplayManager.Phase.GUEST_WAITING);
        manager.handleRealtimeEvent(rejectionEvent("jr_1", "ses_1"));
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.ERROR));
        assertEquals("JOIN_REQUEST_REJECTED", manager.errorCode());
        manager.leave();
    }

    /// Builds a rejection event for the current session.
    private static determination.xenon.mindustry.community.MdtbbsRealtimeEvent rejectionEvent(
            String requestId, String sessionId) {
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("join_request_id", requestId);
        data.addProperty("session_id", sessionId);
        return new determination.xenon.mindustry.community.MdtbbsRealtimeEvent(
                "evt-" + requestId, "multiplayer.join_request.rejected", 1L, data);
    }

    /// An approval that arrives while the join request is in flight is queued.
    @Test
    public void approvalDuringRequestIsNotDropped() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.joined = new Joined(session(), memberPeer(), "rt_member", "");
        fake.peers = List.of(ownerPeer(), memberPeer());
        fake.joinRequestGate = new CountDownLatch(1);
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.requestJoin("ses_1");
        assertTrue(fake.joinRequestStarted.await(5, TimeUnit.SECONDS));
        manager.onJoinApproved("intent_1");
        fake.joinRequestGate.countDown();

        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.GUEST_READY));
        assertTrue(fake.calls.contains("consume:intent_1"));
        manager.leave();
    }

    /// An approval intent consumes the join intent and starts the guest tunnel.
    @Test
    public void approvalConsumesIntent() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.joined = new Joined(session(), memberPeer(), "rt_member", "");
        fake.peers = List.of(ownerPeer(), memberPeer());
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.requestJoin("ses_1");
        assertTrue(await(5000, manager::awaitingApproval));
        manager.onJoinApproved("intent_1");

        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.GUEST_READY));
        assertTrue(fake.calls.contains("consume:intent_1"));
        assertFalse(manager.awaitingApproval());
        manager.leave();
    }

    /// A zombie session is closed and the create is retried exactly once.
    @Test
    public void sessionAlreadyActiveClosesZombieAndRetries() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.createConflict = sessionActiveException("ses_zombie");
        fake.created = ownerJoined();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.createRoom("friends", "friends", "");

        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));
        assertEquals(2, fake.createAttempts.get());
        assertTrue(fake.left.contains("ses_zombie"),
                () -> "the zombie session must be left: " + fake.calls);
        assertEquals("ses_zombie", manager.conflictSessionId());
        assertNotNull(tunnels.lastConfig);
        manager.leave();
    }

    /// A refused zombie cleanup is visible and never retried.
    @Test
    public void sessionAlreadyActiveLeaveFailureIsVisible() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.createConflict = sessionActiveException("ses_zombie");
        fake.created = ownerJoined();
        fake.leaveError = new MdtbbsApiException(403, "FORBIDDEN", "not the session owner", false);
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.createRoom("friends", "friends", "");

        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.ERROR
                && "SESSION_ALREADY_ACTIVE".equals(manager.errorCode())
                && "ses_zombie".equals(manager.statusDetail())));
        assertEquals(1, fake.createAttempts.get());
        assertFalse(fake.left.contains("ses_zombie"));
        assertEquals("SESSION_ALREADY_ACTIVE", manager.errorCode());
        assertEquals("ses_zombie", manager.statusDetail());
        assertEquals("ses_zombie", manager.conflictSessionId());
        assertNull(tunnels.lastConfig);
    }

    /// A 404 on the zombie cleanup counts as success and the create retries.
    @Test
    public void missingZombieSessionStillRetriesCreate() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.createConflict = sessionActiveException("ses_zombie");
        fake.created = ownerJoined();
        fake.leaveError = new MdtbbsApiException(404, "SESSION_NOT_FOUND",
                "the session is already gone", false);
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.createRoom("friends", "friends", "");

        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));
        assertEquals(2, fake.createAttempts.get());
        assertEquals("ses_zombie", manager.conflictSessionId());
        assertFalse(fake.left.contains("ses_zombie"));
        assertNotNull(tunnels.lastConfig);
        manager.leave();
    }

    /// The host list is rebuilt from the server join-request snapshot.
    @Test
    public void refreshJoinRequestsUsesServerSnapshot() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.createRoom("friends", "friends", "");
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));
        fake.joinRequestEntries = List.of(new JoinRequestEntry("jr_1",
                Instant.parse("2026-11-08T10:00:00Z"), Instant.parse("2026-11-08T10:05:00Z"),
                9L, "alice"));

        manager.refreshJoinRequests();

        assertTrue(await(5000, () -> manager.joinRequests().size() == 1),
                () -> "join requests: " + manager.joinRequests());
        MdtbbsNetplayManager.JoinRequest request = manager.joinRequests().get(0);
        assertEquals("jr_1", request.id());
        assertEquals("ses_1", request.sessionId());
        assertEquals(9L, request.userId());
        assertEquals("alice", request.username());
        manager.leave();
    }

    /// An approved request disappears from the host list on the next refresh.
    @Test
    public void approvedJoinRequestDisappearsAfterRefresh() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.createRoom("friends", "friends", "");
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));
        fake.joinRequestEntries = List.of(joinRequestEntry("jr_1", 9L, "alice"));
        manager.refreshJoinRequests();
        assertTrue(await(5000, () -> manager.joinRequests().size() == 1));

        // The host approved the request, so the server no longer lists it.
        fake.joinRequestEntries = List.of();
        manager.refreshJoinRequests();

        assertTrue(await(5000, () -> manager.joinRequests().isEmpty()),
                () -> "join requests: " + manager.joinRequests());
        manager.leave();
    }

    /// A realtime insert survives a server snapshot that has not caught up yet.
    @Test
    public void joinRequestCreatedEventSurvivesServerLag() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);
        AtomicLong clock = new AtomicLong();
        manager.setNanoTimeSource(clock::get);

        manager.createRoom("friends", "friends", "");
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));

        manager.handleRealtimeEvent(createdEvent("jr_local", "ses_1", 9L, "alice"));

        assertTrue(await(5000, () -> manager.joinRequests().size() == 1),
                () -> "join requests: " + manager.joinRequests());
        MdtbbsNetplayManager.JoinRequest request = manager.joinRequests().get(0);
        assertEquals("jr_local", request.id());
        assertEquals("ses_1", request.sessionId());
        assertEquals(9L, request.userId());
        assertEquals("alice", request.username());
        manager.leave();
    }

    /// A local insert is dropped once the grace window elapses.
    @Test
    public void localJoinRequestExpiresAfterGraceWindow() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);
        AtomicLong clock = new AtomicLong();
        manager.setNanoTimeSource(clock::get);

        manager.createRoom("friends", "friends", "");
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));
        manager.handleRealtimeEvent(createdEvent("jr_local", "ses_1", 9L, "alice"));
        assertTrue(await(5000, () -> manager.joinRequests().size() == 1));

        clock.set(TimeUnit.SECONDS.toNanos(5L));
        int before = fake.joinRequestsCalls.get();
        manager.refreshJoinRequests();
        assertTrue(await(2000, () -> fake.joinRequestsCalls.get() > before));
        Thread.sleep(100);
        assertEquals(1, manager.joinRequests().size(),
                "a request younger than the grace window must survive");

        clock.set(TimeUnit.SECONDS.toNanos(11L));
        manager.refreshJoinRequests();
        assertTrue(await(5000, () -> manager.joinRequests().isEmpty()),
                () -> "join requests: " + manager.joinRequests());
        manager.leave();
    }

    /// A failed refresh keeps the previous authoritative list.
    @Test
    public void failedJoinRequestRefreshKeepsPreviousList() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.createRoom("friends", "friends", "");
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));
        fake.joinRequestEntries = List.of(joinRequestEntry("jr_1", 9L, "alice"));
        manager.refreshJoinRequests();
        assertTrue(await(5000, () -> manager.joinRequests().size() == 1));

        fake.failJoinRequests = true;
        int before = fake.joinRequestsCalls.get();
        manager.refreshJoinRequests();
        assertTrue(await(2000, () -> fake.joinRequestsCalls.get() > before));
        Thread.sleep(100);

        assertEquals(1, manager.joinRequests().size(),
                "a failed refresh must not clear the previous list");
        manager.leave();
    }

    /// The refresh merges server pages and requests the capped page size.
    @Test
    public void refreshJoinRequestsMergesServerPages() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.capabilities = new Capabilities(true, true, true, true, 120,
                List.of(), List.of(), true, List.of(), List.of(),
                new Limits(0, 0, 0, 0, 0, 10, 0));
        fake.created = ownerJoined();
        fake.joinRequestEntries = List.of(joinRequestEntry("jr_1", 9L, "alice"));
        fake.joinRequestPage2 = List.of(joinRequestEntry("jr_2", 10L, "bob"));
        fake.joinRequestHasMore = true;
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.createRoom("friends", "friends", "");
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));
        manager.refreshJoinRequests();

        assertTrue(await(5000, () -> manager.joinRequests().size() == 2),
                () -> "join requests: " + manager.joinRequests());
        assertTrue(manager.joinRequests().stream()
                .anyMatch(request -> request.id().equals("jr_1")));
        assertTrue(manager.joinRequests().stream()
                .anyMatch(request -> request.id().equals("jr_2")));
        assertEquals(2, fake.lastJoinRequestPage);
        assertEquals(10, manager.lobbyPageSize());
        assertEquals(Math.min(50, manager.lobbyPageSize()), fake.lastJoinRequestLimit);
        manager.leave();
    }

    /// Approving drops the row and a pre-approval snapshot cannot land it back.
    @Test
    public void approveDropsRowAndOldSnapshotCannotResurrect() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 30);

        manager.createRoom("friends", "friends", "");
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));
        fake.joinRequestEntries = List.of(joinRequestEntry("jr_1", 9L, "alice"));
        manager.refreshJoinRequests();
        assertTrue(await(5000, () -> manager.joinRequests().size() == 1));

        // Freeze a snapshot that still contains the request.
        fake.joinRequestsGate = new CountDownLatch(1);
        int calls = fake.joinRequestsCalls.get();
        manager.refreshJoinRequests();
        assertTrue(await(2000, () -> fake.joinRequestsCalls.get() > calls),
                "the pre-approval snapshot must be in flight");

        // The server approved the request while the old snapshot is frozen.
        fake.joinRequestEntries = List.of();
        manager.approveJoinRequest("jr_1");
        assertTrue(await(5000, () -> manager.joinRequests().isEmpty()),
                () -> "join requests: " + manager.joinRequests());

        fake.joinRequestsGate.countDown();
        Thread.sleep(300);
        assertTrue(manager.joinRequests().isEmpty(),
                "a pre-approval snapshot must not resurrect the row");
        manager.leave();
    }

    /// Two heartbeat ticks 30 s apart trigger exactly one join-request refresh.
    @Test
    public void heartbeatRefreshesJoinRequestsAtMostOncePerMinute() throws Exception {
        FakeMultiplayer fake = new FakeMultiplayer();
        fake.created = ownerJoined();
        FakeTunnels tunnels = new FakeTunnels();
        MdtbbsNetplayManager manager = new MdtbbsNetplayManager(() -> true, fake, tunnels, 1);
        AtomicLong clock = new AtomicLong();
        manager.setNanoTimeSource(clock::get);

        manager.createRoom("friends", "friends", "");
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));
        assertTrue(await(3000, () -> fake.joinRequestsCalls.get() >= 1));
        int before = fake.joinRequestsCalls.get();

        clock.set(TimeUnit.SECONDS.toNanos(30L));
        awaitHeartbeatTick(fake);
        assertEquals(before, fake.joinRequestsCalls.get(),
                "the 60 s heartbeat throttle must still hold at 30 s");

        clock.set(TimeUnit.SECONDS.toNanos(60L));
        awaitHeartbeatTick(fake);
        assertTrue(await(3000, () -> fake.joinRequestsCalls.get() == before + 1),
                () -> "join request calls: " + fake.joinRequestsCalls.get());

        clock.set(TimeUnit.SECONDS.toNanos(90L));
        awaitHeartbeatTick(fake);
        Thread.sleep(100);
        assertEquals(before + 1, fake.joinRequestsCalls.get(),
                "a second tick inside the window must not refresh again");
        manager.leave();
    }

    /// Waits for the next heartbeat tick recorded by the fake.
    private static void awaitHeartbeatTick(FakeMultiplayer fake) throws InterruptedException {
        int beats = fake.heartbeatCalls.size();
        assertTrue(await(3000, () -> fake.heartbeatCalls.size() > beats),
                "the heartbeat tick must run");
    }

    /// One pending join request fixture.
    private static JoinRequestEntry joinRequestEntry(String id, long userId, String username) {
        return new JoinRequestEntry(id, Instant.EPOCH, Instant.EPOCH, userId, username);
    }

    /// Builds a `multiplayer.join_request.created` event.
    private static determination.xenon.mindustry.community.MdtbbsRealtimeEvent createdEvent(
            String requestId, String sessionId, long userId, String username) {
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("join_request_id", requestId);
        data.addProperty("session_id", sessionId);
        com.google.gson.JsonObject requester = new com.google.gson.JsonObject();
        requester.addProperty("id", userId);
        requester.addProperty("username", username);
        data.add("requester", requester);
        return new determination.xenon.mindustry.community.MdtbbsRealtimeEvent(
                "evt-" + requestId, "multiplayer.join_request.created", 1L, data);
    }

    /// Builds the `SESSION_ALREADY_ACTIVE` failure naming a zombie session.
    private static MdtbbsApiException sessionActiveException(String zombieId) {
        com.google.gson.JsonObject detail = new com.google.gson.JsonObject();
        detail.addProperty("session_id", zombieId);
        com.google.gson.JsonArray details = new com.google.gson.JsonArray();
        details.add(detail);
        return new MdtbbsApiException(409, "SESSION_ALREADY_ACTIVE",
                "the account already has an active session", false, details);
    }

    /// Polls a condition until it holds or the timeout expires.
    private static boolean await(long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    /// One owner session fixture.
    private static Session session() {
        return new Session("ses_1", 7, "friends", "friends", "build 160", "Mindustry", 1, 8, "active");
    }

    /// The owner peer fixture.
    private static Peer ownerPeer() {
        return new Peer("peer_owner", 7, "owner", "active");
    }

    /// The member peer fixture.
    private static Peer memberPeer() {
        return new Peer("peer_member", 9, "member", "active");
    }

    /// Owner join result fixture.
    private static Joined ownerJoined() {
        return new Joined(session(), ownerPeer(), "rt_owner", "A1B2C3D4E5");
    }

    /// Fake signalling client with canned results.
    private static final class FakeMultiplayer implements MdtbbsMultiplayerApi {
        Capabilities capabilities = new Capabilities(true, true, true, true, 120);
        @Nullable Joined created;
        @Nullable Joined joined;
        @Nullable Joined resumed;
        List<Peer> peers = List.of();
        RelayAllocation relay = new RelayAllocation("rly_1", "official-eu-1",
                "wss://relay.example/relay/v1", "cred_1", 120);
        @Nullable IOException heartbeatError;
        @Nullable MdtbbsApiException createConflict;
        @Nullable MdtbbsApiException leaveError;
        final java.util.concurrent.atomic.AtomicInteger createAttempts =
                new java.util.concurrent.atomic.AtomicInteger();
        @Nullable CountDownLatch createGate;
        final CountDownLatch createStarted = new CountDownLatch(1);
        @Nullable CountDownLatch joinGate;
        final CountDownLatch joinStarted = new CountDownLatch(1);
        @Nullable CountDownLatch consumeGate;
        final CountDownLatch consumeStarted = new CountDownLatch(1);
        @Nullable CountDownLatch joinRequestGate;
        final CountDownLatch joinRequestStarted = new CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger joinRequestCount =
                new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean failFirstJoinRequest;
        @Nullable CountDownLatch allocateGate;
        final CountDownLatch allocateStarted = new CountDownLatch(1);
        final CountDownLatch resumeCalled = new CountDownLatch(1);
        final CopyOnWriteArrayList<String> heartbeatCalls = new CopyOnWriteArrayList<>();
        volatile String createdVisibility = "";
        volatile String createdJoinPolicy = "";
        volatile boolean createdRequireApproval;
        volatile List<JoinRequestEntry> joinRequestEntries = List.of();
        volatile List<JoinRequestEntry> joinRequestPage2 = List.of();
        volatile boolean joinRequestHasMore;
        volatile boolean failJoinRequests;
        @Nullable CountDownLatch joinRequestsGate;
        volatile int lastJoinRequestPage;
        volatile int lastJoinRequestLimit = -1;
        final java.util.concurrent.atomic.AtomicInteger joinRequestsCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        final List<String> left = new CopyOnWriteArrayList<>();
        final List<String> invited = new CopyOnWriteArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public Capabilities capabilities() {
            return capabilities;
        }

        @Override
        public Joined createSession(String visibility, String joinPolicy, int maxPlayers,
                                    String activityName, String gameVersion,
                                    boolean requireApproval) throws IOException {
            calls.add("create");
            int attempt = createAttempts.incrementAndGet();
            createdVisibility = visibility;
            createdJoinPolicy = joinPolicy;
            createdRequireApproval = requireApproval;
            createStarted.countDown();
            CountDownLatch gate = createGate;
            if (gate != null) {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
            MdtbbsApiException conflict = createConflict;
            if (conflict != null && attempt == 1) {
                createConflict = null;
                throw conflict;
            }
            if (created == null) {
                throw new IOException("no create result");
            }
            return created;
        }

        @Override
        public Session resolveCode(String code) {
            return MdtbbsNetplayManagerTest.session();
        }

        @Override
        public Session session(String sessionId) {
            return MdtbbsNetplayManagerTest.session();
        }

        @Override
        public Joined join(String sessionId, @Nullable String joinCode) throws IOException {
            calls.add("join:" + sessionId);
            joinStarted.countDown();
            waitGate(joinGate);
            if (joined == null) {
                throw new IOException("no join result");
            }
            return joined;
        }

        @Override
        public Joined resume(String sessionId, String resumeToken) throws IOException {
            resumeCalled.countDown();
            if (resumed == null) {
                throw new IOException("no resume result");
            }
            return resumed;
        }

        @Override
        public void leave(String sessionId) throws IOException {
            calls.add("leave:" + sessionId);
            MdtbbsApiException error = leaveError;
            if (error != null) {
                leaveError = null;
                throw error;
            }
            left.add(sessionId);
        }

        @Override
        public List<Peer> peers(String sessionId) {
            return peers;
        }

        @Override
        public void peerHeartbeat(String sessionId, String peerId) throws IOException {
            heartbeatCalls.add(sessionId + ":" + peerId);
            IOException error = heartbeatError;
            if (error != null) {
                heartbeatError = null;
                throw error;
            }
        }

        @Override
        public RelayAllocation allocateRelay(String sessionId) throws IOException {
            calls.add("relay:" + sessionId);
            allocateStarted.countDown();
            waitGate(allocateGate);
            return relay;
        }

        @Override
        public Joined consumeJoinIntent(String intentId) throws IOException {
            calls.add("consume:" + intentId);
            consumeStarted.countDown();
            waitGate(consumeGate);
            if (joined == null) {
                throw new IOException("no intent result");
            }
            return joined;
        }

        @Override
        public String createInvite(String sessionId, long targetUserId) {
            invited.add(sessionId + ":" + targetUserId);
            return "inv_1";
        }

        @Override
        public String createJoinRequest(String sessionId) throws IOException {
            calls.add("join-request:" + sessionId);
            int call = joinRequestCount.incrementAndGet();
            joinRequestStarted.countDown();
            if (failFirstJoinRequest && call == 1) {
                waitGate(joinRequestGate);
                throw new IOException("first request failed");
            }
            return "jr_" + call;
        }

        @Override
        public JoinRequestPage joinRequests(String sessionId, int page, int limit) throws IOException {
            calls.add("join-requests:" + sessionId + ":" + page);
            joinRequestsCalls.incrementAndGet();
            lastJoinRequestPage = page;
            lastJoinRequestLimit = limit;
            if (failJoinRequests) {
                throw new IOException("join requests unavailable");
            }
            boolean firstPage = page <= 1;
            List<JoinRequestEntry> entries = firstPage ? joinRequestEntries : joinRequestPage2;
            // Capture the page before an optional test gate so an in-flight
            // snapshot really represents the pre-approval server state.
            waitGate(joinRequestsGate);
            boolean hasMore = firstPage && joinRequestHasMore;
            return new JoinRequestPage(entries, page, joinRequestHasMore ? 2 : 1, hasMore);
        }

        @Override
        public String approveJoinRequest(String requestId) {
            calls.add("approve:" + requestId);
            return "intent_1";
        }

        @Override
        public void rejectJoinRequest(String requestId) {
            calls.add("reject:" + requestId);
        }

        /// Waits for the optional gate of the current request.
        private void waitGate(@Nullable CountDownLatch gate) throws IOException {
            if (gate == null) {
                return;
            }
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
    }

    /// Fake tunnel factory that records configurations and exposes callbacks.
    private static final class FakeTunnels implements MdtbbsNetplayManager.TunnelFactory {
        @Nullable MdtbbsRelayTunnel.Config lastConfig;
        @Nullable IntConsumer guestReady;

        @Override
        public MdtbbsRelayTunnel create(MdtbbsRelayTunnel.Config config,
                                        MdtbbsRelayTunnel.RelayRenewer renewer,
                                        Consumer<String> onStatus,
                                        Consumer<String> onFailure,
                                        IntConsumer onGuestReady) {
            lastConfig = config;
            guestReady = onGuestReady;
            if (!config.owner() && onGuestReady != null) {
                onGuestReady.accept(MdtbbsNetplayManager.MINDUSTRY_PORT);
            }
            return null;
        }
    }
}
