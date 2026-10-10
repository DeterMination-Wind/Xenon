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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static determination.xenon.util.logging.Logger.LOG;

/// Loopback control server of one running launcher instance.
///
/// The server listens on an ephemeral loopback port and processes one
/// connection at a time: it reads a single JSON request line, validates the
/// registration token, delegates to a [Handler] and writes back a single JSON
/// reply line. Handlers may block while the launcher UI waits for the user,
/// which is why connections are accepted on a dedicated daemon thread and a
/// blocked request only delays the next one.
///
/// Every failure is contained: malformed requests, wrong tokens, dropped
/// clients and handler exceptions are logged and simply end the connection.
@NotNullByDefault
public final class LauncherInstanceServer implements AutoCloseable {

    /// Handles one authenticated request.
    ///
    /// Implementations may block and must be thread-safe.
    @FunctionalInterface
    public interface Handler {
        /// Returns the reply for the request, or `null` to close silently.
        ///
        /// @param request decoded and authenticated request
        /// @return reply to write back, or `null` to send nothing
        @Nullable LauncherInstanceProtocol.Reply handle(LauncherInstanceProtocol.Request request);
    }

    /// Read timeout of one request line, matching the client reply timeout.
    private static final int REQUEST_READ_TIMEOUT_MILLIS = 60_000;

    /// Registration token accepted by this server.
    private final String token;

    /// Request handler invoked for every authenticated request.
    private final Handler handler;

    /// Bound loopback server socket; the local port is part of the registry
    /// record and must be available before the record is written.
    private final ServerSocket serverSocket;

    /// Set once the server is closed; guards [acceptLoop].
    private final AtomicBoolean closed = new AtomicBoolean();

    /// Accept thread started by [start].
    private @Nullable Thread acceptThread;

    /// Binds an ephemeral loopback port for this instance.
    ///
    /// Binding happens in the constructor so [localPort] can be registered
    /// before [start]; the accept thread itself is started by [start].
    ///
    /// @param token registration token accepted by this server
    /// @param handler handler invoked for authenticated requests
    /// @throws IOException when the loopback port cannot be bound
    public LauncherInstanceServer(String token, Handler handler) throws IOException {
        this.token = Objects.requireNonNull(token);
        this.handler = Objects.requireNonNull(handler);
        this.serverSocket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
    }

    /// Starts accepting connections on a daemon thread.
    ///
    /// Repeated calls are ignored; the server keeps running until [close].
    public synchronized void start() {
        if (acceptThread != null || closed.get()) {
            return;
        }
        acceptThread = new Thread(this::acceptLoop, "xenon-instance-server");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /// Returns the bound loopback port.
    public int localPort() {
        return serverSocket.getLocalPort();
    }

    /// Accepts connections until the server is closed.
    private void acceptLoop() {
        while (!closed.get()) {
            try (Socket socket = serverSocket.accept()) {
                handleConnection(socket);
            } catch (IOException e) {
                if (!closed.get()) {
                    LOG.warning("Launcher instance control connection failed", e);
                }
            } catch (RuntimeException e) {
                if (!closed.get()) {
                    LOG.warning("Unexpected launcher instance control error", e);
                }
            }
        }
    }

    /// Serves exactly one authenticated request.
    private void handleConnection(Socket socket) {
        try {
            socket.setSoTimeout(REQUEST_READ_TIMEOUT_MILLIS);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();
            LauncherInstanceProtocol.Request request = LauncherInstanceProtocol.decodeRequest(line);
            if (request == null) {
                return;
            }
            if (!token.equals(request.token())) {
                LOG.warning("Rejected launcher instance request with an invalid token");
                return;
            }
            LauncherInstanceProtocol.Reply reply = handler.handle(request);
            if (reply == null) {
                return;
            }
            Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(LauncherInstanceProtocol.encodeReply(reply));
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            // A client that drops the connection mid-request is expected and
            // must never take the server down.
            LOG.warning("Launcher instance control request failed", e);
        } catch (RuntimeException e) {
            LOG.warning("Unexpected launcher instance control request error", e);
        }
    }

    /// Stops accepting connections; the method is idempotent.
    ///
    /// The accept thread exits on its own once the socket is closed; pending
    /// requests are not interrupted because a handler may be waiting for the
    /// user and its reply is simply lost.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException e) {
            LOG.warning("Failed to close launcher instance server", e);
        }
    }
}
