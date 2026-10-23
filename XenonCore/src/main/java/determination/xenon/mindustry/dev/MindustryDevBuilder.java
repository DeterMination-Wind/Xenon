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
package determination.xenon.mindustry.dev;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

/// Runs one development build command and locates its artifact.
///
/// The command is executed through the platform shell (`cmd /c` on Windows,
/// `sh -c` elsewhere) in the project directory. Output is streamed to the
/// caller; a timeout or cancellation destroys the process. The artifact is the
/// newest file matching the configured glob, defaulting to `build/libs/*.jar`.
@NotNullByDefault
public final class MindustryDevBuilder {

    /// Default artifact glob.
    public static final String DEFAULT_GLOB = "build/libs/*.jar";

    /// Build configuration.
    ///
    /// @param projectDir   project directory
    /// @param command      shell command, or empty to use `./gradlew build`
    /// @param artifactGlob glob relative to the project directory
    /// @param timeout      maximum runtime
    public record Options(Path projectDir, String command, String artifactGlob, Duration timeout) {
    }

    /// One build result.
    ///
    /// @param exitCode whether the command exited successfully
    /// @param artifact newest matching artifact, or `null`
    public record Result(int exitCode, @Nullable Path artifact) {
        /// Whether the build succeeded.
        ///
        /// @return `true` for exit code 0
        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    /// Runs one build.
    ///
    /// @param options   build configuration
    /// @param log       output receiver
    /// @param cancelled cancellation flag, or `null`
    /// @return the exit code and artifact
    /// @throws IOException when the process cannot start
    public static Result build(Options options, Consumer<String> log,
                               @Nullable AtomicBoolean cancelled) throws IOException {
        if (!Files.isDirectory(options.projectDir())) {
            throw new IOException("project directory does not exist: " + options.projectDir());
        }
        String command = options.command() == null || options.command().isBlank()
                ? defaultCommand() : options.command();
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        List<String> invocation = windows
                ? List.of("cmd.exe", "/c", command)
                : List.of("sh", "-c", command);
        ProcessBuilder builder = new ProcessBuilder(invocation)
                .directory(options.projectDir().toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        Thread reader = new Thread(() -> pump(process.getInputStream(), log),
                "xenon-dev-build-output");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.nanoTime()
                + Math.max(1, options.timeout().toSeconds()) * 1_000_000_000L;
        int exitCode;
        try {
            while (true) {
                if (cancelled != null && cancelled.get()) {
                    process.destroyForcibly();
                    throw new IOException("build cancelled");
                }
                if (System.nanoTime() > deadline) {
                    process.destroyForcibly();
                    throw new IOException("build timed out");
                }
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    exitCode = process.exitValue();
                    break;
                }
            }
            try {
                reader.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Path artifact = exitCode == 0 ? findArtifact(options) : null;
            return new Result(exitCode, artifact);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("build interrupted", e);
        }
    }

    /// Finds the newest artifact matching the glob.
    ///
    /// @param options build configuration
    /// @return the newest match, or `null`
    /// @throws IOException when the project cannot be walked
    public static @Nullable Path findArtifact(Options options) throws IOException {
        String glob = options.artifactGlob() == null || options.artifactGlob().isBlank()
                ? DEFAULT_GLOB : options.artifactGlob();
        PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        } catch (RuntimeException e) {
            throw new IOException("invalid artifact glob: " + glob, e);
        }
        try (Stream<Path> stream = Files.walk(options.projectDir())) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> matcher.matches(
                            options.projectDir().relativize(path)))
                    .max(Comparator.comparingLong(MindustryDevBuilder::modified))
                    .orElse(null);
        }
    }

    /// Reads one file's modification time.
    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /// Streams process output line by line.
    private static void pump(InputStream input, Consumer<String> log) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.accept(line);
            }
        } catch (IOException ignored) {
            // The process ended; nothing else to read.
        }
    }

    /// Default command for the current platform.
    private static String defaultCommand() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        return windows ? "gradlew.bat build" : "./gradlew build";
    }

    private MindustryDevBuilder() {
    }
}
