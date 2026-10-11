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
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the suffix-versus-settings-key consistency audit.
@NotNullByDefault
public final class MindustryModStateAuditTest {

    /// Finds both mismatch directions, repairs each, and reports folder mods.
    @Test
    public void findsAndRepairsMismatches(@TempDir Path tempDir) throws IOException {
        Path modsDir = tempDir.resolve("mods");
        writeMod(modsDir.resolve("alpha.zip"), "alpha");
        writeMod(modsDir.resolve("beta.zip"), "beta");
        writeMod(modsDir.resolve("gamma.zip.disabled"), "gamma");
        Files.createDirectories(modsDir.resolve("folder-mod"));
        Files.writeString(modsDir.resolve("folder-mod").resolve("mod.json"),
                "{\"name\": \"folder\"}", StandardCharsets.UTF_8);
        MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of(
                "mod-beta-enabled", Boolean.FALSE,
                "mod-gamma-enabled", Boolean.TRUE));

        List<MindustryModStateAudit.Mismatch> mismatches = MindustryModStateAudit.mismatches(tempDir);

        assertEquals(2, mismatches.size());
        MindustryModStateAudit.Mismatch beta = find(mismatches, "beta.zip");
        assertTrue(beta.suffixEnabled());
        assertFalse(beta.keyEnabled());
        MindustryModStateAudit.Mismatch gamma = find(mismatches, "gamma.zip.disabled");
        assertFalse(gamma.suffixEnabled());
        assertTrue(gamma.keyEnabled());

        // Adopt the game-side key: beta gets the suffix appended.
        MindustryModStateAudit.sync(tempDir, beta, false);
        assertTrue(Files.isRegularFile(modsDir.resolve("beta.zip.disabled")));
        assertFalse(MindustrySettingsBin.getBool(tempDir, "mod-beta-enabled", true));

        // Adopt the file side: gamma's key follows its suffix.
        MindustryModStateAudit.sync(tempDir, gamma, true);
        assertFalse(MindustrySettingsBin.getBool(tempDir, "mod-gamma-enabled", true));
        assertTrue(Files.isRegularFile(modsDir.resolve("gamma.zip.disabled")));

        assertTrue(MindustryModStateAudit.mismatches(tempDir).isEmpty());
        assertEquals(List.of("folder-mod"), MindustryModStateAudit.folderMods(tempDir));
    }

    private static MindustryModStateAudit.Mismatch find(
            List<MindustryModStateAudit.Mismatch> mismatches, String fileName) {
        return mismatches.stream()
                .filter(mismatch -> mismatch.fileName().equals(fileName))
                .findFirst()
                .orElseThrow();
    }

    private static Path writeMod(Path archive, String internalName) throws IOException {
        String descriptor = """
                {
                  "name": "%s",
                  "displayName": "%s",
                  "version": "1"
                }
                """.formatted(internalName, internalName);
        Files.createDirectories(archive.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("mod.json"));
            zip.write(descriptor.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return archive;
    }
}
