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

import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Spawns a Mindustry client jar with the given {@link LaunchOptions}.
 *
 * <p>Stays out of HMCL's launching machinery on purpose: Mindustry
 * doesn't have libraries / asset indexes / auth, so dragging the MC
 * launcher pipeline into this code path would be a net loss. The whole
 * thing is one {@link ProcessBuilder} plus two reader threads.</p>
 */
@NotNullByDefault
public final class XenonLauncher {

    private XenonLauncher() {
    }

    /**
     * Start the Mindustry process. The data directory is created if it
     * does not exist yet. Caller controls how stdout/stderr lines are
     * consumed via {@code stdout}/{@code stderr}; pass {@code null} to
     * silently drop them.
     */
    public static MindustryProcess launch(LaunchOptions options,
                                          @Nullable Consumer<String> stdout,
                                          @Nullable Consumer<String> stderr) throws IOException {
        Files.createDirectories(options.getDataDir());

        ProcessBuilder pb = new ProcessBuilder(options.buildCommandLine());
        if (options.getWorkingDirectory() != null) {
            Files.createDirectories(options.getWorkingDirectory());
            pb.directory(options.getWorkingDirectory().toFile());
        }
        // Mindustry honours mindustry.data.dir as a JVM property, but
        // a few mods (and the server) also fall back to MINDUSTRY_DATA_DIR.
        pb.environment().put("MINDUSTRY_DATA_DIR", options.getDataDir().toAbsolutePath().toString());
        pb.redirectErrorStream(false);

        @Nullable LaunchLogWriter log = LaunchLogWriter.open(options.getLaunchLogFile());
        if (log != null) {
            log.writeHeader(options.buildCommandLine());
        }

        Logger.LOG.info("Xenon launching: " + String.join(" ", options.buildCommandLine()));
        Process process;
        try {
            process = pb.start();
        } catch (IOException ex) {
            // Nothing will write to the capture file when the JVM never started.
            if (log != null) {
                log.close(null);
            }
            throw ex;
        }

        Thread tOut = pump(process.getInputStream(), tee(stdout, log, null), "xenon-stdout");
        Thread tErr = pump(process.getErrorStream(), tee(stderr, log, "[stderr] "), "xenon-stderr");

        return new MindustryProcess(process, tOut, tErr, log);
    }

    /// Combines the caller's line consumer with the launcher-captured log.
    ///
    /// The captured file always receives the line; `delegate` (when present)
    /// keeps receiving the unprefixed line so the in-memory recent-line buffer
    /// and the dialog preview are unaffected by the file tagging.
    private static Consumer<String> tee(@Nullable Consumer<String> delegate,
                                        @Nullable LaunchLogWriter log,
                                        @Nullable String prefix) {
        if (log == null) {
            return delegate != null ? delegate : XenonLauncher::sink;
        }
        return line -> {
            log.write(prefix == null ? line : prefix + line);
            if (delegate != null) {
                delegate.accept(line);
            }
        };
    }

    private static Thread pump(InputStream stream, Consumer<String> consumer, String name) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    consumer.accept(line);
                }
            } catch (IOException ignored) {
                // pipe broke when the process exited; treat as EOF.
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void sink(String line) {
        // dropped on purpose
    }

    /// Appends the launcher-captured process output to one log file.
    ///
    /// Lines are flushed as they arrive so the file stays usable after a hard
    /// crash of the game (or of the launcher). Failures disable further
    /// writes instead of aborting the pump threads.
    private static final class LaunchLogWriter {

        /// Destination file, kept open for the whole process lifetime.
        private final BufferedWriter writer;

        /// Set once writing failed; all later writes become no-ops.
        private boolean broken;

        /// Opens the capture file, returning `null` when the file is absent or cannot be created.
        private static @Nullable LaunchLogWriter open(@Nullable Path file) {
            if (file == null) {
                return null;
            }
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                return new LaunchLogWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE));
            } catch (IOException | RuntimeException ex) {
                Logger.LOG.warning("Unable to create the Mindustry launch log " + file + ": " + ex);
                return null;
            }
        }

        private LaunchLogWriter(BufferedWriter writer) {
            this.writer = writer;
        }

        /// Writes the capture header describing the spawned command line.
        private synchronized void writeHeader(List<String> commandLine) {
            writeRaw("# Xenon launcher capture — " + OffsetDateTime.now());
            writeRaw("# Command: " + String.join(" ", commandLine));
        }

        /// Appends one output line, flushing immediately.
        private synchronized void write(String line) {
            writeRaw(line);
        }

        /// Appends the exit code footer and closes the file.
        private synchronized void close(@Nullable Integer exitCode) {
            if (!broken && exitCode != null) {
                writeRaw("# Process exited with code " + exitCode);
            }
            try {
                writer.close();
            } catch (IOException ex) {
                Logger.LOG.warning("Unable to close the Mindustry launch log: " + ex);
            }
        }

        /// Writes one line, disabling the writer after the first failure.
        private void writeRaw(String line) {
            if (broken) {
                return;
            }
            try {
                writer.write(line);
                writer.newLine();
                writer.flush();
            } catch (IOException ex) {
                broken = true;
                Logger.LOG.warning("Unable to write the Mindustry launch log: " + ex);
            }
        }
    }

    /** Handle to a running Mindustry process plus its log-pump threads. */
    public static final class MindustryProcess {
        private final Process process;
        private final Thread stdoutThread;
        private final Thread stderrThread;
        private final @Nullable LaunchLogWriter log;

        MindustryProcess(Process process,
                         Thread stdoutThread,
                         Thread stderrThread,
                         @Nullable LaunchLogWriter log) {
            this.process = process;
            this.stdoutThread = stdoutThread;
            this.stderrThread = stderrThread;
            this.log = log;
        }

        public Process getProcess() {
            return process;
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public CompletableFuture<Integer> onExit() {
            return process.onExit().thenApply(p -> {
                try {
                    stdoutThread.join(2000);
                    stderrThread.join(2000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                int exitCode = p.exitValue();
                if (log != null) {
                    log.close(exitCode);
                }
                return exitCode;
            });
        }

        public void destroy() {
            process.destroy();
        }

        public void destroyForcibly() {
            process.destroyForcibly();
        }
    }
}
