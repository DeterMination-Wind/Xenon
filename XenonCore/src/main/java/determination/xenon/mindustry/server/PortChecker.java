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
package determination.xenon.mindustry.server;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Small helper for checking whether a TCP port is currently free to bind.
 *
 * <p>Used by the server-instance UI to warn users before they start a
 * server on a port that is already in use.</p>
 */
public final class PortChecker {

    /** Bind/connect timeout for the probe, in milliseconds. */
    private static final int TIMEOUT_MS = 500;

    private PortChecker() {
    }

    /**
     * Try to bind {@code 0.0.0.0:port}. Returns {@code true} if the bind
     * succeeded (and was promptly released), {@code false} otherwise.
     *
     * @param port TCP port number, 1..65535
     */
    public static boolean isPortFree(int port) {
        if (port < 1 || port > 65535) return false;
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.setSoTimeout(TIMEOUT_MS);
            socket.bind(new InetSocketAddress(port), 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
