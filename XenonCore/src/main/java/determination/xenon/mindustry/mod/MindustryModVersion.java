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
package determination.xenon.mindustry.mod;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Compares the free-form version strings mod descriptors carry.
///
/// Mindustry mods routinely publish tags such as `v1.2.3`, `1.2.10`,
/// `2026.10`, `1.3.0-beta` or `2.0-rc1`. The comparison is deliberately
/// lenient instead of SemVer-strict: it splits the string into digit and
/// letter runs, compares numeric runs as [BigInteger] (so `1.2.10` is newer
/// than `1.2.9`), compares letter runs case-insensitively, and treats a
/// pre-release suffix on the same numeric core as older than the release.
///
/// A value with no digits at all cannot be ordered; callers get `null`
/// instead of a guessed result.
@NotNullByDefault
public final class MindustryModVersion {

    /// Prevents instantiation of this utility class.
    private MindustryModVersion() {
    }

    /// Compares two mod versions.
    ///
    /// @param left  first version, possibly `null`
    /// @param right second version, possibly `null`
    /// @return a negative number when `left` is older, zero when equal, a
    ///     positive number when `left` is newer, or `null` when either value
    ///     has no digits and therefore cannot be ordered
    public static @Nullable Integer compare(@Nullable String left, @Nullable String right) {
        List<String> leftTokens = tokens(left);
        List<String> rightTokens = tokens(right);
        if (leftTokens == null || rightTokens == null) {
            return null;
        }

        int shared = Math.min(leftTokens.size(), rightTokens.size());
        for (int i = 0; i < shared; i++) {
            String leftToken = leftTokens.get(i);
            String rightToken = rightTokens.get(i);
            boolean leftNumber = isNumber(leftToken);
            boolean rightNumber = isNumber(rightToken);
            int compared;
            if (leftNumber && rightNumber) {
                compared = new BigInteger(leftToken).compareTo(new BigInteger(rightToken));
            } else if (leftNumber != rightNumber) {
                // A numeric extension outranks a pre-release word at the same
                // position: 1.2.3.1 > 1.2.3-rc1.
                compared = leftNumber ? 1 : -1;
            } else {
                compared = leftToken.compareTo(rightToken);
            }
            if (compared != 0) {
                return Integer.signum(compared);
            }
        }

        if (leftTokens.size() == rightTokens.size()) {
            return 0;
        }
        if (leftTokens.size() < rightTokens.size()) {
            // The shorter side is the release when the other side continues
            // with a pre-release word, and older when it continues with a
            // numeric component.
            return isNumber(rightTokens.get(leftTokens.size())) ? -1 : 1;
        }
        return isNumber(leftTokens.get(rightTokens.size())) ? 1 : -1;
    }

    /// Splits a version into numeric and lower-cased letter runs.
    ///
    /// Build metadata after `+` is ignored, a leading `v` before a digit is
    /// dropped, and punctuation-only runs are skipped. A value without any
    /// digit yields `null`.
    ///
    /// @param raw version text, possibly `null`
    /// @return ordered tokens, or `null` when the value cannot be ordered
    private static @Nullable List<String> tokens(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > 1
                && (value.charAt(0) == 'v' || value.charAt(0) == 'V')
                && isAsciiDigit(value.charAt(1))) {
            value = value.substring(1);
        }
        int metadata = value.indexOf('+');
        if (metadata >= 0) {
            value = value.substring(0, metadata).trim();
        }
        if (value.isEmpty()) {
            return null;
        }

        List<String> tokens = new ArrayList<>();
        boolean anyDigit = false;
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            int start = index;
            if (isAsciiDigit(current)) {
                while (index < value.length() && isAsciiDigit(value.charAt(index))) {
                    index++;
                }
                tokens.add(value.substring(start, index));
                anyDigit = true;
            } else {
                while (index < value.length() && !isAsciiDigit(value.charAt(index))) {
                    index++;
                }
                String letters = stripNonLetters(value.substring(start, index));
                if (!letters.isEmpty()) {
                    tokens.add(letters.toLowerCase(Locale.ROOT));
                }
            }
        }
        return anyDigit ? tokens : null;
    }

    /// Removes punctuation so `-rc` and `rc` compare the same.
    ///
    /// @param segment one non-numeric run
    /// @return only the letters of the run
    private static String stripNonLetters(String segment) {
        StringBuilder out = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (Character.isLetter(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /// Returns whether a token is a pure ASCII decimal integer.
    ///
    /// @param token one token
    /// @return `true` when every character is an ASCII digit
    private static boolean isNumber(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!isAsciiDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /// Returns whether one character is an ASCII decimal digit.
    ///
    /// @param c character to test
    /// @return `true` for `0` through `9`
    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
