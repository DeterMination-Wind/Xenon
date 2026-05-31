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
package determination.xenon.mindustry.download;

import determination.xenon.JavaFXLauncher;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/// Tests Mindustry-specific download source selection and local reuse.
@NotNullByDefault
public final class MindustryDownloadTaskTest {
    /// Starts the JavaFX toolkit because `Task` progress updates use `Platform.runLater`.
    @BeforeAll
    public static void startJavaFx() {
        JavaFXLauncher.start();
    }

    /// Ensures a matching local jar is copied before the network downloader runs.
    @Test
    public void copiesReusableJarBeforeNetwork(@TempDir Path tempDir) throws Exception {
        byte[] content = "local".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path source = tempDir.resolve("source.jar");
        Path target = tempDir.resolve("target.jar");
        Files.write(source, content);

        MindustryDownloadTask task = new MindustryDownloadTask(
                "https://example.invalid/should-not-be-requested.jar",
                target,
                content.length,
                tempDir,
                List.of(source));

        task.run();

        assertArrayEquals(content, Files.readAllBytes(target));
    }
}
