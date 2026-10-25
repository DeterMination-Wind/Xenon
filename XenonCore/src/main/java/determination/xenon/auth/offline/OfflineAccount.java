/*
 * Xenon Launcher
 * Copyright (C) 2021-2026  Xenon contributors
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

import determination.xenon.auth.Account;
import determination.xenon.util.StringUtils;
import determination.xenon.util.gson.UUIDTypeAdapter;

import java.util.Map;
import java.util.UUID;

import static java.util.Objects.requireNonNull;
import static determination.xenon.util.Lang.mapOf;
import static determination.xenon.util.Pair.pair;

/// A local identity: a nickname plus a UUID.
///
/// The UI labels this account type "Mindustry UID". The nickname is injected as
/// `-Dmindustry.player.name` and the UUID is encoded for
/// `-Dmindustry.player.uuid` when the instance launches.
public final class OfflineAccount extends Account {

    private final String username;
    private final UUID uuid;

    public OfflineAccount(String username, UUID uuid) {
        if (StringUtils.isBlank(username)) {
            throw new IllegalArgumentException("Username cannot be blank");
        }
        this.username = requireNonNull(username);
        this.uuid = requireNonNull(uuid);
    }

    /// Creates an account whose UUID is derived from the nickname, matching
    /// the classic offline-player scheme.
    public static OfflineAccount fromUsername(String username) {
        return new OfflineAccount(username, uuidFor(username));
    }

    public static UUID uuidFor(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Override
    public UUID getUUID() {
        return uuid;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getIdentifier() {
        return username + ":" + uuid;
    }

    @Override
    public Map<Object, Object> toStorage() {
        return mapOf(
                pair("uuid", UUIDTypeAdapter.fromUUID(uuid)),
                pair("username", username)
        );
    }
}
