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
package determination.xenon.mindustry;

import determination.xenon.auth.Account;
import determination.xenon.auth.offline.OfflineAccount;
import determination.xenon.mindustry.uuid.UuidGenerator;
import determination.xenon.mindustry.uuid.UuidProfile;
import determination.xenon.setting.Accounts;

import java.time.Instant;

/**
 * Bridges the launcher's account list (HMCL-style {@link Account}) to the
 * {@link UuidProfile} that {@link MindustryLaunchService} feeds into the
 * {@code -Dmindustry.player.*} JVM properties.
 *
 * <p>Xenon's "offline" account is repurposed as a Mindustry UID profile —
 * see {@code account.methods.offline=Mindustry UID} in the i18n bundle.
 * The user-visible name is the offline username; the UID is the
 * 12-character standard-base64 form of the account UUID's high half,
 * exactly the encoding {@code UuidGenerator#fromUuid} produces.</p>
 */
public final class CurrentPlayerProfile {

    private CurrentPlayerProfile() {}

    /**
     * @return a {@link UuidProfile} for the currently selected offline
     *         account, or {@code null} when no account is selected (or
     *         the selection is a non-offline account that we don't know
     *         how to translate).
     */
    public static UuidProfile current() {
        Account a = Accounts.getSelectedAccount();
        if (!(a instanceof OfflineAccount)) return null;
        OfflineAccount oa = (OfflineAccount) a;
        String name = oa.getUsername();
        if (name == null || name.isBlank()) return null;
        String uid = UuidGenerator.fromUuid(oa.getUUID());
        UuidProfile p = new UuidProfile();
        p.uuid = uid;
        p.nickname = name;
        p.createdAt = Instant.EPOCH;
        p.lastUsedAt = Instant.now();
        p.note = "";
        return p;
    }
}
