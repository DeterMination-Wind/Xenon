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

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Formats Mindustry build labels for UI rows and release selectors.
@NotNullByDefault
public final class MindustryVersionDisplay {
    private static final Pattern X_OR_B_SUFFIX = Pattern.compile("(?i)[XB](\\d+)\\b");

    private MindustryVersionDisplay() {
    }

    /// Returns a concise build label such as `X34`, `B454`, or `build 146`.
    public static String buildLabel(VersionVariant variant,
                                    int build,
                                    @Nullable String buildType,
                                    String @Nullable ... sources) {
        String channel = detectMindustryXChannel(buildType, sources);
        int parsedBuild = build > 0 ? build : extractMindustryXBuild(sources);
        if (channel != null && parsedBuild > 0) {
            return channel + parsedBuild;
        }
        if (variant == VersionVariant.MINDUSTRY_X) {
            parsedBuild = parsedBuild > 0 ? parsedBuild : extractMindustryXBuild(buildType);
            channel = detectMindustryXChannel(buildType, sources);
            if (channel != null && parsedBuild > 0) {
                return channel + parsedBuild;
            }
        }
        return build > 0 ? "build " + build : "";
    }

    /// Extracts the numeric part of the last `X##` or `B##` token from the given strings.
    public static int extractMindustryXBuild(String @Nullable ... sources) {
        int last = 0;
        if (sources == null) {
            return 0;
        }
        for (String source : sources) {
            if (source == null || source.isBlank()) {
                continue;
            }
            Matcher matcher = X_OR_B_SUFFIX.matcher(source);
            while (matcher.find()) {
                try {
                    last = Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    last = 0;
                }
            }
        }
        return last;
    }

    /// Detects the MindustryX channel marker: `X` for release, `B` for preview.
    public static @Nullable String detectMindustryXChannel(@Nullable String buildType,
                                                           String @Nullable ... sources) {
        Character direct = normalizeChannel(buildType);
        if (direct != null) {
            return String.valueOf(direct);
        }
        if (sources == null) {
            return null;
        }
        Character found = null;
        for (String source : sources) {
            if (source == null || source.isBlank()) {
                continue;
            }
            Matcher matcher = X_OR_B_SUFFIX.matcher(source);
            while (matcher.find()) {
                found = Character.toUpperCase(matcher.group().charAt(0));
            }
            if (found == null && source.toLowerCase(Locale.ROOT).startsWith("prerelease")) {
                found = 'B';
            }
        }
        return found == null ? null : String.valueOf(found);
    }

    private static @Nullable Character normalizeChannel(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.equalsIgnoreCase("X")) {
            return 'X';
        }
        if (text.equalsIgnoreCase("B")) {
            return 'B';
        }
        Matcher matcher = X_OR_B_SUFFIX.matcher(text);
        Character found = null;
        while (matcher.find()) {
            found = Character.toUpperCase(matcher.group().charAt(0));
        }
        return found;
    }
}
