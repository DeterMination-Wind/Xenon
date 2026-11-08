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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the lenient mod-version comparison.
@NotNullByDefault
public final class MindustryModVersionTest {

    /// A leading `v` does not change the version.
    @Test
    public void ignoresLeadingV() {
        assertEquals(0, MindustryModVersion.compare("v1.2.3", "1.2.3"));
        assertEquals(0, MindustryModVersion.compare("V1.2.3", "v1.2.3"));
    }

    /// Numeric runs compare numerically, not lexicographically.
    @Test
    public void comparesNumbersNumerically() {
        assertTrue(MindustryModVersion.compare("1.2.10", "1.2.9") > 0);
        assertTrue(MindustryModVersion.compare("2026.10", "2026.9") > 0);
        assertTrue(MindustryModVersion.compare("1.10", "1.9") > 0);
    }

    /// A pre-release suffix is older than the bare release.
    @Test
    public void prereleaseIsOlderThanRelease() {
        assertTrue(MindustryModVersion.compare("1.3.0-beta", "1.3.0") < 0);
        assertTrue(MindustryModVersion.compare("1.3.0-rc1", "1.3.0") < 0);
        assertTrue(MindustryModVersion.compare("1.3.0-rc2", "1.3.0-rc1") > 0);
        assertTrue(MindustryModVersion.compare("1.3.0-alpha", "1.3.0-beta") < 0);
    }

    /// Build metadata and punctuation differences do not affect equality.
    @Test
    public void ignoresMetadataAndPunctuation() {
        assertEquals(0, MindustryModVersion.compare("1.2.3+build.5", "1.2.3"));
        assertEquals(0, MindustryModVersion.compare("1.2.3-rc.1", "1.2.3-rc1"));
    }

    /// Values without digits cannot be ordered.
    @Test
    public void rejectsNonNumericVersions() {
        assertNull(MindustryModVersion.compare("alpha", "beta"));
        assertNull(MindustryModVersion.compare("1.0", "beta"));
        assertNull(MindustryModVersion.compare(null, "1.0"));
        assertNull(MindustryModVersion.compare("1.0", null));
        assertNull(MindustryModVersion.compare("v", "1.0"));
    }
}
