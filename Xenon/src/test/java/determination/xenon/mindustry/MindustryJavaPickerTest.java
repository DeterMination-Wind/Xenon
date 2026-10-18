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

import determination.xenon.java.JavaInfo;
import determination.xenon.java.JavaRuntime;
import determination.xenon.util.platform.Platform;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies the Java resolution rule that skips runtimes missing from disk.
@NotNullByDefault
public final class MindustryJavaPickerTest {

    /// A stale pool entry is skipped and the next version-compatible runtime is
    /// picked, instead of handing the missing path to the process builder.
    @Test
    public void skipsMissingPoolEntries(@TempDir Path tempDir) throws IOException {
        Path stale = tempDir.resolve("jdk17-stale").resolve("bin").resolve("java");
        Path usable = Files.createFile(
                Files.createDirectories(tempDir.resolve("jdk21").resolve("bin")).resolve("java"));
        Path launcher = tempDir.resolve("launcher-jre").resolve("bin").resolve("java");

        Path picked = MindustryJavaPicker.chooseJavaExecutable(null,
                List.of(runtime(stale, 17), runtime(usable, 21)),
                17, launcher, existing(usable, launcher));

        assertEquals(usable, picked);
    }

    /// An instance whose configured javaHome disappeared falls back to a usable
    /// runtime from the pool.
    @Test
    public void fallsBackToPoolWhenExplicitJavaIsMissing(@TempDir Path tempDir) throws IOException {
        Path explicit = tempDir.resolve("gone").resolve("bin").resolve("java");
        Path usable = Files.createFile(
                Files.createDirectories(tempDir.resolve("jdk17").resolve("bin")).resolve("java"));
        Path launcher = tempDir.resolve("launcher-jre").resolve("bin").resolve("java");

        Path picked = MindustryJavaPicker.chooseJavaExecutable(explicit,
                List.of(runtime(usable, 17)),
                17, launcher, existing(usable, launcher));

        assertEquals(usable, picked);
    }

    /// A configured javaHome that exists always wins over the pool.
    @Test
    public void prefersExistingExplicitJava(@TempDir Path tempDir) throws IOException {
        Path explicit = Files.createFile(
                Files.createDirectories(tempDir.resolve("chosen").resolve("bin")).resolve("java"));
        Path pool = tempDir.resolve("jdk17").resolve("bin").resolve("java");
        Path launcher = tempDir.resolve("launcher-jre").resolve("bin").resolve("java");

        Path picked = MindustryJavaPicker.chooseJavaExecutable(explicit,
                List.of(runtime(pool, 17)),
                17, launcher, existing(explicit, pool, launcher));

        assertEquals(explicit, picked);
    }

    /// Runtimes below the required major version are ignored even when they exist.
    @Test
    public void ignoresTooOldRuntimes(@TempDir Path tempDir) throws IOException {
        Path tooOld = Files.createFile(
                Files.createDirectories(tempDir.resolve("jdk8").resolve("bin")).resolve("java"));
        Path launcher = Files.createFile(
                Files.createDirectories(tempDir.resolve("launcher-jre").resolve("bin")).resolve("java"));

        Path picked = MindustryJavaPicker.chooseJavaExecutable(null,
                List.of(runtime(tooOld, 8)),
                17, launcher, existing(tooOld, launcher));

        assertEquals(launcher, picked);
    }

    /// When every candidate is missing the picker reports the instance-specific
    /// path so the guidance dialog can name it.
    @Test
    public void throwsWhenNothingExists(@TempDir Path tempDir) {
        Path explicit = tempDir.resolve("gone").resolve("bin").resolve("java");
        Path pool = tempDir.resolve("jdk17").resolve("bin").resolve("java");
        Path launcher = tempDir.resolve("launcher-jre").resolve("bin").resolve("java");

        MindustryJavaMissingException exception = assertThrows(MindustryJavaMissingException.class,
                () -> MindustryJavaPicker.chooseJavaExecutable(explicit,
                        List.of(runtime(pool, 17)),
                        17, launcher, path -> false));

        assertEquals(explicit, exception.getJavaExecutable());
    }

    /// Without any explicit override the launcher JVM path is reported.
    @Test
    public void reportsLauncherJavaWhenNoOverrideExists(@TempDir Path tempDir) {
        Path pool = tempDir.resolve("jdk17").resolve("bin").resolve("java");
        Path launcher = tempDir.resolve("launcher-jre").resolve("bin").resolve("java");

        MindustryJavaMissingException exception = assertThrows(MindustryJavaMissingException.class,
                () -> MindustryJavaPicker.chooseJavaExecutable(null,
                        List.of(runtime(pool, 17)),
                        17, launcher, path -> false));

        assertEquals(launcher, exception.getJavaExecutable());
    }

    /// Builds a runtime descriptor without touching the real Java catalog.
    private static JavaRuntime runtime(Path binary, int major) {
        return new JavaRuntime(binary,
                new JavaInfo(Platform.CURRENT_PLATFORM, major + ".0.1", null),
                false, false);
    }

    /// Treats only the listed paths as present.
    private static Predicate<Path> existing(Path... paths) {
        Set<Path> present = Set.of(paths);
        return present::contains;
    }
}
