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

import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/// Answers Mindustry LAN discovery probes on this machine.
///
/// Mindustry discovers LAN servers by sending a packet to the multicast group
/// `227.2.7.7:20151`; every local server answers with
/// [NetworkIO#writeServerData]'s packet shape. The guest relay proxy is not a
/// real server, but it speaks the same TCP/UDP game protocol, so advertising it
/// makes the room appear in the client's LAN list and removes the manual
/// "paste 127.0.0.1" step.
///
/// Only probes originating from this machine are answered: a reply to another
/// host on the LAN would advertise a proxy that is not reachable from there
/// and would leak the private relay session. The advertised address is the
/// source address of the reply, which Mindustry takes from the packet, so the
/// guest proxy listens on all local addresses (loopback included).
///
/// Starting is best-effort. When `20151` is taken (for example by a real
/// Mindustry server) or multicast is unavailable, [#start()] returns `false`
/// and the caller keeps the copyable loopback address as a fallback.
@NotNullByDefault
public final class MindustryLanAdvertiser implements AutoCloseable {

    /// Mindustry's LAN discovery multicast group.
    public static final String MULTICAST_GROUP = "227.2.7.7";

    /// Mindustry's LAN discovery multicast port.
    public static final int MULTICAST_PORT = 20151;

    /// Version marker read by the client; `-1` is older than any real build,
    /// so the client never rejects the room as "server is newer".
    public static final int ADVERTISED_VERSION = -1;

    /// One advertisement.
    ///
    /// @param name       room name shown in the LAN list
    /// @param mapName    map name shown in the LAN list
    /// @param players    current player count
    /// @param maxPlayers player limit
    /// @param gamePort   port the client should connect to (the guest proxy)
    /// @param wave       wave number shown in the LAN list
    public record Config(String name, String mapName, int players, int maxPlayers,
                         int gamePort, int wave) {

        /// Builds an advertisement for wave zero.
        ///
        /// @param name       room name
        /// @param mapName    map name
        /// @param players    current player count
        /// @param maxPlayers player limit
        /// @param gamePort   proxy port
        public Config(String name, String mapName, int players, int maxPlayers, int gamePort) {
            this(name, mapName, players, maxPlayers, gamePort, 0);
        }
    }

    private final Config config;
    private final int port;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean running;
    private volatile @Nullable MulticastSocket multicast;
    private volatile @Nullable DatagramSocket responder;
    private volatile @Nullable Thread thread;
    private volatile Set<InetAddress> localAddresses = Set.of();

    /// Creates an advertiser for the production multicast port.
    ///
    /// @param config advertisement contents
    public MindustryLanAdvertiser(Config config) {
        this(config, MULTICAST_PORT);
    }

    /// Creates an advertiser on an explicit port (tests).
    ///
    /// @param config advertisement contents
    /// @param port   multicast port, or `0` for an ephemeral port
    MindustryLanAdvertiser(Config config, int port) {
        this.config = config;
        this.port = port;
    }

    /// Starts listening for local discovery probes.
    ///
    /// @return `true` when the advertiser is listening; `false` when the port
    ///         or multicast group is unavailable
    public synchronized boolean start() {
        if (running) {
            return true;
        }
        if (closed.get()) {
            return false;
        }
        MulticastSocket socket = null;
        DatagramSocket reply = null;
        try {
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            socket.setSoTimeout(1000);
            socket.joinGroup(InetAddress.getByName(MULTICAST_GROUP));
            // A wildcard-bound reply socket can reach any local probe address;
            // wherever it lands, the guest proxy listens there as well.
            reply = new DatagramSocket(new InetSocketAddress(0));
            localAddresses = collectLocalAddresses();
            multicast = socket;
            responder = reply;
            running = true;
            Thread worker = new Thread(this::listen, "xenon-mindustry-lan-advertiser");
            worker.setDaemon(true);
            thread = worker;
            worker.start();
            return true;
        } catch (IOException | RuntimeException e) {
            closeQuietly(socket);
            closeQuietly(reply);
            Logger.LOG.info("Mindustry LAN advertisement disabled: " + e.getMessage());
            return false;
        }
    }

    /// Whether the listener is active.
    public boolean isRunning() {
        return running;
    }

    /// The advertised port.
    public int advertisedPort() {
        return config.gamePort();
    }

