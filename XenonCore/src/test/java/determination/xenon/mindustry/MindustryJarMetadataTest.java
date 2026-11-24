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
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests reading a Mindustry jar's own build identity.
@NotNullByDefault
public final class MindustryJarMetadataTest {

    /// A real release build reports its numeric build and stable variant.
    @Test
    public void readsOfficialBuild(@TempDir Path tempDir) throws IOException {
        Path jar = writeJar(tempDir, "mindustry-158.jar", """
                number=8
                build=158
                modifier=release
                type=official
                buildDate=November 1, 2026
                commitHash=abcdef1
                """, "mindustry.desktop.DesktopLauncher");

        MindustryJarMetadata metadata = MindustryJarMetadata.read(jar);

        assertEquals(158, metadata.build());
        assertEquals(8, metadata.versionNumber());
        assertEquals("release", metadata.normalizedBuildType());
        assertEquals(VersionVariant.VANILLA, metadata.variantHint());
        assertEquals("abcdef1", metadata.commitHash());
        assertEquals("mindustry.desktop.DesktopLauncher", metadata.mainClass());
        assertTrue(metadata.hasGameMainClass());
        assertTrue(metadata.isMindustryClient());
        assertTrue(metadata.hasVersionProperties());
    }

    /// Bleeding-edge builds use a large build id and must not be treated as v135.
    @Test
    public void readsBleedingEdgeBuild(@TempDir Path tempDir) throws IOException {
        Path jar = writeJar(tempDir, "Mindustry-BE-desktop.jar", """
                number=8
                build=27771
                modifier=custom build
                type=bleeding-edge
                """, "mindustry.desktop.DesktopLauncher");

        MindustryJarMetadata metadata = MindustryJarMetadata.read(jar);

        assertEquals(27771, metadata.build());
        assertEquals(VersionVariant.BE, metadata.variantHint());
        assertEquals("custom", metadata.normalizedBuildType());
    }

    /// A renamed download still resolves its build from the jar, not the name.
    @Test
    public void ignoresMisleadingFileName(@TempDir Path tempDir) throws IOException {
        Path jar = writeJar(tempDir, "mindustry-v135.jar", """
                number=8
                build=158
                modifier=release
                type=official
                """, "mindustry.desktop.DesktopLauncher");

        MindustryJarMetadata metadata = MindustryJarMetadata.read(jar);
        assertEquals(158, metadata.build());
    }

    /// A plain library jar yields empty metadata instead of failing the import.
    @Test
    public void handlesJarWithoutMindustryMarkers(@TempDir Path tempDir) throws IOException {
        Path jar = writeJar(tempDir, "library.jar", null, null);

        MindustryJarMetadata metadata = MindustryJarMetadata.read(jar);

        assertNull(metadata.build());
        assertNull(metadata.mainClass());
        assertFalse(metadata.hasVersionProperties());
        assertFalse(metadata.hasGameMainClass());
        assertFalse(metadata.isMindustryClient());
        assertNull(metadata.variantHint());
        assertTrue(metadata.describe().contains("version.properties"));
    }

    /// A non-numeric build property must not abort the read.
    @Test
    public void toleratesNonNumericBuild(@TempDir Path tempDir) throws IOException {
        Path jar = writeJar(tempDir, "custom.jar", """
                number=8
                build=custom build
                modifier=release
                type=custom
                """, "mindustry.desktop.DesktopLauncher");

        MindustryJarMetadata metadata = MindustryJarMetadata.read(jar);
        assertNull(metadata.build());
        assertNull(metadata.variantHint());
        assertTrue(metadata.isMindustryClient());
    }

    /// Writes a jar with an optional {@code version.properties} and main class.
    ///
    /// @param dir     temporary folder
    /// @param name    jar file name
    /// @param props   body of {@code version.properties}, or `null` to omit the entry
    /// @param main    manifest {@code Main-Class}, or `null` to omit the manifest
    /// @return path of the written jar
    private static Path writeJar(Path dir, String name, @Nullable String props,
                                 @Nullable String main) throws IOException {
        Path jar = dir.resolve(name);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (main != null) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, main);
        }
        try (OutputStream raw = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(raw, manifest)) {
            if (props != null) {
                out.putNextEntry(new JarEntry(MindustryJarMetadata.VERSION_PROPERTIES));
                out.write(props.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            } else {
                out.putNextEntry(new JarEntry("META-INF/marker.txt"));
                out.write("x".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }
}
