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

import determination.xenon.util.io.JarUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static determination.xenon.util.logging.Logger.LOG;

/// Negotiates with a running launcher instance from the newly started process.
///
/// The client prefers an alive instance of the same version, falls back to the
/// most recently started instance of any version, asks it what to do and
/// follows the answer. Every failure or timeout falls back to
/// [Decision#START], so a broken instance channel can never prevent the
/// launcher from starting; only an explicit [LauncherInstanceProtocol.Reply]
/// lets the starting process terminate.
@NotNullByDefault
public final class LauncherInstanceClient {

    /// Decision the newly started process must follow.
    public enum Decision {
        /// Keep starting and register a new instance.
        START,
        /// Focus the running instance and terminate quietly.
        EXIT
    }

    /// Default timeout for connecting to a running instance.
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 1500;

    /// Default timeout for waiting for the instance reply.
    public static final int DEFAULT_REPLY_TIMEOUT_MILLIS = 60_000;

    /// Negotiates with the newest matching instance using the default timeouts.
    ///
    /// @param alive instances that were alive when they were scanned
    /// @param selfVersion version of the starting process
    /// @param args command-line arguments of the starting process
    /// @return decision for the starting process; never `null`
    public static Decision negotiate(List<LauncherInstance> alive, String selfVersion, List<String> args) {
        return negotiate(alive, selfVersion, args,
                DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_REPLY_TIMEOUT_MILLIS);
    }

    /// Negotiates with the newest matching instance.
    ///
    /// A same-version instance is preferred; when none is alive the newest
    /// instance of any version is asked. The starting process exits when the
    /// running instance answers [LauncherInstanceProtocol.Action#FOCUS] or,
    /// on the same-version path, [LauncherInstanceProtocol.Action#SPAWN].
    /// Any unavailable, unanswered or malformed reply starts normally.
    ///
    /// @param alive instances that were alive when they were scanned
    /// @param selfVersion version of the starting process
    /// @param args command-line arguments of the starting process
    /// @param connectTimeoutMillis timeout for opening the loopback connection
    /// @param replyTimeoutMillis timeout for the instance reply
    /// @return decision for the starting process; never `null`
    public static Decision negotiate(List<LauncherInstance> alive, String selfVersion, List<String> args,
                                     int connectTimeoutMillis, int replyTimeoutMillis) {
        Objects.requireNonNull(alive);
        Objects.requireNonNull(selfVersion);
        Objects.requireNonNull(args);
        if (alive.isEmpty()) {
            return Decision.START;
        }

        LauncherInstance sameVersion = newestMatching(alive, selfVersion);
        LauncherInstance target = sameVersion != null ? sameVersion : newest(alive);
        if (target == null) {
            return Decision.START;
        }
        LauncherInstanceProtocol.Reply reply = request(target, selfVersion, args,
                connectTimeoutMillis, replyTimeoutMillis);
        if (reply == null) {
            LOG.warning("No usable reply from launcher instance " + target.pid() + "; starting normally");
            return Decision.START;
        }
        return switch (reply.action()) {
            case FOCUS -> Decision.EXIT;
            case SPAWN -> sameVersion != null ? Decision.EXIT : Decision.START;
        };
    }

    /// Sends one request and returns the decoded reply, or `null` on failure.
    private static @Nullable LauncherInstanceProtocol.Reply request(
            LauncherInstance target, String selfVersion, List<String> args,
            int connectTimeoutMillis, int replyTimeoutMillis) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), target.port()),
                    connectTimeoutMillis);
            socket.setSoTimeout(replyTimeoutMillis);

            Path selfJar = JarUtils.thisJarPath();
            LauncherInstanceProtocol.Request request = new LauncherInstanceProtocol.Request(
                    target.token(), selfVersion, ProcessHandle.current().pid(),
                    selfJar == null ? null : selfJar.toString(), List.copyOf(args));

            Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(LauncherInstanceProtocol.encodeRequest(request));
            writer.write('\n');
            writer.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return LauncherInstanceProtocol.decodeReply(reader.readLine());
        } catch (IOException | RuntimeException e) {
            LOG.warning("Failed to negotiate with launcher instance " + target.pid(), e);
            return null;
        }
    }

    /// Returns the newest alive instance with the given version, if any.
    private static @Nullable LauncherInstance newestMatching(List<LauncherInstance> instances, String version) {
        LauncherInstance newest = null;
        for (LauncherInstance instance : instances) {
            if (instance == null || !version.equals(instance.version())) {
                continue;
            }
            if (newest == null || instance.startedAt() > newest.startedAt()) {
                newest = instance;
            }
        }
        return newest;
    }

    /// Returns the newest alive instance, or `null` when none is usable.
    private static @Nullable LauncherInstance newest(List<LauncherInstance> instances) {
        LauncherInstance newest = null;
        for (LauncherInstance instance : instances) {
            if (instance == null) {
                continue;
            }
            if (newest == null || instance.startedAt() > newest.startedAt()) {
                newest = instance;
            }
        }
        return newest;
    }

    private LauncherInstanceClient() {
    }
}
