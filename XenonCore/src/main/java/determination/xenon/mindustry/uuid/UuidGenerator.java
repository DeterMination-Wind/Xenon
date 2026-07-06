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

import org.jetbrains.annotations.NotNullByDefault;

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
 * UUID purely as an identifier). Steam's desktop launcher uses a separate
 * deterministic path exposed through {@link #fromSteamAccountId(int)}.</p>
 */
@NotNullByDefault
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
     * Generate the same local Mindustry UID as Steam
     * {@code DesktopLauncher#getUUID()} for one Steam account id.
     *
     * <p>This is the 12-character UID stored in Mindustry's local
     * {@code settings.bin}. It is not the decimal account id Steam lobby
     * servers see after the networking layer rewrites connect packets.</p>
     */
    public static String fromSteamAccountId(int accountId) {
        return ENCODER.encodeToString(ByteBuffer.allocate(UID_BYTES)
                .putLong(firstArcRandLong(accountId))
                .array());
    }

    /**
     * Long overload for callers that carry Steam account ids as unsigned
     * 32-bit values. The value is narrowed to the Java {@code int} shape
     * used by Steamworks before seeding Arc {@code Rand}.
     */
    public static String fromSteamAccountId(long accountId) {
        return fromSteamAccountId((int)accountId);
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

    private static long firstArcRandLong(long seed) {
        long seed0 = murmurHash3(seed == 0L ? Long.MIN_VALUE : seed);
        long seed1 = murmurHash3(seed0);
        long s1 = seed0;
        long s0 = seed1;
        s1 ^= s1 << 23;
        return (s1 ^ s0 ^ (s1 >>> 17) ^ (s0 >>> 26)) + s0;
    }

    private static long murmurHash3(long x) {
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return x;
    }
}
