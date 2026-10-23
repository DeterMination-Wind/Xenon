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
package determination.xenon.mindustry.community;

import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.UploadMetadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests cloud upload metadata collection.
@NotNullByDefault
public final class MindustryCloudSaveMetadataTest {

    /// Metadata includes the game version, device id and enabled Mods.
    @Test
    public void collectsGameAndModMetadata(@TempDir Path dir) throws Exception {
        Path dataDir = dir.resolve("data");
        Path modsDir = Files.createDirectories(dataDir.resolve("mods"));
        writeMod(modsDir.resolve("test-mod.zip"), "test-mod", "Test Mod", "1.2.0");
        Files.writeString(modsDir.resolve("disabled-mod.zip.disabled"), "not a mod");

        Path save = dir.resolve("save.msav");
        Files.writeString(save, "not a real save");
        UploadMetadata metadata = MindustryCloudSaveMetadata.collect(
                save, "v157", 157, dataDir, "device-1");

        assertEquals("v157", metadata.game().version());
        assertEquals(157, metadata.game().build());
        assertEquals("device-1", metadata.deviceId());
        assertNull(metadata.save(), "an unreadable save header must not fail the upload");
        assertEquals(1, metadata.mods().size());
        assertEquals("test-mod", metadata.mods().get(0).id());
        assertEquals("Test Mod", metadata.mods().get(0).name());
        assertEquals("1.2.0", metadata.mods().get(0).version());
        assertEquals(64, metadata.mods().get(0).sha256().length());
        assertFalse(metadata.mods().get(0).sha256().isBlank());
    }

    /// Missing mods directories and unreadable saves are normal.
    @Test
    public void toleratesMissingInputs(@TempDir Path dir) {
        UploadMetadata metadata = MindustryCloudSaveMetadata.collect(
                dir.resolve("missing.msav"), "build 1", 0, dir.resolve("data"), "");
        assertNotNull(metadata.game());
        assertNull(metadata.save());
        assertTrue(metadata.mods().isEmpty());
    }

    /// Writes one minimal Mindustry Mod archive.
    private static void writeMod(Path target, String name, String displayName, String version)
            throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            zip.putNextEntry(new ZipEntry("mod.hjson"));
            String descriptor = "{\n"
                    + "  name: " + name + "\n"
                    + "  displayName: " + displayName + "\n"
                    + "  version: " + version + "\n"
                    + "  minGameVersion: 146\n"
                    + "}\n";
            zip.write(descriptor.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
