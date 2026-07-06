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
package determination.xenon.mindustry.uuid;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests Arc-compatible Mindustry settings.bin reads.
@NotNullByDefault
public final class MindustrySettingsBinTest {

    /// Reads boolean values from an uncompressed Arc settings file.
    @Test
    public void readsPlainBoolean(@TempDir Path tempDir) throws IOException {
        writeSettings(tempDir, false, Map.of("mod-alpha-enabled", Boolean.FALSE));

        LinkedHashMap<String, Object> values = MindustrySettingsBin.readValues(tempDir);

        assertEquals(Boolean.FALSE, values.get("mod-alpha-enabled"));
        assertFalse(MindustrySettingsBin.getBool(tempDir, "mod-alpha-enabled", true));
        assertTrue(MindustrySettingsBin.getBool(tempDir, "missing", true));
    }

    /// Reads boolean values from Arc's optional zlib-compressed settings file.
    @Test
    public void readsCompressedBoolean(@TempDir Path tempDir) throws IOException {
        writeSettings(tempDir, true, Map.of("mod-beta-enabled", Boolean.FALSE));

        assertFalse(MindustrySettingsBin.getBool(tempDir, "mod-beta-enabled", true));
    }

    private static void writeSettings(Path dataDir,
                                      boolean compressed,
                                      Map<String, Object> values) throws IOException {
        Files.createDirectories(dataDir);
        OutputStream base = Files.newOutputStream(dataDir.resolve(MindustrySettingsBin.FILE_NAME));
        OutputStream encoded = compressed ? new DeflaterOutputStream(base) : base;
        try (DataOutputStream out = new DataOutputStream(encoded)) {
            out.writeInt(values.size());
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                out.writeUTF(entry.getKey());
                Object value = entry.getValue();
                if (value instanceof Boolean) {
                    out.writeByte(0);
                    out.writeBoolean((Boolean) value);
                } else {
                    throw new IOException("Unsupported test value " + value);
                }
            }
        }
    }
}