    /// The bound discovery port, or `0` when not listening.
    public int localPort() {
        MulticastSocket socket = multicast;
        return socket == null ? 0 : socket.getLocalPort();
    }

    /// Stops the listener.
    @Override
    public void close() {
        closed.set(true);
        running = false;
        Thread worker = thread;
        thread = null;
        if (worker != null) {
            worker.interrupt();
        }
        MulticastSocket socket = multicast;
        multicast = null;
        if (socket != null) {
            try {
                socket.leaveGroup(InetAddress.getByName(MULTICAST_GROUP));
            } catch (IOException | RuntimeException ignored) {
                // The socket may already be closed.
            }
            closeQuietly(socket);
        }
        DatagramSocket reply = responder;
        responder = null;
        closeQuietly(reply);
    }

    /// Receives discovery probes until [#close()].
    private void listen() {
        byte[] buffer = new byte[512];
        while (running) {
            MulticastSocket socket = multicast;
            DatagramSocket reply = responder;
            if (socket == null || reply == null || socket.isClosed()) {
                return;
            }
            DatagramPacket probe = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(probe);
            } catch (SocketTimeoutException ignored) {
                continue;
            } catch (IOException e) {
                if (running) {
                    Logger.LOG.info("Mindustry LAN advertisement stopped: " + e.getMessage());
                }
                running = false;
                return;
            }
            if (!isLocalAddress(probe.getAddress(), localAddresses)) {
                continue;
            }
            byte[] payload = encodeServerData(config);
            try {
                reply.send(new DatagramPacket(payload, payload.length,
                        probe.getAddress(), probe.getPort()));
            } catch (IOException e) {
                // A single failed reply must not stop the listener.
                Logger.LOG.debug("Mindustry LAN reply failed: " + e.getMessage());
            }
        }
    }

    /// Encodes one Mindustry `writeServerData` packet.
    ///
    /// The layout matches `mindustry.net.NetworkIO#writeServerData`:
    /// name (100), map (64), players, wave, version, version type, gamemode
    /// ordinal, player limit, description (100), mode name (50), port.
    ///
    /// @param config advertisement contents
    /// @return the encoded packet
    public static byte[] encodeServerData(Config config) {
        ByteBuffer buffer = ByteBuffer.allocate(500);
        writeString(buffer, config.name(), 100);
        writeString(buffer, config.mapName(), 64);
        buffer.putInt(Math.max(0, config.players()));
        buffer.putInt(Math.max(0, config.wave()));
        buffer.putInt(ADVERTISED_VERSION);
        writeString(buffer, "", 32);
        buffer.put((byte) 0); // Gamemode.survival ordinal
        buffer.putInt(Math.max(0, config.maxPlayers()));
        writeString(buffer, "MDTBBS relay room", 100);
        writeString(buffer, "", 50);
        buffer.putShort((short) Math.max(0, Math.min(65535, config.gamePort())));
        byte[] packet = new byte[buffer.position()];
        buffer.flip();
        buffer.get(packet);
        return packet;
    }

    /// Writes a length-prefixed UTF-8 string, truncating like Mindustry does.
    private static void writeString(ByteBuffer buffer, String value, int maxLength) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxLength) {
            byte[] truncated = new byte[maxLength];
            System.arraycopy(bytes, 0, truncated, 0, maxLength);
            bytes = truncated;
        }
        buffer.put((byte) bytes.length);
        buffer.put(bytes);
    }

    /// Whether a probe address belongs to this machine.
    ///
    /// @param address probe source address
    /// @param locals  addresses collected when the listener started
    /// @return whether the address is local or a loopback address
    static boolean isLocalAddress(@Nullable InetAddress address, Set<InetAddress> locals) {
        if (address == null) {
            return false;
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        return locals.contains(address);
    }

    /// Collects every address bound to this machine.
    private static Set<InetAddress> collectLocalAddresses() {
        Set<InetAddress> result = new HashSet<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp()) {
                    continue;
                }
                result.addAll(Collections.list(network.getInetAddresses()));
            }
        } catch (SocketException e) {
            Logger.LOG.debug("Cannot enumerate local interfaces: " + e.getMessage());
        }
        return Set.copyOf(result);
    }

    /// Closes a socket without propagating errors.
    private static void closeQuietly(@Nullable DatagramSocket socket) {
        if (socket != null) {
            socket.close();
        }
    }
}
