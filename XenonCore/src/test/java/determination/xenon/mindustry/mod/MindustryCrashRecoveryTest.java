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

import determination.xenon.mindustry.uuid.MindustrySettingsBin;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the launcher-side takeover of Mindustry's failed-launch protocol.
@NotNullByDefault
public final class MindustryCrashRecoveryTest {

    /// Safe mode snapshots the visible state, disables everything, and the
    /// snapshot restores the exact previous configuration afterwards.
    @Test
    public void safeModeSnapshotsThenRestores(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeMod(modsDir.resolve("alpha.zip"), "alpha", "Alpha");
        writeMod(modsDir.resolve("beta.zip.disabled"), "beta", "Beta");
        MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of(
                "mod-beta-enabled", Boolean.TRUE,
                "mod-ghost-enabled", Boolean.TRUE));
        Files.writeString(tempDir.resolve(MindustryCrashRecovery.LAUNCH_MARKER_NAME), "go away");

        assertTrue(MindustryCrashRecovery.pending(tempDir));
        Path snapshot = MindustryCrashRecovery.takeOver(tempDir);

        assertFalse(MindustryCrashRecovery.pending(tempDir));
        assertFalse(MindustrySettingsBin.getBool(tempDir, "mod-alpha-enabled", true));
        assertFalse(MindustrySettingsBin.getBool(tempDir, "mod-beta-enabled", true));
        assertFalse(MindustrySettingsBin.getBool(tempDir, "mod-ghost-enabled", true));
        assertEquals(snapshot, MindustryCrashRecovery.latestSnapshot(tempDir));

        MindustryCrashRecovery.RestoreResult result =
                MindustryCrashRecovery.restore(tempDir, snapshot);

        assertTrue(result.failures().isEmpty());
        assertEquals(1, result.enabled());
        assertEquals(1, result.disabled());
        assertTrue(MindustrySettingsBin.getBool(tempDir, "mod-alpha-enabled", false));
        assertFalse(MindustrySettingsBin.getBool(tempDir, "mod-beta-enabled", true));
        assertTrue(MindustrySettingsBin.getBool(tempDir, "mod-ghost-enabled", false));
        assertTrue(Files.isRegularFile(modsDir.resolve("alpha.zip")));
        assertTrue(Files.isRegularFile(modsDir.resolve("beta.zip.disabled")));
        assertNull(MindustryCrashRecovery.latestSnapshot(tempDir));
    }

    /// A data directory without the marker reports no pending recovery.
    @Test
    public void pendingRequiresMarker(@TempDir Path tempDir) {
        assertFalse(MindustryCrashRecovery.pending(tempDir));
    }

    private static Path writeMod(Path archive, String internalName, String displayName)
            throws IOException {
        String descriptor = """
                {
                  "name": "%s",
                  "displayName": "%s",
                  "author": "Xenon",
                  "version": "1"
                }
                """.formatted(internalName, displayName);
        Files.createDirectories(archive.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("mod.json"));
            zip.write(descriptor.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return archive;
    }
}
