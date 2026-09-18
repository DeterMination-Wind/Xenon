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
package determination.xenon.game;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies that HMCL's Minecraft repository scan leaves Mindustry instances alone.
///
/// Mindustry instances share `<gameDir>/versions` with Minecraft versions, but
/// their manifests are not Minecraft version JSONs. The scan used to rename
/// `version.json` to `<id>.json`, which silently broke every Xenon instance.
@NotNullByDefault
public final class DefaultGameRepositoryMindustryScanTest {

    /// A Mindustry instance directory keeps its manifest, jar and is not listed.
    @Test
    public void skipsMindustryInstanceDirectories(@TempDir Path gameDir) throws IOException {
        Path versionDir = gameDir.resolve("versions").resolve("mindustry_x-485");
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve("version.json"), """
                {
                  "id": "mindustry_x-485",
                  "name": "mindustry_x-485",
                  "variant": "MINDUSTRY_X",
                  "build": 485,
                  "jarPath": "mindustry_x-485.jar",
                  "dataDirPolicy": "CUSTOM",
                  "customDataDir": "D:\\\\MindustryData"
                }
                """, StandardCharsets.UTF_8);
        Files.write(versionDir.resolve("mindustry_x-485.jar"), new byte[]{1, 2, 3});

        DefaultGameRepository repository = new DefaultGameRepository(gameDir);
        repository.refreshVersions();

        assertFalse(repository.hasVersion("mindustry_x-485"));
        assertTrue(Files.isRegularFile(versionDir.resolve("version.json")));
        assertFalse(Files.exists(versionDir.resolve("mindustry_x-485.json")));
        assertTrue(Files.isRegularFile(versionDir.resolve("mindustry_x-485.jar")));
    }

    /// The legacy `<id>/<id>.json` Mindustry layout is skipped as well.
    @Test
    public void skipsLegacyMindustryManifestLayout(@TempDir Path gameDir) throws IOException {
        Path versionDir = gameDir.resolve("versions").resolve("legacy-mindustry");
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve("legacy-mindustry.json"), """
                {
                  "id": "legacy-mindustry",
                  "name": "Legacy Mindustry",
                  "variant": "VANILLA",
                  "build": 158,
                  "jarPath": "legacy-mindustry.jar"
                }
                """, StandardCharsets.UTF_8);

        DefaultGameRepository repository = new DefaultGameRepository(gameDir);
        repository.refreshVersions();

        assertFalse(repository.hasVersion("legacy-mindustry"));
        assertTrue(Files.isRegularFile(versionDir.resolve("legacy-mindustry.json")));
    }

    /// Real Minecraft versions in the same directory are still loaded.
    @Test
    public void stillLoadsMinecraftVersions(@TempDir Path gameDir) throws IOException {
        Path versionDir = gameDir.resolve("versions").resolve("1.20.1");
        Files.createDirectories(versionDir);
        Files.writeString(versionDir.resolve("1.20.1.json"), """
                {
                  "id": "1.20.1",
                  "jar": "1.20.1"
                }
                """, StandardCharsets.UTF_8);

        DefaultGameRepository repository = new DefaultGameRepository(gameDir);
        repository.refreshVersions();

        assertTrue(repository.hasVersion("1.20.1"));
        assertTrue(Files.isRegularFile(versionDir.resolve("1.20.1.json")));
    }
}
