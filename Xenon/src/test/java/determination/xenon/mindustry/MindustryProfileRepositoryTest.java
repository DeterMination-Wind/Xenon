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
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    /// Renaming an instance moves the directory and rewrites its manifest.
    @Test
    public void renamesInstanceAndRewritesManifest(@TempDir Path tempDir) throws IOException {
        Path gameDir = tempDir.resolve("game");
        Profile profile = new Profile("Rename", gameDir);
        Path sourceJar = tempDir.resolve("mindustry-build-158.jar");
        Files.write(sourceJar, "jar-content".getBytes(StandardCharsets.UTF_8));

        XenonGameRepository repository = MindustryImportFlow.repository(profile);
        MindustryVersion version = MindustryLaunchService.importLocalJar(repository, sourceJar, "old-id", null);
        Path oldRoot = repository.getVersionRoot(version);
        // Simulate the <id>.json copy HMCL's Minecraft scan used to create.
        Files.copy(oldRoot.resolve("version.json"), oldRoot.resolve("old-id.json"));

        assertTrue(repository.renameVersion("old-id", "new-id"));

        Path newRoot = gameDir.resolve("versions/new-id");
        assertFalse(Files.exists(gameDir.resolve("versions/old-id")));
        assertTrue(Files.isRegularFile(newRoot.resolve("version.json")));
        assertTrue(Files.isRegularFile(newRoot.resolve("new-id.jar")));
        assertFalse(Files.exists(newRoot.resolve("old-id.jar")));
        assertFalse(Files.exists(newRoot.resolve("old-id.json")));
        assertFalse(repository.has("old-id"));

        MindustryVersion renamed = repository.get("new-id").orElseThrow();
        assertEquals("new-id", renamed.getName());
        assertEquals("new-id.jar", renamed.getJarPath());
        assertTrue(Files.isRegularFile(renamed.resolveJar(newRoot)));
        assertEquals(newRoot.resolve(".data").toAbsolutePath().normalize(),
                renamed.resolveDataDir(newRoot));
    }

    /// Invalid or already used target ids leave the instance untouched.
    @Test
    public void rejectsInvalidRenameTargets(@TempDir Path tempDir) throws IOException {
        Path gameDir = tempDir.resolve("game");
        Profile profile = new Profile("Rename", gameDir);
        Path sourceJar = tempDir.resolve("mindustry-build-158.jar");
        Files.write(sourceJar, "jar-content".getBytes(StandardCharsets.UTF_8));

        XenonGameRepository repository = MindustryImportFlow.repository(profile);
        MindustryLaunchService.importLocalJar(repository, sourceJar, "first", null);
        MindustryLaunchService.importLocalJar(repository, sourceJar, "second", null);

        assertFalse(repository.renameVersion("first", "second"));
        assertFalse(repository.renameVersion("first", "bad/id"));
        assertFalse(repository.renameVersion("first", ""));

        assertTrue(repository.has("first"));
        assertTrue(Files.isDirectory(gameDir.resolve("versions/first")));
        assertTrue(Files.isRegularFile(gameDir.resolve("versions/first/first.jar")));
    }

    /// A manifest left behind by the old rename path is repaired from the directory name.
    @Test
    public void adoptsDirectoryNameWhenManifestIdIsStale(@TempDir Path tempDir) throws IOException {
        Path versionsRoot = tempDir.resolve("versions");
        Path instanceDir = versionsRoot.resolve("renamed-instance");
        Files.createDirectories(instanceDir);
        Files.write(instanceDir.resolve("renamed-instance.jar"), "jar-content".getBytes(StandardCharsets.UTF_8));
        Files.writeString(instanceDir.resolve("version.json"), """
                {
                  "id": "old-id",
                  "name": "old-id",
                  "variant": "MINDUSTRY_X",
                  "build": 35,
                  "jarPath": "old-id.jar",
                  "dataDirPolicy": "ISOLATED"
                }
                """, StandardCharsets.UTF_8);

        XenonGameRepository repository = new XenonGameRepository(versionsRoot);
        repository.refresh();

        assertFalse(repository.has("old-id"));
        MindustryVersion repaired = repository.get("renamed-instance").orElseThrow();
        assertEquals("renamed-instance", repaired.getName());
        assertEquals("renamed-instance.jar", repaired.getJarPath());
        assertTrue(Files.isRegularFile(repaired.resolveJar(repository.getVersionRoot(repaired))));
    }

    /// An external installation found in a custom game folder is registered there,
    /// never in the shared home repository, and is isolated from AppData.
    @Test
    public void registersExternalInstallInProfileRepository(@TempDir Path tempDir) throws IOException {
        Path install = tempDir.resolve("MyMindustry");
        writeJar(install.resolve("Mindustry.jar"), """
                build=159
                type=official
                """);
        Path gameDir = tempDir.resolve("custom-game-folder");
        Profile profile = new Profile("Custom", gameDir);

        MindustryVersion version = MindustryImportFlow.syncExternalInstallation(profile, install).orElseThrow();

        Path versionRoot = gameDir.resolve("versions").resolve(version.getId());
        assertTrue(Files.isRegularFile(versionRoot.resolve("version.json")));
        assertEquals(DataDirectoryPolicy.ISOLATED, version.getDataDirPolicy());
        assertEquals(versionRoot.resolve(".data").toAbsolutePath().normalize(),
                version.resolveDataDir(MindustryImportFlow.versionRoot(version)));
    }

    private static void writeJar(Path jar, String versionProperties) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("version.properties"));
            zip.write(versionProperties.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
