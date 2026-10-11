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
package determination.xenon.mindustry.ui;

import com.jfoenix.controls.JFXButton;
import determination.xenon.mindustry.download.GitHubRateLimitException;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.ui.main.LauncherSettingsPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static determination.xenon.util.i18n.I18n.i18n;

/// Shows a throttled dialog that guides the user to configure a GitHub token
/// when an operation fails because the GitHub API rate limit was reached.
///
/// The prompt is shown only for explicit user actions; background refreshes
/// should log the failure instead of interrupting the user. At most three
/// prompts are shown per launcher session, and never twice within five
/// minutes, so a rate-limited mirror cannot spam dialogs.
@NotNullByDefault
public final class GitHubRateLimitPrompt {

    /// Maximum number of prompts shown per launcher session.
    static final int MAX_PROMPTS_PER_SESSION = 3;

    /// Minimum delay between two prompts, in milliseconds.
    static final long MIN_INTERVAL_MILLIS = 5L * 60L * 1000L;

    private static final Object THROTTLE_LOCK = new Object();
    private static final DateTimeFormatter RESET_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private static long lastPromptAt = Long.MIN_VALUE;
    private static int promptCount;

    private GitHubRateLimitPrompt() {
    }

    /// Shows the GitHub-token guidance dialog when {@code throwable} is a rate
    /// limit failure and the per-session throttle allows another prompt.
    ///
    /// @param throwable the failure thrown by an explicit user action, or `null`
    /// @return `true` when the guidance dialog was shown
    public static boolean showIfRateLimited(@Nullable Throwable throwable) {
        GitHubRateLimitException rateLimit = findRateLimit(throwable);
        if (rateLimit == null) {
            return false;
        }
        if (!tryAcquire(System.currentTimeMillis())) {
            return false;
        }

        JFXButton settingsButton = new JFXButton(i18n("xenon.github.ratelimit.action"));
        settingsButton.getStyleClass().add("dialog-accept");
        settingsButton.setOnAction(e -> {
            LauncherSettingsPage page = Controllers.getSettingsPage();
            page.showDownloadSettings();
            Controllers.navigate(page);
        });
        Controllers.confirmAction(message(rateLimit), i18n("xenon.github.ratelimit.title"),
                MessageDialogPane.MessageType.WARNING, settingsButton);
        return true;
    }

    /// Consumes one prompt slot when the session throttle allows it.
    ///
    /// @param nowMillis the current wall-clock time in milliseconds
    /// @return `true` when a prompt may be shown now
    static boolean tryAcquire(long nowMillis) {
        synchronized (THROTTLE_LOCK) {
            if (promptCount >= MAX_PROMPTS_PER_SESSION) {
                return false;
            }
            if (promptCount > 0 && nowMillis - lastPromptAt < MIN_INTERVAL_MILLIS) {
                return false;
            }
            promptCount++;
            lastPromptAt = nowMillis;
            return true;
        }
    }

    /// Clears the throttle state so tests start from a fresh session.
    static void resetThrottleForTesting() {
        synchronized (THROTTLE_LOCK) {
            promptCount = 0;
            lastPromptAt = Long.MIN_VALUE;
        }
    }

    /// Walks the cause chain looking for a rate limit failure.
    private static @Nullable GitHubRateLimitException findRateLimit(@Nullable Throwable throwable) {
        Throwable current = throwable;
        // Guard against self-referencing cause chains with a fixed depth.
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof GitHubRateLimitException rateLimit) {
                return rateLimit;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }

    /// Builds the guidance text for anonymous and token-based rate limits.
    private static String message(GitHubRateLimitException exception) {
        if (!exception.isTokenConfigured()) {
            return i18n("xenon.github.ratelimit.anonymous");
        }
        Instant resetAt = exception.getResetAt();
        String resetSuffix = resetAt == null
                ? ""
                : i18n("xenon.github.ratelimit.resets_at", RESET_FORMATTER.format(resetAt));
        return i18n("xenon.github.ratelimit.token", resetSuffix);
    }
}
