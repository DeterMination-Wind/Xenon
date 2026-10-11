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

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the per-session throttle of [GitHubRateLimitPrompt].
@NotNullByDefault
public final class GitHubRateLimitPromptTest {

    private static final long BASE_TIME = 1_000_000_000L;

    @BeforeEach
    public void resetThrottle() {
        GitHubRateLimitPrompt.resetThrottleForTesting();
    }

    /// Three prompts are allowed per session when they are spaced apart.
    @Test
    public void allowsThreeSpacedPromptsPerSession() {
        long now = BASE_TIME;
        assertTrue(GitHubRateLimitPrompt.tryAcquire(now));
        now += GitHubRateLimitPrompt.MIN_INTERVAL_MILLIS;
        assertTrue(GitHubRateLimitPrompt.tryAcquire(now));
        now += GitHubRateLimitPrompt.MIN_INTERVAL_MILLIS;
        assertTrue(GitHubRateLimitPrompt.tryAcquire(now));
    }

    /// A prompt inside the minimum interval is suppressed.
    @Test
    public void throttlesRapidPrompts() {
        assertTrue(GitHubRateLimitPrompt.tryAcquire(BASE_TIME));
        assertFalse(GitHubRateLimitPrompt.tryAcquire(BASE_TIME + 1L));
        assertFalse(GitHubRateLimitPrompt.tryAcquire(
                BASE_TIME + GitHubRateLimitPrompt.MIN_INTERVAL_MILLIS - 1L));
        assertTrue(GitHubRateLimitPrompt.tryAcquire(
                BASE_TIME + GitHubRateLimitPrompt.MIN_INTERVAL_MILLIS));
    }

    /// The fourth prompt of a session is suppressed even after the interval.
    @Test
    public void suppressesPromptsBeyondSessionLimit() {
        long now = BASE_TIME;
        for (int i = 0; i < GitHubRateLimitPrompt.MAX_PROMPTS_PER_SESSION; i++) {
            assertTrue(GitHubRateLimitPrompt.tryAcquire(now));
            now += GitHubRateLimitPrompt.MIN_INTERVAL_MILLIS;
        }
        assertFalse(GitHubRateLimitPrompt.tryAcquire(now));
        assertFalse(GitHubRateLimitPrompt.tryAcquire(now + 24L * 60L * 60L * 1000L));
    }

    /// Failures without a rate-limit cause never prompt.
    @Test
    public void ignoresOtherFailures() {
        assertFalse(GitHubRateLimitPrompt.showIfRateLimited(null));
        assertFalse(GitHubRateLimitPrompt.showIfRateLimited(new IOException("boom")));
        assertFalse(GitHubRateLimitPrompt.showIfRateLimited(
                new RuntimeException("wrapped", new IllegalStateException("inner"))));
    }
}
