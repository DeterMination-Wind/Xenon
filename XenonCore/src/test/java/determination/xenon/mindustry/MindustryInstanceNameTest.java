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

/// Tests the lenient instance-name matcher used by the CLI.
@NotNullByDefault
public final class MindustryInstanceNameTest {

    /// Normalization folds width, case and whitespace.
    @Test
    public void normalizesWidthCaseAndWhitespace() {
        assertEquals("146", MindustryInstanceName.normalize("  V146  "));
        assertEquals("146", MindustryInstanceName.normalize("v 146"));
        assertEquals("146", MindustryInstanceName.normalize("Mindustry  146"));
        assertEquals("146", MindustryInstanceName.normalize("ｍｉｎｄｕｓｔｒｙ　146"));
        assertEquals("mindustryx 146", MindustryInstanceName.normalize("MindustryX 146"));
        assertEquals("", MindustryInstanceName.normalize(null));
        assertEquals("", MindustryInstanceName.normalize("   "));
    }

    /// Every tolerated spelling resolves to the same instance.
    @Test
    public void matchesEquivalentSpellings() {
        MindustryVersion version = version("146", "146");

        assertTrue(MindustryInstanceName.matches(version, "146"));
        assertTrue(MindustryInstanceName.matches(version, "V146"));
        assertTrue(MindustryInstanceName.matches(version, "v 146"));
        assertTrue(MindustryInstanceName.matches(version, "ｍｉｎｄｕｓｔｒｙ　146"));
        assertTrue(MindustryInstanceName.matches(version, "Mindustry 146"));
        assertTrue(MindustryInstanceName.matches(version, "Mindustry v146"));
        assertTrue(MindustryInstanceName.matches(version, "v Mindustry 146"));
        assertTrue(MindustryInstanceName.matches(version, "ｍｉｎｄｕｓｔｒｙ　ｖ１４６"));
    }

    /// Ids and display names are both accepted.
    @Test
    public void matchesIdAndDisplayName() {
        MindustryVersion version = version("stable-146", "Mindustry 146 Stable");

        assertTrue(MindustryInstanceName.matches(version, "stable-146"));
        assertTrue(MindustryInstanceName.matches(version, "Mindustry 146 Stable"));
        assertTrue(MindustryInstanceName.matches(version, "mindustry 146 stable"));
        assertFalse(MindustryInstanceName.matches(version, "stable"));
    }

    /// Partial numbers never match a longer instance name.
    @Test
    public void doesNotMatchPrefixNumbers() {
        MindustryVersion version = version("146", "146");

        assertFalse(MindustryInstanceName.matches(version, "14"));
        assertFalse(MindustryInstanceName.matches(version, "1466"));
        assertFalse(MindustryInstanceName.matches(version, ""));
        assertFalse(MindustryInstanceName.matches(version, null));
        assertFalse(MindustryInstanceName.matches(null, "146"));
    }

    /// Builds a detached instance with an id and display name.
    private static MindustryVersion version(String id, String name) {
        MindustryVersion version = new MindustryVersion();
        version.setId(id);
        version.setName(name);
        return version;
    }
}
