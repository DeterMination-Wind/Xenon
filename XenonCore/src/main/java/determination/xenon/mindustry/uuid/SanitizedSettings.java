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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Removes personal identity from a Mindustry `settings.bin` entry set.
///
/// A `.xenon` export is meant to be shared, but `settings.bin` carries the
/// player's persistent UUID, the per-server identity hashes Mindustry stores as
/// `usid-<ip>`, the last server IP, the saved server list and any ban list the
/// player accumulated. Packing those into a shared archive hands the recipient
/// the sender's server identity, which is exactly what a launcher must not do.
///
/// The sanitizer keeps every gameplay setting (keybinds, volume, locale, last
/// campaign, …) and drops only the identity-bearing entries:
///
/// * `uuid` — Arc's player UUID, used by servers to recognise a returning
///   player;
/// * `usid-*` — Mindustry's per-server identity hash;
/// * `ip` — the last server address;
/// * `servers` — the saved-server JSON list;
/// * `bans` / `banned-ips` — local ban lists mirrored into settings by some
///   builds.
///
/// @param values     the filtered entries, in their original order
/// @param removedKeys names of the entries that were dropped
@NotNullByDefault
public record SanitizedSettings(Map<String, Object> values, List<String> removedKeys) {

    /// Defensive copy so callers cannot mutate the result.
    public SanitizedSettings {
        values = new LinkedHashMap<>(values);
        removedKeys = List.copyOf(removedKeys);
    }

    /// Whether any entry had to be removed.
    ///
    /// @return `true` when at least one identity entry was present
    public boolean changed() {
        return !removedKeys.isEmpty();
    }

    /// Filters one settings entry set.
    ///
    /// @param values entries read from `settings.bin`, in file order
    /// @return the kept entries plus the names of the dropped ones
    public static SanitizedSettings filter(Map<String, Object> values) {
        Map<String, Object> kept = new LinkedHashMap<>();
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (isPersonal(entry.getKey())) {
                removed.add(entry.getKey());
            } else {
                kept.put(entry.getKey(), entry.getValue());
            }
        }
        return new SanitizedSettings(kept, removed);
    }

    /// Whether one settings key carries player identity.
    ///
    /// @param key raw settings key
    /// @return `true` when the entry must not leave the machine
    public static boolean isPersonal(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        if (k.startsWith("usid-")) {
            return true;
        }
        return switch (k) {
            case "uuid", "ip", "servers", "bans", "banned-ips", "bannedips" -> true;
            default -> false;
        };
    }
}
