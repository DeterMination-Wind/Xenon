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

import determination.xenon.util.instance.LauncherInstanceProtocol.Action;
import determination.xenon.util.instance.LauncherInstanceProtocol.Reply;
import determination.xenon.util.instance.LauncherInstanceProtocol.Request;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests the loopback control server, including its failure containment.
@NotNullByDefault
public final class LauncherInstanceServerTest {

    /// An authenticated request is forwarded and answered.
    @Test
    public void servesAuthenticatedRequest() throws Exception {
        AtomicReference<Request> handled = new AtomicReference<>();
        try (LauncherInstanceServer server = new LauncherInstanceServer("secret", request -> {
            handled.set(request);
            return new Reply(Action.FOCUS);
        })) {
            server.start();

            String reply = send(server.localPort(), encodeRequest("secret", "xenon://join?intent=7"));

            assertEquals(new Reply(Action.FOCUS), LauncherInstanceProtocol.decodeReply(reply));
            Request request = handled.get();
            assertNotNull(request);
            assertEquals("1.15.0", request.version());
            assertEquals(List.of("xenon://join?intent=7"), request.args());
        }
    }

    /// A request with the wrong token is ignored.
    @Test
    public void rejectsWrongToken() throws Exception {
        AtomicBoolean handled = new AtomicBoolean();
        try (LauncherInstanceServer server = new LauncherInstanceServer("secret", request -> {
            handled.set(true);
            return new Reply(Action.FOCUS);
        })) {
            server.start();

            String reply = send(server.localPort(), encodeRequest("wrong", "xenon://join?intent=7"));

            assertNull(reply);
            assertFalse(handled.get());
        }
    }

    /// A malformed request line is ignored.
    @Test
    public void ignoresMalformedRequest() throws Exception {
        try (LauncherInstanceServer server = new LauncherInstanceServer("secret",
                request -> new Reply(Action.FOCUS))) {
            server.start();

            String reply = send(server.localPort(), "definitely not json");

            assertNull(reply);
        }
    }

    /// A client dropping the connection does not take the server down.
    @Test
    public void survivesDroppedClient() throws Exception {
        try (LauncherInstanceServer server = new LauncherInstanceServer("secret",
                request -> new Reply(Action.FOCUS))) {
            server.start();

            // Send a valid request, then reset the connection without reading
            // the reply so the write-back fails on the server side.
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(),
                        server.localPort()), 2000);
                socket.setSoLinger(true, 0);
                Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                writer.write(encodeRequest("secret", "xenon://join?intent=1"));
                writer.write('\n');
                writer.flush();
            }

            String reply = send(server.localPort(), encodeRequest("secret", "xenon://join?intent=2"));

            assertEquals(new Reply(Action.FOCUS), LauncherInstanceProtocol.decodeReply(reply));
        }
    }

    /// Closing the server repeatedly is safe.
    @Test
    public void closeIsIdempotent() throws Exception {
        LauncherInstanceServer server = new LauncherInstanceServer("secret",
                request -> new Reply(Action.SPAWN));
        server.start();

        server.close();
        server.close();
    }

    /// Builds a valid request line for the given token.
    private static String encodeRequest(String token, String... args) {
        return LauncherInstanceProtocol.encodeRequest(new Request(token, "1.15.0", 1L, null, List.of(args)));
    }

    /// Sends one line and returns the reply line, or `null` when the server
    /// closes the connection without answering.
    private static @Nullable String send(int port, String line) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            socket.setSoTimeout(5000);
            Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(line);
            writer.write('\n');
            writer.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return reader.readLine();
        }
    }
}
