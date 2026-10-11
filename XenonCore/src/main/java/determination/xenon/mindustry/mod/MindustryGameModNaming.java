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
import org.jetbrains.annotations.Unmodifiable;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Game-faithful mod name handling.
///
/// <p>Mindustry stores a mod's enabled state as the settings key
/// {@code mod-<internalName>-enabled}, where {@code internalName} is built by
/// {@code ModMeta.cleanup()} as
/// {@code Strings.stripColors(name).toLowerCase(Locale.ROOT).replace(" ", "-")}.
/// The launcher must derive the exact same string, otherwise its cold
/// toggles write keys the game never reads.</p>
///
/// <p>{@link #stripColors(CharSequence)} ports Arc's color-tag scanner
/// ({@code arc.util.Strings.stripColors} / {@code parseColorMarkup}) so that
/// names carrying Mindustry color markup produce the same key on both
/// sides.</p>
@NotNullByDefault
public final class MindustryGameModNaming {

    /// Every color name Arc's {@code Colors} map accepts, including the
    /// lowercase variants it registers for each name. {@code stripColors}
    /// only strips tags whose payload is one of these exact strings.
    private static final @Unmodifiable Set<String> COLOR_NAMES = buildColorNames();

    /// Prevents instantiation of this utility class.
    private MindustryGameModNaming() {}

    /// Removes every Mindustry color tag from {@code str}.
    ///
    /// <p>Ports Arc's scanner exactly: {@code [name]} / {@code [#rgb]} tags
    /// with a known color are removed, {@code [[]} escapes a literal bracket,
    /// and {@code []} (pop) is removed as well.</p>
    ///
    /// @param str text that may contain {@code [..]} color markup
    /// @return the text without recognized color tags
    public static String stripColors(CharSequence str) {
        StringBuilder out = new StringBuilder(str.length());
        int i = 0;
        while (i < str.length()) {
            char c = str.charAt(i);
            if (c == '[') {
                int length = parseColorMarkup(str, i + 1, str.length());
                if (length >= 0) {
                    i += length + 2;
                } else {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /// Builds the game's normalized internal name for a raw descriptor name.
    ///
    /// @param rawName the {@code name} field of a mod descriptor; may be `null`
    /// @return {@code stripColors(raw).toLowerCase(ROOT).replace(" ", "-")},
    ///     or an empty string when the name is `null` / blank
    public static String keyName(@Nullable String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "";
        }
        return stripColors(rawName).toLowerCase(Locale.ROOT).replace(" ", "-");
    }

    /// Builds the settings key Mindustry uses for one mod's enabled state.
    ///
    /// @param rawName the {@code name} field of a mod descriptor; may be `null`
    /// @return {@code mod-<keyName>-enabled}, or `null` when no key can be derived
    public static @Nullable String enabledSettingKey(@Nullable String rawName) {
        String name = keyName(rawName);
        return name.isEmpty() ? null : "mod-" + name + "-enabled";
    }

    /// Parses one color tag payload starting after the opening bracket.
    ///
    /// @param str text containing the tag
    /// @param start index of the first payload character
    /// @param end exclusive end of the text
    /// @return payload length when the tag is valid, {@code 0} for the pop
    ///     tag, {@code -1} when invalid, {@code -2} for an escaped {@code [}
    private static int parseColorMarkup(CharSequence str, int start, int end) {
        if (start >= end) return -1;
        switch (str.charAt(start)) {
            case '#':
                // Parse hex color RRGGBBAA where AA is optional and defaults to 0xFF if less
                // than 6 chars are used.
                for (int i = start + 1; i < end; i++) {
                    char ch = str.charAt(i);
                    if (ch == ']') {
                        if (i < start + 2 || i > start + 9) break; // Illegal number of hex digits.
                        return i - start;
                    }
                    if (!(ch >= '0' && ch <= '9' || ch >= 'a' && ch <= 'f' || ch >= 'A' && ch <= 'F')) {
                        break; // Unexpected character in hex color.
                    }
                }
                return -1;
            case '[': // "[[" is an escaped left square bracket.
                return -2;
            case ']': // "[]" is a "pop" color tag.
                return 0;
            default:
                break;
        }
        // Parse named color.
        for (int i = start + 1; i < end; i++) {
            char ch = str.charAt(i);
            if (ch != ']') continue;
            if (!COLOR_NAMES.contains(str.subSequence(start, i).toString())) {
                return -1; // Unknown color name.
            }
            return i - start;
        }
        return -1; // Unclosed color tag.
    }

    /// Builds the accepted color-name set, mirroring Arc's {@code Colors.reset()}.
    private static @Unmodifiable Set<String> buildColorNames() {
        List<String> base = List.of(
                "CLEAR", "BLACK", "WHITE",
                "LIGHT_GRAY", "GRAY", "DARK_GRAY", "LIGHT_GREY", "GREY", "DARK_GREY",
                "BLUE", "NAVY", "ROYAL", "SLATE", "SKY", "CYAN", "TEAL",
                "GREEN", "ACID", "LIME", "FOREST", "OLIVE",
                "YELLOW", "GOLD", "GOLDENROD", "ORANGE",
                "BROWN", "TAN", "BRICK",
                "RED", "SCARLET", "CRIMSON", "CORAL", "SALMON", "PINK", "MAGENTA",
                "PURPLE", "VIOLET", "MAROON");
        Set<String> names = new HashSet<>(base.size() * 2);
        for (String name : base) {
            names.add(name);
            names.add(name.toLowerCase(Locale.ROOT).replace("_", ""));
        }
        return Set.copyOf(names);
    }
}
