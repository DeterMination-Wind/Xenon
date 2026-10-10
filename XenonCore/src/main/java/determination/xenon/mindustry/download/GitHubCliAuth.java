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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Reads GitHub credentials from the official `gh` command line tool.
///
/// The token is executed and held in memory only; it is never written to the
/// launcher configuration. Command execution goes through an injectable
/// {@link CommandRunner} so tests can simulate a logged-in, missing, failing
/// or slow CLI without spawning real processes.
@NotNullByDefault
public final class GitHubCliAuth {

    /// Execution budget for a single `gh` invocation.
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /// Command name resolved from `PATH` (Windows resolves `gh.exe`).
    private static final String COMMAND = "gh";

    /// Pattern matching the account name reported by `gh auth status`.
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("account\\s+(\\S+)");

    /// Executes one command and captures its output.
    @FunctionalInterface
    public interface CommandRunner {
        /// Runs `command` and waits up to `timeout`.
        ///
        /// @param command command and arguments to spawn
        /// @param timeout maximum wait before the process is killed
        /// @return the exit code together with captured standard output and error
        /// @throws IOException when the process cannot be started or exceeds `timeout`
        /// @throws InterruptedException when the calling thread is interrupted while waiting
        CommandResult run(List<String> command, Duration timeout) throws IOException, InterruptedException;
    }

    /// Captured outcome of one command invocation.
    ///
    /// @param exitCode process exit code
    /// @param stdout   captured standard output, decoded as UTF-8
    /// @param stderr   captured standard error, decoded as UTF-8
    public record CommandResult(int exitCode, String stdout, String stderr) {
    }

    /// Authentication state of the local GitHub CLI.
    ///
    /// @param installed     whether a `gh` executable exists on `PATH`
    /// @param authenticated whether `gh auth token` returned a token
    /// @param account       login name reported by `gh auth status`, when parseable
    public record CliStatus(boolean installed, boolean authenticated, @Nullable String account) {
    }

    /// Runner used when no test double is supplied.
    private static final CommandRunner DEFAULT_RUNNER = GitHubCliAuth::runProcess;

    private GitHubCliAuth() {
    }

    /// Searches `PATH` for a GitHub CLI executable.
    ///
    /// @return the executable path, or empty when `gh` is not installed
    public static Optional<Path> detect() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        List<String> names = executableNames();
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String name : names) {
                try {
                    Path candidate = Path.of(dir, name);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate);
                    }
                } catch (InvalidPathException ignored) {
                    // Malformed PATH entry — keep searching.
                }
            }
        }
        return Optional.empty();
    }

    /// Runs `gh auth token` and returns the first non-blank output line.
    ///
    /// @return the token, or empty when `gh` is missing, not logged in, fails or returns nothing
    public static Optional<String> readToken() {
        return readToken(DEFAULT_RUNNER);
    }

    /// Test seam for {@link #readToken()}.
    static Optional<String> readToken(CommandRunner runner) {
        try {
            CommandResult result = runner.run(List.of(COMMAND, "auth", "token"), DEFAULT_TIMEOUT);
            if (result.exitCode() != 0) {
                return Optional.empty();
            }
            return firstNonBlankLine(result.stdout());
        } catch (IOException ex) {
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /// Runs `gh auth status` and extracts the active account login.
    ///
    /// @return the account name, or empty when it cannot be determined
    public static Optional<String> readAccount() {
        return readAccount(DEFAULT_RUNNER);
    }

    /// Test seam for {@link #readAccount()}.
    static Optional<String> readAccount(CommandRunner runner) {
        try {
            CommandResult result = runner.run(List.of(COMMAND, "auth", "status"), DEFAULT_TIMEOUT);
            String output = result.stdout() + "\n" + result.stderr();
            Matcher matcher = ACCOUNT_PATTERN.matcher(output);
            if (matcher.find()) {
                return Optional.of(matcher.group(1));
            }
            return Optional.empty();
        } catch (IOException ex) {
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /// Probes the local CLI once for installation, login state and account.
    ///
    /// May spawn up to two processes; callers should invoke it off the UI thread.
    ///
    /// @return the aggregated status; never `null`
    public static CliStatus status() {
        return status(DEFAULT_RUNNER, detect().isPresent());
    }

    /// Test seam for {@link #status()} that receives the installation probe result.
    static CliStatus status(CommandRunner runner, boolean installed) {
        if (!installed) {
            return new CliStatus(false, false, null);
        }
        Optional<String> token = readToken(runner);
        String account = readAccount(runner).orElse(null);
        return new CliStatus(true, token.isPresent(), account);
    }

    /// Returns the executable names to probe, most specific first.
    private static List<String> executableNames() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            // PATH usually holds a bare `gh`, but a manual install may only
            // register the extension-bearing variants.
            return List.of("gh.exe", "gh.cmd", "gh.bat");
        }
        return List.of("gh");
    }

    /// Extracts the first non-blank line, mirroring how `gh auth token` prints.
    private static Optional<String> firstNonBlankLine(@Nullable String output) {
        if (output == null) return Optional.empty();
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return Optional.of(trimmed);
            }
        }
        return Optional.empty();
    }

    /// Default runner: spawns the process, waits with a timeout and captures output.
    private static CommandResult runProcess(List<String> command, Duration timeout) throws IOException {
        Process process = new ProcessBuilder(command).start();
        process.getOutputStream().close();
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + command.get(0), ex);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Timed out running " + command.get(0));
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CommandResult(process.exitValue(), stdout, stderr);
    }
}
