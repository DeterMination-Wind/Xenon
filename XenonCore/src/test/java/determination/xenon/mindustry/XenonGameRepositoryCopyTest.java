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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the copy semantics of [XenonGameRepository#copyVersion].
///
/// Duplication is the supported way to give an instance a new id without
/// triggering the Mindustry-manifest-becomes-Minecraft-version class of bug,
/// so both the data-directory filters and the jar renaming are checked here.
@NotNullByDefault
public final class XenonGameRepositoryCopyTest {

    private static final String SOURCE_ID = "shared-campaign";
    private static final String TARGET_ID = "shared-campaign-copy";

    /// A full copy keeps the jar, saves, mods, settings and playtime journal.
    @Test
    public void copiesInstanceWithDataAndRenamesJar(@TempDir Path versionsRoot) throws IOException {
        XenonGameRepository repository = repositoryWithSource(versionsRoot, version -> {
        });
        Path targetRoot = versionsRoot.resolve(TARGET_ID);

        MindustryVersion copy = repository.copyVersion(SOURCE_ID, TARGET_ID, true, true);

        assertEquals(TARGET_ID, copy.getId());
        assertEquals(TARGET_ID, copy.getName());
        assertTrue(Files.isRegularFile(targetRoot.resolve(TARGET_ID + ".jar")));
        assertFalse(Files.exists(targetRoot.resolve(SOURCE_ID + ".jar")));
        assertTrue(Files.isRegularFile(targetRoot.resolve(".data/settings.bin")));
        assertTrue(Files.isRegularFile(targetRoot.resolve(".data/mods/example.jar")));
        assertTrue(Files.isRegularFile(targetRoot.resolve("saves/world.msav")));
        assertTrue(Files.isRegularFile(targetRoot.resolve("xenon-playtime.jsonl")));
        assertTrue(repository.has(TARGET_ID));
        assertTrue(repository.has(SOURCE_ID));

        // The source instance must stay untouched and complete.
        Path sourceRoot = versionsRoot.resolve(SOURCE_ID);
        assertTrue(Files.isRegularFile(sourceRoot.resolve(SOURCE_ID + ".jar")));
        assertTrue(Files.isRegularFile(sourceRoot.resolve(".data/settings.bin")));
        assertTrue(Files.isRegularFile(sourceRoot.resolve("xenon-playtime.jsonl")));

        // A fresh repository sees the copy with its own manifest.
        XenonGameRepository reloaded = new XenonGameRepository(versionsRoot);
        reloaded.refresh();
        MindustryVersion persisted = reloaded.get(TARGET_ID).orElseThrow();
        assertEquals(TARGET_ID + ".jar", persisted.getJarPath());
        assertFalse(Files.readString(targetRoot.resolve("version.json")).contains("repositoryRoot"));
    }

    /// Without copying data, the root contents stay but the data directory and
    /// the playtime journal do not.
    @Test
    public void skipsDataWhenNotRequested(@TempDir Path versionsRoot) throws IOException {
        XenonGameRepository repository = repositoryWithSource(versionsRoot, version -> {
        });
        Path targetRoot = versionsRoot.resolve(TARGET_ID);

        repository.copyVersion(SOURCE_ID, TARGET_ID, false, true);

        assertFalse(Files.exists(targetRoot.resolve(".data")));
        assertFalse(Files.exists(targetRoot.resolve("xenon-playtime.jsonl")));
        assertTrue(Files.isRegularFile(targetRoot.resolve(TARGET_ID + ".jar")));
        assertTrue(Files.isRegularFile(targetRoot.resolve("saves/world.msav")));
        assertTrue(Files.isRegularFile(targetRoot.resolve("version.json")));
    }

    /// Copied data may omit `settings.bin` while keeping the rest of the data directory.
    @Test
    public void skipsSettingsBinWhenDisabled(@TempDir Path versionsRoot) throws IOException {
        XenonGameRepository repository = repositoryWithSource(versionsRoot, version -> {
        });
        Path targetRoot = versionsRoot.resolve(TARGET_ID);

        repository.copyVersion(SOURCE_ID, TARGET_ID, true, false);

        assertFalse(Files.exists(targetRoot.resolve(".data/settings.bin")));
        assertTrue(Files.isRegularFile(targetRoot.resolve(".data/mods/example.jar")));
        assertTrue(Files.isRegularFile(targetRoot.resolve("xenon-playtime.jsonl")));
    }

    /// An absolute jar outside the instance root is shared instead of copied.
    @Test
    public void keepsExternalAbsoluteJarPath(@TempDir Path tempDir) throws IOException {
        Path versionsRoot = tempDir.resolve("versions");
        Path externalJar = tempDir.resolve("external").resolve("mindustry-1604.jar");
        Files.createDirectories(externalJar.getParent());
        Files.write(externalJar, new byte[]{9, 9});
        XenonGameRepository repository = repositoryWithSource(versionsRoot,
                version -> version.setJarPath(externalJar.toAbsolutePath().toString()));

        MindustryVersion copy = repository.copyVersion(SOURCE_ID, TARGET_ID, true, true);

        assertEquals(externalJar.toAbsolutePath().toString(), copy.getJarPath());
        assertTrue(Files.isRegularFile(externalJar));
        assertFalse(Files.exists(versionsRoot.resolve(TARGET_ID).resolve(SOURCE_ID + ".jar")));
        assertFalse(Files.exists(versionsRoot.resolve(TARGET_ID).resolve(TARGET_ID + ".jar")));
    }

    /// GLOBAL data directories live outside the root, so the copy shares them.
    @Test
    public void sharesGlobalDataDir(@TempDir Path versionsRoot) throws IOException {
        XenonGameRepository repository = repositoryWithSource(versionsRoot,
                version -> version.setDataDirPolicy(DataDirectoryPolicy.GLOBAL));
        Path sourceRoot = versionsRoot.resolve(SOURCE_ID);
        Path targetRoot = versionsRoot.resolve(TARGET_ID);

        MindustryVersion copy = repository.copyVersion(SOURCE_ID, TARGET_ID, true, true);

        assertEquals(DataDirectoryPolicy.GLOBAL, copy.getDataDirPolicy());
        assertFalse(Files.exists(targetRoot.resolve(".data")));
        assertEquals(MindustryVersion.defaultMindustryDataDir(),
                copy.resolveDataDir(targetRoot));
        assertEquals(repository.get(SOURCE_ID).orElseThrow().resolveDataDir(sourceRoot),
                copy.resolveDataDir(targetRoot));
    }

    /// An id that is already taken is rejected before anything is written.
    @Test
    public void rejectsExistingTargetId(@TempDir Path versionsRoot) throws IOException {
        XenonGameRepository repository = repositoryWithSource(versionsRoot, version -> {
        });
        MindustryVersion other = new MindustryVersion();
        other.setId("existing");
        other.setJarPath("existing.jar");
        repository.save(other);

        assertThrows(IOException.class, () -> repository.copyVersion(SOURCE_ID, "existing", true, true));
    }

    /// Ids that would escape the versions directory are rejected.
    @Test
    public void rejectsInvalidId(@TempDir Path versionsRoot) throws IOException {
        XenonGameRepository repository = repositoryWithSource(versionsRoot, version -> {
        });

        assertThrows(IOException.class, () -> repository.copyVersion(SOURCE_ID, "../escape", true, true));
        assertThrows(IOException.class, () -> repository.copyVersion(SOURCE_ID, "bad/id", true, true));
        assertFalse(Files.exists(versionsRoot.getParent().resolve("escape")));
    }

    /// Unknown source instances cannot be copied.
    @Test
    public void rejectsMissingSource(@TempDir Path versionsRoot) throws IOException {
        XenonGameRepository repository = repositoryWithSource(versionsRoot, version -> {
        });

        assertThrows(IOException.class, () -> repository.copyVersion("missing", TARGET_ID, true, true));
        assertFalse(Files.exists(versionsRoot.resolve(TARGET_ID)));
    }

    /// Creates a source instance with an isolated data directory and sample content.
    private static XenonGameRepository repositoryWithSource(
            Path versionsRoot, Consumer<MindustryVersion> customizer) throws IOException {
        XenonGameRepository repository = new XenonGameRepository(versionsRoot);
        MindustryVersion source = new MindustryVersion();
        source.setId(SOURCE_ID);
        source.setName("Shared Campaign");
        source.setJarPath(SOURCE_ID + ".jar");
        source.setDataDirPolicy(DataDirectoryPolicy.ISOLATED);
        customizer.accept(source);
        repository.save(source);

        Path sourceRoot = versionsRoot.resolve(source.getId());
        String declaredJar = source.getJarPath();
        if (declaredJar != null && !Path.of(declaredJar).isAbsolute()) {
            Files.write(sourceRoot.resolve(declaredJar), new byte[]{1, 2, 3});
        }
        Files.createDirectories(sourceRoot.resolve("saves"));
        Files.write(sourceRoot.resolve("saves").resolve("world.msav"), new byte[]{4, 5});
        if (source.getDataDirPolicy() == DataDirectoryPolicy.ISOLATED) {
            Files.createDirectories(sourceRoot.resolve(".data").resolve("mods"));
            Files.write(sourceRoot.resolve(".data").resolve("mods").resolve("example.jar"), new byte[]{6});
            Files.write(sourceRoot.resolve(".data").resolve("settings.bin"), new byte[]{7});
        }
        Files.writeString(sourceRoot.resolve("xenon-playtime.jsonl"),
                "{\"ms\":1}\n", StandardCharsets.UTF_8);
        repository.refresh();
        return repository;
    }
}
