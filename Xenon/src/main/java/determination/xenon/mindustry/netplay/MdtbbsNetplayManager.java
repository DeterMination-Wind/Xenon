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

import com.google.gson.JsonObject;
import determination.xenon.mindustry.community.MdtbbsApiException;
import determination.xenon.mindustry.community.MdtbbsCommunity;
import determination.xenon.mindustry.community.MdtbbsJson;
import determination.xenon.mindustry.community.MdtbbsMultiplayerApi;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Capabilities;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Joined;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Peer;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.RelayAllocation;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Session;
import determination.xenon.mindustry.community.MdtbbsRealtimeClient;
import determination.xenon.mindustry.community.MdtbbsRealtimeEvent;
import determination.xenon.netplay.mdtbbs.MdtbbsRelayTunnel;
import determination.xenon.setting.ConfigHolder;
import determination.xenon.task.Schedulers;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /// One pending join request shown to the host.
    ///
    /// @param id       join request id
    /// @param sessionId target session
    /// @param userId   requesting user id, or `0` when unknown
    /// @param username requesting user name, or empty
    public record JoinRequest(String id, String sessionId, long userId, String username) {
    }

    private final BooleanSupplier loggedIn;
    private final BooleanSupplier lanDiscovery;
    private final MdtbbsMultiplayerApi multiplayer;
    private final TunnelFactory tunnelFactory;
    private final @Nullable MdtbbsRealtimeClient realtime;
    private final List<JoinRequest> joinRequests = new CopyOnWriteArrayList<>();
    private volatile boolean realtimeStarted;
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
    private volatile String visibility = "";
    private volatile String joinPolicy = "";
    private volatile boolean requireApproval;
    private volatile String activityName = "";
    private final Object approvalLock = new Object();
    private volatile String requestedSessionId = "";
    private volatile String pendingApprovedIntent = "";
    private volatile boolean awaitingApproval;
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
                () -> ConfigHolder.globalConfig().isMindustryLanDiscovery(),
                new MdtbbsMultiplayerClient(community.api()),
                MdtbbsRelayTunnel::new,
                DEFAULT_HEARTBEAT_SECONDS,
                new MdtbbsRealtimeClient(community.api()));
    }

    /// Creates a manager with injected dependencies (tests).
    ///
    /// @param loggedIn       whether the account is logged in
    /// @param multiplayer    signalling client
    /// @param tunnelFactory  relay tunnel factory
    /// @param heartbeatSeconds peer heartbeat interval
    MdtbbsNetplayManager(BooleanSupplier loggedIn, MdtbbsMultiplayerApi multiplayer,
                         TunnelFactory tunnelFactory, long heartbeatSeconds) {
        this(loggedIn, () -> true, multiplayer, tunnelFactory, heartbeatSeconds, null);
    }

    /// Creates a manager with injected dependencies (tests).
    ///
    /// @param loggedIn       whether the account is logged in
    /// @param lanDiscovery   whether guest sessions advertise on the LAN list
    /// @param multiplayer    signalling client
    /// @param tunnelFactory  relay tunnel factory
    /// @param heartbeatSeconds peer heartbeat interval
    MdtbbsNetplayManager(BooleanSupplier loggedIn, BooleanSupplier lanDiscovery,
                         MdtbbsMultiplayerApi multiplayer,
                         TunnelFactory tunnelFactory, long heartbeatSeconds) {
        this(loggedIn, lanDiscovery, multiplayer, tunnelFactory, heartbeatSeconds, null);
    }

    /// Creates a manager with injected dependencies (tests).
    ///
    /// @param loggedIn       whether the account is logged in
    /// @param lanDiscovery   whether guest sessions advertise on the LAN list
    /// @param multiplayer    signalling client
    /// @param tunnelFactory  relay tunnel factory
    /// @param heartbeatSeconds peer heartbeat interval
    /// @param realtime       realtime event client, or `null` in tests
    MdtbbsNetplayManager(BooleanSupplier loggedIn, BooleanSupplier lanDiscovery,
                         MdtbbsMultiplayerApi multiplayer,
                         TunnelFactory tunnelFactory, long heartbeatSeconds,
                         @Nullable MdtbbsRealtimeClient realtime) {
        this.loggedIn = loggedIn;
        this.lanDiscovery = lanDiscovery;
        this.multiplayer = multiplayer;
        this.tunnelFactory = tunnelFactory;
        this.heartbeatSeconds = Math.max(1L, heartbeatSeconds);
        this.realtime = realtime;
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

    /// Visibility of the current session, or empty.
    public String visibility() {
        return visibility;
    }

    /// Join policy of the current session, or empty.
    public String joinPolicy() {
        return joinPolicy;
    }

    /// Whether the current session requires host approval.
    public boolean requireApproval() {
        return requireApproval;
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

    /// Whether a join request is waiting for host approval.
    public boolean awaitingApproval() {
        return awaitingApproval;
    }

    /// Pending join requests shown to the host.
    ///
    /// @return an immutable snapshot of the pending requests
    public List<JoinRequest> joinRequests() {
        return List.copyOf(joinRequests);
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
    /// Deprecated enum values are normalised instead of being sent to the
    /// server: `unlisted` becomes `private`, `open` becomes `public` and
    /// `request` becomes `friends` plus host approval. `invite_only` always
    /// clears the approval flag because it has no request path.
    ///
    /// @param visibility      `private`, `friends` or `public`
    /// @param joinPolicy      `invite_only`, `friends` or `public`
    /// @param requireApproval whether the host must approve join requests
    /// @param gameVersion     version text shown to friends, or empty
    public void createRoom(String visibility, String joinPolicy, boolean requireApproval,
                           String gameVersion) {
        if (phase == Phase.WORKING) {
            return;
        }
        String requestedJoin = normalizeJoinPolicy(joinPolicy);
        String requestedVisibility = normalizeVisibility(visibility);
        boolean approval = normalizeApproval(requestedJoin, requireApproval);
        long token = generation.incrementAndGet();
        String previous = detachLocalSession();
        setPhase(Phase.WORKING, null, null);
        Schedulers.io().execute(() -> {
            try {
                awaitCleanup();
                leaveQuietly(previous);
                requireCapabilities();
                Joined joined = multiplayer.createSession(requestedVisibility, requestedJoin, 8,
                        "Mindustry", gameVersion, approval);
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

    /// Creates a hosted session without an approval step.
    ///
    /// @param visibility  `private`, `friends` or `public`
    /// @param joinPolicy  `invite_only`, `friends` or `public`
    /// @param gameVersion version text shown to friends, or empty
    public void createRoom(String visibility, String joinPolicy, String gameVersion) {
        createRoom(visibility, joinPolicy, false, gameVersion);
    }

    /// Normalises a visibility value to the 1.7.0 enum.
    private static String normalizeVisibility(@Nullable String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "friends" -> "friends";
            case "public" -> "public";
            // `unlisted` was the old "hidden but code-joinable" value; its
            // migration target is `private`, and the join policy carries the
            // old code semantics.
            default -> "private";
        };
    }

    /// Normalises a join-policy value to the 1.7.0 enum.
    private static String normalizeJoinPolicy(@Nullable String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "invite_only" -> "invite_only";
            case "public", "open" -> "public";
            case "friends" -> "friends";
            // The removed `request` value meant "friends plus approval".
            default -> "friends";
        };
    }

    /// Clears approval for `invite_only`, which has no request path.
    private static boolean normalizeApproval(String joinPolicy, boolean requireApproval) {
        return requireApproval && !"invite_only".equals(joinPolicy);
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
                if (text.isEmpty()) {
                    throw new MdtbbsApiException(400, "JOIN_CODE_INVALID",
                            "a join code or session id is required", false);
                }
                String id = text;
                String code = null;
                if (!text.startsWith("ses_")) {
                    if (!text.matches("[0-9A-Fa-f]{10}")) {
                        throw new MdtbbsApiException(400, "JOIN_CODE_INVALID",
                                "MDTBBS join codes are 10 hexadecimal characters", false);
                    }
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

    /// Requests to join a session that requires host approval.
    ///
    /// The request stays valid for five minutes; when the host approves it the
    /// realtime client calls [#onJoinApproved(String)] with the returned join
    /// intent. Joining without an approval is refused by the server, so this is
    /// the only correct path for `require_approval` rooms.
    ///
    /// @param sessionId target session id
    public void requestJoin(String sessionId) {
        if (phase == Phase.WORKING) {
            return;
        }
        String id = sessionId == null ? "" : sessionId.trim();
        if (!id.startsWith("ses_")) {
            setPhase(Phase.ERROR, "SESSION_NOT_FOUND", "not a session id: " + id);
            return;
        }
        long token = generation.incrementAndGet();
        String previous = detachLocalSession();
        // Publish the target before the request returns: the approval event can
        // arrive while the HTTP call is still in flight.
        synchronized (approvalLock) {
            requestedSessionId = id;
            pendingApprovedIntent = "";
            awaitingApproval = true;
        }
        setPhase(Phase.WORKING, null, null);
        Schedulers.io().execute(() -> {
            try {
                awaitCleanup();
                leaveQuietly(previous);
                requireCapabilities();
                multiplayer.createJoinRequest(id);
                String pending;
                synchronized (approvalLock) {
                    if (isStale(token) || !awaitingApproval) {
                        return;
                    }
                    pending = pendingApprovedIntent;
                    pendingApprovedIntent = "";
                    if (!pending.isBlank()) {
                        awaitingApproval = false;
                    }
                }
                setPhase(Phase.GUEST_WAITING, null,
                        pending.isBlank() ? "approval_pending" : null);
                if (!pending.isBlank()) {
                    joinByIntent(pending);
                }
            } catch (IOException | RuntimeException e) {
                if (!isStale(token)) {
                    synchronized (approvalLock) {
                        if (!isStale(token)) {
                            awaitingApproval = false;
                            requestedSessionId = "";
                            pendingApprovedIntent = "";
                        }
                    }
                }
                fail(token, e);
            }
        });
    }

    /// Consumes the join intent produced by an approved join request.
    ///
    /// Unsolicited intents are ignored so an old event cannot pull the user
    /// into a session they no longer wait for.
    ///
    /// @param intentId approved join intent id
    public void onJoinApproved(String intentId) {
        synchronized (approvalLock) {
            if (phase == Phase.WORKING) {
                // The HTTP join-request call has not returned yet; keep the
                // intent so it can be consumed instead of being acknowledged
                // and dropped. The worker drains it under the same lock.
                pendingApprovedIntent = intentId;
                return;
            }
            if (!awaitingApproval) {
                return;
            }
            awaitingApproval = false;
        }
        joinByIntent(intentId);
    }

    /// Approves one join request (host side).
    ///
    /// @param requestId join request id from the realtime event
    public void approveJoinRequest(String requestId) {
        Schedulers.io().execute(() -> {
            try {
                multiplayer.approveJoinRequest(requestId);
                joinRequests.removeIf(request -> request.id().equals(requestId));
                notifyStateChange();
            } catch (IOException | RuntimeException e) {
                Logger.LOG.info("MDTBBS approve join request failed: " + e.getMessage());
            }
        });
    }

    /// Rejects one join request (host side).
    ///
    /// @param requestId join request id from the realtime event
    public void rejectJoinRequest(String requestId) {
        Schedulers.io().execute(() -> {
            try {
                multiplayer.rejectJoinRequest(requestId);
                joinRequests.removeIf(request -> request.id().equals(requestId));
                notifyStateChange();
            } catch (IOException | RuntimeException e) {
                Logger.LOG.info("MDTBBS reject join request failed: " + e.getMessage());
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
        String id = sessionId;
        if (!hosting || id.isEmpty()) {
            setPhase(Phase.ERROR, "SESSION_NOT_FOUND", "invite without a hosted session");
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                String inviteId = multiplayer.createInvite(id, targetUserId);
                statusDetail = inviteId.isEmpty() ? null : inviteId;
                notifyStateChange();
            } catch (IOException | RuntimeException e) {
                Logger.LOG.info("MDTBBS invite failed: " + e.getMessage());
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
        closeRealtime();
        scheduler.shutdownNow();
    }

    /// Closes the active tunnel/heartbeat, clears session state and returns the
    /// previous session id for an optional server-side leave.
    private String detachLocalSession() {
        stopHeartbeat();
        MdtbbsRealtimeClient events = realtime;
        if (events != null && !sessionId.isEmpty()) {
            events.unsubscribe(sessionId);
        }
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
        visibility = "";
        joinPolicy = "";
        requireApproval = false;
        activityName = "";
        synchronized (approvalLock) {
            requestedSessionId = "";
            pendingApprovedIntent = "";
            awaitingApproval = false;
        }
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

    /// Starts the realtime client once and subscribes the attached session.
    private void startRealtime() {
        MdtbbsRealtimeClient events = realtime;
        if (events == null || sessionId.isEmpty()) {
            return;
        }
        if (!realtimeStarted) {
            realtimeStarted = true;
            events.setListener(new RealtimeListener());
            events.start();
        }
        events.subscribe(sessionId);
    }

    /// Closes the realtime client on launcher shutdown.
    private void closeRealtime() {
        MdtbbsRealtimeClient events = realtime;
        if (events != null) {
            events.close();
        }
    }

    /// Re-reads the session snapshot after a failed stream resume.
    private void refreshSnapshot() {
        String id = sessionId;
        if (id.isEmpty()) {
            return;
        }
        long token = generation.get();
        Schedulers.io().execute(() -> {
            try {
                Session session = multiplayer.session(id);
                List<Peer> peers = multiplayer.peers(id);
                if (isStale(token)) {
                    return;
                }
                if (session == null) {
                    detachLocalSession();
                    setPhase(Phase.ERROR, "SESSION_CLOSED", null);
                    publishActivity();
                    return;
                }
                maxPlayers = session.maxPlayers();
                playerCount = peers.isEmpty() ? session.currentPlayers() : peers.size();
                notifyStateChange();
            } catch (IOException e) {
                Logger.LOG.info("MDTBBS snapshot refresh failed: " + e.getMessage());
            }
        });
    }

    /// Applies one realtime event to the session state.
    private void handleRealtimeEvent(MdtbbsRealtimeEvent event) {
        String name = event.event();
        switch (name) {
            case "multiplayer.join_request.created" -> handleJoinRequestCreated(event);
            case "multiplayer.join_request.approved" -> {
                String intentId = event.string("intent_id");
                String approvedSession = event.sessionId();
                boolean matches = approvedSession.isEmpty()
                        || approvedSession.equals(requestedSessionId);
                if (!intentId.isBlank() && matches) {
                    onJoinApproved(intentId);
                }
            }
            case "multiplayer.join_request.rejected" -> {
                synchronized (approvalLock) {
                    if (awaitingApproval) {
                        awaitingApproval = false;
                        requestedSessionId = "";
                        pendingApprovedIntent = "";
                    } else {
                        return;
                    }
                }
                setPhase(Phase.ERROR, "JOIN_REQUEST_REJECTED", null);
            }
            case "session.closed" -> {
                if (!sessionId.isEmpty() && sessionId.equals(event.sessionId())) {
                    detachLocalSession();
                    setPhase(Phase.ERROR, "SESSION_CLOSED", null);
                    publishActivity();
                }
            }
            case "session.updated" -> {
                if (!sessionId.isEmpty() && sessionId.equals(event.sessionId())) {
                    long players = event.number("current_players", -1);
                    if (players >= 0) {
                        playerCount = (int) players;
                    }
                    notifyStateChange();
                }
            }
            case "peer.joined" -> adjustPeerCount(1);
            case "peer.left", "peer.disconnected" -> {
                if (event.string("role").equals("owner") || event.string("peer_id").equals(ownerPeerId)) {
                    setPhase(Phase.ERROR, "HOST_LEFT", null);
                } else {
                    adjustPeerCount(-1);
                }
            }
            case "peer.updated", "multiplayer.invite.created", "multiplayer.invite.accepted",
                    "multiplayer.invite.revoked" -> notifyStateChange();
            default -> Logger.LOG.debug("Ignoring realtime event " + name);
        }
    }

    /// Adds one pending join request from a realtime event.
    private void handleJoinRequestCreated(MdtbbsRealtimeEvent event) {
        String requestId = event.string("join_request_id");
        if (requestId.isBlank()) {
            requestId = event.string("id");
        }
        String target = event.sessionId();
        if (requestId.isBlank() || (!target.isEmpty() && !target.equals(sessionId))) {
            return;
        }
        JsonObject requester = MdtbbsJson.objectOf(event.data(), "requester");
        if (requester == null) {
            requester = MdtbbsJson.objectOf(event.data(), "user");
        }
        long userId = requester == null ? 0L : MdtbbsJson.longOf(requester, "id", 0L);
        String username = requester == null
                ? event.string("username")
                : MdtbbsJson.stringOf(requester, "username");
        String finalRequestId = requestId;
        joinRequests.removeIf(request -> request.id().equals(finalRequestId));
        joinRequests.add(new JoinRequest(finalRequestId, target.isEmpty() ? sessionId : target,
                userId, username));
        notifyStateChange();
    }

    /// Adjusts the player count, keeping it within the advertised limit.
    private void adjustPeerCount(int delta) {
        int next = playerCount + delta;
        playerCount = Math.max(1, maxPlayers > 0 ? Math.min(maxPlayers, next) : next);
        notifyStateChange();
    }

    /// Notifies the listener without changing the phase.
    private void notifyStateChange() {
        Runnable listener = stateListener;
        if (listener != null) {
            listener.run();
        }
    }

    /// Realtime client callbacks for this manager.
    private final class RealtimeListener implements MdtbbsRealtimeClient.Listener {
        @Override
        public void onEvent(MdtbbsRealtimeEvent event) {
            handleRealtimeEvent(event);
        }

        @Override
        public void onResumeFailed() {
            refreshSnapshot();
        }

        @Override
        public void onState(String state, @Nullable String detail) {
            Logger.LOG.debug("MDTBBS realtime " + state
                    + (detail == null || detail.isBlank() ? "" : ": " + detail));
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
        visibility = joined.session().visibility();
        joinPolicy = joined.session().joinPolicy();
        requireApproval = joined.session().requireApproval();
        activityName = joined.session().activityName();
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
        startRealtime();
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
                MINDUSTRY_PORT, 0, allocation.expiresInSeconds(),
                activityName, maxPlayers, playerCount, lanDiscovery.getAsBoolean());
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
                0, MINDUSTRY_PORT, allocation.expiresInSeconds(),
                activityName, maxPlayers, playerCount, lanDiscovery.getAsBoolean());
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
    ///
    /// The first heartbeat is sent immediately: a fresh peer otherwise has a
    /// full interval to publish its presence, and relay allocation can race
    /// that window and fail with `PEER_UNAVAILABLE`.
    private void startHeartbeat() {
        stopHeartbeat();
        if (sessionId.isEmpty() || peerId.isEmpty()) {
            return;
        }
        heartbeat = scheduler.scheduleAtFixedRate(this::sendHeartbeat,
                0L, heartbeatSeconds, TimeUnit.SECONDS);
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
            setPhase(Phase.ERROR, "PEER_RESUME_INVALID", e.getMessage());
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
