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
package determination.xenon.setting;

import determination.xenon.mindustry.download.GitHubAuth;
import determination.xenon.mindustry.download.GitHubCliAuth;
import determination.xenon.task.Schedulers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/// Resolves which GitHub credential XenonCore should use for API requests.
///
/// Priority: a manually entered token always wins; otherwise the local GitHub
/// CLI is consulted when the user enabled it; otherwise requests stay
/// anonymous. The resolved token is pushed into
/// {@link GitHubAuth} and never persisted to the configuration file.
@NotNullByDefault
public final class GitHubCliTokenSource {

    /// Where the credential currently in use came from.
    public enum Source {
        /// The token typed into the download settings page.
        MANUAL,
        /// The account configured in the local `gh` CLI.
        GH_CLI,
        /// No credential; GitHub's anonymous rate limit applies.
        ANONYMOUS
    }

    /// One resolved credential.
    ///
    /// @param source  where the token came from
    /// @param token   token handed to GitHub API calls; empty in anonymous mode
    /// @param account account login when known, otherwise `null`
    public record Resolved(Source source, String token, @Nullable String account) {
    }

    /// Latest resolved credential, for status displays.
    private static final AtomicReference<Resolved> CURRENT =
            new AtomicReference<>(new Resolved(Source.ANONYMOUS, "", null));

    /// Monotonic id used to drop stale asynchronous `gh` probes.
    private static final AtomicLong GENERATION = new AtomicLong();

    private GitHubCliTokenSource() {
    }

    /// The credential currently installed into {@link GitHubAuth}.
    public static Resolved resolved() {
        return CURRENT.get();
    }

    /// Resolves and installs the credential matching the current settings.
    ///
    /// Fast paths (manual token present, switch disabled) update
    /// {@link GitHubAuth} synchronously; the `gh` lookup runs on the IO
    /// scheduler so callers never block the UI thread.
    ///
    /// @param manualToken value of the manual token setting; may be blank
    /// @param ghEnabled   whether the GitHub CLI fallback is enabled
    public static void apply(@Nullable String manualToken, boolean ghEnabled) {
        String manual = manualToken == null ? "" : manualToken.trim();
        long generation = GENERATION.incrementAndGet();
        if (!manual.isEmpty()) {
            publish(generation, new Resolved(Source.MANUAL, manual, null));
            return;
        }
        if (!ghEnabled) {
            publish(generation, new Resolved(Source.ANONYMOUS, "", null));
            return;
        }
        Schedulers.io().execute(() -> {
            String token = GitHubCliAuth.readToken().orElse("");
            if (token.isEmpty()) {
                publish(generation, new Resolved(Source.ANONYMOUS, "", null));
                return;
            }
            String account = GitHubCliAuth.readAccount().orElse(null);
            publish(generation, new Resolved(Source.GH_CLI, token, account));
        });
    }

    /// Probes the CLI for the settings page status line.
    ///
    /// Runs `gh` commands and may block; call from an IO scheduler.
    ///
    /// @return the CLI status; never `null`
    public static GitHubCliAuth.CliStatus cliStatus() {
        return GitHubCliAuth.status();
    }

    /// Publishes a resolution unless a newer request has superseded it.
    private static void publish(long generation, Resolved resolved) {
        if (generation != GENERATION.get()) {
            return;
        }
        CURRENT.set(resolved);
        GitHubAuth.setToken(resolved.token());
    }
}
