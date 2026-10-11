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
package determination.xenon.mindustry.steam;

import determination.xenon.mindustry.uuid.UuidGenerator;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// One Steam account visible in the local Steam client data.
///
/// <p>The Steam build of Mindustry derives its player UUID from the account
/// id: {@code new Rand(SteamID.getAccountID()).nextBytes(8)} encoded as
/// base64. {@link #mindustryUid()} reproduces that value, so importing a
/// Steam account keeps one identity across Steam-launched sessions and
/// Xenon-launched sessions.</p>
///
/// @param steamId64           the 64-bit individual Steam id (`7656…`) as
///                            stored by `loginusers.vdf`, `config.vdf` and
///                            the app manifest `LastOwner` field
/// @param accountId           low 32 bits of `steamId64`; the `int` value
///                            `steamworks.SteamID#getAccountID()` hands to
///                            the game, sign-extended for ids above
///                            {@code 2^31}
/// @param accountName         Steam login name, or `null` when unknown
/// @param personaName         Steam display name, or `null` when unknown
/// @param autoLogin           whether Steam would sign this account in
///                            automatically (`AutoLogin`/`MostRecent`)
/// @param lastOwner           whether this account is the `LastOwner` of the
///                            Mindustry app manifest
/// @param lastLoginTimestamp  epoch seconds of the last recorded sign-in,
///                            or `0` when unknown
@NotNullByDefault
public record SteamAccount(
        long steamId64,
        int accountId,
        @Nullable String accountName,
        @Nullable String personaName,
        boolean autoLogin,
        boolean lastOwner,
        long lastLoginTimestamp
) {

    /// The Mindustry player UUID this Steam account maps to.
    ///
    /// @return the 12-character base64 UID the Steam build sends to servers
    public String mindustryUid() {
        return UuidGenerator.fromSteamAccountId(accountId);
    }

    /// Best available human-readable name for this account.
    ///
    /// @return the persona name, the login name, or the decimal SteamID64
    ///         when neither is known
    public String displayName() {
        if (personaName != null && !personaName.isBlank()) {
            return personaName;
        }
        if (accountName != null && !accountName.isBlank()) {
            return accountName;
        }
        return Long.toString(steamId64);
    }
}
