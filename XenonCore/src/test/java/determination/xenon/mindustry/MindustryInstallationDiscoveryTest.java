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
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests discovery of existing desktop Mindustry installations.
@NotNullByDefault
public final class MindustryInstallationDiscoveryTest {

    /// A Steam-style install is registered as one instance with jar, data dir and bundled JRE.
    @Test
    public void discoversSteamInstallLayout(@TempDir Path tempDir) throws IOException {
        Path install = tempDir.resolve("Mindustry");
        Path jar = install.resolve("jre/desktop.jar");
        Path javaBin = install.resolve("jre/bin");
        Path data = install.resolve("saves");
        Files.createDirectories(javaBin);
        Files.createDirectories(data);
        Files.createFile(javaBin.resolve("java"));
        Files.createFile(javaBin.resolve("java.exe"));
        Files.createFile(install.resolve("steam_api64.dll"));
        Files.createFile(data.resolve("steam_autocloud.vdf"));
        writeJar(jar, """
                build=158.1
                modifier=steam
                type=official
                """);
        Files.writeString(install.resolve("Mindustry.json"), """
                {
                  "jrePath": "jre",
                  "classPath": ["jre/desktop.jar"],
                  "mainClass": "mindustry.desktop.DesktopLauncher",
                  "vmArgs": ["-Dhttps.protocols=TLSv1.2", "--enable-native-access=ALL-UNNAMED"]
                }
                """, StandardCharsets.UTF_8);

        MindustryInstallationDiscovery.DiscoveredInstallation discovered =
                MindustryInstallationDiscovery.discover(install).orElseThrow();

        assertEquals("steam-mindustry", discovered.getSuggestedId());
        assertEquals("Steam Mindustry", discovered.getDisplayName());
        assertEquals(jar.toAbsolutePath().normalize(), discovered.getJar());
        assertEquals(install.resolve("jre").toAbsolutePath().normalize(), discovered.getJavaHome());
        assertEquals(data.toAbsolutePath().normalize(), discovered.getDataDir());
        assertEquals(install.toAbsolutePath().normalize(), discovered.getWorkingDirectory());
        assertEquals(VersionVariant.VANILLA, discovered.getVariant());
        assertEquals(158, discovered.getBuild());
        assertEquals("stable", discovered.getBuildType());
        assertTrue(discovered.getJvmArgs().contains("--enable-native-access=ALL-UNNAMED"));
    }

    private static void writeJar(Path jar, String versionProperties) throws IOException {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("version.properties"));
            zip.write(versionProperties.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
