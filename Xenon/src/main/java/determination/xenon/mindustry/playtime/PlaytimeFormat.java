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
package determination.xenon.mindustry.playtime;

import org.jetbrains.annotations.NotNullByDefault;

import static determination.xenon.util.i18n.I18n.i18n;

/// Shared rendering of recorded playtime durations.
///
/// The playtime model lives in XenonCore and cannot localize text, so every
/// launcher view formats durations through this helper.
@NotNullByDefault
public final class PlaytimeFormat {

    /// Prevents instantiation of this utility class.
    private PlaytimeFormat() {
    }

    /// Formats a duration as hours + minutes, or minutes when under one hour.
    ///
    /// @param millis duration in milliseconds, expected to be non-negative
    /// @return localized duration text such as `2 h 5 min` or `42 min`
    public static String duration(long millis) {
        long hours = millis / 3_600_000;
        long minutes = (millis % 3_600_000) / 60_000;
        if (hours > 0) {
            return i18n("xenon.mindustry.playtime.duration.hm", hours, minutes);
        }
        return i18n("xenon.mindustry.playtime.duration.m", minutes);
    }
}
