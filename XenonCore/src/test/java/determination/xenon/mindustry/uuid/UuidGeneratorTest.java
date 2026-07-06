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
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests Mindustry UID generation helpers.
@NotNullByDefault
public final class UuidGeneratorTest {

    /// Steam account ids use the same Arc Rand seed path as DesktopLauncher#getUUID().
    @Test
    public void generatesSteamMindustryUidFromAccountId() {
        assertEquals("KNATdqE4onk=", UuidGenerator.fromSteamAccountId(0));
        assertEquals("NJE9JqfizS8=", UuidGenerator.fromSteamAccountId(1));
        assertEquals("MVBa94jYF3E=", UuidGenerator.fromSteamAccountId(42));
        assertEquals("n1IU8pbuGZ4=", UuidGenerator.fromSteamAccountId(123456789));
        assertEquals("6DA/KFEPKaU=", UuidGenerator.fromSteamAccountId(Integer.MAX_VALUE));
    }

    /// Unsigned-looking long account ids are narrowed to the Steamworks int bit pattern.
    @Test
    public void generatesSteamMindustryUidFromUnsignedLongAccountId() {
        assertEquals("cP+51tLEOLI=", UuidGenerator.fromSteamAccountId(2_147_483_648L));
        assertEquals("Ovbpbzm0OFU=", UuidGenerator.fromSteamAccountId(3_000_000_000L));
        assertEquals("ldE8G2LH7HM=", UuidGenerator.fromSteamAccountId(4_294_967_295L));
    }

    /// The Java UUID projection preserves only the 8 UID bytes and zero-fills the low half.
    @Test
    public void projectsSteamMindustryUidToJavaUuid() {
        String uid = UuidGenerator.fromSteamAccountId(42);

        UUID projected = UuidGenerator.toUuid(uid);

        assertEquals(ByteBuffer.wrap(Base64.getDecoder().decode(uid)).getLong(),
                projected.getMostSignificantBits());
        assertEquals(0L, projected.getLeastSignificantBits());
    }
}
