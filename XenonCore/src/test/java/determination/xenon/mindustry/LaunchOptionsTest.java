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

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests Mindustry process command line assembly.
@NotNullByDefault
public final class LaunchOptionsTest {

    /// Steam external instances still launch java -jar desktop.jar with the Steam saves dir injected.
    @Test
    public void buildsSteamExternalInstallCommandLine(@TempDir Path tempDir) {
        Path install = tempDir.resolve("Steam/steamapps/common/Mindustry");
        Path jar = install.resolve("jre/desktop.jar");
        Path dataDir = install.resolve("saves");
        Path java = install.resolve("jre/bin/java.exe");

        LaunchOptions options = LaunchOptions.builder()
                .javaExecutable(java)
                .jar(jar)
                .workingDirectory(install)
                .dataDir(dataDir)
                .build();

        List<String> command = options.buildCommandLine();

        assertEquals(install, options.getWorkingDirectory());
        assertTrue(command.contains("-Dmindustry.data.dir=" + dataDir.toAbsolutePath()));
        int jarSwitch = command.indexOf("-jar");
        assertTrue(jarSwitch >= 0);
        assertEquals(jar.toAbsolutePath().toString(), command.get(jarSwitch + 1));
    }
}
