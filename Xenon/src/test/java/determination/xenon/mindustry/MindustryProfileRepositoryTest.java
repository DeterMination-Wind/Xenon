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

import determination.xenon.setting.Profile;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Profile-scoped Mindustry repositories and local jar imports.
@NotNullByDefault
public final class MindustryProfileRepositoryTest {

    /// Each Profile gets an independent versions directory and may reuse an id.
    @Test
    public void importsSameIdIntoSeparateProfileRepositories(@TempDir Path tempDir) throws IOException {
        Path firstGameDir = tempDir.resolve("first-game");
        Path secondGameDir = tempDir.resolve("second-game");
        Profile first = new Profile("First", firstGameDir);
        Profile second = new Profile("Second", secondGameDir);
        Path sourceJar = tempDir.resolve("mindustry-build-158.jar");
        Files.write(sourceJar, "jar-content".getBytes(StandardCharsets.UTF_8));

        XenonGameRepository firstRepository = MindustryImportFlow.repository(first);
        XenonGameRepository secondRepository = MindustryImportFlow.repository(second);
        MindustryVersion firstVersion = MindustryLaunchService.importLocalJar(
                firstRepository, sourceJar, "shared", null);
        MindustryVersion secondVersion = MindustryLaunchService.importLocalJar(
                secondRepository, sourceJar, "shared", null);

        Path firstRoot = firstGameDir.resolve("versions/shared");
        Path secondRoot = secondGameDir.resolve("versions/shared");
        assertEquals(firstGameDir.resolve("versions").toAbsolutePath().normalize(),
                firstRepository.getVersionsRoot());
        assertEquals(secondGameDir.resolve("versions").toAbsolutePath().normalize(),
                secondRepository.getVersionsRoot());
        assertEquals(firstRoot.toAbsolutePath().normalize(),
                firstRepository.getVersionRoot(firstVersion));
        assertEquals(secondRoot.toAbsolutePath().normalize(),
                secondRepository.getVersionRoot(secondVersion));
        assertTrue(Files.isRegularFile(firstRoot.resolve("shared.jar")));
        assertTrue(Files.isRegularFile(firstRoot.resolve("version.json")));
        assertTrue(Files.isRegularFile(secondRoot.resolve("shared.jar")));
        assertTrue(Files.isRegularFile(secondRoot.resolve("version.json")));
        assertFalse(Files.readString(firstRoot.resolve("version.json")).contains("repositoryRoot"));

        assertEquals(firstRoot.toAbsolutePath().normalize(),
                MindustryImportFlow.findVersion(first, "shared").orElseThrow()
                        .getRepositoryRoot().resolve("shared"));
        assertEquals(secondRoot.toAbsolutePath().normalize(),
                MindustryImportFlow.findVersion(second, "shared").orElseThrow()
                        .getRepositoryRoot().resolve("shared"));
    }

    /// A Minecraft version manifest in a shared HMCL directory is not a Mindustry instance.
    @Test
    public void ignoresMinecraftManifestButLoadsLegacyMindustryManifest(@TempDir Path tempDir)
            throws IOException {
        Path versionsRoot = tempDir.resolve("versions");
        Path minecraft = versionsRoot.resolve("1.20.1");
        Files.createDirectories(minecraft);
        Files.writeString(minecraft.resolve("version.json"), """
                {
                  "id": "1.20.1",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "libraries": []
                }
                """, StandardCharsets.UTF_8);

        Path legacy = versionsRoot.resolve("legacy-mindustry");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("legacy-mindustry.json"), """
                {
                  "id": "legacy-mindustry",
                  "name": "Legacy Mindustry",
                  "variant": "VANILLA",
                  "build": 158,
                  "jarPath": "legacy-mindustry.jar"
                }
                """, StandardCharsets.UTF_8);

        XenonGameRepository repository = new XenonGameRepository(versionsRoot);
        repository.refresh();

        assertEquals(1, repository.size());
        MindustryVersion loaded = repository.get("legacy-mindustry").orElseThrow();
        assertNotNull(loaded.getRepositoryRoot());
        assertEquals("legacy-mindustry", loaded.getId());
        assertTrue(repository.has("legacy-mindustry"));
        assertFalse(repository.has("1.20.1"));
    }
}
