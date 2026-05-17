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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
                                          Consumer<String> stdout,
                                          Consumer<String> stderr) throws IOException {
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

        Logger.LOG.info("Xenon launching: " + String.join(" ", options.buildCommandLine()));
        Process process = pb.start();

        Thread tOut = pump(process.getInputStream(), stdout != null ? stdout : XenonLauncher::sink, "xenon-stdout");
        Thread tErr = pump(process.getErrorStream(), stderr != null ? stderr : XenonLauncher::sink, "xenon-stderr");

        return new MindustryProcess(process, tOut, tErr);
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

    /** Handle to a running Mindustry process plus its log-pump threads. */
    public static final class MindustryProcess {
        private final Process process;
        private final Thread stdoutThread;
        private final Thread stderrThread;

        MindustryProcess(Process process, Thread stdoutThread, Thread stderrThread) {
            this.process = process;
            this.stdoutThread = stdoutThread;
            this.stderrThread = stderrThread;
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
                return p.exitValue();
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
