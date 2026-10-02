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

import determination.xenon.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Verifies that the launcher captures process output even when the game JVM
/// dies before Mindustry could write its own `last_log.txt`.
@NotNullByDefault
public final class XenonLauncherLogTest {

    /// Records the failing JVM output into the launcher log file.
    @Test
    public void capturesOutputOfFailingProcess(@TempDir Path tempDir) throws Exception {
        Path jar = writeJarWithoutMainClass(tempDir.resolve("broken.jar"));
        Path dataDir = tempDir.resolve("data");
        Path logFile = tempDir.resolve("logs").resolve("launch.log");

        LaunchOptions options = LaunchOptions.builder()
                .javaExecutable(currentJava())
                .jar(jar)
                .dataDir(dataDir)
                .launchLogFile(logFile)
                .build();

        XenonLauncher.MindustryProcess process = XenonLauncher.launch(options, null, null);
        int exitCode = process.onExit().get(60, SECONDS);

        assertNotEquals(0, exitCode, "a jar without a main class must fail");
        assertTrue(Files.isRegularFile(logFile), "launcher log was not created: " + logFile);
        String log = Files.readString(logFile, StandardCharsets.UTF_8);
        assertTrue(log.contains("# Command: "), "header missing:\n" + log);
        assertTrue(log.contains("[stderr]") && log.contains("does.not.Exist"),
                "JVM error output missing:\n" + log);
        assertTrue(log.contains("# Process exited with code " + exitCode),
                "exit footer missing:\n" + log);
    }

    /// Resolves the `java` executable of the JVM running the tests.
    private static Path currentJava() {
        String binary = OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home")).resolve("bin").resolve(binary);
        if (!Files.isRegularFile(java)) {
            fail("Test JVM executable not found: " + java);
        }
        return java;
    }

    /// Writes a syntactically valid jar whose manifest points at a missing class.
    private static Path writeJarWithoutMainClass(Path file) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "does.not.Exist");
        try (OutputStream out = Files.newOutputStream(file);
             JarOutputStream jar = new JarOutputStream(out, manifest)) {
            jar.putNextEntry(new ZipEntry("README.txt"));
            jar.write("stub".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return file;
    }
}
