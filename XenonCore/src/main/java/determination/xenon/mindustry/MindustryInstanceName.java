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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.text.Normalizer;
import java.util.Locale;

/// Lenient matching between a user typed instance name and an installed
/// Mindustry instance.
///
/// The CLI accepts whatever a shell script or a human passes as an instance
/// reference: the exact id, the display name, a build number with an optional
/// `v` prefix, full-width characters and collapsed or repeated whitespace.
/// Both sides are normalized before the comparison so `V146`, `v 146`,
/// `ｍｉｎｄｕｓｔｒｙ　146` and `Mindustry 146` all resolve to the same instance
/// while `14` never matches `146`.
@NotNullByDefault
public final class MindustryInstanceName {

    /// Prevents instantiation of this utility class.
    private MindustryInstanceName() {
    }

    /// Normalizes one instance reference for comparison.
    ///
    /// The result is NFKC folded (full-width characters become ASCII),
    /// lower-cased with [Locale#ROOT], whitespace-collapsed to single spaces,
    /// trimmed, and stripped of a leading version marker (`v` followed by a
    /// digit or a space) plus a leading `mindustry` word.
    ///
    /// @param raw user supplied reference, possibly `null`
    /// @return the normalized reference, or an empty string
    public static String normalize(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
        String previous;
        do {
            previous = normalized;
            normalized = stripMindustryPrefix(normalized);
            normalized = stripVersionPrefix(normalized);
        } while (!normalized.equals(previous));
        return normalized;
    }

    /// Tests whether `query` identifies `version`.
    ///
    /// Matching tries, in order: an exact id match, an exact display-name
    /// match, then the normalized forms of both. The normalized comparison
    /// also ignores a leading `mindustry` word on either side.
    ///
    /// @param version installed instance to test, possibly `null`
    /// @param query   user supplied instance reference, possibly `null`
    /// @return `true` when `query` names `version`
    public static boolean matches(@Nullable MindustryVersion version, @Nullable String query) {
        if (version == null || query == null || query.isBlank()) {
            return false;
        }
        String trimmed = query.trim();
        String id = version.getId();
        if (id != null && id.equals(trimmed)) {
            return true;
        }
        String name = version.getName();
        if (name.equals(trimmed)) {
            return true;
        }
        String normalizedQuery = normalize(trimmed);
        if (normalizedQuery.isEmpty()) {
            return false;
        }
        return normalize(name).equals(normalizedQuery);
    }

    /// Removes one leading version marker from an already normalized value.
    ///
    /// A `v` only counts as a marker when it is followed by a digit or a space,
    /// so ordinary names such as `vanilla` keep their first letter.
    ///
    /// @param value normalized reference
    /// @return the value without a leading `v` marker
    private static String stripVersionPrefix(String value) {
        if (value.length() > 1
                && value.charAt(0) == 'v'
                && (Character.isDigit(value.charAt(1)) || value.charAt(1) == ' ')) {
            return value.substring(1).trim();
        }
        return value;
    }

    /// Removes one leading `mindustry` word from an already normalized value.
    ///
    /// @param value normalized reference
    /// @return the value without a leading `mindustry` token
    private static String stripMindustryPrefix(String value) {
        if (value.equals("mindustry")) {
            return "";
        }
        if (value.startsWith("mindustry ")) {
            return value.substring("mindustry ".length()).trim();
        }
        return value;
    }
}
