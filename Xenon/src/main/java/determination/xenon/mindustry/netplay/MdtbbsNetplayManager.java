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
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.JoinRequestEntry;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.JoinRequestPage;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

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

    /// How long a locally inserted join request survives a server snapshot
    /// that has not published it yet.
    private static final long JOIN_REQUEST_GRACE_NANOS = TimeUnit.SECONDS.toNanos(10L);

    /// Minimum interval between heartbeat-driven join-request refreshes.
    private static final long JOIN_REQUEST_REFRESH_NANOS = TimeUnit.SECONDS.toNanos(60L);

    /// Minimum interval between realtime-event join-request refreshes.
    private static final long JOIN_REQUEST_EVENT_THROTTLE_NANOS = TimeUnit.SECONDS.toNanos(2L);

    /// Upper bound of join-request pages merged into one authoritative snapshot.
    private static final int JOIN_REQUEST_MAX_PAGES = 3;

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

    /// Normalised parameters of one create request, retained so a stale
    /// `SESSION_ALREADY_ACTIVE` failure can be retried without asking the UI.
    ///
    /// @param visibility      normalised `visibility` value
    /// @param joinPolicy      normalised `join_policy` value
    /// @param requireApproval whether the host must approve join requests
    /// @param gameVersion     version text shown to friends, or empty
    private record CreateRequest(String visibility, String joinPolicy,
                                 boolean requireApproval, String gameVersion) {
    }

    private final BooleanSupplier loggedIn;
    private final BooleanSupplier lanDiscovery;
    private final MdtbbsMultiplayerApi multiplayer;
    private final TunnelFactory tunnelFactory;
    private final @Nullable MdtbbsRealtimeClient realtime;
    private final List<JoinRequest> joinRequests = new CopyOnWriteArrayList<>();

    /// Grace deadlines of locally inserted join requests, keyed by id.
    ///
    /// Values are `System.nanoTime()` deadlines, so wall-clock steps cannot
    /// extend or shorten the grace window.
    private final ConcurrentHashMap<String, Long> joinRequestSeenNanos = new ConcurrentHashMap<>();

    /// Serialises the local insert with the server-snapshot merge, so a request
    /// that arrives while a refresh is in flight cannot be dropped.
    private final Object joinRequestLock = new Object();

    /// Monotonic refresh counter; only the newest issued snapshot is applied,
    /// so a slow older refresh cannot overwrite a newer result.
    private final AtomicLong joinRequestRefreshSequence = new AtomicLong();

    /// Nano-time source of the join-request grace window and refresh throttle.
    private volatile LongSupplier nanoClock = System::nanoTime;

    /// Nano timestamp of the last join-request refresh that was scheduled.
    private volatile long lastJoinRequestRefreshNanos;

    /// Nano timestamp of the last realtime-event join-request refresh.
    private volatile long lastJoinRequestEventRefreshNanos;

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
    private volatile String activeJoinRequestId = "";
    private String pendingApprovedIntent = "";
    private boolean approvalInFlight;
    private boolean approvalRejected;
    private volatile boolean awaitingApproval;
    private volatile int playerCount;
    private volatile int maxPlayers;
    private volatile @Nullable Capabilities capabilities;
    private volatile @Nullable MdtbbsRelayTunnel tunnel;
    private volatile @Nullable ScheduledFuture<?> heartbeat;

    /// Last create request, replayed by [#retryCreateRoom()].
    private volatile @Nullable CreateRequest lastCreateRequest;

    /// Zombie session named by the last `SESSION_ALREADY_ACTIVE` failure, or `null`.
    private volatile @Nullable String conflictSessionId;

    /// Stable code of the last failed invite attempt, or `null`.
    ///
    /// Invite failures are published here instead of moving a live hosted
    /// session into [`Phase#ERROR`].
    private volatile @Nullable String lastInviteError;

    /// Target user id of [#lastInviteError], or `null`.
    private volatile @Nullable String lastInviteTarget;

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

    /// Replaces the pending join-request list with the server snapshot.
    ///
    /// Only a host with an attached session refreshes; guests and idle
    /// managers ignore the call. Up to three pages are merged so the snapshot
    /// is complete, and a locally inserted request survives the snapshot for a
    /// short grace window while the server catches up. A failed refresh keeps
    /// the previous list instead of clearing it.
    public void refreshJoinRequests() {
        if (!hosting) {
            return;
        }
        String id = sessionId;
        if (id.isEmpty()) {
            return;
        }
        long token = generation.get();
        int pageSize = Math.max(1, Math.min(50, lobbyPageSize()));
        long sequence = joinRequestRefreshSequence.incrementAndGet();
        lastJoinRequestRefreshNanos = nanoClock.getAsLong();
        Schedulers.io().execute(() -> {
            List<JoinRequestEntry> entries = new ArrayList<>();
            int fetched = 0;
            int pageNumber = 1;
            while (fetched < JOIN_REQUEST_MAX_PAGES) {
                JoinRequestPage page;
                try {
                    page = multiplayer.joinRequests(id, pageNumber, pageSize);
                } catch (IOException | RuntimeException e) {
                    Logger.LOG.info("MDTBBS join request refresh failed: " + e.getMessage());
                    // A failed fetch must not suppress the next heartbeat retry
                    // for a whole minute; rewind the throttle instead.
                    lastJoinRequestRefreshNanos =
                            nanoClock.getAsLong() - JOIN_REQUEST_REFRESH_NANOS;
                    return;
                }
                entries.addAll(page.requests());
                fetched++;
                if (!page.hasMore()) {
                    break;
                }
                pageNumber++;
            }
            if (isStale(token) || !hosting || !id.equals(sessionId)) {
                return;
            }
            long now = nanoClock.getAsLong();
            synchronized (joinRequestLock) {
                if (joinRequestRefreshSequence.get() != sequence) {
                    // A newer snapshot was issued while this one was in flight.
                    return;
                }
                Set<String> serverIds = new HashSet<>();
                List<JoinRequest> merged = new ArrayList<>(entries.size());
                for (JoinRequestEntry entry : entries) {
                    if (entry.id().isBlank() || !serverIds.add(entry.id())) {
                        continue;
                    }
                    merged.add(new JoinRequest(entry.id(), id, entry.userId(), entry.username()));
                }
                for (JoinRequest local : joinRequests) {
                    if (!local.sessionId().equals(id) || serverIds.contains(local.id())) {
                        continue;
                    }
                    @Nullable Long deadline = joinRequestSeenNanos.get(local.id());
                    if (deadline != null && now - deadline < 0L) {
                        merged.add(local);
                    }
                }
                joinRequestSeenNanos.values().removeIf(deadline -> now - deadline >= 0L);
                joinRequests.clear();
                joinRequests.addAll(merged);
            }
            notifyStateChange();
        });
    }

    /// Replaces the nano-time source used by the join-request grace window and
    /// the heartbeat refresh throttle.
    ///
    /// Package-private test hook; passing `null` restores `System.nanoTime()`.
    ///
    /// @param source nano-time supplier, or `null` for the system clock
    void setNanoTimeSource(@Nullable LongSupplier source) {
        nanoClock = source == null ? System::nanoTime : source;
    }

    /// Whether the account is logged in.
    public boolean isLoggedIn() {
        return loggedIn.getAsBoolean();
    }

    /// Last capability snapshot, or `null` before the first probe.
    public @Nullable Capabilities capabilities() {
        return capabilities;
    }

    /// Probes and caches the multiplayer capabilities before the first lobby load.
    ///
    /// Blocks on the HTTP call, so callers must run it off the FX thread. The
    /// cached snapshot is kept; failures are logged and the documented
    /// defaults stay in place so the lobby keeps working.
    public void preloadCapabilities() {
        if (capabilities != null) {
            return;
        }
        try {
            capabilities = multiplayer.capabilities();
            notifyStateChange();
        } catch (IOException | RuntimeException e) {
            Logger.LOG.info("MDTBBS capability preload failed: " + e.getMessage());
        }
    }

    /// Zombie session id named by the last `SESSION_ALREADY_ACTIVE` failure.
    ///
    /// The id is kept after a successful recovery so the UI can still explain
    /// which stale session was closed.
    ///
    /// @return the session id, or `null` when no conflict happened yet
    public @Nullable String conflictSessionId() {
        return conflictSessionId;
    }

    /// Stable code of the last failed invite attempt, or `null`.
    ///
    /// A failed invite never moves a live hosted session into
    /// [`Phase#ERROR`]; the invite dialog reads this code instead.
    ///
    /// @return the failure code, or `null` when the last invite succeeded
    public @Nullable String lastInviteError() {
        return lastInviteError;
    }

    /// Target user id of the last failed invite attempt, or `null`.
    ///
    /// Stored as a decimal string so the invite dialog can reject a failure
    /// that belongs to an earlier attempt for another user.
    ///
    /// @return the target user id, or `null` when no invite failed
    public @Nullable String lastInviteTarget() {
        return lastInviteTarget;
    }

    /// Page size requested for the public lobby snapshot.
    ///
    /// Uses the advertised `max_lobby_page_size` when the server reported a
    /// positive value, otherwise the fixed default of `20`.
    ///
    /// @return the lobby page size
    public int lobbyPageSize() {
        Capabilities caps = capabilities;
        long limit = caps == null ? 0L : caps.limits().maxLobbyPageSize();
        return limit > 0 ? (int) Math.min(20L, limit) : 20;
    }

    /// Advertised limit of pending invites per sender.
    ///
    /// @return the limit, or `0` when the server did not advertise one
    public long maxPendingInvites() {
        Capabilities caps = capabilities;
        return caps == null ? 0L : caps.limits().maxPendingInvitesPerSender();
    }

    /// Creates a hosted session and starts the owner relay bridge.
    ///
    /// Deprecated enum values are normalised instead of being sent to the
    /// server: `unlisted` becomes `private`, `open` becomes `public` and
    /// `request` becomes `friends` plus host approval. `invite_only` always
    /// clears the approval flag because it has no request path.
    ///
    /// When the account still owns a zombie session from an earlier crash or
    /// another device, the server refuses with `SESSION_ALREADY_ACTIVE`; the
    /// named session is left and the create is retried exactly once. A failed
    /// cleanup surfaces as [#errorCode()] `SESSION_ALREADY_ACTIVE` with the
    /// zombie id in [#statusDetail()].
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
        CreateRequest request = new CreateRequest(requestedVisibility, requestedJoin,
                approval, gameVersion == null ? "" : gameVersion);
        lastCreateRequest = request;
        startCreate(request);
    }

    /// Creates a hosted session without an approval step.
    ///
    /// @param visibility  `private`, `friends` or `public`
    /// @param joinPolicy  `invite_only`, `friends` or `public`
    /// @param gameVersion version text shown to friends, or empty
    public void createRoom(String visibility, String joinPolicy, String gameVersion) {
        createRoom(visibility, joinPolicy, false, gameVersion);
    }

    /// Replays the last [#createRoom(String, String, boolean, String)] request.
    ///
    /// Intended only for the `SESSION_ALREADY_ACTIVE` recovery affordance: the
    /// call is a no-op unless the manager is in that exact error state, no
    /// session is attached and a create request was stored. The request keeps
    /// the same one-shot zombie cleanup rule.
    public void retryCreateRoom() {
        CreateRequest request = lastCreateRequest;
        if (request == null
                || phase != Phase.ERROR
                || !"SESSION_ALREADY_ACTIVE".equals(errorCode)
                || hasSession()) {
            return;
        }
        startCreate(request);
    }

    /// Runs one stored create request, including the one-shot zombie recovery.
    private void startCreate(CreateRequest request) {
        long token;
        String previous;
        synchronized (approvalLock) {
            token = generation.incrementAndGet();
            previous = detachLocalSession();
            setPhase(Phase.WORKING, null, null);
        }
        Schedulers.io().execute(() -> {
            try {
                awaitCleanup();
                leaveQuietly(previous);
                requireCapabilities();
                Joined joined;
                try {
                    joined = requestCreate(request);
                } catch (MdtbbsApiException conflict) {
                    joined = recoverConflict(token, request, conflict);
                    if (joined == null) {
                        return;
                    }
                }
                if (!attachIfCurrent(token, joined, true)) {
                    // The user left while the session was being created; do not
                    // leave an orphan session behind.
                    enqueueCleanup(joined.session().id());
                    return;
                }
                refreshJoinRequests();
                startOwnerTunnel(token);
            } catch (IOException | RuntimeException e) {
                fail(token, e);
            }
        });
    }

    /// Sends one create request with the advertised player limit.
    private Joined requestCreate(CreateRequest request) throws IOException {
        return multiplayer.createSession(request.visibility(), request.joinPolicy(),
                createMaxPlayers(), "Mindustry", request.gameVersion(),
                request.requireApproval());
    }

    /// Player limit requested for a new session.
    ///
    /// Uses the advertised `max_players_per_session` when it is positive,
    /// otherwise the historical default of `8`.
    private int createMaxPlayers() {
        Capabilities caps = capabilities;
        long limit = caps == null ? 0L : caps.limits().maxPlayersPerSession();
        return limit > 0 ? (int) Math.min(8L, limit) : 8;
    }

    /// Closes the zombie session behind a `SESSION_ALREADY_ACTIVE` failure and
    /// retries the create exactly once.
    ///
    /// A retry is attempted only when the failure names a session and the
    /// operation token is still current. Failures of the cleanup itself are
    /// published instead of being retried, because a second create would fail
    /// the same way. Failures of the retried create propagate so the caller's
    /// normal failure path reports them.
    ///
    /// @param token    lifecycle token of the create operation
    /// @param request  stored create request
    /// @param conflict the `SESSION_ALREADY_ACTIVE` failure
    /// @return the retried join result, or `null` when the outcome was already published
    /// @throws IOException when the failure is unrelated or the retried create fails
    private @Nullable Joined recoverConflict(long token, CreateRequest request,
                                             MdtbbsApiException conflict) throws IOException {
        String zombieId = "SESSION_ALREADY_ACTIVE".equals(conflict.getErrorCode())
                ? conflict.detailString("session_id").trim() : "";
        if (zombieId.isEmpty()) {
            throw conflict;
        }
        conflictSessionId = zombieId;
        if (isStale(token)) {
            throw conflict;
        }
        if (!leaveConflict(token, zombieId)) {
            // The cleanup failure is already published; never retry blindly.
            return null;
        }
        if (isStale(token)) {
            throw conflict;
        }
        return requestCreate(request);
    }

    /// Leaves the stale session named by a conflict, publishing real failures.
    ///
    /// Unlike [#leaveQuietly(String)] a failure here is user-visible: the UI
    /// must offer a retry instead of silently creating a second session. A
    /// session that is already gone counts as a successful cleanup.
    ///
    /// @param token       lifecycle token of the create operation
    /// @param sessionId   zombie session id
    /// @return whether the session was left and the create can be retried
    private boolean leaveConflict(long token, String sessionId) {
        try {
            multiplayer.leave(sessionId);
            Logger.LOG.info("MDTBBS stale session " + sessionId + " closed before retrying");
            return true;
        } catch (IOException | RuntimeException e) {
            if (e instanceof MdtbbsApiException apiException && missingSession(apiException)) {
                // Already gone: the account no longer owns a live session, so
                // the cleanup goal is met and the create can be retried.
                Logger.LOG.info("MDTBBS stale session " + sessionId + " was already gone");
                return true;
            }
            if (!isStale(token)) {
                setPhase(Phase.ERROR, "SESSION_ALREADY_ACTIVE", sessionId);
            }
            Logger.LOG.info("MDTBBS stale session " + sessionId
                    + " could not be closed: " + e.getMessage());
            return false;
        }
    }

    /// Whether an API failure means the session no longer exists.
    private static boolean missingSession(MdtbbsApiException e) {
        return e.getStatusCode() == 404
                || "SESSION_NOT_FOUND".equals(e.getErrorCode())
                || "SESSION_EXPIRED".equals(e.getErrorCode());
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
        long token;
        String previous;
        synchronized (approvalLock) {
            token = generation.incrementAndGet();
            previous = detachLocalSession();
            setPhase(Phase.WORKING, null, null);
        }
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
                if (!attachIfCurrent(token, joined, false)) {
                    enqueueCleanup(joined.session().id());
                    return;
                }
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
        long token;
        String previous;
        synchronized (approvalLock) {
            token = generation.incrementAndGet();
            previous = detachLocalSession();
            // Publish the target before the request returns: the approval
            // event can arrive while the HTTP call is still in flight.
            requestedSessionId = id;
            activeJoinRequestId = "";
            pendingApprovedIntent = "";
            approvalInFlight = true;
            approvalRejected = false;
            awaitingApproval = true;
            setPhase(Phase.WORKING, null, null);
        }
        Schedulers.io().execute(() -> {
            try {
                awaitCleanup();
                leaveQuietly(previous);
                requireCapabilities();
                String requestId = multiplayer.createJoinRequest(id);
                // Everything below runs under the same lock that guards
                // generation bumps and detach, so a stale worker can never
                // publish over a newer request.
                String consume = "";
                synchronized (approvalLock) {
                    if (isStale(token)) {
                        return;
                    }
                    if (!awaitingApproval || approvalRejected) {
                        approvalInFlight = false;
                        return;
                    }
                    activeJoinRequestId = requestId;
                    String pending = pendingApprovedIntent;
                    pendingApprovedIntent = "";
                    if (pending.isBlank()) {
                        setPhase(Phase.GUEST_WAITING, null, "approval_pending");
                        // Drain once more while still in flight: an approval
                        // that arrived during the publish is consumed here.
                        pending = pendingApprovedIntent;
                        pendingApprovedIntent = "";
                    } else {
                        setPhase(Phase.GUEST_WAITING, null, null);
                    }
                    approvalInFlight = false;
                    if (!pending.isBlank()) {
                        awaitingApproval = false;
                        consume = pending;
                    }
                }
                if (!consume.isBlank()) {
                    joinByIntent(token, consume);
                }
            } catch (IOException | RuntimeException e) {
                if (!isStale(token)) {
                    synchronized (approvalLock) {
                        if (!isStale(token)) {
                            awaitingApproval = false;
                            approvalInFlight = false;
                            requestedSessionId = "";
                            activeJoinRequestId = "";
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
        long token;
        synchronized (approvalLock) {
            if (approvalInFlight) {
                // The HTTP join-request call (or its phase publish) has not
                // finished; keep the intent so the worker can drain it instead
                // of acknowledging and dropping it.
                pendingApprovedIntent = intentId;
                return;
            }
            if (!awaitingApproval) {
                return;
            }
            awaitingApproval = false;
            token = generation.get();
        }
        joinByIntent(token, intentId);
    }

    /// Approves one join request (host side).
    ///
    /// @param requestId join request id from the realtime event
    public void approveJoinRequest(String requestId) {
        Schedulers.io().execute(() -> {
            try {
                multiplayer.approveJoinRequest(requestId);
                dropPendingJoinRequest(requestId);
                refreshJoinRequests();
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
                dropPendingJoinRequest(requestId);
                refreshJoinRequests();
            } catch (IOException | RuntimeException e) {
                Logger.LOG.info("MDTBBS reject join request failed: " + e.getMessage());
            }
        });
    }

    /// Drops one request from the pending list and invalidates in-flight
    /// snapshots so a pre-removal snapshot cannot land it back.
    private void dropPendingJoinRequest(String requestId) {
        synchronized (joinRequestLock) {
            joinRequestSeenNanos.remove(requestId);
            joinRequests.removeIf(request -> request.id().equals(requestId));
            joinRequestRefreshSequence.incrementAndGet();
        }
        notifyStateChange();
    }

    /// Consumes a Join Intent and joins the bound session as a guest.
    ///
    /// @param intentId intent id from a deep link or an accepted invite
    public void joinByIntent(String intentId) {
        joinByIntent(generation.get(), intentId);
    }

    /// Joins only when the caller still owns the expected lifecycle token.
    ///
    /// Used by the approval handoff: the worker releases the approval lock
    /// before this call, and a newer request/leave invalidates the token so an
    /// old approval cannot start a session after cancellation.
    private void joinByIntent(long expectedToken, String intentId) {
        long token;
        String previous;
        synchronized (approvalLock) {
            if (isStale(expectedToken) || phase == Phase.WORKING) {
                return;
            }
            token = generation.incrementAndGet();
            previous = detachLocalSession();
            setPhase(Phase.WORKING, null, null);
        }
        Schedulers.io().execute(() -> {
            try {
                awaitCleanup();
                leaveQuietly(previous);
                requireCapabilities();
                Joined joined = multiplayer.consumeJoinIntent(intentId);
                if (!attachIfCurrent(token, joined, false)) {
                    enqueueCleanup(joined.session().id());
                    return;
                }
                startGuestTunnel(token);
            } catch (IOException | RuntimeException e) {
                fail(token, e);
            }
        });
    }

    /// Invites one friend into the hosted session.
    ///
    /// A failure is published through [#lastInviteError()] only: a live hosted
    /// session keeps its [`Phase#HOSTING`] banner and tunnel while the invite
    /// dialog explains the problem.
    ///
    /// @param targetUserId forum user id
    public void inviteFriend(long targetUserId) {
        String id = sessionId;
        if (!hosting || id.isEmpty()) {
            lastInviteError = "SESSION_NOT_FOUND";
            lastInviteTarget = Long.toString(targetUserId);
            notifyStateChange();
            return;
        }
        long token = generation.get();
        lastInviteError = null;
        Schedulers.io().execute(() -> {
            try {
                String inviteId = multiplayer.createInvite(id, targetUserId);
                if (isStale(token) || !hosting || !id.equals(sessionId)) {
                    return;
                }
                lastInviteError = null;
                statusDetail = inviteId.isEmpty() ? null : inviteId;
                notifyStateChange();
            } catch (IOException | RuntimeException e) {
                Logger.LOG.info("MDTBBS invite failed: " + e.getMessage());
                if (isStale(token) || !hosting || !id.equals(sessionId)) {
                    return;
                }
                String code = e instanceof MdtbbsApiException apiException
                        ? apiException.getErrorCode() : null;
                // Write the target before the code so a reader that sees the
                // code also sees which attempt it belongs to.
                lastInviteTarget = Long.toString(targetUserId);
                lastInviteError = code == null ? "INVITE_FAILED" : code;
                notifyStateChange();
            }
        });
    }

    /// Leaves the current session and tears the tunnel down.
    public void leave() {
        boolean wasHosting = hosting;
        String previous;
        synchronized (approvalLock) {
            generation.incrementAndGet();
            previous = detachLocalSession();
            setPhase(Phase.IDLE, null, null);
        }
        clearJoinRequests();
        publishActivity();
        if (previous.isEmpty()) {
            return;
        }
        enqueueCleanup(previous);
        Logger.LOG.info("MDTBBS multiplayer session closed" + (wasHosting ? " (host)" : ""));
    }

    /// Stops the tunnel and heartbeat without network calls (launcher exit).
    public void shutdown() {
        synchronized (approvalLock) {
            generation.incrementAndGet();
            detachLocalSession();
        }
        clearJoinRequests();
        closeRealtime();
        scheduler.shutdownNow();
    }

    /// Clears the host-side join-request list and its grace deadlines.
    ///
    /// Runs after the approval lock is released so the two locks are never
    /// nested in the opposite order.
    private void clearJoinRequests() {
        synchronized (joinRequestLock) {
            joinRequests.clear();
            joinRequestSeenNanos.clear();
            joinRequestRefreshSequence.incrementAndGet();
        }
        notifyStateChange();
    }

    /// Closes the active tunnel/heartbeat, clears session state and returns the
    /// previous session id for an optional server-side leave.
    private String detachLocalSession() {
        synchronized (approvalLock) {

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
            requestedSessionId = "";
            pendingApprovedIntent = "";
            approvalInFlight = false;
            approvalRejected = false;
            awaitingApproval = false;
            playerCount = 0;
            maxPlayers = 0;
            lastInviteError = null;
            lastInviteTarget = null;
            return previous;
        }
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
                    if (!detachIfCurrent(token, id)) {
                        return;
                    }
                    setPhase(Phase.ERROR, "SESSION_CLOSED", null);
                    publishActivity();
                    return;
                }
                maxPlayers = session.maxPlayers();
                playerCount = peers.isEmpty() ? session.currentPlayers() : peers.size();
                notifyStateChange();
                refreshJoinRequests();
            } catch (IOException e) {
                Logger.LOG.info("MDTBBS snapshot refresh failed: " + e.getMessage());
            }
        });
    }

    /// Whether an approval/rejection event belongs to the current request.
    ///
    /// The join request id is authoritative when both sides carry it; older
    /// servers that omit it fall back to the session id.
    private boolean matchesJoinRequest(MdtbbsRealtimeEvent event) {
        String eventRequest = event.string("join_request_id");
        if (!eventRequest.isBlank() && !activeJoinRequestId.isBlank()) {
            return eventRequest.equals(activeJoinRequestId);
        }
        String session = event.sessionId();
        return session.isEmpty() || session.equals(requestedSessionId);
    }

    /// Applies one realtime event to the session state.
    void handleRealtimeEvent(MdtbbsRealtimeEvent event) {
        String name = event.event();
        switch (name) {
            case "multiplayer.join_request.created" -> handleJoinRequestCreated(event);
            case "multiplayer.join_request.approved" -> {
                if (hosting) {
                    // The request left the pending list; re-read the host view.
                    refreshJoinRequests();
                    return;
                }
                String intentId = event.string("intent_id");
                if (!intentId.isBlank() && matchesJoinRequest(event)) {
                    onJoinApproved(intentId);
                }
            }
            case "multiplayer.join_request.rejected" -> {
                if (hosting) {
                    refreshJoinRequests();
                    return;
                }
                if (!matchesJoinRequest(event)) {
                    return;
                }
                synchronized (approvalLock) {
                    approvalRejected = true;
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
        if (!hosting) {
            // Guests never own the approval list; do not accumulate entries.
            return;
        }
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
        synchronized (joinRequestLock) {
            joinRequestSeenNanos.put(finalRequestId,
                    nanoClock.getAsLong() + JOIN_REQUEST_GRACE_NANOS);
            joinRequests.removeIf(request -> request.id().equals(finalRequestId));
            joinRequests.add(new JoinRequest(finalRequestId,
                    target.isEmpty() ? sessionId : target, userId, username));
        }
        notifyStateChange();
        refreshJoinRequestsOnEvent();
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

    /// Attaches a joined session only while the operation token is current.
    ///
    /// The staleness re-check and the attach run under the approval lock, so a
    /// concurrent leave or newer operation cannot interleave between them.
    ///
    /// @param token  lifecycle token of the operation
    /// @param joined joined session response
    /// @param owner  whether this client owns the session
    /// @return whether the session was attached
    private boolean attachIfCurrent(long token, Joined joined, boolean owner) {
        synchronized (approvalLock) {
            if (isStale(token)) {
                return false;
            }
            attach(joined, owner);
            return true;
        }
    }

    /// Detaches the local session only while the token and session id match.
    ///
    /// The check and the detach run under the approval lock, so a concurrent
    /// leave or newer operation cannot interleave between them.
    ///
    /// @param token lifecycle token of the operation
    /// @param id    session id the caller observed
    /// @return whether the session was detached
    private boolean detachIfCurrent(long token, String id) {
        synchronized (approvalLock) {
            if (isStale(token) || !id.equals(sessionId)) {
                return false;
            }
            detachLocalSession();
            return true;
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
        lastInviteError = null;
        lastInviteTarget = null;
        // A successful attach consumes the remembered create request; a later
        // retry must not replay it over the live session.
        lastCreateRequest = null;
        if (sessionId.isEmpty() || peerId.isEmpty()) {
            throw new IllegalStateException("MDTBBS session response is incomplete");
        }
        // The caller refreshes right after attaching; remember that so the
        // first heartbeat tick does not issue a duplicate refresh. The event
        // throttle starts expired so the first created event refreshes.
        long now = nanoClock.getAsLong();
        lastJoinRequestRefreshNanos = now;
        lastJoinRequestEventRefreshNanos = now - JOIN_REQUEST_EVENT_THROTTLE_NANOS;
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
        MdtbbsRelayTunnel created = tunnelFactory.create(config, this::renewRelay,
                detail -> updateDetail(token, detail),
                message -> handleTunnelFailure(token, message),
                port -> {
                    // The owner bridge does not expose a local port.
                });
        synchronized (approvalLock) {
            if (isStale(token)) {
                // The user left while the tunnel was being created; close it
                // instead of leaving an orphan behind.
                if (created != null) {
                    created.close();
                }
                return;
            }
            tunnel = created;
            setPhase(Phase.HOSTING, null, null);
        }
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
        int guestPlayers = peers.size();
        MdtbbsRelayTunnel.Config config = new MdtbbsRelayTunnel.Config(
                allocation.endpoint(), allocation.credential(), allocation.allocationId(),
                allocation.agentId(), id, peer, owner, false,
                0, MINDUSTRY_PORT, allocation.expiresInSeconds(),
                activityName, maxPlayers, guestPlayers, lanDiscovery.getAsBoolean());
        MdtbbsRelayTunnel created = tunnelFactory.create(config, this::renewRelay,
                detail -> updateDetail(token, detail),
                message -> handleTunnelFailure(token, message),
                port -> {
                    synchronized (approvalLock) {
                        if (isStale(token) || !id.equals(sessionId)) {
                            return;
                        }
                        localAddress = "127.0.0.1:" + port;
                        setPhase(Phase.GUEST_READY, null, null);
                    }
                });
        synchronized (approvalLock) {
            if (isStale(token)) {
                // The user left while the tunnel was being created; close it
                // instead of leaving an orphan behind.
                if (created != null) {
                    created.close();
                }
                return;
            }
            ownerPeerId = owner;
            playerCount = guestPlayers;
            tunnel = created;
            // A synchronous GUEST_READY callback may already have advanced the
            // phase; never overwrite it with the waiting banner.
            if (phase == Phase.WORKING) {
                setPhase(Phase.GUEST_WAITING, null, null);
            }
        }
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
        refreshJoinRequestsOnHeartbeat();
    }

    /// Refreshes the pending join-request list on the heartbeat tick.
    ///
    /// Runs at most once per minute and reuses the existing heartbeat
    /// scheduler; no extra timer is created.
    private void refreshJoinRequestsOnHeartbeat() {
        if (!hosting) {
            return;
        }
        if (nanoClock.getAsLong() - lastJoinRequestRefreshNanos < JOIN_REQUEST_REFRESH_NANOS) {
            return;
        }
        refreshJoinRequests();
    }

    /// Refreshes the pending list after a realtime create event, throttled.
    ///
    /// A burst of created events must not trigger one full 1–3 page refresh
    /// each; the local insert already keeps the new row visible.
    private void refreshJoinRequestsOnEvent() {
        long now = nanoClock.getAsLong();
        if (now - lastJoinRequestEventRefreshNanos < JOIN_REQUEST_EVENT_THROTTLE_NANOS) {
            return;
        }
        lastJoinRequestEventRefreshNanos = now;
        refreshJoinRequests();
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
        setPhase(Phase.ERROR, code, failureDetail(throwable));
        Logger.LOG.info("MDTBBS multiplayer operation failed: " + throwable.getMessage());
    }

    /// Builds the player-facing detail of a failure, keeping the server's
    /// `retry_after_seconds` hint instead of dropping it.
    ///
    /// `MdtbbsApiException#retryAfterSeconds()` ignores non-primitive or
    /// malformed details, so this never throws on an unexpected payload.
    private static String failureDetail(Throwable throwable) {
        @Nullable String message = throwable.getMessage();
        String text = message == null ? "" : message;
        if (throwable instanceof MdtbbsApiException apiException) {
            long retryAfter = apiException.retryAfterSeconds();
            if (retryAfter > 0L) {
                return text.isBlank()
                        ? "retry after " + retryAfter + "s"
                        : text + " (retry after " + retryAfter + "s)";
            }
        }
        return text;
    }

    /// Whether an async operation was superseded.
    private boolean isStale(long token) {
        return token != generation.get();
    }
}
