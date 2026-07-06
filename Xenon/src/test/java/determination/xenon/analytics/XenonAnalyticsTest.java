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
package determination.xenon.analytics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for Xenon's PostHog event construction and reporting timing.
public final class XenonAnalyticsTest {

    /// CPU architecture labels are normalized for dashboard readability.
    @Test
    public void normalizeCpuArch() {
        assertEquals("x64", XenonAnalytics.normalizeCpuArch("amd64"));
        assertEquals("x64", XenonAnalytics.normalizeCpuArch("x86_64"));
        assertEquals("arm64", XenonAnalytics.normalizeCpuArch("aarch64"));
        assertEquals("arm32", XenonAnalytics.normalizeCpuArch("arm"));
        assertEquals("x86", XenonAnalytics.normalizeCpuArch("i386"));
    }

    /// The session timer becomes due on the ten-minute boundary and reports only deltas.
    @Test
    public void sessionTimerReportsTenMinuteDeltas() {
        Instant start = Instant.parse("2026-07-06T00:00:00Z");
        XenonAnalytics.SessionTimer timer = new XenonAnalytics.SessionTimer(start);

        assertFalse(timer.isDue(start.plus(Duration.ofMinutes(9)).plusSeconds(59), XenonAnalytics.REPORT_INTERVAL));
        assertTrue(timer.isDue(start.plus(Duration.ofMinutes(10)), XenonAnalytics.REPORT_INTERVAL));
        assertEquals(10.0D, timer.elapsedMinutes(start.plus(Duration.ofMinutes(10))), 0.0001D);

        timer.markReported(start.plus(Duration.ofMinutes(10)));
        assertEquals(2.0D, timer.elapsedMinutes(start.plus(Duration.ofMinutes(12))), 0.0001D);
    }

    /// Usage payloads use the selected player UUID for PostHog player fields.
    @Test
    public void usagePropertiesUseSelectedUuidAsPlayerName() {
        XenonAnalytics.PlayerIdentity identity = new XenonAnalytics.PlayerIdentity(
                "uuid-123", "uuid-123", "uuid-123", "Player", "install-1", "player_profile");
        XenonAnalytics.SystemSnapshot system = new XenonAnalytics.SystemSnapshot(
                "Windows 11", 32768, "21.0.3", "x64", 16, "zh_CN");

        Map<String, Object> properties = XenonAnalytics.buildUsageProperties(identity, 10.5D, true, system);

        assertEquals("xenon_event", XenonAnalytics.EVENT_USAGE);
        assertEquals("uuid-123", properties.get("player_id"));
        assertEquals("uuid-123", properties.get("player_name"));
        assertEquals("Player", properties.get("player_nickname"));
        assertEquals(10.5D, properties.get("usage_minutes"));
        assertEquals(true, properties.get("is_first_launch"));
        assertNotEquals("unknown", properties.get("player_name"));
    }

    /// Crash payloads carry the Xenon exception details and the same player identity fields.
    @Test
    public void crashPropertiesUseSelectedUuidIdentity() {
        XenonAnalytics.PlayerIdentity identity = new XenonAnalytics.PlayerIdentity(
                "uuid-123", "uuid-123", "uuid-123", "Player", "install-1", "player_profile");
        XenonAnalytics.SystemSnapshot system = new XenonAnalytics.SystemSnapshot(
                "Linux", 8192, "17.0.12", "arm64", 8, "en_US");
        RuntimeException throwable = new RuntimeException("boom");

        Map<String, Object> properties = XenonAnalytics.buildCrashProperties(
                throwable, "test-thread", identity, system);

        assertEquals("boom", properties.get("error_message"));
        assertEquals("RuntimeException", properties.get("error_type"));
        assertTrue(properties.get("stack_trace").toString().contains("boom"));
        assertEquals("uuid-123", properties.get("player_id"));
        assertEquals("uuid-123", properties.get("player_name"));
        assertEquals("test-thread", properties.get("thread_name"));
    }

    /// Crash capture immediately flushes the SDK queue.
    @Test
    public void crashCaptureFlushesImmediately() {
        FakeClient client = new FakeClient();
        XenonAnalytics.PlayerIdentity identity = new XenonAnalytics.PlayerIdentity(
                "uuid-123", "uuid-123", "uuid-123", null, "install-1", "player_profile");
        XenonAnalytics.SystemSnapshot system = new XenonAnalytics.SystemSnapshot(
                "macOS 14", 16384, "21.0.3", "arm64", 10, "zh_CN");
        IllegalStateException throwable = new IllegalStateException("failed");

        XenonAnalytics.captureCrashWithClient(client, Thread.currentThread(), throwable, identity, system);

        assertEquals(1, client.captureCalls);
        assertEquals(1, client.flushCalls);
        assertEquals("uuid-123", client.distinctId);
        assertEquals(XenonAnalytics.EVENT_CRASH, client.event);
        assertEquals("failed", client.properties.get("error_message"));
    }

    private static final class FakeClient implements XenonAnalytics.AnalyticsClient {
        private int captureCalls;
        private int flushCalls;
        private String distinctId;
        private String event;
        private Map<String, Object> properties = Map.of();

        @Override
        public void capture(String distinctId, String event, Map<String, Object> properties) {
            captureCalls++;
            this.distinctId = distinctId;
            this.event = event;
            this.properties = properties;
        }

        @Override
        public void flush() {
            flushCalls++;
        }

        @Override
        public void close() {
        }
    }
}
