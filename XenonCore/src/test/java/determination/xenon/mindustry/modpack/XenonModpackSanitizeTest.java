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
package determination.xenon.mindustry.modpack;

import determination.xenon.mindustry.DataDirectoryPolicy;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.XenonGameRepository;
import determination.xenon.mindustry.uuid.MindustrySettingsBin;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests that `.xenon` exports strip the exporter's identity by default.
@NotNullByDefault
public final class XenonModpackSanitizeTest {

    /// The default export drops uuid / usid / ip / servers / nickname but keeps
    /// gameplay settings.
    @Test
    public void stripsPersonalSettingsByDefault(@TempDir Path tempDir) throws IOException {
        Fixture fixture = fixture(tempDir);

        XenonModpackPacker.XenonModpackManifest manifest =
                XenonModpackPacker.pack(fixture.repo(), fixture.version(),
                        tempDir.resolve("out.xenon"));

        assertTrue(manifest.sanitized);
        assertEquals(5, manifest.removedSettingsKeys.size());

        Map<String, Object> exported = readExportedSettings(tempDir.resolve("out.xenon"));
        assertFalse(exported.containsKey("uuid"));
        assertFalse(exported.containsKey("usid-1.2.3.4"));
        assertFalse(exported.containsKey("ip"));
        assertFalse(exported.containsKey("servers"));
        assertFalse(exported.containsKey("name"));
        assertEquals(70, exported.get("volume-music"));
        assertEquals("en", exported.get("locale"));
    }

    /// The live settings.bin keeps its identity entries after an export.
    @Test
    public void leavesLiveSettingsUntouched(@TempDir Path tempDir) throws IOException {
        Fixture fixture = fixture(tempDir);

        XenonModpackPacker.pack(fixture.repo(), fixture.version(), tempDir.resolve("out.xenon"));

        Map<String, Object> live = MindustrySettingsBin.read(fixture.dataDir()).values();
        assertTrue(live.containsKey("uuid"));
        assertEquals("127.0.0.1", live.get("ip"));
    }

    /// `--keep-personal` exports the entries verbatim.
    @Test
    public void keepsPersonalSettingsWhenAsked(@TempDir Path tempDir) throws IOException {
        Fixture fixture = fixture(tempDir);

        XenonModpackPacker.XenonModpackManifest manifest =
                XenonModpackPacker.pack(fixture.repo(), fixture.version(),
                        tempDir.resolve("raw.xenon"), XenonModpackPacker.ExportMonitor.NONE, false);

        assertFalse(manifest.sanitized);
        assertTrue(manifest.removedSettingsKeys.isEmpty());

        Map<String, Object> exported = readExportedSettings(tempDir.resolve("raw.xenon"));
        assertTrue(exported.containsKey("uuid"));
        assertTrue(exported.containsKey("usid-1.2.3.4"));
        assertEquals("player-nick", exported.get("name"));
    }

    /// Builds an instance whose settings.bin mixes identity and gameplay keys.
    ///
    /// @param tempDir temporary folder
    /// @return repository, version and data directory
    private static Fixture fixture(Path tempDir) throws IOException {
        Path versionsRoot = tempDir.resolve("versions");
        XenonGameRepository repo = new XenonGameRepository(versionsRoot);
        MindustryVersion version = new MindustryVersion();
        version.setId("demo");
        version.setName("Demo");
        version.setJarPath("demo.jar");
        version.setDataDirPolicy(DataDirectoryPolicy.ISOLATED);
        repo.save(version);

        Path root = versionsRoot.resolve("demo");
        Files.write(root.resolve("demo.jar"), new byte[]{1, 2, 3});
        Path dataDir = root.resolve(".data");
        Files.createDirectories(dataDir);
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("uuid", "1234-uuid");
        settings.put("usid-1.2.3.4", "server-identity");
        settings.put("ip", "127.0.0.1");
        settings.put("servers", "[{\"ip\":\"127.0.0.1\"}]");
        settings.put("name", "player-nick");
        settings.put("volume-music", 70);
        settings.put("locale", "en");
        MindustrySettingsBin.writeFile(dataDir.resolve("settings.bin"), settings);
        repo.refresh();
        return new Fixture(repo, version, dataDir);
    }

    /// Reads the `settings.bin` entry out of a `.xenon` archive.
    ///
    /// @param archive archive to inspect
    /// @return parsed settings entries
    private static Map<String, Object> readExportedSettings(Path archive) throws IOException {
        Path temp = Files.createTempDirectory("xenon-unzip");
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry entry = zip.getEntry("settings.bin");
            assertNotNull(entry);
            Path out = temp.resolve("settings.bin");
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, out);
            }
            Path dataDir = temp.resolve("data");
            Files.createDirectories(dataDir);
            Files.move(out, dataDir.resolve("settings.bin"));
            return MindustrySettingsBin.read(dataDir).values();
        }
    }

    /// Repository, version and data directory of one fixture instance.
    ///
    /// @param repo    repository that owns the instance
    /// @param version instance under test
    /// @param dataDir instance data directory
    private record Fixture(XenonGameRepository repo, MindustryVersion version, Path dataDir) {
    }
}
