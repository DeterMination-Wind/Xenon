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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests dependency-cycle detection over installed mods.
@NotNullByDefault
public final class MindustryModDependenciesTest {

    /// A two-mod loop is reported once, whatever mod the walk starts from.
    @Test
    public void findsTwoModCycle(@TempDir Path modsDir) throws IOException {
        List<List<String>> cycles = MindustryModDependencies.findCycles(List.of(
                mod(modsDir, "alpha", List.of("beta")),
                mod(modsDir, "beta", List.of("alpha"))));

        assertEquals(1, cycles.size());
        assertEquals(List.of("alpha", "beta", "alpha"), cycles.get(0));
    }

    /// A three-mod loop keeps dependency order.
    @Test
    public void findsThreeModCycle(@TempDir Path modsDir) throws IOException {
        List<List<String>> cycles = MindustryModDependencies.findCycles(List.of(
                mod(modsDir, "a", List.of("b")),
                mod(modsDir, "b", List.of("c")),
                mod(modsDir, "c", List.of("a"))));

        assertEquals(1, cycles.size());
        assertEquals(List.of("a", "b", "c", "a"), cycles.get(0));
    }

    /// A diamond is not a cycle.
    @Test
    public void ignoresAcyclicGraph(@TempDir Path modsDir) throws IOException {
        List<List<String>> cycles = MindustryModDependencies.findCycles(List.of(
                mod(modsDir, "core", List.of()),
                mod(modsDir, "left", List.of("core")),
                mod(modsDir, "right", List.of("core")),
                mod(modsDir, "top", List.of("left", "right"))));

        assertTrue(cycles.isEmpty());
    }

    /// A missing dependency is not a cycle, and built-ins never form one.
    @Test
    public void ignoresMissingAndBuiltInDependencies(@TempDir Path modsDir) throws IOException {
        List<List<String>> cycles = MindustryModDependencies.findCycles(List.of(
                mod(modsDir, "addon", List.of("mindustry", "arc", "not-installed"))));

        assertTrue(cycles.isEmpty());
    }

    /// Dependency names are matched case-insensitively.
    @Test
    public void matchesNamesCaseInsensitively(@TempDir Path modsDir) throws IOException {
        List<List<String>> cycles = MindustryModDependencies.findCycles(List.of(
                mod(modsDir, "Upper", List.of("lower")),
                mod(modsDir, "Lower", List.of("upper"))));

        assertEquals(1, cycles.size());
    }

    /// Writes one enabled mod archive and parses it into a summary entry.
    ///
    /// @param dir          mods folder
    /// @param name         internal name
    /// @param dependencies declared dependencies
    /// @return parsed entry
    private static MindustryLocalMod mod(Path dir, String name,
                                         List<String> dependencies) throws IOException {
        Path file = dir.resolve(name + "-1.0.zip");
        Files.write(file, new byte[]{1});
        return new MindustryLocalMod(file, name, name, "author", "1.0", "desc",
                null, 146, false, dependencies);
    }
}
