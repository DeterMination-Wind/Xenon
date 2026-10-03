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
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/// Codec for the MDTBBS official relay data channel (`MLR1` frames).
///
/// Binary frames carry tunnelled TCP streams and UDP datagrams between relay
/// peers. Layout: the four magic bytes `MLR1`, one version byte (1), one type
/// byte, then type-specific fields.
///
/// - TCP frames (`1` data / `2` close) continue with a 4-byte stream id;
///   data frames then carry 1..16384 payload bytes.
/// - UDP frames (`3`) continue with a 1-byte target peer id length, the
///   ASCII target peer id (1..128 bytes) and up to 65401 payload bytes.
@NotNullByDefault
public final class MdtbbsRelayFrames {

    /// Frame magic: `MLR1`.
    public static final byte[] MAGIC = {77, 76, 82, 49};

    /// Protocol version carried in every frame.
    public static final byte VERSION = 1;

    /// TCP payload frame.
    public static final int TYPE_TCP_DATA = 1;

    /// TCP stream close frame.
    public static final int TYPE_TCP_CLOSE = 2;

    /// UDP datagram frame.
    public static final int TYPE_UDP_DATAGRAM = 3;

    /// Maximum TCP payload per frame.
    public static final int MAX_TCP_PAYLOAD = 16384;

    /// Maximum UDP payload per frame.
    public static final int MAX_UDP_PAYLOAD = 65401;

    /// Maximum peer id length in a UDP frame.
    public static final int MAX_PEER_ID = 128;

    private MdtbbsRelayFrames() {
    }

    /// One decoded frame.
    ///
    /// @param type         one of the `TYPE_*` constants
    /// @param streamId     TCP stream id, or `0` for UDP frames
    /// @param targetPeerId UDP target peer id, or empty for TCP frames
    /// @param payload      frame payload, possibly empty
    public record Frame(int type, int streamId, String targetPeerId, byte[] payload) {

        /// Returns the payload length.
        public int length() {
            return payload.length;
        }
    }

    /// Encodes one TCP frame.
    ///
    /// @param type     {@link #TYPE_TCP_DATA} or {@link #TYPE_TCP_CLOSE}
    /// @param streamId stream id
    /// @param data     payload buffer
    /// @param length   payload byte count
    /// @return the encoded frame
    public static byte[] tcpFrame(int type, int streamId, byte[] data, int length) {
        byte[] frame = new byte[10 + Math.max(0, length)];
        System.arraycopy(MAGIC, 0, frame, 0, MAGIC.length);
        frame[4] = VERSION;
        frame[5] = (byte) type;
        writeInt(frame, 6, streamId);
        if (length > 0) {
            System.arraycopy(data, 0, frame, 10, length);
        }
        return frame;
    }

    /// Encodes one UDP frame.
    ///
    /// @param targetPeerId destination peer id
    /// @param payload      datagram payload
    /// @return the encoded frame, or `null` when the inputs exceed the limits
    public static @Nullable byte[] udpFrame(String targetPeerId, byte[] payload) {
        if (targetPeerId == null || payload == null) {
            return null;
        }
        byte[] target = targetPeerId.getBytes(StandardCharsets.US_ASCII);
        if (target.length < 1 || target.length > MAX_PEER_ID
                || payload.length < 1 || payload.length > MAX_UDP_PAYLOAD) {
            return null;
        }
        byte[] frame = new byte[7 + target.length + payload.length];
        System.arraycopy(MAGIC, 0, frame, 0, MAGIC.length);
        frame[4] = VERSION;
        frame[5] = (byte) TYPE_UDP_DATAGRAM;
        frame[6] = (byte) target.length;
        System.arraycopy(target, 0, frame, 7, target.length);
        System.arraycopy(payload, 0, frame, 7 + target.length, payload.length);
        return frame;
    }

    /// Decodes one frame.
    ///
    /// @param frame raw frame bytes, or `null`
    /// @return the decoded frame, or `null` when the frame is malformed
    public static @Nullable Frame parse(@Nullable byte[] frame) {
        if (frame == null || frame.length < 6 || !hasMagic(frame) || frame[4] != VERSION) {
            return null;
        }
        int type = frame[5] & 0xFF;
        if (type == TYPE_TCP_DATA || type == TYPE_TCP_CLOSE) {
            if (frame.length < 10) {
                return null;
            }
            int streamId = readInt(frame, 6);
            int length = frame.length - 10;
            if (type == TYPE_TCP_DATA && (length < 1 || length > MAX_TCP_PAYLOAD)) {
                return null;
            }
            if (type == TYPE_TCP_CLOSE && length != 0) {
                return null;
            }
            byte[] payload = Arrays.copyOfRange(frame, 10, frame.length);
            return new Frame(type, streamId, "", payload);
        }
        if (type == TYPE_UDP_DATAGRAM) {
            if (frame.length < 8) {
                return null;
            }
            int peerLength = frame[6] & 0xFF;
            if (peerLength < 1 || peerLength > MAX_PEER_ID || frame.length <= 7 + peerLength) {
                return null;
            }
            String target = new String(frame, 7, peerLength, StandardCharsets.US_ASCII);
            int offset = 7 + peerLength;
            int length = frame.length - offset;
            if (length < 1 || length > MAX_UDP_PAYLOAD) {
                return null;
            }
            byte[] payload = Arrays.copyOfRange(frame, offset, frame.length);
            return new Frame(type, 0, target, payload);
        }
        return null;
    }

    /// Whether the frame starts with the `MLR1` magic.
    ///
    /// @param frame candidate frame
    /// @return whether the magic matches
    public static boolean hasMagic(byte[] frame) {
        if (frame == null || frame.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (frame[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /// Reads a big-endian 32-bit integer.
    private static int readInt(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF) << 24
                | (buffer[offset + 1] & 0xFF) << 16
                | (buffer[offset + 2] & 0xFF) << 8
                | (buffer[offset + 3] & 0xFF);
    }

    /// Writes a big-endian 32-bit integer.
    private static void writeInt(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value >>> 24);
        buffer[offset + 1] = (byte) (value >>> 16);
        buffer[offset + 2] = (byte) (value >>> 8);
        buffer[offset + 3] = (byte) value;
    }
}
