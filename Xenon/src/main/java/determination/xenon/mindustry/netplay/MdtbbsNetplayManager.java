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
import determination.xenon.mindustry.community.MdtbbsCommunity;
import determination.xenon.mindustry.community.MdtbbsMultiplayerApi;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Capabilities;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Joined;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Peer;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.RelayAllocation;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Session;
import determination.xenon.netplay.mdtbbs.MdtbbsRelayTunnel;
import determination.xenon.task.Schedulers;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/// Orchestrates one MDTBBS multiplayer session and its relay tunnel.
///
/// Owns the session lifecycle (create / join / resume / leave), peer
/// heartbeats, presence activity hints and the [MdtbbsRelayTunnel] that carries
/// the game data. The UI observes [#phase()] and the session accessors and
/// calls the `create…`, `join…` and [#leave()] methods; all blocking work runs
/// on the IO scheduler and state callbacks fire on background threads, so the
/// listener must hop to the FX thread itself.
///
/// The launcher-side UX is the lightweight one: guests receive a loopback
/// address (`127.0.0.1:<port>`, preferably Mindustry's own port) that they
/// paste into the in-game join dialog once; hosts start their game normally
/// and the tunnel bridges relay streams into `127.0.0.1:6567`.
@NotNullByDefault
public final class MdtbbsNetplayManager {

    /// Mindustry's default game port used by the owner bridge and preferred
    /// for the guest loopback proxy.
    public static final int MINDUSTRY_PORT = 6567;

    /// Default peer heartbeat interval required by the platform.
    private static final long DEFAULT_HEARTBEAT_SECONDS = 30L;

    /// Creates a relay tunnel; injectable so the manager can be tested.
    @FunctionalInterface
    public interface TunnelFactory {
        /// Builds and starts one tunnel.
        ///
        /// @param config       connection settings
        /// @param renewer      credential renewer
        /// @param onStatus     status callback
        /// @param onFailure    terminal failure callback
        /// @param onGuestReady guest proxy port callback
        /// @return the tunnel
        MdtbbsRelayTunnel create(MdtbbsRelayTunnel.Config config,
                                 MdtbbsRelayTunnel.RelayRenewer renewer,
                                 Consumer<String> onStatus,
                                 Consumer<String> onFailure,
                                 IntConsumer onGuestReady);
    }

    /// High-level state shown by the UI.
    public enum Phase {
        /// No session and no pending operation.
        IDLE,
        /// A network operation is in flight.
        WORKING,
        /// This client owns a session and waits for guests.
        HOSTING,
        /// A join is in progress and the relay is not ready yet.
        GUEST_WAITING,
        /// The guest relay is up and the loopback address is available.
        GUEST_READY,
        /// The last operation failed; [`#errorCode()`] carries the reason.
        ERROR
    }

    /// Sink used to publish Rich Activity for the friends list.
    public interface ActivitySink {
        /// Publishes or clears the multiplayer activity.
        ///
        /// @param sessionId current session id, or `null` to clear
        /// @param hosting   whether this client is the host
        void update(@Nullable String sessionId, boolean hosting);
    }

