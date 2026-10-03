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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the vanilla build-to-era classification.
@NotNullByDefault
public final class MindustryEraTest {

    /// Builds at the documented release boundaries fall into the right era.
    @Test
    public void eraBoundariesMatchUpstreamReleases() {
        assertEquals(MindustryEra.CLASSIC, MindustryEra.of(VersionVariant.VANILLA, 96));
        assertEquals(MindustryEra.V5, MindustryEra.of(VersionVariant.VANILLA, 97));
        assertEquals(MindustryEra.V5, MindustryEra.of(VersionVariant.VANILLA, 104));
        assertEquals(MindustryEra.V6, MindustryEra.of(VersionVariant.VANILLA, 105));
        assertEquals(MindustryEra.V6, MindustryEra.of(VersionVariant.VANILLA, 126));
        assertEquals(MindustryEra.V7, MindustryEra.of(VersionVariant.VANILLA, 127));
        assertEquals(MindustryEra.V7, MindustryEra.of(VersionVariant.VANILLA, 147));
        assertEquals(MindustryEra.V8, MindustryEra.of(VersionVariant.VANILLA, 148));
        assertEquals(MindustryEra.V8, MindustryEra.of(VersionVariant.VANILLA, 160));
    }

    /// Non-vanilla feeds and unknown builds are not classified.
    @Test
    public void nonVanillaVariantsStayUnknown() {
        assertEquals(MindustryEra.UNKNOWN, MindustryEra.of(VersionVariant.BE, 27965));
        assertEquals(MindustryEra.UNKNOWN, MindustryEra.of(VersionVariant.MINDUSTRY_X, 34));
        assertEquals(MindustryEra.UNKNOWN, MindustryEra.of(VersionVariant.VANILLA, 0));
    }

    /// The legacy flag covers everything before v7.
    @Test
    public void legacyFlagCoversPreV7Eras() {
        assertTrue(MindustryEra.CLASSIC.isLegacy());
        assertTrue(MindustryEra.V5.isLegacy());
        assertTrue(MindustryEra.V6.isLegacy());
        assertFalse(MindustryEra.V7.isLegacy());
        assertFalse(MindustryEra.V8.isLegacy());
    }

    /// Build 140 is the Java 17 cut-off.
    @Test
    public void javaRequirementSwitchesAtBuild140() {
        assertTrue(MindustryEra.requiresJava8(139));
        assertFalse(MindustryEra.requiresJava8(140));
        assertFalse(MindustryEra.requiresJava8(0));
    }
}
