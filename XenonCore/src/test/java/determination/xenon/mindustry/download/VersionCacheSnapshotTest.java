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
package determination.xenon.mindustry.download;

import determination.xenon.mindustry.VersionVariant;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the bundled offline version snapshots.
@NotNullByDefault
public final class VersionCacheSnapshotTest {

    /// The vanilla snapshot ships with usable rows.
    @Test
    public void vanillaSnapshotLoads() {
        List<MindustryRemoteVersion> versions = VersionCache.loadBundled(VersionVariant.VANILLA);
        assertFalse(versions.isEmpty());
        assertTrue(versions.stream().allMatch(version -> version.getVariant() == VersionVariant.VANILLA));
        assertTrue(versions.stream().allMatch(version -> version.getBuild() > 0));
        assertTrue(versions.stream().anyMatch(version -> "Mindustry.jar".equals(version.getFileName())));
    }

    /// The Bleeding-Edge snapshot ships with usable rows.
    @Test
    public void beSnapshotLoads() {
        List<MindustryRemoteVersion> versions = VersionCache.loadBundled(VersionVariant.BE);
        assertFalse(versions.isEmpty());
        assertTrue(versions.stream().allMatch(version -> version.getBuild() > 0));
        assertTrue(versions.stream().allMatch(version -> version.getDownloadUrl().startsWith("https://")));
    }

    /// Variants without a shipped snapshot return an empty list.
    @Test
    public void variantsWithoutSnapshotAreEmpty() {
        assertTrue(VersionCache.loadBundled(VersionVariant.FOO).isEmpty());
    }
}
