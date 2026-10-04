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
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Joined;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Peer;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.RelayAllocation;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Session;
import determination.xenon.netplay.mdtbbs.MdtbbsRelayTunnel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        assertNotNull(tunnels.lastConfig);
        assertTrue(tunnels.lastConfig.owner());
        assertEquals(MdtbbsNetplayManager.MINDUSTRY_PORT, tunnels.lastConfig.gamePort());
        assertTrue(activity.stream().anyMatch(entry -> entry.equals("ses_1:true")),
                () -> "activity sink calls: " + activity);

        manager.leave();
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.IDLE));
        assertTrue(await(5000, () -> fake.left.contains("ses_1")));
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
        assertEquals(MdtbbsNetplayManager.Phase.ERROR, manager.phase());
        assertEquals("SESSION_NOT_FOUND", manager.errorCode());

        fake.created = ownerJoined();
        manager.createRoom("friends", "friends", "");
        assertTrue(await(5000, () -> manager.phase() == MdtbbsNetplayManager.Phase.HOSTING));
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
        @Nullable CountDownLatch createGate;
        final CountDownLatch createStarted = new CountDownLatch(1);
        final CountDownLatch resumeCalled = new CountDownLatch(1);
        final List<String> left = new CopyOnWriteArrayList<>();
        final List<String> invited = new CopyOnWriteArrayList<>();
        final List<String> calls = new CopyOnWriteArrayList<>();

        @Override
        public Capabilities capabilities() {
            return capabilities;
        }

        @Override
        public Joined createSession(String visibility, String joinPolicy, int maxPlayers,
                                    String activityName, String gameVersion) throws IOException {
            calls.add("create");
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
            if (created == null) {
                throw new IOException("no create result");
            }
            return created;
        }

        @Override
        public Session resolveCode(String code) {
            return session();
        }

        @Override
        public Joined join(String sessionId, @Nullable String joinCode) throws IOException {
            calls.add("join:" + sessionId);
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
        public void leave(String sessionId) {
            calls.add("leave:" + sessionId);
            left.add(sessionId);
        }

        @Override
        public List<Peer> peers(String sessionId) {
            return peers;
        }

        @Override
        public void peerHeartbeat(String sessionId, String peerId) throws IOException {
            IOException error = heartbeatError;
            if (error != null) {
                heartbeatError = null;
                throw error;
            }
        }

        @Override
        public RelayAllocation allocateRelay(String sessionId) {
            calls.add("relay:" + sessionId);
            return relay;
        }

        @Override
        public Joined consumeJoinIntent(String intentId) throws IOException {
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
