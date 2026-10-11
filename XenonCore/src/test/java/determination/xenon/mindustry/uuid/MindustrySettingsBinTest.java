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
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /// Every Arc value type survives a launcher write and a read.
    @Test
    public void roundTripsEveryArcValueType(@TempDir Path tempDir) throws IOException {
        LinkedHashMap<String, Object> written = new LinkedHashMap<>();
        written.put("enabled", Boolean.TRUE);
        written.put("count", 42);
        written.put("total", 1234567890123L);
        written.put("zoom", 1.25f);
        written.put("nickname", "Xenon");
        written.put("hash", new byte[]{1, 2, 3, -1});

        MindustrySettingsBin.putValuesOrThrow(tempDir, written);

        MindustrySettingsBin.ReadResult result = MindustrySettingsBin.read(tempDir);
        assertEquals(MindustrySettingsBin.ReadStatus.OK, result.status());
        assertEquals(written.size(), result.values().size());
        assertEquals(Boolean.TRUE, result.values().get("enabled"));
        assertEquals(42, result.values().get("count"));
        assertEquals(1234567890123L, result.values().get("total"));
        assertEquals(1.25f, result.values().get("zoom"));
        assertEquals("Xenon", result.values().get("nickname"));
        assertArrayEquals(new byte[]{1, 2, 3, -1}, (byte[]) result.values().get("hash"));
    }

    /// A missing file is reported as MISSING instead of as an empty parse result.
    @Test
    public void readDistinguishesMissingFile(@TempDir Path tempDir) throws IOException {
        MindustrySettingsBin.ReadResult result = MindustrySettingsBin.read(tempDir);

        assertEquals(MindustrySettingsBin.ReadStatus.MISSING, result.status());
        assertTrue(result.values().isEmpty());
    }

    /// A corrupt file throws instead of yielding an empty map that a save could persist over it.
    @Test
    public void readThrowsOnCorruptFile(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve(MindustrySettingsBin.FILE_NAME), "not a settings file");

        assertThrows(IOException.class, () -> MindustrySettingsBin.read(tempDir));
    }

    /// Unknown entry types abort the parse rather than silently truncating the file.
    @Test
    public void readThrowsOnUnknownValueType(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir);
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(
                tempDir.resolve(MindustrySettingsBin.FILE_NAME)))) {
            out.writeInt(1);
            out.writeUTF("mystery");
            out.writeByte(99);
            out.writeInt(1);
        }

        assertThrows(IOException.class, () -> MindustrySettingsBin.read(tempDir));
    }

    /// A merge write must refuse to overwrite a corrupt file with launcher-only values.
    @Test
    public void putValuesOrThrowKeepsCorruptFileUntouched(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir);
        Path file = tempDir.resolve(MindustrySettingsBin.FILE_NAME);
        Files.writeString(file, "not a settings file");
        byte[] before = Files.readAllBytes(file);

        assertThrows(IOException.class,
                () -> MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of("name", "Xenon")));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    /// Read-only callers keep their lenient fallback on a corrupt file.
    @Test
    public void readValuesStaysLenientOnCorruptFile(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve(MindustrySettingsBin.FILE_NAME), "not a settings file");

        assertTrue(MindustrySettingsBin.readValues(tempDir).isEmpty());
        assertTrue(MindustrySettingsBin.getBool(tempDir, "missing", true));
    }

    /// replaceWith drops absent keys and mirrors the result into settings_backup.bin.
    @Test
    public void replaceWithDropsAbsentKeysAndSyncsBackup(@TempDir Path tempDir) throws IOException {
        MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of("keep", 1, "drop", Boolean.TRUE));

        MindustrySettingsBin.replaceWith(tempDir, Map.of("keep", 2));

        LinkedHashMap<String, Object> values = MindustrySettingsBin.read(tempDir).values();
        assertEquals(1, values.size());
        assertEquals(2, values.get("keep"));
        LinkedHashMap<String, Object> backup = MindustrySettingsBin.readFileOrThrow(
                tempDir.resolve(MindustrySettingsBin.BACKUP_NAME));
        assertEquals(values, backup);
    }

    /// A corrupt primary is recovered from settings_backup.bin, like the game does.
    @Test
    public void recoversCorruptPrimaryFromBackup(@TempDir Path tempDir) throws IOException {
        MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of("name", "Xenon"));
        Files.copy(tempDir.resolve(MindustrySettingsBin.FILE_NAME),
                tempDir.resolve(MindustrySettingsBin.BACKUP_NAME),
                StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(tempDir.resolve(MindustrySettingsBin.FILE_NAME), "not a settings file");

        assertEquals("Xenon", MindustrySettingsBin.readValues(tempDir).get("name"));

        MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of("count", 2));

        LinkedHashMap<String, Object> values = MindustrySettingsBin.read(tempDir).values();
        assertEquals("Xenon", values.get("name"));
        assertEquals(2, values.get("count"));
    }

    /// When the primary is missing, a valid backup still seeds the write.
    @Test
    public void recoversMissingPrimaryFromBackup(@TempDir Path tempDir) throws IOException {
        MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of("name", "Xenon"));
        Files.move(tempDir.resolve(MindustrySettingsBin.FILE_NAME),
                tempDir.resolve(MindustrySettingsBin.BACKUP_NAME),
                StandardCopyOption.REPLACE_EXISTING);

        MindustrySettingsBin.putValuesOrThrow(tempDir, Map.of("count", 2));

        LinkedHashMap<String, Object> values = MindustrySettingsBin.read(tempDir).values();
        assertEquals("Xenon", values.get("name"));
        assertEquals(2, values.get("count"));
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
                } else if (value instanceof Integer) {
                    out.writeByte(1);
                    out.writeInt((Integer) value);
                } else if (value instanceof Long) {
                    out.writeByte(2);
                    out.writeLong((Long) value);
                } else if (value instanceof Float) {
                    out.writeByte(3);
                    out.writeFloat((Float) value);
                } else if (value instanceof String) {
                    out.writeByte(4);
                    out.writeUTF((String) value);
                } else if (value instanceof byte[] bytes) {
                    out.writeByte(5);
                    out.writeInt(bytes.length);
                    out.write(bytes);
                } else {
                    throw new IOException("Unsupported test value " + value);
                }
            }
        }
    }
}
