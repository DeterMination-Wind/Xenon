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
package determination.xenon.auth.offline;

import determination.xenon.util.gson.UUIDTypeAdapter;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies the identity contract Mindustry relies on: a stable UUID per
/// nickname and a storage round-trip that keeps both values.
public final class OfflineAccountTest {

    @Test
    public void uuidIsStablePerNickname() {
        UUID first = OfflineAccount.uuidFor("Steve");
        UUID second = OfflineAccount.uuidFor("Steve");
        UUID other = OfflineAccount.uuidFor("Alex");

        assertEquals(first, second);
        assertNotEquals(first, other);
    }

    @Test
    public void storageKeepsUsernameAndUuid() {
        OfflineAccount account = new OfflineAccount("Steve", UUID.randomUUID());
        Map<Object, Object> storage = account.toStorage();

        assertEquals("Steve", storage.get("username"));
        assertEquals(account.getUUID(),
                UUIDTypeAdapter.fromString((String) storage.get("uuid")));
    }

    @Test
    public void equalAccountsShareTheSameHashCode() {
        UUID uuid = UUID.randomUUID();
        OfflineAccount first = new OfflineAccount("Steve", uuid);
        OfflineAccount same = new OfflineAccount("Steve", uuid);
        OfflineAccount other = new OfflineAccount("Steve", UUID.randomUUID());

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, other);
    }

    @Test
    public void blankNicknameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> OfflineAccount.fromUsername(" "));
    }
}
