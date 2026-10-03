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
package determination.xenon.mindustry.migrate;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests cross-instance content copying.
@NotNullByDefault
public final class InstanceDataMigrationTest {

    /// Saves, mods, schematics, maps and playtime copy into the target.
    @Test
    public void copiesSelectedCategories(@TempDir Path tempDir) throws IOException {
        Fixture fixture = fixture(tempDir);
        InstanceDataMigration.Request request = new InstanceDataMigration.Request(
                fixture.sourceRoot(), fixture.sourceData(),
                fixture.targetRoot(), fixture.targetData(),
                EnumSet.of(InstanceDataMigration.Category.SAVES,
                        InstanceDataMigration.Category.MODS,
                        InstanceDataMigration.Category.SCHEMATICS,
                        InstanceDataMigration.Category.MAPS,
                        InstanceDataMigration.Category.PLAYTIME),
                false);

        InstanceDataMigration.Result result = InstanceDataMigration.run(request, null);

        assertTrue(Files.isRegularFile(fixture.targetData().resolve("saves/run.msav")));
        assertTrue(Files.isRegularFile(fixture.targetData().resolve("saves/backups/old.msav")));
        assertTrue(Files.isRegularFile(fixture.targetData().resolve("mods/mod.jar")));
        assertTrue(Files.isRegularFile(fixture.targetData().resolve("mods/mod.jar.disabled")));
        assertTrue(Files.isRegularFile(fixture.targetData().resolve("schematics/base.msch")));
        assertTrue(Files.isRegularFile(fixture.targetData().resolve("maps/custom.msav")));
        assertTrue(Files.isRegularFile(fixture.targetRoot().resolve("xenon-playtime.jsonl")));
        // settings.bin was not requested.
        assertFalse(Files.exists(fixture.targetData().resolve("settings.bin")));
        assertEquals(7, result.totalCopied());
        assertEquals(0, result.totalSkipped());
    }

    /// Existing target files survive unless overwrite is enabled.
    @Test
    public void skipsExistingFilesWithoutOverwrite(@TempDir Path tempDir) throws IOException {
        Fixture fixture = fixture(tempDir);
        Path existing = fixture.targetData().resolve("saves/run.msav");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "target-local");

        InstanceDataMigration.Request request = new InstanceDataMigration.Request(
                fixture.sourceRoot(), fixture.sourceData(),
                fixture.targetRoot(), fixture.targetData(),
                EnumSet.of(InstanceDataMigration.Category.SAVES, InstanceDataMigration.Category.SETTINGS),
                false);
        InstanceDataMigration.Result result = InstanceDataMigration.run(request, null);

        assertEquals("target-local", Files.readString(existing));
        assertEquals(2, result.totalCopied());
        assertEquals(1, result.totalSkipped());
        // settings.bin did not exist in the target, so it was copied.
        assertTrue(Files.isRegularFile(fixture.targetData().resolve("settings.bin")));
    }

    /// Overwrite replaces same-named files.
    @Test
    public void overwriteReplacesExistingFiles(@TempDir Path tempDir) throws IOException {
        Fixture fixture = fixture(tempDir);
        Path existing = fixture.targetData().resolve("saves/run.msav");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "target-local");

        InstanceDataMigration.Request request = new InstanceDataMigration.Request(
                fixture.sourceRoot(), fixture.sourceData(),
                fixture.targetRoot(), fixture.targetData(),
                EnumSet.of(InstanceDataMigration.Category.SAVES),
                true);
        InstanceDataMigration.Result result = InstanceDataMigration.run(request, null);

        assertEquals("source-run", Files.readString(existing));
        assertEquals(2, result.totalCopied());
        assertEquals(0, result.totalSkipped());
    }

    /// Migrating an instance onto itself is rejected.
    @Test
    public void sameDirectoryIsRejected(@TempDir Path tempDir) throws IOException {
        Fixture fixture = fixture(tempDir);
        InstanceDataMigration.Request request = new InstanceDataMigration.Request(
                fixture.sourceRoot(), fixture.sourceData(),
                fixture.sourceRoot(), fixture.sourceData(),
                EnumSet.allOf(InstanceDataMigration.Category.class),
                false);
        assertThrows(IOException.class, () -> InstanceDataMigration.run(request, null));
    }

    /// Builds a source and target instance with representative content.
    private static Fixture fixture(Path tempDir) throws IOException {
        Path sourceRoot = tempDir.resolve("source");
        Path sourceData = sourceRoot.resolve(".data");
        Path targetRoot = tempDir.resolve("target");
        Path targetData = targetRoot.resolve(".data");
        Files.createDirectories(targetData);

        write(sourceData.resolve("saves/run.msav"), "source-run");
        write(sourceData.resolve("saves/backups/old.msav"), "backup");
        write(sourceData.resolve("mods/mod.jar"), "mod");
        write(sourceData.resolve("mods/mod.jar.disabled"), "disabled-mod");
        write(sourceData.resolve("schematics/base.msch"), "schematic");
        write(sourceData.resolve("maps/custom.msav"), "map");
        write(sourceData.resolve("settings.bin"), "settings");
        write(sourceRoot.resolve("xenon-playtime.jsonl"), "{\"type\":\"start\"}\n");
        return new Fixture(sourceRoot, sourceData, targetRoot, targetData);
    }

    /// Writes one small text file, creating parents.
    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /// Path pair used by one test.
    private record Fixture(Path sourceRoot, Path sourceData, Path targetRoot, Path targetData) {
    }

    /// Sanity check that the set of all categories is non-trivial.
    @Test
    public void allCategoriesCoverEveryContentType() {
        Set<InstanceDataMigration.Category> categories =
                EnumSet.allOf(InstanceDataMigration.Category.class);
        assertEquals(6, categories.size());
    }
}
