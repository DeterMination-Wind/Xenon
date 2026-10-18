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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the last-gate Java validation of [XenonLauncher].
@NotNullByDefault
public final class XenonLauncherTest {

    /// A missing Java executable is reported as [MindustryJavaMissingException]
    /// before the operating system can produce `CreateProcess error=2`.
    @Test
    public void rejectsMissingJavaExecutable(@TempDir Path tempDir) {
        Path missingJava = tempDir.resolve("jre").resolve("bin").resolve("java.exe");
        Path dataDir = tempDir.resolve("data");
        LaunchOptions options = LaunchOptions.builder()
                .javaExecutable(missingJava)
                .jar(tempDir.resolve("desktop.jar"))
                .dataDir(dataDir)
                .build();

        MindustryJavaMissingException exception = assertThrows(MindustryJavaMissingException.class,
                () -> XenonLauncher.launch(options, null, null));

        assertEquals(missingJava, exception.getJavaExecutable());
        assertTrue(exception.getMessage().contains(missingJava.toString()),
                "the message must name the missing path: " + exception.getMessage());
        assertFalse(Files.exists(dataDir), "the data directory must not be prepared without Java");
    }

    /// A directory named like the executable is not treated as a runtime.
    @Test
    public void rejectsDirectoryInsteadOfJavaExecutable(@TempDir Path tempDir) throws Exception {
        Path javaDirectory = Files.createDirectories(tempDir.resolve("jre").resolve("bin").resolve("java"));
        LaunchOptions options = LaunchOptions.builder()
                .javaExecutable(javaDirectory)
                .jar(tempDir.resolve("desktop.jar"))
                .dataDir(tempDir.resolve("data"))
                .build();

        MindustryJavaMissingException exception = assertThrows(MindustryJavaMissingException.class,
                () -> XenonLauncher.launch(options, null, null));

        assertEquals(javaDirectory, exception.getJavaExecutable());
    }
}
