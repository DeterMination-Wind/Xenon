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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the Mindustry LAN discovery packet encoder and local-probe filter.
@NotNullByDefault
public final class MindustryLanAdvertiserTest {

    /// The encoded packet matches `NetworkIO.readServerData` field by field.
    @Test
    public void encodesServerData() throws Exception {
        MindustryLanAdvertiser.Config config = new MindustryLanAdvertiser.Config(
                "Test Room", "Ground Zero", 3, 8, 6567, 12);
        ByteBuffer buffer = ByteBuffer.wrap(MindustryLanAdvertiser.encodeServerData(config));

        assertEquals("Test Room", readString(buffer));
        assertEquals("Ground Zero", readString(buffer));
        assertEquals(3, buffer.getInt());
        assertEquals(12, buffer.getInt());
        assertEquals(-1, buffer.getInt());
        assertEquals("", readString(buffer));
        assertEquals(0, buffer.get());
        assertEquals(8, buffer.getInt());
        assertEquals("MDTBBS relay room", readString(buffer));
        assertEquals("", readString(buffer));
        assertEquals(6567, Short.toUnsignedInt(buffer.getShort()));
        assertFalse(buffer.hasRemaining());
    }

    /// Long strings are truncated to the Mindustry field limits.
    @Test
    public void truncatesLongStrings() {
        MindustryLanAdvertiser.Config config = new MindustryLanAdvertiser.Config(
                "x".repeat(200), "m".repeat(100), 1, 2, 6567);
        ByteBuffer buffer = ByteBuffer.wrap(MindustryLanAdvertiser.encodeServerData(config));
        assertEquals(100, buffer.get(0));
        assertEquals(64, buffer.get(101));
    }

    /// Only loopback and local interface addresses may receive a reply.
    @Test
    public void filtersRemoteProbes() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        InetAddress local = InetAddress.getByName("192.0.2.10");
        InetAddress remote = InetAddress.getByName("203.0.113.20");
        Set<InetAddress> locals = Set.of(local);

        assertTrue(MindustryLanAdvertiser.isLocalAddress(loopback, locals));
        assertTrue(MindustryLanAdvertiser.isLocalAddress(local, locals));
        assertFalse(MindustryLanAdvertiser.isLocalAddress(remote, locals));
        assertFalse(MindustryLanAdvertiser.isLocalAddress(null, locals));
    }

    /// A local discovery probe receives a decodable reply.
    @Test
    public void answersLocalMulticastProbe() throws Exception {
        MindustryLanAdvertiser advertiser = new MindustryLanAdvertiser(
                new MindustryLanAdvertiser.Config("Probe Room", "Ground Zero", 2, 8, 6567), 0);
        org.junit.jupiter.api.Assumptions.assumeTrue(advertiser.start(),
                "multicast discovery is unavailable on this host");
        try (java.net.DatagramSocket probe = new java.net.DatagramSocket()) {
            probe.setSoTimeout(3000);
            byte[] ping = {1, 2, 3};
            probe.send(new java.net.DatagramPacket(ping, ping.length,
                    InetAddress.getByName(MindustryLanAdvertiser.MULTICAST_GROUP),
                    advertiser.localPort()));
            byte[] buffer = new byte[512];
            java.net.DatagramPacket reply = new java.net.DatagramPacket(buffer, buffer.length);
            probe.receive(reply);
            ByteBuffer decoded = ByteBuffer.wrap(reply.getData(), 0, reply.getLength());
            assertEquals("Probe Room", readString(decoded));
            assertEquals("Ground Zero", readString(decoded));
            assertEquals(2, decoded.getInt());
        } finally {
            advertiser.close();
        }
    }

    /// Reads one length-prefixed UTF-8 string.
    private static String readString(ByteBuffer buffer) {
        int length = Byte.toUnsignedInt(buffer.get());
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
