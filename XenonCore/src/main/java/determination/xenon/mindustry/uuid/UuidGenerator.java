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
package determination.xenon.mindustry.uuid;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * Random Mindustry-shaped player-UUID generator.
 *
 * <p>Mindustry's wire format for the player UUID is the standard base64
 * encoding (with {@code +/} alphabet and trailing {@code =} padding) of
 * an 8-byte buffer. Encoded that way the UUID is 12 characters total:
 * 11 alphabet bytes followed by exactly one {@code =} pad. This matches
 * what {@code uuidManager} stores and what the engine itself emits in
 * {@code Mindustry.player.uuid}.</p>
 *
 * <p>{@link #fromUuid(UUID)} projects a Java {@link UUID} into the
 * Mindustry shape by keeping the most-significant 8 bytes; the inverse
 * {@link #toUuid(String)} decodes the 8 bytes back and zero-fills the
 * lower half so the round-trip is total but lossy on the
 * least-significant half (intentional — the launcher uses the resulting
 * UUID purely as an identifier).</p>
 */
public final class UuidGenerator {

    private static final SecureRandom RNG = new SecureRandom();
    /** Standard base64 with padding — produces the 12-char {@code …=} form. */
    private static final Base64.Encoder ENCODER = Base64.getEncoder();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    /** Decoded byte length of a valid Mindustry UID. */
    private static final int UID_BYTES = 8;

    private UuidGenerator() {
    }

    /**
     * Generate a fresh 12-character standard-base64 UUID suitable for
     * use as a Mindustry player UUID.
     *
     * @return a never-{@code null} 12-character string ending with {@code =}
     */
    public static String generate() {
        byte[] bytes = new byte[UID_BYTES];
        RNG.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /**
     * Project a {@link UUID} into Mindustry's 12-character standard-base64
     * form. Only the most-significant 8 bytes are preserved.
     */
    public static String fromUuid(UUID uuid) {
        ByteBuffer bb = ByteBuffer.allocate(UID_BYTES);
        bb.putLong(uuid.getMostSignificantBits());
        return ENCODER.encodeToString(bb.array());
    }

    /**
     * Inverse of {@link #fromUuid(UUID)}. Accepts the 12-character
     * standard-base64 form. The decoded 8 bytes become the high half of
     * the returned {@link UUID}; the low half is zero. Throws
     * {@link IllegalArgumentException} on any other shape.
     */
    public static UUID toUuid(String mindustryUid) {
        if (mindustryUid == null) {
            throw new IllegalArgumentException("Mindustry UID is null");
        }
        String trimmed = mindustryUid.trim();
        byte[] bytes = DECODER.decode(trimmed);
        if (bytes.length != UID_BYTES) {
            throw new IllegalArgumentException("Mindustry UID must decode to "
                    + UID_BYTES + " bytes (got " + bytes.length + ")");
        }
        long msb = ByteBuffer.wrap(bytes).getLong();
        return new UUID(msb, 0L);
    }

    /** True iff {@code s} is a syntactically valid Mindustry UID. */
    public static boolean isValid(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        try {
            byte[] bytes = DECODER.decode(t);
            return bytes.length == UID_BYTES;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
