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

import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.RelayAllocation;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// End-to-end tests for the relay tunnel against a minimal fake relay agent.
///
/// The fake agent implements just enough of RFC 6455 (handshake + text/binary
/// frames) to exercise the real `MdtbbsRelayTunnel` WebSocket client, the TCP
/// and UDP bridging, the owner bridge to a loopback game server, failure
/// reporting and credential renewal.
@NotNullByDefault
public final class MdtbbsRelayTunnelTest {

    /// Guest TCP traffic flows local client -> proxy -> relay -> agent and back.
    @Test
    public void guestProxyForwardsTcpOverRelay() throws Exception {
        try (FakeRelayAgent agent = FakeRelayAgent.start()) {
            AtomicInteger port = new AtomicInteger();
            CountDownLatch ready = new CountDownLatch(1);
            AtomicInteger failures = new AtomicInteger();
            MdtbbsRelayTunnel tunnel = new MdtbbsRelayTunnel(
                    config(agent, false, 0, 0, "peer_member", "peer_owner"),
                    failingRenewer(), status -> {}, message -> failures.incrementAndGet(),
                    recordPort(port, ready));
            try {
                assertNotNull(agent.awaitText(text -> text.contains("auth"), 5000),
                        "tunnel should send the auth frame");
                agent.sendText(authOk(false, "peer_member"));
                assertTrue(ready.await(5, TimeUnit.SECONDS), "guest proxy should become ready");
                assertTrue(port.get() > 0);

                try (Socket client = new Socket()) {
                    client.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port.get()), 3000);
                    client.setTcpNoDelay(true);
                    client.setSoTimeout(5000);
                    client.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush();

                    String open = agent.awaitText(text -> text.contains("tcp_open"), 5000);
                    assertNotNull(open, "proxy should open a relay stream");
                    assertTrue(open.contains("peer_owner"), () -> "unexpected tcp_open: " + open);
                    agent.sendText("{\"op\":\"tcp_open_pending\",\"stream_id\":1}");
                    agent.sendText("{\"op\":\"tcp_opened\",\"stream_id\":1}");

                    byte[] forwarded = agent.awaitBinary(frame -> {
                        MdtbbsRelayFrames.Frame parsed = MdtbbsRelayFrames.parse(frame);
                        return parsed != null
                                && parsed.type() == MdtbbsRelayFrames.TYPE_TCP_DATA
                                && "ping".equals(new String(parsed.payload(), StandardCharsets.UTF_8));
                    }, 5000);
                    assertNotNull(forwarded, "relay should receive the local TCP bytes");

                    agent.sendBinary(MdtbbsRelayFrames.tcpFrame(MdtbbsRelayFrames.TYPE_TCP_DATA, 1,
                            "pong".getBytes(StandardCharsets.UTF_8), 4));
                    byte[] reply = client.getInputStream().readNBytes(4);
                    assertEquals("pong", new String(reply, StandardCharsets.UTF_8));
                }
                assertEquals(0, failures.get(), "no failure expected during the round trip");
            } finally {
                tunnel.close();
            }
        }
    }

    /// The owner bridge connects to the local game server and relays TCP and UDP.
    @Test
    public void ownerBridgeForwardsToLocalGameServer() throws Exception {
        try (ServerSocket gameTcp = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
             DatagramSocket gameUdp = new DatagramSocket(
                     new InetSocketAddress(InetAddress.getLoopbackAddress(), gameTcp.getLocalPort()));
             FakeRelayAgent agent = FakeRelayAgent.start()) {
            gameTcp.setSoTimeout(5000);
            int gamePort = gameTcp.getLocalPort();
            MdtbbsRelayTunnel tunnel = new MdtbbsRelayTunnel(
                    config(agent, true, gamePort, 0, "peer_owner", null),
                    failingRenewer(), status -> {}, message -> {}, port -> {});
            try {
                assertNotNull(agent.awaitText(text -> text.contains("auth"), 5000),
                        "tunnel should send the auth frame");
                agent.sendText(authOk(true, "peer_owner"));
                agent.sendText("{\"op\":\"tcp_open\",\"stream_id\":7}");

                Socket accepted;
                try {
                    accepted = gameTcp.accept();
                } catch (java.net.SocketTimeoutException e) {
                    fail("owner bridge did not connect to the game server");
                    return;
                }
                try (Socket game = accepted) {
                    game.setTcpNoDelay(true);
                    String result = agent.awaitText(text -> text.contains("tcp_open_result"), 5000);
                    assertNotNull(result, "owner should answer the stream handshake");
                    assertTrue(result.contains("\"ok\":true"), () -> "unexpected result: " + result);

                    agent.sendBinary(MdtbbsRelayFrames.tcpFrame(MdtbbsRelayFrames.TYPE_TCP_DATA, 7,
                            "hello".getBytes(StandardCharsets.UTF_8), 5));
                    byte[] toGame = game.getInputStream().readNBytes(5);
                    assertEquals("hello", new String(toGame, StandardCharsets.UTF_8));

                    game.getOutputStream().write("world".getBytes(StandardCharsets.UTF_8));
                    game.getOutputStream().flush();
                    byte[] relayed = agent.awaitBinary(frame -> {
                        MdtbbsRelayFrames.Frame parsed = MdtbbsRelayFrames.parse(frame);
                        return parsed != null
                                && parsed.type() == MdtbbsRelayFrames.TYPE_TCP_DATA
                                && "world".equals(new String(parsed.payload(), StandardCharsets.UTF_8));
                    }, 5000);
                    assertNotNull(relayed, "game server reply should reach the relay");
                }

                agent.sendBinary(MdtbbsRelayFrames.udpFrame("peer_guest",
                        "u1".getBytes(StandardCharsets.UTF_8)));
                gameUdp.setSoTimeout(5000);
                DatagramPacket packet = new DatagramPacket(new byte[64], 64);
                gameUdp.receive(packet);
                assertEquals("u1", new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8));

                gameUdp.send(new DatagramPacket("u2".getBytes(StandardCharsets.UTF_8), 2,
                        packet.getAddress(), packet.getPort()));
                byte[] udp = agent.awaitBinary(frame -> {
                    MdtbbsRelayFrames.Frame parsed = MdtbbsRelayFrames.parse(frame);
                    return parsed != null
                            && parsed.type() == MdtbbsRelayFrames.TYPE_UDP_DATAGRAM
                            && "peer_guest".equals(parsed.targetPeerId())
                            && "u2".equals(new String(parsed.payload(), StandardCharsets.UTF_8));
                }, 5000);
                assertNotNull(udp, "game server datagram should reach the relay");
            } finally {
                tunnel.close();
            }
        }
    }

    /// A relay error reports once and closing twice stays safe.
    @Test
    public void failureReportedOnceAndCloseIsIdempotent() throws Exception {
        try (FakeRelayAgent agent = FakeRelayAgent.start()) {
            AtomicInteger failures = new AtomicInteger();
            MdtbbsRelayTunnel tunnel = new MdtbbsRelayTunnel(
                    config(agent, false, 0, 0, "peer_member", "peer_owner"),
                    failingRenewer(), status -> {}, message -> failures.incrementAndGet(), port -> {});
            assertNotNull(agent.awaitText(text -> text.contains("auth"), 5000),
                    "tunnel should send the auth frame");
            agent.sendText(authOk(false, "peer_member"));
            agent.sendText("{\"op\":\"error\",\"code\":\"TEST_FAILURE\"}");

            assertTrue(await(5000, () -> failures.get() == 1), "failure should be reported exactly once");
            tunnel.close();
            tunnel.close();
            assertEquals(1, failures.get());
        }
    }

    /// Credential renewal asks the renewer and sends a reauth frame.
    @Test
    public void renewalSendsReauth() throws Exception {
        MdtbbsRelayTunnel.setRenewalMarginSecondsForTests(200L);
        try (FakeRelayAgent agent = FakeRelayAgent.start()) {
            AtomicInteger renewals = new AtomicInteger();
            MdtbbsRelayTunnel tunnel = new MdtbbsRelayTunnel(
                    config(agent, false, 0, 0, "peer_member", "peer_owner"),
                    () -> {
                        renewals.incrementAndGet();
                        return new RelayAllocation("rly_2", "agent-1", agent.url(), "cred_2", 120);
                    },
                    status -> {}, message -> {}, port -> {});
            try {
                assertNotNull(agent.awaitText(text -> text.contains("auth"), 5000),
                        "tunnel should send the auth frame");
                agent.sendText(authOk(false, "peer_member"));
                String reauth = agent.awaitText(text -> text.contains("reauth"), 5000);
                assertNotNull(reauth, "renewal should send a reauth frame");
                assertTrue(reauth.contains("cred_2"), () -> "unexpected reauth: " + reauth);
                agent.sendText("{\"op\":\"reauth_ok\",\"expires_at\":\"2099-01-01T00:00:00Z\"}");
                assertTrue(await(5000, () -> "rly_2".equals(tunnel.allocationId())),
                        "new allocation id should be adopted");
                assertTrue(renewals.get() >= 1);
            } finally {
                tunnel.close();
            }
        } finally {
            MdtbbsRelayTunnel.setRenewalMarginSecondsForTests(20L);
        }
    }

    /// Builds a tunnel config for the fake agent.
    private static MdtbbsRelayTunnel.Config config(FakeRelayAgent agent, boolean owner, int gamePort,
                                                   int preferredLocalPort, String peerId,
                                                   @Nullable String ownerPeerId) {
        return new MdtbbsRelayTunnel.Config(agent.url(), "cred_1", "rly_1", "agent-1",
                "ses_1", peerId, ownerPeerId, owner, gamePort, preferredLocalPort, 120);
    }

    /// Renewer that always fails (unused in most tests).
    private static MdtbbsRelayTunnel.RelayRenewer failingRenewer() {
        return () -> {
            throw new IOException("renewal not expected");
        };
    }

    /// Builds a callback that records the guest port.
    private static IntConsumer recordPort(AtomicInteger port, CountDownLatch ready) {
        return value -> {
            port.set(value);
            ready.countDown();
        };
    }

    /// Builds an `auth_ok` frame for the given role.
    private static String authOk(boolean owner, String peerId) {
        return "{\"op\":\"auth_ok\",\"session_id\":\"ses_1\",\"peer_id\":\"" + peerId
                + "\",\"peer_role\":\"" + (owner ? "owner" : "member")
                + "\",\"expires_at\":\"2099-01-01T00:00:00Z\"}";
    }

    /// Polls a condition until it holds or the timeout expires.
    private static boolean await(long timeoutMs, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    /// Minimal RFC 6455 server used as the relay agent.
    private static final class FakeRelayAgent implements AutoCloseable {
        private final ServerSocket server;
        private final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        private final BlockingQueue<byte[]> binaries = new LinkedBlockingQueue<>();
        private volatile @Nullable Socket socket;
        private volatile @Nullable OutputStream out;
        private volatile boolean closed;

        private FakeRelayAgent(ServerSocket server) {
            this.server = server;
        }

        static FakeRelayAgent start() throws IOException {
            ServerSocket server = new ServerSocket();
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            FakeRelayAgent agent = new FakeRelayAgent(server);
            Thread thread = new Thread(agent::serve, "fake-relay-agent");
            thread.setDaemon(true);
            thread.start();
            return agent;
        }

        String url() {
            return "ws://127.0.0.1:" + server.getLocalPort() + "/relay/v1";
        }

        /// Accepts one connection, performs the upgrade and pumps frames.
        private void serve() {
            try {
                Socket accepted = server.accept();
                accepted.setTcpNoDelay(true);
                socket = accepted;
                OutputStream output = accepted.getOutputStream();
                out = output;
                upgrade(accepted.getInputStream(), output);
                readLoop(accepted.getInputStream());
            } catch (IOException ignored) {
                // Closing the agent ends the loop.
            }
        }

        /// Completes the WebSocket upgrade handshake.
        private static void upgrade(InputStream in, OutputStream out) throws IOException {
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int previous = 0;
            int current;
            while ((current = in.read()) >= 0) {
                header.write(current);
                if (current == '\n' && previous == '\r'
                        && header.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                    break;
                }
                previous = current;
            }
            String request = header.toString(StandardCharsets.US_ASCII);
            String key = "";
            for (String line : request.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && "Sec-WebSocket-Key".equalsIgnoreCase(line.substring(0, colon).trim())) {
                    key = line.substring(colon + 1).trim();
                }
            }
            String accept;
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                accept = Base64.getEncoder().encodeToString(digest.digest(
                        (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            } catch (Exception e) {
                throw new IOException("cannot compute the WebSocket accept", e);
            }
            String response = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        /// Reads client frames until the socket closes.
        private void readLoop(InputStream in) throws IOException {
            while (!closed) {
                WsFrame frame = readFrame(in);
                if (frame == null) {
                    return;
                }
                switch (frame.opcode()) {
                    case 0x1 -> texts.add(new String(frame.payload(), StandardCharsets.UTF_8));
                    case 0x2 -> binaries.add(frame.payload());
                    case 0x8 -> {
                        sendClose();
                        return;
                    }
                    case 0x9 -> sendFrame(0xA, frame.payload());
                    default -> {
                        // Ignore pongs and continuation frames (payloads are small).
                    }
                }
            }
        }

        /// Sends one text message.
        synchronized void sendText(String text) throws IOException {
            sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        /// Sends one binary message.
        synchronized void sendBinary(byte[] payload) throws IOException {
            sendFrame(0x2, payload);
        }

        /// Sends a close frame and closes the socket.
        private void sendClose() {
            try {
                sendFrame(0x8, new byte[0]);
            } catch (IOException ignored) {
                // The peer is already gone.
            }
        }

        /// Sends one unmasked server frame.
        private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
            OutputStream output = out;
            if (output == null || closed) {
                return;
            }
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x80 | opcode);
            if (payload.length < 126) {
                frame.write(payload.length);
            } else if (payload.length <= 0xFFFF) {
                frame.write(126);
                frame.write((payload.length >>> 8) & 0xFF);
                frame.write(payload.length & 0xFF);
            } else {
                frame.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    frame.write((int) (((long) payload.length >>> shift) & 0xFF));
                }
            }
            frame.write(payload);
            output.write(frame.toByteArray());
            output.flush();
        }

        /// Reads one client frame (always masked).
        private static @Nullable WsFrame readFrame(InputStream in) throws IOException {
            int first = in.read();
            if (first < 0) {
                return null;
            }
            int second = in.read();
            if (second < 0) {
                return null;
            }
            int opcode = first & 0x0F;
            long length = second & 0x7F;
            if (length == 126) {
                length = (readByte(in) << 8) | readByte(in);
            } else if (length == 127) {
                length = 0;
                for (int i = 0; i < 8; i++) {
                    length = (length << 8) | readByte(in);
                }
            }
            byte[] mask = new byte[4];
            for (int i = 0; i < mask.length; i++) {
                mask[i] = (byte) readByte(in);
            }
            byte[] payload = new byte[(int) length];
            int read = 0;
            while (read < payload.length) {
                int count = in.read(payload, read, payload.length - read);
                if (count < 0) {
                    return null;
                }
                read += count;
            }
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
            return new WsFrame(opcode, payload);
        }

        /// Reads one unsigned byte, failing on EOF.
        private static int readByte(InputStream in) throws IOException {
            int value = in.read();
            if (value < 0) {
                throw new IOException("unexpected end of stream");
            }
            return value;
        }

        /// Waits for a received text message matching `predicate`.
        @Nullable String awaitText(Predicate<String> predicate, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                String text = texts.poll(remaining, TimeUnit.MILLISECONDS);
                if (text == null) {
                    return null;
                }
                if (predicate.test(text)) {
                    return text;
                }
            }
        }

        /// Waits for a received binary message matching `predicate`.
        @Nullable byte[] awaitBinary(Predicate<byte[]> predicate, long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return null;
                }
                byte[] data = binaries.poll(remaining, TimeUnit.MILLISECONDS);
                if (data == null) {
                    return null;
                }
                if (predicate.test(data)) {
                    return data;
                }
            }
        }

        @Override
        public void close() {
            closed = true;
            Socket current = socket;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                    // Best effort.
                }
            }
            try {
                server.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        }

        /// One received WebSocket frame.
        private record WsFrame(int opcode, byte[] payload) {
        }
    }
}
