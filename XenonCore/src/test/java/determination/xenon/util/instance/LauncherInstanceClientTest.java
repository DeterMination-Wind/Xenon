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
package determination.xenon.util.instance;

import determination.xenon.util.instance.LauncherInstanceClient.Decision;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests the instance negotiation decisions, including every fail-open path.
@NotNullByDefault
public final class LauncherInstanceClientTest {

    /// A same-version instance answering FOCUS exits and receives the arguments.
    @Test
    public void sameVersionFocusExitsAndForwardsArguments() throws Exception {
        try (FakeInstance instance = new FakeInstance("1.15.0", 1000L, line -> "{\"action\":\"focus\"}")) {
            Decision decision = LauncherInstanceClient.negotiate(
                    List.of(instance.instance), "1.15.0", List.of("xenon://join?intent=1"), 1500, 5000);

            assertEquals(Decision.EXIT, decision);
            LauncherInstanceProtocol.Request received = LauncherInstanceProtocol.decodeRequest(instance.awaitRequest());
            assertNotNull(received);
            assertEquals(List.of("xenon://join?intent=1"), received.args());
        }
    }

    /// A same-version instance answering SPAWN also makes the starter exit.
    @Test
    public void sameVersionSpawnExits() throws Exception {
        try (FakeInstance instance = new FakeInstance("1.15.0", 1000L, line -> "{\"action\":\"spawn\"}")) {
            Decision decision = LauncherInstanceClient.negotiate(
                    List.of(instance.instance), "1.15.0", List.of(), 1500, 5000);

            assertEquals(Decision.EXIT, decision);
        }
    }

    /// A newer instance of another version opens a new window on SPAWN.
    @Test
    public void differentVersionSpawnStarts() throws Exception {
        try (FakeInstance instance = new FakeInstance("1.16.0", 2000L, line -> "{\"action\":\"spawn\"}")) {
            Decision decision = LauncherInstanceClient.negotiate(
                    List.of(instance.instance), "1.15.0", List.of(), 1500, 5000);

            assertEquals(Decision.START, decision);
        }
    }

    /// A different-version instance answering FOCUS makes the starter exit.
    @Test
    public void differentVersionFocusExits() throws Exception {
        try (FakeInstance instance = new FakeInstance("1.16.0", 2000L, line -> "{\"action\":\"focus\"}")) {
            Decision decision = LauncherInstanceClient.negotiate(
                    List.of(instance.instance), "1.15.0", List.of(), 1500, 5000);

            assertEquals(Decision.EXIT, decision);
        }
    }

    /// A same-version instance is preferred over a newer one of another version.
    @Test
    public void sameVersionIsPreferredOverNewerVersion() throws Exception {
        try (FakeInstance sameVersion = new FakeInstance("1.15.0", 1000L, line -> "{\"action\":\"focus\"}");
             FakeInstance newer = new FakeInstance("1.16.0", 2000L, line -> "{\"action\":\"spawn\"}")) {
            Decision decision = LauncherInstanceClient.negotiate(
                    List.of(sameVersion.instance, newer.instance), "1.15.0", List.of(), 1500, 5000);

            assertEquals(Decision.EXIT, decision);
            assertNotNull(sameVersion.awaitRequest());
            Thread.sleep(150L);
            assertNull(newer.received.get());
        }
    }

    /// Without a same-version instance the newest one is asked.
    @Test
    public void newestVersionIsAskedWithoutSameVersion() throws Exception {
        try (FakeInstance older = new FakeInstance("1.14.0", 1000L, line -> "{\"action\":\"focus\"}");
             FakeInstance newer = new FakeInstance("1.16.0", 2000L, line -> "{\"action\":\"spawn\"}")) {
            Decision decision = LauncherInstanceClient.negotiate(
                    List.of(older.instance, newer.instance), "1.15.0", List.of(), 1500, 5000);

            assertEquals(Decision.START, decision);
            assertNotNull(newer.awaitRequest());
            Thread.sleep(150L);
            assertNull(older.received.get());
        }
    }

    /// With no alive instance at all the launcher starts normally.
    @Test
    public void noAliveInstancesStarts() {
        assertEquals(Decision.START, LauncherInstanceClient.negotiate(List.of(), "1.15.0", List.of()));
    }

    /// A refused connection fails open.
    @Test
    public void refusedConnectionStarts() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        LauncherInstance dead = new LauncherInstance(1L, port, "token", "1.15.0", null, 1L);

        Decision decision = LauncherInstanceClient.negotiate(List.of(dead), "1.15.0", List.of(), 500, 1000);

        assertEquals(Decision.START, decision);
    }

    /// A missing reply times out and fails open.
    @Test
    public void replyTimeoutStarts() throws Exception {
        try (FakeInstance instance = new FakeInstance("1.15.0", 1000L, line -> {
            Thread.sleep(1500L);
            return null;
        })) {
            Decision decision = LauncherInstanceClient.negotiate(
                    List.of(instance.instance), "1.15.0", List.of(), 500, 200);

            assertEquals(Decision.START, decision);
        }
    }

    /// A malformed reply fails open.
    @Test
    public void malformedReplyStarts() throws Exception {
        try (FakeInstance instance = new FakeInstance("1.15.0", 1000L, line -> "not json")) {
            Decision decision = LauncherInstanceClient.negotiate(
                    List.of(instance.instance), "1.15.0", List.of(), 500, 1000);

            assertEquals(Decision.START, decision);
        }
    }

    /// Responder of a fake instance server.
    @FunctionalInterface
    private interface Responder {
        /// Returns the reply line, or `null` to close the connection silently.
        @Nullable String respond(String line) throws Exception;
    }

    /// Loopback server emulating one running launcher instance.
    private static final class FakeInstance implements AutoCloseable {
        /// Registered identity of the fake instance.
        final LauncherInstance instance;

        /// Line received from the client, if any.
        final AtomicReference<String> received = new AtomicReference<>();

        /// Server socket backing the fake instance.
        private final ServerSocket serverSocket;

        /// Thread serving exactly one connection.
        private final Thread thread;

        /// Starts a fake instance that answers one connection.
        FakeInstance(String version, long startedAt, Responder responder) throws IOException {
            this.serverSocket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            this.instance = new LauncherInstance(ProcessHandle.current().pid(), serverSocket.getLocalPort(),
                    "token-" + startedAt, version, null, startedAt);
            this.thread = new Thread(() -> serve(responder), "fake-instance-" + startedAt);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        /// Waits up to two seconds for the client request and returns it.
        @Nullable String awaitRequest() throws InterruptedException {
            thread.join(2000L);
            return received.get();
        }

        /// Serves exactly one client connection.
        private void serve(Responder responder) {
            try (Socket socket = serverSocket.accept()) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line = reader.readLine();
                received.set(line);
                String reply = responder.respond(line);
                if (reply != null) {
                    Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                    writer.write(reply);
                    writer.write('\n');
                    writer.flush();
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // The test closes the server while the thread may still run.
            }
        }

        /// Closes the fake instance server.
        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}
