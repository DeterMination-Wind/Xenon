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

import java.util.Locale;

/// Mindustry release era derived from a vanilla build number.
///
/// The boundaries come from the upstream release names: v4 ends at build 96,
/// v5 spans 97-104, v6 spans 105-126, v7 spans 127-147 and the v8 line starts
/// at build 148. Only vanilla builds share this numbering — Bleeding-Edge and
/// client forks count differently, so they report {@link #UNKNOWN}.
@NotNullByDefault
public enum MindustryEra {

    /// v4.x and earlier; predates the modern mod and schematic ecosystem.
    CLASSIC,

    /// v5.x.
    V5,

    /// v6.x.
    V6,

    /// v7.x.
    V7,

    /// v8.x and later.
    V8,

    /// Unknown build, or a variant that does not use the vanilla numbering.
    UNKNOWN;

    /// Classifies one version row.
    ///
    /// @param variant release variant; only {@link VersionVariant#VANILLA} is classified
    /// @param build   build number, or `0` when unknown
    /// @return the era, or {@link #UNKNOWN} when the row cannot be classified
    public static MindustryEra of(VersionVariant variant, int build) {
        if (variant != VersionVariant.VANILLA || build <= 0) {
            return UNKNOWN;
        }
        if (build <= 96) {
            return CLASSIC;
        }
        if (build <= 104) {
            return V5;
        }
        if (build <= 126) {
            return V6;
        }
        if (build <= 147) {
            return V7;
        }
        return V8;
    }

    /// Whether this era predates the mod and multiplayer ecosystem that
    /// current servers and mods target.
    public boolean isLegacy() {
        return this == CLASSIC || this == V5 || this == V6;
    }

    /// Whether a vanilla build of this number runs on Java 8.
    ///
    /// Builds before 140 predate the Java 17 requirement; newer builds need
    /// Java 17.
    ///
    /// @param build vanilla build number
    /// @return whether Java 8 is the compatible runtime
    public static boolean requiresJava8(int build) {
        return build > 0 && build < 140;
    }

    /// The translation key of the human-readable era label.
    public String i18nKey() {
        return "xenon.mindustry.era." + name().toLowerCase(Locale.ROOT);
    }
}