    private final BooleanSupplier loggedIn;
    private final MdtbbsMultiplayerApi multiplayer;
    private final TunnelFactory tunnelFactory;
    private final long heartbeatSeconds;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xenon-mdtbbs-netplay");
        thread.setDaemon(true);
        return thread;
    });

    /// Monotonic operation counter; stale async results are discarded.
    private final AtomicLong generation = new AtomicLong();

    /// Serialises server-side leaves so a new session can never be created
    /// before an older one finished leaving.
    private final Object cleanupLock = new Object();
    private CompletableFuture<Void> pendingCleanup = CompletableFuture.completedFuture(null);

    private volatile @Nullable ActivitySink activitySink;
    private volatile @Nullable Runnable stateListener;
    private volatile Phase phase = Phase.IDLE;
    private volatile @Nullable String errorCode;
    private volatile @Nullable String statusDetail;
    private volatile String sessionId = "";
    private volatile String joinCode = "";
    private volatile String localAddress = "";
    private volatile String ownerPeerId = "";
    private volatile String peerId = "";
    private volatile String resumeToken = "";
    private volatile boolean hosting;
    private volatile int playerCount;
    private volatile int maxPlayers;
    private volatile @Nullable Capabilities capabilities;
    private volatile @Nullable MdtbbsRelayTunnel tunnel;
    private volatile @Nullable ScheduledFuture<?> heartbeat;

    /// Creates the manager over the shared account coordinator.
    ///
    /// @param community account coordinator carrying the MindAuth token
    public MdtbbsNetplayManager(MdtbbsCommunity community) {
        this(community::isLoggedIn,
                new MdtbbsMultiplayerClient(community.api()),
                MdtbbsRelayTunnel::new,
                DEFAULT_HEARTBEAT_SECONDS);
    }

    /// Creates a manager with injected dependencies (tests).
    ///
    /// @param loggedIn       whether the account is logged in
    /// @param multiplayer    signalling client
    /// @param tunnelFactory  relay tunnel factory
    /// @param heartbeatSeconds peer heartbeat interval
    MdtbbsNetplayManager(BooleanSupplier loggedIn, MdtbbsMultiplayerApi multiplayer,
                         TunnelFactory tunnelFactory, long heartbeatSeconds) {
        this.loggedIn = loggedIn;
        this.multiplayer = multiplayer;
        this.tunnelFactory = tunnelFactory;
        this.heartbeatSeconds = Math.max(1L, heartbeatSeconds);
    }

    /// Replaces the activity sink used for the friends list.
    ///
    /// @param sink activity sink, or `null` to disable
    public void setActivitySink(@Nullable ActivitySink sink) {
        this.activitySink = sink;
    }

    /// Replaces the state-change listener; only the newest listener is kept.
    ///
    /// @param listener listener invoked on background threads, or `null`
    public void setStateListener(@Nullable Runnable listener) {
        this.stateListener = listener;
    }

    /// Current phase.
    public Phase phase() {
        return phase;
    }

    /// Stable error code of the last failure, or `null`.
    public @Nullable String errorCode() {
        return errorCode;
    }

    /// Technical detail of the last failure/status, or `null`.
    public @Nullable String statusDetail() {
        return statusDetail;
    }

    /// Current session id, or empty.
    public String sessionId() {
        return sessionId;
    }

    /// Unlisted join code of the current session, or empty.
    public String joinCode() {
        return joinCode;
    }

    /// Loopback address guests paste into the game, or empty.
    public String localAddress() {
        return localAddress;
    }

    /// Whether this client owns the current session.
    public boolean hosting() {
        return hosting;
    }

    /// Occupied player count of the current session.
    public int playerCount() {
        return playerCount;
    }

    /// Player limit of the current session.
    public int maxPlayers() {
        return maxPlayers;
    }

    /// Whether a session is attached.
    public boolean hasSession() {
        return !sessionId.isEmpty();
    }

    /// Whether the account is logged in.
    public boolean isLoggedIn() {
        return loggedIn.getAsBoolean();
    }

    /// Last capability snapshot, or `null` before the first probe.
    public @Nullable Capabilities capabilities() {
        return capabilities;
    }

    /// Creates a hosted session and starts the owner relay bridge.
    ///
    /// @param visibility `private`, `friends` or `unlisted`
    /// @param joinPolicy `open`, `friends`, `request` or `invite_only`
    /// @param gameVersion version text shown to friends, or empty
    public void createRoom(String visibility, String joinPolicy, String gameVersion) {
        if (phase == Phase.WORKING) {
            return;
        }
        long token = generation.incrementAndGet();
        String previous = detachLocalSession();
        setPhase(Phase.WORKING, null, null);
        Schedulers.io().execute(() -> {
            try {
                awaitCleanup();
                leaveQuietly(previous);
                requireCapabilities();
                Joined joined = multiplayer.createSession(visibility, joinPolicy, 8,
                        "Mindustry", gameVersion);
                if (isStale(token)) {
                    // The user left while the session was being created; do not
                    // leave an orphan session behind.
                    enqueueCleanup(joined.session().id());
                    return;
                }
                attach(joined, true);
                startOwnerTunnel(token);
            } catch (IOException | RuntimeException e) {
                fail(token, e);
            }
        });
    }

    /// Resolves a join code (or session id) and joins as a guest.
    ///
    /// @param codeOrId ten-character join code or an opaque session id
    public void joinByCode(String codeOrId) {
        if (phase == Phase.WORKING) {
            return;
        }
        long token = generation.incrementAndGet();
        String previous = detachLocalSession();
        setPhase(Phase.WORKING, null, null);
        Schedulers.io().execute(() -> {
            try {
                awaitCleanup();
                leaveQuietly(previous);
                requireCapabilities();
                String text = codeOrId == null ? "" : codeOrId.trim();
                String id = text;
                String code = null;
                if (!text.startsWith("ses_")) {
                    Session resolved = multiplayer.resolveCode(text);
                    id = resolved.id();
                    code = text;
                }
                Joined joined = multiplayer.join(id, code);
                if (isStale(token)) {
                    enqueueCleanup(joined.session().id());
                    return;
                }
                attach(joined, false);
                startGuestTunnel(token);
            } catch (IOException | RuntimeException e) {
                fail(token, e);
            }
        });
    }

    /// Consumes a Join Intent and joins the bound session as a guest.
    ///
    /// @param intentId intent id from a deep link or an accepted invite
    public void joinByIntent(String intentId) {
        if (phase == Phase.WORKING) {
            return;
        }
        long token = generation.incrementAndGet();
        String previous = detachLocalSession();
        setPhase(Phase.WORKING, null, null);
        Schedulers.io().execute(() -> {
            try {
                awaitCleanup();
                leaveQuietly(previous);
                requireCapabilities();
                Joined joined = multiplayer.consumeJoinIntent(intentId);
                if (isStale(token)) {
                    enqueueCleanup(joined.session().id());
                    return;
                }
                attach(joined, false);
                startGuestTunnel(token);
            } catch (IOException | RuntimeException e) {
                fail(token, e);
            }
        });
    }

    /// Invites one friend into the hosted session.
    ///
    /// @param targetUserId forum user id
    public void inviteFriend(long targetUserId) {
        long token = generation.incrementAndGet();
        String id = sessionId;
        if (!hosting || id.isEmpty()) {
            setPhase(Phase.ERROR, "SESSION_NOT_FOUND", "invite without a hosted session");
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                String inviteId = multiplayer.createInvite(id, targetUserId);
                if (!isStale(token)) {
                    setPhase(Phase.HOSTING, null, inviteId.isEmpty() ? null : inviteId);
                }
            } catch (IOException | RuntimeException e) {
                fail(token, e);
            }
        });
    }

    /// Leaves the current session and tears the tunnel down.
    public void leave() {
        generation.incrementAndGet();
        boolean wasHosting = hosting;
        String previous = detachLocalSession();
        setPhase(Phase.IDLE, null, null);
        publishActivity();
        if (previous.isEmpty()) {
            return;
        }
        enqueueCleanup(previous);
        Logger.LOG.info("MDTBBS multiplayer session closed" + (wasHosting ? " (host)" : ""));
    }

    /// Stops the tunnel and heartbeat without network calls (launcher exit).
    public void shutdown() {
        generation.incrementAndGet();
        detachLocalSession();
        scheduler.shutdownNow();
    }

    /// Closes the active tunnel/heartbeat, clears session state and returns the
    /// previous session id for an optional server-side leave.
    private String detachLocalSession() {
        stopHeartbeat();
        MdtbbsRelayTunnel current = tunnel;
        tunnel = null;
        if (current != null) {
            current.close();
        }
        String previous = sessionId;
        sessionId = "";
        joinCode = "";
        localAddress = "";
        ownerPeerId = "";
        peerId = "";
        resumeToken = "";
        hosting = false;
        playerCount = 0;
        maxPlayers = 0;
        return previous;
    }

    /// Asks the server to leave a session, ignoring failures.
    private void leaveQuietly(String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || !isLoggedIn()) {
            return;
        }
        try {
            multiplayer.leave(sessionId);
        } catch (IOException e) {
            Logger.LOG.info("MDTBBS session leave failed for " + sessionId + ": " + e.getMessage());
        }
    }

    /// Queues one server-side leave and chains it after earlier cleanups.
    ///
    /// A following create/join awaits the chain, so the server never observes
    /// a new session before an older one finished leaving.
    private void enqueueCleanup(String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || !isLoggedIn()) {
            return;
        }
        synchronized (cleanupLock) {
            pendingCleanup = pendingCleanup
                    .handle((value, error) -> null)
                    .thenRunAsync(() -> leaveQuietly(sessionId), Schedulers.io());
        }
    }

    /// Waits until every queued cleanup finished.
    private void awaitCleanup() {
        CompletableFuture<Void> current;
        synchronized (cleanupLock) {
            current = pendingCleanup;
        }
        try {
            current.join();
        } catch (CompletionException ignored) {
            // leaveQuietly already swallows failures.
        }
    }

    /// Verifies capabilities before any session operation.
    private void requireCapabilities() throws IOException {
        Capabilities caps = multiplayer.capabilities();
        capabilities = caps;
        if (!caps.sessionsV1()) {
            throw new MdtbbsApiException(200, "SESSIONS_DISABLED",
                    "MDTBBS multiplayer sessions are disabled", false);
        }
        if (!caps.thirdParty()) {
            throw new MdtbbsApiException(403, "CLIENT_CAPABILITY_NOT_APPROVED",
                    "this client is not approved for third-party multiplayer", false);
        }
        if (!caps.relayV1()) {
            throw new MdtbbsApiException(200, "RELAY_UNAVAILABLE",
                    "MDTBBS relay is disabled", false);
        }
    }

    /// Stores the joined session and starts the heartbeat.
    private void attach(Joined joined, boolean owner) {
        // Any previous tunnel must not outlive the new session; the caller
        // normally detached already, this is a safety net.
        MdtbbsRelayTunnel previous = tunnel;
        tunnel = null;
        if (previous != null) {
            previous.close();
        }
        sessionId = joined.session().id();
        peerId = joined.peer().peerId();
        resumeToken = joined.resumeToken();
        joinCode = joined.joinCode();
        hosting = owner;
        localAddress = "";
        playerCount = joined.session().currentPlayers();
        maxPlayers = joined.session().maxPlayers();
        errorCode = null;
        statusDetail = null;
        if (sessionId.isEmpty() || peerId.isEmpty()) {
            throw new IllegalStateException("MDTBBS session response is incomplete");
        }
        startHeartbeat();
        publishActivity();
    }

    /// Allocates the relay and starts the owner bridge.
    private void startOwnerTunnel(long token) throws IOException {
        String id = sessionId;
        String peer = peerId;
        if (id.isEmpty() || peer.isEmpty()) {
            return;
        }
        RelayAllocation allocation = multiplayer.allocateRelay(id);
        if (isStale(token)) {
            enqueueCleanup(id);
            return;
        }
        MdtbbsRelayTunnel.Config config = new MdtbbsRelayTunnel.Config(
                allocation.endpoint(), allocation.credential(), allocation.allocationId(),
                allocation.agentId(), id, peer, null, true,
                MINDUSTRY_PORT, 0, allocation.expiresInSeconds());
        tunnel = tunnelFactory.create(config, this::renewRelay,
                detail -> updateDetail(token, detail),
                message -> handleTunnelFailure(token, message),
                port -> {
                    // The owner bridge does not expose a local port.
                });
        setPhase(Phase.HOSTING, null, null);
    }

    /// Joins the session as a guest and starts the loopback proxy.
    private void startGuestTunnel(long token) throws IOException {
        String id = sessionId;
        String peer = peerId;
        if (id.isEmpty() || peer.isEmpty()) {
            return;
        }
        List<Peer> peers = multiplayer.peers(id);
        String owner = "";
        for (Peer candidate : peers) {
            if (candidate.owner()) {
                owner = candidate.peerId();
                break;
            }
        }
        if (owner.isEmpty()) {
            throw new IOException("The session host is not online yet");
        }
        RelayAllocation allocation = multiplayer.allocateRelay(id);
        if (isStale(token)) {
            enqueueCleanup(id);
            return;
        }
        ownerPeerId = owner;
        playerCount = peers.size();
        setPhase(Phase.GUEST_WAITING, null, null);
        MdtbbsRelayTunnel.Config config = new MdtbbsRelayTunnel.Config(
                allocation.endpoint(), allocation.credential(), allocation.allocationId(),
                allocation.agentId(), id, peer, owner, false,
                0, MINDUSTRY_PORT, allocation.expiresInSeconds());
        tunnel = tunnelFactory.create(config, this::renewRelay,
                detail -> updateDetail(token, detail),
                message -> handleTunnelFailure(token, message),
                port -> {
                    if (isStale(token)) {
                        return;
                    }
                    localAddress = "127.0.0.1:" + port;
                    setPhase(Phase.GUEST_READY, null, null);
                });
    }

    /// Renews the relay allocation for the current peer.
    private RelayAllocation renewRelay() throws IOException {
        String id = sessionId;
        if (id.isEmpty()) {
            throw new IOException("Session already closed");
        }
        return multiplayer.allocateRelay(id);
    }

    /// Starts the peer heartbeat loop.
    private void startHeartbeat() {
        stopHeartbeat();
        if (sessionId.isEmpty() || peerId.isEmpty()) {
            return;
        }
        heartbeat = scheduler.scheduleAtFixedRate(this::sendHeartbeat,
                heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }

    /// Stops the peer heartbeat loop.
    private void stopHeartbeat() {
        ScheduledFuture<?> current = heartbeat;
        heartbeat = null;
        if (current != null) {
            current.cancel(false);
        }
    }

    /// Sends one heartbeat, resuming the peer if the server expired it.
    private void sendHeartbeat() {
        String id = sessionId;
        String peer = peerId;
        if (id.isEmpty() || peer.isEmpty()) {
            return;
        }
        try {
            multiplayer.peerHeartbeat(id, peer);
        } catch (MdtbbsApiException e) {
            if ("PEER_EXPIRED".equals(e.getErrorCode())) {
                resumePeer(id);
            } else {
                Logger.LOG.info("MDTBBS peer heartbeat failed: " + e.getErrorCode());
            }
        } catch (IOException e) {
            Logger.LOG.info("MDTBBS peer heartbeat failed: " + e.getMessage());
        }
    }

    /// Restores the peer inside its 60-second recovery window.
    private void resumePeer(String id) {
        String token = resumeToken;
        if (token.isEmpty()) {
            setPhase(Phase.ERROR, "PEER_EXPIRED", null);
            return;
        }
        try {
            Joined resumed = multiplayer.resume(id, token);
            if (!id.equals(sessionId)) {
                return;
            }
            peerId = resumed.peer().peerId();
            resumeToken = resumed.resumeToken();
            Logger.LOG.info("MDTBBS peer resumed in session " + id);
        } catch (IOException e) {
            setPhase(Phase.ERROR, "PEER_EXPIRED", e.getMessage());
        }
    }

    /// Publishes the current activity to the friends list.
    private void publishActivity() {
        ActivitySink sink = activitySink;
        if (sink != null) {
            sink.update(sessionId.isEmpty() ? null : sessionId, hosting);
        }
    }

    /// Updates the phase and notifies the UI listener.
    private void setPhase(Phase next, @Nullable String code, @Nullable String detail) {
        phase = next;
        errorCode = code;
        statusDetail = detail;
        Runnable listener = stateListener;
        if (listener != null) {
            listener.run();
        }
    }

    /// Updates only the human-readable detail of the current phase.
    private void updateDetail(long token, String detail) {
        if (isStale(token)) {
            return;
        }
        statusDetail = detail;
        Runnable listener = stateListener;
        if (listener != null) {
            listener.run();
        }
    }

    /// Reports a tunnel failure, preserving the relay's stable error code.
    private void handleTunnelFailure(long token, String message) {
        if (isStale(token)) {
            return;
        }
        setPhase(Phase.ERROR, tunnelErrorCode(message), message);
    }

    /// Extracts a stable relay error code from a tunnel failure message.
    private static String tunnelErrorCode(String message) {
        int open = message.indexOf('(');
        int close = open < 0 ? -1 : message.indexOf(')', open + 1);
        if (open > 0 && close > open) {
            String candidate = message.substring(open + 1, close).trim();
            if (candidate.matches("[A-Z][A-Z0-9_]{2,31}")) {
                return candidate;
            }
        }
        return "RELAY_ERROR";
    }

    /// Reports an operation failure with a stable code when available.
    private void fail(long token, Throwable throwable) {
        if (isStale(token)) {
            return;
        }
        String code = throwable instanceof MdtbbsApiException apiException
                ? apiException.getErrorCode() : null;
        setPhase(Phase.ERROR, code, throwable.getMessage());
        Logger.LOG.info("MDTBBS multiplayer operation failed: " + throwable.getMessage());
    }

    /// Whether an async operation was superseded.
    private boolean isStale(long token) {
        return token != generation.get();
    }
}
