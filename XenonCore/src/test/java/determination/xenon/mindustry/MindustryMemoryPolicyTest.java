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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the mod-size-aware heap policy.
@NotNullByDefault
public final class MindustryMemoryPolicyTest {

    /// Only enabled mod archives contribute to the measured size.
    @Test
    public void measureCountsEnabledArchivesOnly(@TempDir Path tempDir) throws IOException {
        Path mods = tempDir.resolve("mods");
        Files.createDirectories(mods);
        Files.write(mods.resolve("a.jar"), new byte[1000]);
        Files.write(mods.resolve("b.zip"), new byte[500]);
        Files.write(mods.resolve("c.jar.disabled"), new byte[4000]);
        Files.write(mods.resolve("notes.txt"), new byte[8000]);

        assertEquals(1500L, MindustryMemoryPolicy.measureEnabledModsBytes(tempDir));
    }

    /// A missing data directory measures zero instead of failing.
    @Test
    public void measureMissingDirectoryIsZero(@TempDir Path tempDir) {
        assertEquals(0L, MindustryMemoryPolicy.measureEnabledModsBytes(tempDir.resolve("nope")));
        assertEquals(0L, MindustryMemoryPolicy.measureEnabledModsBytes(null));
    }

    /// The suggestion starts from a quarter of RAM and grows with mods.
    @Test
    public void suggestionGrowsWithMods() {
        long light = MindustryMemoryPolicy.suggestHeapMb(16384, 8192, 0);
        long heavy = MindustryMemoryPolicy.suggestHeapMb(16384, 8192, 600L * 1024 * 1024);
        assertEquals(4096L, light);
        assertEquals(4096L + 1280L, heavy);
    }

    /// The suggestion never exceeds available memory minus the reserve.
    @Test
    public void suggestionRespectsAvailableMemory() {
        long suggestion = MindustryMemoryPolicy.suggestHeapMb(8192, 2048, 4L * 1024 * 1024 * 1024);
        assertEquals(2048L, suggestion);
    }

    /// Unknown memory falls back to the mod-aware sum instead of a cap.
    @Test
    public void suggestionWithoutMemoryInfoKeepsBasePlusMods() {
        long suggestion = MindustryMemoryPolicy.suggestHeapMb(0, 0, 100L * 1024 * 1024);
        assertTrue(suggestion >= MindustryMemoryPolicy.MINIMUM_HEAP_MB);
        assertEquals(1024L + 256L, suggestion);
    }

    /// Explicit heap arguments are detected so the policy can step aside.
    @Test
    public void explicitHeapLimitIsDetected() {
        assertTrue(MindustryMemoryPolicy.hasExplicitHeapLimit(List.of("-Xmx4G")));
        assertTrue(MindustryMemoryPolicy.hasExplicitHeapLimit(List.of("-XX:MaxHeapSize=2g")));
        assertFalse(MindustryMemoryPolicy.hasExplicitHeapLimit(List.of("-Xms512m")));
        assertFalse(MindustryMemoryPolicy.hasExplicitHeapLimit(List.of()));
        assertFalse(MindustryMemoryPolicy.hasExplicitHeapLimit(null));
    }
}
