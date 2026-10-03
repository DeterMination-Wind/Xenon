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
package determination.xenon.netplay.mdtbbs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.RelayAllocation;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/// One MDTBBS official relay data-plane connection.
///
/// Implements the `MLR1` tunnel between a relay agent and this peer. The owner
/// side bridges incoming relay streams to the local Mindustry server
/// (`127.0.0.1:<gamePort>`); the guest side opens a loopback TCP/UDP proxy that
/// the local game client can connect to, and forwards its traffic through the
/// relay to the owner peer.
///
/// Lifecycle: construct (starts connecting), callbacks report status and the
/// local proxy port, [#close()] tears everything down. A failed connection or
/// authentication reports once through the failure callback.
@NotNullByDefault
public final class MdtbbsRelayTunnel {

    /// Callback used to renew the relay credential before it expires.
    public interface RelayRenewer {
        /// Requests a fresh allocation for the same session/peer.
        ///
        /// @return the renewed allocation
        /// @throws IOException when the allocation cannot be renewed
        RelayAllocation renew() throws IOException;
    }

    /// Connection settings for one tunnel.
    ///
    /// @param endpoint        `wss://…/relay/v1` endpoint
    /// @param credential      short-lived credential from the allocation
    /// @param allocationId    current allocation id
    /// @param agentId         relay agent id
    /// @param sessionId       session id
    /// @param peerId          this peer id
    /// @param ownerPeerId     owner peer id (required for guests)
    /// @param owner           whether this peer is the session host
    /// @param gamePort        local Mindustry server port bridged by the owner
    /// @param preferredLocalPort preferred guest proxy port, or `0` for random
    /// @param credentialTtlSeconds credential lifetime hint
    public record Config(String endpoint, String credential, String allocationId, String agentId,
                         String sessionId, String peerId, @Nullable String ownerPeerId,
                         boolean owner, int gamePort, int preferredLocalPort,
                         long credentialTtlSeconds) {
    }

    /// How long the WebSocket handshake may take.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);

    /// Renewal is attempted this long before the credential expires.
    private static final long RENEWAL_MARGIN_SECONDS = 20L;

    /// Effective renewal margin; tests shorten it through the package-private setter.
    private static volatile long renewalMarginSeconds = RENEWAL_MARGIN_SECONDS;

    /// Local proxy bind attempts before giving up.
    private static final int PROXY_BIND_ATTEMPTS = 12;

