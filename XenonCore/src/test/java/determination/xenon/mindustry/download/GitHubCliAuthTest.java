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
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests GitHub CLI token probing.
@NotNullByDefault
public final class GitHubCliAuthTest {

    /// A logged-in CLI prints the token on standard output.
    @Test
    public void readsTokenWhenLoggedIn() {
        GitHubCliAuth.CommandRunner runner = fixed(0, "gho_1234567890\n", "");

        assertEquals("gho_1234567890", GitHubCliAuth.readToken(runner).orElseThrow());
    }

    /// A missing executable is reported as an empty result instead of an error.
    @Test
    public void returnsEmptyWhenCliIsMissing() {
        GitHubCliAuth.CommandRunner runner = (command, timeout) -> {
            throw new IOException("Cannot run program \"gh\"");
        };

        assertTrue(GitHubCliAuth.readToken(runner).isEmpty());
    }

    /// A non-zero exit code means "not logged in" and yields no token.
    @Test
    public void returnsEmptyOnNonZeroExit() {
        GitHubCliAuth.CommandRunner runner = fixed(1, "", "not logged in");

        assertTrue(GitHubCliAuth.readToken(runner).isEmpty());
    }

    /// A timeout maps to an empty result while preserving the interrupt contract.
    @Test
    public void returnsEmptyOnTimeout() {
        GitHubCliAuth.CommandRunner runner = (command, timeout) -> {
            throw new IOException("Timed out running gh");
        };

        assertTrue(GitHubCliAuth.readToken(runner).isEmpty());
    }

    /// Multi-line output is reduced to the first non-blank line; blank output yields nothing.
    @Test
    public void cleansMultiLineAndBlankOutput() {
        GitHubCliAuth.CommandRunner multiLine = fixed(0, "\n   gho_abc   \nwarning: stale config\n", "");
        GitHubCliAuth.CommandRunner blank = fixed(0, "  \n\t\n", "");

        assertEquals("gho_abc", GitHubCliAuth.readToken(multiLine).orElseThrow());
        assertTrue(GitHubCliAuth.readToken(blank).isEmpty());
    }

    /// An interrupted wait restores the interrupt flag and yields no token.
    @Test
    public void returnsEmptyWhenInterrupted() {
        GitHubCliAuth.CommandRunner runner = (command, timeout) -> {
            throw new InterruptedException("interrupted");
        };

        assertTrue(GitHubCliAuth.readToken(runner).isEmpty());
        assertTrue(Thread.interrupted(), "Interrupt flag must be restored");
    }

    /// The account is parsed from `gh auth status` output, including stderr.
    @Test
    public void parsesAccountFromStatusOutput() {
        GitHubCliAuth.CommandRunner runner = (command, timeout) -> new GitHubCliAuth.CommandResult(0, "",
                """
                        github.com
                          \u2713 Logged in to github.com account octocat (keyring)
                          - Active account: true
                        """);

        assertEquals("octocat", GitHubCliAuth.readAccount(runner).orElseThrow());
    }

    /// status() reports installation, login state and account for a healthy CLI.
    @Test
    public void reportsHealthyCliStatus() {
        AtomicInteger calls = new AtomicInteger();
        GitHubCliAuth.CommandRunner runner = (command, timeout) -> {
            calls.incrementAndGet();
            if (command.contains("token")) {
                return new GitHubCliAuth.CommandResult(0, "gho_token\n", "");
            }
            return new GitHubCliAuth.CommandResult(0, "Logged in to github.com account octocat\n", "");
        };

        GitHubCliAuth.CliStatus status = GitHubCliAuth.status(runner, true);

        assertTrue(status.installed());
        assertTrue(status.authenticated());
        assertEquals("octocat", status.account());
        assertEquals(2, calls.get());
    }

    /// A machine without the CLI is reported as not installed without spawning anything.
    @Test
    public void reportsMissingCliStatus() {
        GitHubCliAuth.CliStatus status = GitHubCliAuth.status((command, timeout) -> {
            throw new AssertionError("must not run a command");
        }, false);

        assertFalse(status.installed());
        assertFalse(status.authenticated());
    }

    /// An installed but logged-out CLI is reported as unauthenticated.
    @Test
    public void reportsLoggedOutCliStatus() {
        GitHubCliAuth.CommandRunner runner = (command, timeout) ->
                new GitHubCliAuth.CommandResult(1, "", "not logged in");

        GitHubCliAuth.CliStatus status = GitHubCliAuth.status(runner, true);

        assertTrue(status.installed());
        assertFalse(status.authenticated());
    }

    /// The default timeout is the documented five seconds.
    @Test
    public void usesFiveSecondTimeout() {
        assertEquals(Duration.ofSeconds(5), GitHubCliAuth.DEFAULT_TIMEOUT);
    }

    private static GitHubCliAuth.CommandRunner fixed(int exitCode, String stdout, String stderr) {
        return (command, timeout) -> new GitHubCliAuth.CommandResult(exitCode, stdout, stderr);
    }
}
