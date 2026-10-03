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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the MDTBBS relay `MLR1` frame codec.
@NotNullByDefault
public final class MdtbbsRelayFramesTest {

    /// TCP data frames round-trip with their stream id and payload.
    @Test
    public void tcpDataRoundTrip() {
        byte[] payload = {1, 2, 3, 4, 5};
        byte[] frame = MdtbbsRelayFrames.tcpFrame(MdtbbsRelayFrames.TYPE_TCP_DATA, 42, payload, payload.length);

        assertTrue(MdtbbsRelayFrames.hasMagic(frame));
        MdtbbsRelayFrames.Frame parsed = MdtbbsRelayFrames.parse(frame);
        assertNotNull(parsed);
        assertEquals(MdtbbsRelayFrames.TYPE_TCP_DATA, parsed.type());
        assertEquals(42, parsed.streamId());
        assertArrayEquals(payload, parsed.payload());
    }

    /// TCP close frames carry an empty payload and the stream id.
    @Test
    public void tcpCloseRoundTrip() {
        MdtbbsRelayFrames.Frame parsed = MdtbbsRelayFrames.parse(
                MdtbbsRelayFrames.tcpFrame(MdtbbsRelayFrames.TYPE_TCP_CLOSE, 7, new byte[0], 0));
        assertNotNull(parsed);
        assertEquals(MdtbbsRelayFrames.TYPE_TCP_CLOSE, parsed.type());
        assertEquals(7, parsed.streamId());
        assertEquals(0, parsed.length());
    }

    /// UDP frames round-trip with their ASCII target peer id.
    @Test
    public void udpRoundTrip() {
        byte[] payload = "hello".getBytes(StandardCharsets.US_ASCII);
        byte[] frame = MdtbbsRelayFrames.udpFrame("peer_AbC-123", payload);
        assertNotNull(frame);

        MdtbbsRelayFrames.Frame parsed = MdtbbsRelayFrames.parse(frame);
        assertNotNull(parsed);
        assertEquals(MdtbbsRelayFrames.TYPE_UDP_DATAGRAM, parsed.type());
        assertEquals("peer_AbC-123", parsed.targetPeerId());
        assertArrayEquals(payload, parsed.payload());
    }

    /// Malformed frames are rejected instead of parsed.
    @Test
    public void malformedFramesRejected() {
        assertNull(MdtbbsRelayFrames.parse(null));
        assertNull(MdtbbsRelayFrames.parse(new byte[0]));
        assertNull(MdtbbsRelayFrames.parse(new byte[]{1, 2, 3, 4, 1, 1}));
        assertFalse(MdtbbsRelayFrames.hasMagic(new byte[]{77, 76}));

        byte[] wrongVersion = MdtbbsRelayFrames.tcpFrame(MdtbbsRelayFrames.TYPE_TCP_DATA, 1, new byte[]{9}, 1);
        wrongVersion[4] = 2;
        assertNull(MdtbbsRelayFrames.parse(wrongVersion));

        byte[] badUdp = MdtbbsRelayFrames.udpFrame("peer", new byte[]{1});
        assertNotNull(badUdp);
        badUdp[6] = (byte) 200; // peer id length beyond the frame
        assertNull(MdtbbsRelayFrames.parse(badUdp));
    }

    /// UDP frames reject oversized peer ids and payloads.
    @Test
    public void udpFrameLimits() {
        assertNull(MdtbbsRelayFrames.udpFrame("", new byte[]{1}));
        assertNull(MdtbbsRelayFrames.udpFrame("x".repeat(129), new byte[]{1}));
        assertNull(MdtbbsRelayFrames.udpFrame("peer", new byte[0]));
        assertNull(MdtbbsRelayFrames.udpFrame("peer",
                new byte[MdtbbsRelayFrames.MAX_UDP_PAYLOAD + 1]));
        assertNotNull(MdtbbsRelayFrames.udpFrame("peer", new byte[MdtbbsRelayFrames.MAX_UDP_PAYLOAD]));
    }
}