    private final Config config;
    private final Consumer<String> onStatus;
    private final Consumer<String> onFailure;
    private final IntConsumer onGuestReady;
    private final RelayRenewer renewer;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean authenticated = new AtomicBoolean();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xenon-mdtbbs-relay");
        thread.setDaemon(true);
        return thread;
    });

    /// Active TCP streams keyed by relay stream id.
    private final Map<Integer, Socket> tcpStreams = new ConcurrentHashMap<>();

    /// Per-guest UDP sockets used by the owner to reach the local game.
    private final Map<String, DatagramSocket> ownerUdpSockets = new ConcurrentHashMap<>();

    private volatile @Nullable WebSocket socket;
    private volatile @Nullable RelayGuestProxy guestProxy;
    private volatile @Nullable Socket pendingGuestSocket;
    private volatile @Nullable ScheduledFuture<?> renewal;
    private volatile long expiresAtMillis;
    private volatile String allocationId;
    private volatile String pendingRenewalAllocationId = "";

    /// Serialises outgoing WebSocket messages; the JDK allows one send at a time.
    private CompletableFuture<WebSocket> sendChain = CompletableFuture.completedFuture(null);

    /// Accumulates partial text messages.
    private final StringBuilder textBuffer = new StringBuilder();

    /// Accumulates partial binary messages.
    private final java.io.ByteArrayOutputStream binaryBuffer = new java.io.ByteArrayOutputStream();

    /// Creates and starts one tunnel.
    ///
    /// @param config      connection settings
    /// @param renewer     credential renewer
    /// @param onStatus    human-readable status updates
    /// @param onFailure   terminal failure message
    /// @param onGuestReady local proxy port callback for guests
    public MdtbbsRelayTunnel(Config config, RelayRenewer renewer,
                             Consumer<String> onStatus, Consumer<String> onFailure,
                             IntConsumer onGuestReady) {
        this.config = config;
        this.renewer = renewer;
        this.onStatus = onStatus;
        this.onFailure = onFailure;
        this.onGuestReady = onGuestReady;
        this.allocationId = config.allocationId();
        this.expiresAtMillis = System.currentTimeMillis()
                + Math.max(10L, config.credentialTtlSeconds()) * 1000L;
        connect();
    }

    /// The current allocation id.
    public String allocationId() {
        return allocationId;
    }

    /// Overrides the renewal margin for tests.
    ///
    /// @param seconds margin in seconds; the production default is 20
    static void setRenewalMarginSecondsForTests(long seconds) {
        renewalMarginSeconds = seconds;
    }

    /// The local proxy port for guests, or `0` when not ready.
    public int localPort() {
        RelayGuestProxy proxy = guestProxy;
        return proxy == null ? 0 : proxy.port();
    }

    /// Whether the tunnel is still alive.
    public boolean isOpen() {
        return !closed.get();
    }

    /// Tears the tunnel down; safe to call multiple times.
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ScheduledFuture<?> pulse = renewal;
        renewal = null;
        if (pulse != null) {
            pulse.cancel(false);
        }
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "closed");
            } catch (RuntimeException ignored) {
                // The socket may already be broken.
            }
            current.abort();
        }
        RelayGuestProxy proxy = guestProxy;
        guestProxy = null;
        if (proxy != null) {
            proxy.close();
        }
        closeQuietly(pendingGuestSocket);
        pendingGuestSocket = null;
        for (Socket stream : tcpStreams.values()) {
            closeQuietly(stream);
        }
        tcpStreams.clear();
        for (DatagramSocket datagram : ownerUdpSockets.values()) {
            datagram.close();
        }
        ownerUdpSockets.clear();
        worker.shutdownNow();
    }

    /// Opens the WebSocket connection and authenticates.
    private void connect() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            client.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(URI.create(config.endpoint()), new Listener())
                    .whenComplete((webSocket, throwable) -> {
                        if (throwable != null) {
                            fail("MDTBBS Relay 连接失败: " + rootMessage(throwable));
                            return;
                        }
                        socket = webSocket;
                        sendAuth();
                    });
        } catch (RuntimeException e) {
            fail("MDTBBS Relay 地址无效: " + e.getMessage());
        }
    }

    /// Sends the first authentication frame.
    private void sendAuth() {
        JsonObject auth = new JsonObject();
        auth.addProperty("op", "auth");
        auth.addProperty("version", 1);
        auth.addProperty("credential", config.credential());
        sendText(auth.toString());
    }

    /// Handles one complete text message.
    private void handleText(String text) {
        if (closed.get()) {
            return;
        }
        JsonObject message;
        try {
            message = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            fail("MDTBBS Relay 返回了无效响应");
            return;
        }
        String op = string(message, "op");
        switch (op) {
            case "auth_ok" -> handleAuthOk(message);
            case "reauth_ok" -> {
                allocationId = pendingRenewalAllocationId;
                pendingRenewalAllocationId = "";
                scheduleRenewal(string(message, "expires_at"));
            }
            case "tcp_open_pending" -> {
                int streamId = integer(message, "stream_id");
                Socket waiting = pendingGuestSocket;
                if (streamId <= 0 || waiting == null) {
                    fail("MDTBBS Relay TCP 握手无效");
                    return;
                }
                tcpStreams.put(streamId, waiting);
                pendingGuestSocket = null;
            }
            case "tcp_open" -> {
                if (config.owner()) {
                    openOwnerTcp(integer(message, "stream_id"));
                }
            }
            case "tcp_opened" -> {
                int streamId = integer(message, "stream_id");
                Socket stream = tcpStreams.get(streamId);
                if (stream != null) {
                    pumpTcp(stream, streamId);
                }
            }
            case "tcp_open_failed" -> {
                int streamId = integer(message, "stream_id");
                closeQuietly(tcpStreams.remove(streamId));
                pendingGuestSocket = null;
            }
            case "tcp_open_result" -> {
                if (config.owner()) {
                    int streamId = integer(message, "stream_id");
                    boolean ok = message.has("ok") && message.get("ok").getAsBoolean();
                    Socket stream = tcpStreams.get(streamId);
                    if (ok && stream != null) {
                        pumpTcp(stream, streamId);
                    } else if (!ok) {
                        closeQuietly(tcpStreams.remove(streamId));
                    }
                }
            }
            case "error" -> fail("MDTBBS Relay 拒绝连接 (" + string(message, "code") + ")");
            default -> Logger.LOG.debug("Ignoring unknown relay op " + op);
        }
    }

    /// Validates the authentication response and starts the role behaviour.
    private void handleAuthOk(JsonObject message) {
        boolean identityMatches = config.sessionId().equals(string(message, "session_id"))
                && config.peerId().equals(string(message, "peer_id"))
                && (config.owner() ? "owner" : "member").equals(string(message, "peer_role"));
        if (!identityMatches) {
            fail("MDTBBS Relay 身份校验失败");
            return;
        }
        authenticated.set(true);
        scheduleRenewal(string(message, "expires_at"));
        if (config.owner()) {
            onStatus.accept("MDTBBS Relay 房主通道已连接");
            return;
        }
        try {
            RelayGuestProxy proxy = new RelayGuestProxy();
            proxy.start();
            guestProxy = proxy;
            onStatus.accept("MDTBBS Relay 已连接，本地代理端口 " + proxy.port());
            onGuestReady.accept(proxy.port());
        } catch (IOException e) {
            fail("无法创建本地联机代理: " + e.getMessage());
        }
    }

    /// Handles one complete binary frame.
    private void handleBinary(byte[] frame) {
        if (closed.get()) {
            return;
        }
        MdtbbsRelayFrames.Frame parsed = MdtbbsRelayFrames.parse(frame);
        if (parsed == null) {
            fail("MDTBBS Relay 数据帧无效");
            return;
        }
        switch (parsed.type()) {
            case MdtbbsRelayFrames.TYPE_TCP_DATA -> {
                Socket stream = tcpStreams.get(parsed.streamId());
                if (stream == null) {
                    return;
                }
                try {
                    OutputStream output = stream.getOutputStream();
                    output.write(parsed.payload());
                    output.flush();
                } catch (IOException e) {
                    closeTcpStream(parsed.streamId());
                }
            }
            case MdtbbsRelayFrames.TYPE_TCP_CLOSE -> closeQuietly(tcpStreams.remove(parsed.streamId()));
            case MdtbbsRelayFrames.TYPE_UDP_DATAGRAM -> {
                if (config.owner()) {
                    sendToHostGame(parsed.targetPeerId(), parsed.payload());
                } else if (config.peerId().equals(parsed.targetPeerId())) {
                    RelayGuestProxy proxy = guestProxy;
                    if (proxy != null) {
                        proxy.sendToLocalGame(parsed.payload());
                    }
                }
            }
            default -> Logger.LOG.debug("Ignoring unknown relay frame type " + parsed.type());
        }
    }

    /// Owner side: opens one stream to the local Mindustry server.
    private void openOwnerTcp(int streamId) {
        if (streamId <= 0) {
            fail("MDTBBS Relay TCP 流 id 无效");
            return;
        }
        worker.execute(() -> {
            Socket local = new Socket();
            try {
                local.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), config.gamePort()), 5000);
                local.setTcpNoDelay(true);
                tcpStreams.put(streamId, local);
                sendText(streamResultJson(streamId, true));
                pumpTcp(local, streamId);
            } catch (IOException e) {
                closeQuietly(local);
                sendText(streamResultJson(streamId, false));
            }
        });
    }

    /// Pumps one local socket into relay TCP data frames.
    private void pumpTcp(Socket local, int streamId) {
        Thread pump = new Thread(() -> {
            try {
                InputStream input = local.getInputStream();
                byte[] buffer = new byte[MdtbbsRelayFrames.MAX_TCP_PAYLOAD];
                while (!closed.get()) {
                    int count = input.read(buffer);
                    if (count < 0) {
                        break;
                    }
                    sendBinary(MdtbbsRelayFrames.tcpFrame(
                            MdtbbsRelayFrames.TYPE_TCP_DATA, streamId, buffer, count));
                }
            } catch (IOException ignored) {
                // The close frame below reports the stream end.
            } finally {
                closeTcpStream(streamId);
            }
        }, "xenon-mdtbbs-relay-tcp-" + streamId);
        pump.setDaemon(true);
        pump.start();
    }

    /// Closes one stream and tells the peer about it.
    private void closeTcpStream(int streamId) {
        Socket stream = tcpStreams.remove(streamId);
        if (stream != null) {
            closeQuietly(stream);
            sendBinary(MdtbbsRelayFrames.tcpFrame(
                    MdtbbsRelayFrames.TYPE_TCP_CLOSE, streamId, new byte[0], 0));
        }
    }

    /// Owner side: forwards one relayed datagram to the local game server.
    private void sendToHostGame(String guestPeerId, byte[] payload) {
        DatagramSocket datagram = ownerUdpSockets.get(guestPeerId);
        if (datagram == null) {
            try {
                DatagramSocket created = new DatagramSocket();
                created.setSoTimeout(1000);
                DatagramSocket raced = ownerUdpSockets.putIfAbsent(guestPeerId, created);
                if (raced != null) {
                    created.close();
                    datagram = raced;
                } else {
                    datagram = created;
                    startOwnerUdpReader(guestPeerId, created);
                }
            } catch (SocketException e) {
                Logger.LOG.warning("MDTBBS Relay UDP socket unavailable: " + e.getMessage());
                return;
            }
        }
        try {
            datagram.send(new DatagramPacket(payload, payload.length,
                    InetAddress.getLoopbackAddress(), config.gamePort()));
        } catch (IOException e) {
            datagram.close();
            ownerUdpSockets.remove(guestPeerId, datagram);
        }
    }

    /// Owner side: reads local server replies and relays them to the guest.
    private void startOwnerUdpReader(String guestPeerId, DatagramSocket datagram) {
        Thread reader = new Thread(() -> {
            byte[] buffer = new byte[65507];
            while (!closed.get() && !datagram.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    datagram.receive(packet);
                    if (packet.getLength() <= 0 || packet.getLength() > MdtbbsRelayFrames.MAX_UDP_PAYLOAD) {
                        continue;
                    }
                    byte[] payload = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), packet.getOffset(), payload, 0, packet.getLength());
                    byte[] frame = MdtbbsRelayFrames.udpFrame(guestPeerId, payload);
                    if (frame != null) {
                        sendBinary(frame);
                    }
                } catch (SocketTimeoutException ignored) {
                    // Keep waiting.
                } catch (IOException e) {
                    break;
                }
            }
            ownerUdpSockets.remove(guestPeerId, datagram);
            datagram.close();
        }, "xenon-mdtbbs-relay-udp-" + guestPeerId);
        reader.setDaemon(true);
        reader.start();
    }

    /// Renews the relay credential before it expires.
    private void scheduleRenewal(String expiresAt) {
        ScheduledFuture<?> previous = renewal;
        if (previous != null) {
            previous.cancel(false);
        }
        long delaySeconds = Math.max(1L,
                (expiresAtMillis - System.currentTimeMillis()) / 1000L - renewalMarginSeconds);
        renewal = worker.schedule(this::renewAllocation, delaySeconds, TimeUnit.SECONDS);
    }

    /// Performs one renewal round-trip.
    private void renewAllocation() {
        if (closed.get() || !authenticated.get()) {
            return;
        }
        try {
            RelayAllocation next = renewer.renew();
            if (!config.endpoint().equals(next.endpoint()) || !next.agentId().equals(config.agentId())) {
                throw new IOException("Relay allocation changed unexpectedly");
            }
            pendingRenewalAllocationId = next.allocationId();
            expiresAtMillis = System.currentTimeMillis() + Math.max(10L, next.expiresInSeconds()) * 1000L;
            JsonObject reauth = new JsonObject();
            reauth.addProperty("op", "reauth");
            reauth.addProperty("version", 1);
            reauth.addProperty("credential", next.credential());
            sendText(reauth.toString());
        } catch (IOException e) {
            if (expiresAtMillis <= System.currentTimeMillis() + 8000L) {
                fail("MDTBBS Relay 授权已过期");
                return;
            }
            renewal = worker.schedule(this::renewAllocation, 5, TimeUnit.SECONDS);
        }
    }

    /// Builds a `tcp_open_result` message.
    private static String streamResultJson(int streamId, boolean ok) {
        JsonObject message = new JsonObject();
        message.addProperty("op", "tcp_open_result");
        message.addProperty("stream_id", streamId);
        message.addProperty("ok", ok);
        return message.toString();
    }

    /// Serialises one text send onto the WebSocket.
    private void sendText(String text) {
        enqueue(webSocket -> webSocket.sendText(text, true));
    }

    /// Serialises one binary send onto the WebSocket.
    private void sendBinary(byte[] frame) {
        if (frame == null) {
            return;
        }
        enqueue(webSocket -> webSocket.sendBinary(ByteBuffer.wrap(frame), true));
    }

    /// Chains one outgoing message after the previous one.
    private synchronized void enqueue(java.util.function.Function<WebSocket, CompletableFuture<WebSocket>> action) {
        WebSocket current = socket;
        if (current == null || closed.get()) {
            return;
        }
        sendChain = sendChain
                .handle((webSocket, throwable) -> null)
                .thenCompose(ignored -> action.apply(current))
                .exceptionally(throwable -> {
                    fail("MDTBBS Relay 发送失败: " + rootMessage(throwable));
                    return null;
                });
    }

    /// Reports a terminal failure once.
    private void fail(String message) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Logger.LOG.warning("MDTBBS Relay: " + message);
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            current.abort();
        }
        RelayGuestProxy proxy = guestProxy;
        guestProxy = null;
        if (proxy != null) {
            proxy.close();
        }
        closeQuietly(pendingGuestSocket);
        pendingGuestSocket = null;
        for (Socket stream : tcpStreams.values()) {
            closeQuietly(stream);
        }
        tcpStreams.clear();
        for (DatagramSocket datagram : ownerUdpSockets.values()) {
            datagram.close();
        }
        ownerUdpSockets.clear();
        worker.shutdownNow();
        onFailure.accept(message);
    }

    /// Reads a string field with an empty fallback.
    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                ? object.get(key).getAsString() : "";
    }

    /// Reads an integer field with a zero fallback.
    private static int integer(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /// Extracts a short root-cause message.
    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank()
                ? current.getClass().getSimpleName()
                : message;
    }

    /// Closes a socket without propagating errors.
    private static void closeQuietly(@Nullable Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        }
    }

    /// WebSocket listener implementing the relay handshake.
    private final class Listener implements WebSocket.Listener {

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String complete = textBuffer.toString();
                textBuffer.setLength(0);
                handleText(complete);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            binaryBuffer.write(chunk, 0, chunk.length);
            if (last) {
                byte[] complete = binaryBuffer.toByteArray();
                binaryBuffer.reset();
                handleBinary(complete);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!closed.get()) {
                fail("MDTBBS Relay 连接已断开 (" + statusCode + ")");
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!closed.get()) {
                fail("MDTBBS Relay 安全连接失败: " + rootMessage(error));
            }
        }
    }

    /// Loopback TCP/UDP proxy that the guest's game client connects to.
    private final class RelayGuestProxy {
        private final InetAddress loopback = InetAddress.getLoopbackAddress();
        private volatile @Nullable ServerSocket tcpServer;
        private volatile @Nullable DatagramSocket udpSocket;
        private volatile @Nullable InetSocketAddress gameClient;
        private volatile boolean stopped;
        private int port;

        /// Binds the proxy, preferring the configured port.
        void start() throws IOException {
            int attempts = config.preferredLocalPort() > 0 ? PROXY_BIND_ATTEMPTS : PROXY_BIND_ATTEMPTS;
            for (int i = 0; i < attempts; i++) {
                int candidate = i == 0 && config.preferredLocalPort() > 0
                        ? config.preferredLocalPort() : 0;
                ServerSocket tcp = new ServerSocket();
                tcp.setReuseAddress(false);
                DatagramSocket udp = new DatagramSocket(null);
                udp.setReuseAddress(false);
                try {
                    tcp.bind(new InetSocketAddress(loopback, candidate), 4);
                    int boundPort = tcp.getLocalPort();
                    udp.bind(new InetSocketAddress(loopback, boundPort));
                    tcp.setSoTimeout(1000);
                    udp.setSoTimeout(1000);
                    tcpServer = tcp;
                    udpSocket = udp;
                    port = boundPort;
                    Thread accept = new Thread(this::acceptLoop, "xenon-mdtbbs-relay-proxy-tcp");
                    accept.setDaemon(true);
                    accept.start();
                    Thread datagrams = new Thread(this::udpLoop, "xenon-mdtbbs-relay-proxy-udp");
                    datagrams.setDaemon(true);
                    datagrams.start();
                    return;
                } catch (BindException e) {
                    closeServerQuietly(tcp);
                    udp.close();
                } catch (IOException e) {
                    closeServerQuietly(tcp);
                    udp.close();
                    throw e;
                }
            }
            throw new BindException("No free local TCP/UDP port for the relay proxy");
        }

        /// The bound loopback port.
        int port() {
            return port;
        }

        /// Accepts game-client TCP connections and opens relay streams.
        private void acceptLoop() {
            while (!stopped) {
                ServerSocket server = tcpServer;
                if (server == null || server.isClosed()) {
                    return;
                }
                try {
                    Socket accepted = server.accept();
                    accepted.setTcpNoDelay(true);
                    acceptGuestTcp(accepted);
                } catch (SocketTimeoutException ignored) {
                    // Poll the stopped flag.
                } catch (IOException e) {
                    return;
                }
            }
        }

        /// Forwards local UDP datagrams to the owner peer.
        private void udpLoop() {
            byte[] buffer = new byte[65507];
            while (!stopped) {
                DatagramSocket udp = udpSocket;
                if (udp == null || udp.isClosed()) {
                    return;
                }
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udp.receive(packet);
                    if (packet.getLength() < 1 || packet.getLength() > MdtbbsRelayFrames.MAX_UDP_PAYLOAD) {
                        continue;
                    }
                    gameClient = new InetSocketAddress(packet.getAddress(), packet.getPort());
                    byte[] payload = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), packet.getOffset(), payload, 0, packet.getLength());
                    String ownerPeerId = config.ownerPeerId();
                    if (ownerPeerId != null) {
                        byte[] frame = MdtbbsRelayFrames.udpFrame(ownerPeerId, payload);
                        if (frame != null) {
                            sendBinary(frame);
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                    // Poll the stopped flag.
                } catch (IOException e) {
                    return;
                }
            }
        }

        /// Writes one relayed datagram back to the game client.
        void sendToLocalGame(byte[] payload) {
            InetSocketAddress target = gameClient;
            DatagramSocket udp = udpSocket;
            if (target == null || udp == null || udp.isClosed()
                    || payload.length < 1 || payload.length > MdtbbsRelayFrames.MAX_UDP_PAYLOAD) {
                return;
            }
            try {
                udp.send(new DatagramPacket(payload, payload.length, target));
            } catch (IOException ignored) {
                // The client socket may already be gone.
            }
        }

        /// Stops the proxy and closes its sockets.
        void close() {
            stopped = true;
            ServerSocket server = tcpServer;
            tcpServer = null;
            if (server != null) {
                try {
                    server.close();
                } catch (IOException ignored) {
                    // Best effort.
                }
            }
            DatagramSocket udp = udpSocket;
            udpSocket = null;
            if (udp != null) {
                udp.close();
            }
        }

        /// Records one accepted client connection and asks the relay to open it.
        private void acceptGuestTcp(Socket accepted) {
            if (!authenticated.get() || closed.get()) {
                closeQuietly(accepted);
                return;
            }
            Socket previous = pendingGuestSocket;
            if (previous != null) {
                closeQuietly(accepted);
                return;
            }
            pendingGuestSocket = accepted;
            JsonObject open = new JsonObject();
            open.addProperty("op", "tcp_open");
            String ownerPeerId = config.ownerPeerId();
            if (ownerPeerId != null) {
                open.addProperty("target_peer_id", ownerPeerId);
            }
            sendText(open.toString());
        }

        /// Closes a server socket without propagating errors.
        private void closeServerQuietly(ServerSocket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        }
    }
}
