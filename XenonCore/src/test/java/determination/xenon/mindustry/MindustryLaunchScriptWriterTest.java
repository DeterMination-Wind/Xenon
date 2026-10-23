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

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the standalone launch-script renderer.
@NotNullByDefault
public final class MindustryLaunchScriptWriterTest {

    /// Windows scripts export the data directory and quote every argument.
    @Test
    public void rendersWindowsScript() {
        LaunchOptions options = options();
        String script = MindustryLaunchScriptWriter.renderWindows(options);
        assertTrue(script.contains("set \"MINDUSTRY_DATA_DIR="));
        assertTrue(script.contains("-Dmindustry.data.dir="));
        assertTrue(script.contains("\"%MINDUSTRY_DATA_DIR%\"") == false);
        assertTrue(script.contains("\"java\"") || script.contains("\\java\""));
        assertTrue(script.contains("\"-jar\""));
    }

    /// Unix scripts export the data directory and use single-quote escaping.
    @Test
    public void rendersUnixScript() {
        String script = MindustryLaunchScriptWriter.renderUnix(options());
        assertTrue(script.startsWith("#!/bin/sh"));
        assertTrue(script.contains("export MINDUSTRY_DATA_DIR='"));
        assertTrue(script.contains("exec "));
        assertTrue(script.contains("'--data'"));
    }

    /// Builds one representative launch command.
    private static LaunchOptions options() {
        return LaunchOptions.builder()
                .javaExecutable(Path.of("java"))
                .jar(Path.of("server.jar"))
                .workingDirectory(Path.of("work dir"))
                .dataDir(Path.of("data dir"))
                .addGameArgs("--data")
                .build();
    }
}
