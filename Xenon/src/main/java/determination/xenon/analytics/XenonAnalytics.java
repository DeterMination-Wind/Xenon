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

import com.posthog.server.PostHog;
import com.posthog.server.PostHogCaptureOptions;
import com.posthog.server.PostHogConfig;
import com.posthog.server.PostHogInterface;
import determination.xenon.Metadata;
import determination.xenon.mindustry.CurrentPlayerProfile;
import determination.xenon.mindustry.uuid.UuidProfile;
import determination.xenon.setting.ConfigHolder;
import determination.xenon.setting.GlobalConfig;
import determination.xenon.util.DataSizeUnit;
import determination.xenon.util.StringUtils;
import determination.xenon.util.io.JarUtils;
import determination.xenon.util.platform.Architecture;
import determination.xenon.util.platform.OperatingSystem;
import determination.xenon.util.platform.SystemInfo;
import determination.xenon.util.platform.hardware.CentralProcessor;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static determination.xenon.util.logging.Logger.LOG;

/// PostHog-backed analytics for Xenon launcher process usage and launcher crashes.
@NotNullByDefault
public final class XenonAnalytics {

    /// PostHog host used by DeterMination's US-region project.
    static final String HOST = "https://us.i.posthog.com";

    /// Regular launcher usage event name.
    static final String EVENT_USAGE = "xenon_event";

    /// Launcher crash event name.
    static final String EVENT_CRASH = "xenon_crash";

    /// Period between regular usage reports.
    static final Duration REPORT_INTERVAL = Duration.ofMinutes(10);

    private static final Object LOCK = new Object();

    private static Clock clock = Clock.systemUTC();
    private static AnalyticsClientFactory clientFactory = SdkAnalyticsClient::new;

    private static @Nullable AnalyticsClient client;
    private static @Nullable SessionTimer sessionTimer;
    private static @Nullable ScheduledExecutorService scheduler;
    private static @Nullable ScheduledFuture<?> periodicTask;
    private static @Nullable Thread shutdownHook;
    private static boolean enabledLogWritten;

    private XenonAnalytics() {
    }

    /// Starts periodic Xenon usage reporting after configuration is loaded.
    public static void start() {
        synchronized (LOCK) {
            if (sessionTimer != null) {
                return;
            }
            if (!initializeClientLocked()) {
                return;
            }

            sessionTimer = new SessionTimer(clock.instant());
            ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "xenon-posthog-analytics");
                thread.setDaemon(true);
                return thread;
            });
            scheduler = executor;
            periodicTask = executor.scheduleAtFixedRate(
                    () -> captureUsageSafely(false),
                    REPORT_INTERVAL.toMillis(),
                    REPORT_INTERVAL.toMillis(),
                    TimeUnit.MILLISECONDS);
            installShutdownHookLocked();
            LOG.info("[Xenon/PostHog] analytics started; intervalMinutes=" + REPORT_INTERVAL.toMinutes());
        }
    }

    /// Captures one launcher crash event and flushes it immediately.
    public static void captureCrash(Thread thread, Throwable throwable) {
        synchronized (LOCK) {
            if (!isAnalyticsEnabledLocked()) {
                return;
            }
            if (!initializeClientLocked()) {
                return;
            }
            AnalyticsClient local = client;
            if (local == null) {
                return;
            }
            try {
                PlayerIdentity identity = resolvePlayerIdentity();
                captureCrashWithClient(local, thread, throwable, identity, SystemSnapshot.current());
            } catch (Throwable ex) {
                LOG.warning("[Xenon/PostHog] crash capture failed", ex);
            }
        }
    }

    /// Flushes pending usage, closes the SDK client, and stops the analytics executor.
    public static void shutdown() {
        synchronized (LOCK) {
            try {
                captureUsageLocked(true);
            } catch (Throwable ex) {
                LOG.warning("[Xenon/PostHog] final usage capture failed", ex);
            }

            ScheduledFuture<?> task = periodicTask;
            if (task != null) {
                task.cancel(false);
                periodicTask = null;
            }

            ScheduledExecutorService executor = scheduler;
            if (executor != null) {
                executor.shutdownNow();
                scheduler = null;
            }

            AnalyticsClient local = client;
            client = null;
            sessionTimer = null;
            if (local != null) {
                try {
                    local.close();
                } catch (Throwable ex) {
                    LOG.warning("[Xenon/PostHog] close failed", ex);
                }
            }

            Thread hook = shutdownHook;
            shutdownHook = null;
            if (hook != null && Thread.currentThread() != hook) {
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException ignored) {
                    // JVM is already shutting down.
                }
            }
        }
    }

    private static void captureUsageSafely(boolean force) {
        synchronized (LOCK) {
            try {
                captureUsageLocked(force);
            } catch (Throwable ex) {
                LOG.warning("[Xenon/PostHog] usage capture failed", ex);
            }
        }
    }

    private static void captureUsageLocked(boolean force) {
        AnalyticsClient local = client;
        SessionTimer timer = sessionTimer;
        if (local == null || timer == null) {
            return;
        }

        Instant now = clock.instant();
        GlobalConfig config = ConfigHolder.globalConfig();
        if (!config.isAnalyticsEnabled()) {
            timer.markReported(now);
            return;
        }

        if (!force && !timer.isDue(now, REPORT_INTERVAL)) {
            return;
        }

        double usageMinutes = timer.elapsedMinutes(now);
        if (usageMinutes <= 0.0D) {
            timer.markReported(now);
            return;
        }

        boolean firstLaunch = !config.isAnalyticsFirstLaunchSent();
        PlayerIdentity identity = resolvePlayerIdentity();
        Map<String, Object> properties = buildUsageProperties(
                identity,
                usageMinutes,
                firstLaunch,
                SystemSnapshot.current());
        local.capture(identity.distinctId(), EVENT_USAGE, properties);
        local.flush();
        timer.markReported(now);
        if (firstLaunch) {
            config.setAnalyticsFirstLaunchSent(true);
        }
    }

    private static boolean initializeClientLocked() {
        GlobalConfig config;
        try {
            config = ConfigHolder.globalConfig();
        } catch (IllegalStateException ex) {
            LOG.info("[Xenon/PostHog] analytics skipped: config is not loaded");
            return false;
        }
        if (!config.isAnalyticsEnabled()) {
            if (!enabledLogWritten) {
                enabledLogWritten = true;
                LOG.info("[Xenon/PostHog] analytics disabled by configuration");
            }
            return false;
        }

        if (client != null) {
            return true;
        }

        String apiKey = resolveApiKey();
        if (apiKey == null) {
            LOG.info("[Xenon/PostHog] analytics disabled: API key is not configured");
            return false;
        }

        try {
            client = clientFactory.create(apiKey);
            return true;
        } catch (Throwable ex) {
            LOG.warning("[Xenon/PostHog] failed to initialize SDK client", ex);
            return false;
        }
    }

    private static boolean isAnalyticsEnabledLocked() {
        try {
            return ConfigHolder.globalConfig().isAnalyticsEnabled();
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    private static @Nullable String resolveApiKey() {
        String key = normalizeSecret(System.getProperty("xenon.posthog.api_key"));
        if (key != null) {
            return key;
        }
        key = normalizeSecret(System.getenv("XENON_POSTHOG_API_KEY"));
        if (key != null) {
            return key;
        }
        key = normalizeSecret(System.getenv("POSTHOG_API_KEY"));
        if (key != null) {
            return key;
        }
        return normalizeSecret(JarUtils.getAttribute("xenon.posthog.api_key", null));
    }

    private static @Nullable String normalizeSecret(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static void installShutdownHookLocked() {
        if (shutdownHook != null) {
            return;
        }
        Thread hook = new Thread(XenonAnalytics::shutdown, "xenon-posthog-shutdown");
        shutdownHook = hook;
        try {
            Runtime.getRuntime().addShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // JVM is already shutting down.
        } catch (Throwable ex) {
            LOG.warning("[Xenon/PostHog] failed to install shutdown hook", ex);
        }
    }

    static Map<String, Object> buildUsageProperties(
            PlayerIdentity identity,
            double usageMinutes,
            boolean firstLaunch,
            SystemSnapshot system) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("usage_minutes", usageMinutes);
        properties.put("mod_version", Metadata.VERSION);
        properties.put("player_name", identity.playerName());
        properties.put("player_id", identity.playerId());
        if (identity.playerNickname() != null) {
            properties.put("player_nickname", identity.playerNickname());
        }
        properties.put("os_name", system.osName());
        properties.put("memory_mb", system.memoryMb());
        properties.put("java_version", system.javaVersion());
        properties.put("cpu_arch", system.cpuArch());
        properties.put("cpu_cores", system.cpuCores());
        properties.put("locale", system.locale());
        properties.put("is_first_launch", firstLaunch);
        properties.put("xenon_install_id", identity.installId());
        properties.put("identity_source", identity.source());
        properties.put("build_channel", Metadata.BUILD_CHANNEL);
        return properties;
    }

    static Map<String, Object> buildCrashProperties(
            Throwable throwable,
            String threadName,
            PlayerIdentity identity,
            SystemSnapshot system) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("error_message", throwable.getMessage() != null
                ? throwable.getMessage()
                : throwable.toString());
        properties.put("error_type", throwable.getClass().getSimpleName());
        properties.put("stack_trace", stackTrace(throwable));
        properties.put("mod_version", Metadata.VERSION);
        properties.put("java_version", system.javaVersion());
        properties.put("os_name", system.osName());
        properties.put("cpu_arch", system.cpuArch());
        properties.put("player_id", identity.playerId());
        properties.put("player_name", identity.playerName());
        if (identity.playerNickname() != null) {
            properties.put("player_nickname", identity.playerNickname());
        }
        properties.put("xenon_install_id", identity.installId());
        properties.put("identity_source", identity.source());
        properties.put("thread_name", threadName);
        properties.put("build_channel", Metadata.BUILD_CHANNEL);
        return properties;
    }

    static void captureCrashWithClient(
            AnalyticsClient local,
            Thread thread,
            Throwable throwable,
            PlayerIdentity identity,
            SystemSnapshot system) {
        Map<String, Object> properties = buildCrashProperties(
                throwable,
                thread.getName(),
                identity,
                system);
        local.capture(identity.distinctId(), EVENT_CRASH, properties);
        local.flush();
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    static PlayerIdentity resolvePlayerIdentity() {
        String installId = resolveInstallId();
        try {
            UuidProfile profile = CurrentPlayerProfile.current();
            if (profile != null && !StringUtils.isBlank(profile.uuid)) {
                String uuid = profile.uuid.trim();
                String nickname = StringUtils.isBlank(profile.nickname) ? null : profile.nickname.trim();
                return new PlayerIdentity(uuid, uuid, uuid, nickname, installId, "player_profile");
            }
        } catch (Throwable ex) {
            LOG.warning("[Xenon/PostHog] failed to resolve selected player profile", ex);
        }
        return new PlayerIdentity(installId, installId, installId, null, installId, "install_id");
    }

    private static String resolveInstallId() {
        try {
            GlobalConfig config = ConfigHolder.globalConfig();
            String existing = config.getAnalyticsInstallId();
            if (!StringUtils.isBlank(existing)) {
                return existing.trim();
            }
            String generated = UUID.randomUUID().toString();
            config.setAnalyticsInstallId(generated);
            return generated;
        } catch (IllegalStateException ex) {
            return "anonymous";
        }
    }

    static String normalizeCpuArch(@Nullable String raw) {
        if (raw == null) {
            return "unknown";
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "amd64", "x86_64", "x64", "x8664", "intel64" -> "x64";
            case "x86", "i386", "i486", "i586", "i686", "ia32", "x32" -> "x86";
            case "aarch64", "arm64" -> "arm64";
            case "arm", "arm32" -> "arm32";
            default -> {
                Architecture arch = Architecture.parseArchName(raw);
                yield switch (arch) {
                    case X86_64 -> "x64";
                    case X86, IA32 -> "x86";
                    case ARM64 -> "arm64";
                    case ARM32 -> "arm32";
                    case UNKNOWN -> raw.trim().isEmpty() ? "unknown" : raw.trim();
                    default -> arch.getCheckedName();
                };
            }
        };
    }

    static void setClientFactoryForTesting(AnalyticsClientFactory factory) {
        synchronized (LOCK) {
            clientFactory = factory;
        }
    }

    static void setClockForTesting(Clock value) {
        synchronized (LOCK) {
            clock = value;
        }
    }

    static void resetForTesting() {
        synchronized (LOCK) {
            shutdown();
            clock = Clock.systemUTC();
            clientFactory = SdkAnalyticsClient::new;
            enabledLogWritten = false;
        }
    }

    /// Minimal client surface used by the launcher analytics facade.
    interface AnalyticsClient {
        /// Captures one event with properties.
        void capture(String distinctId, String event, Map<String, Object> properties);

        /// Flushes queued events.
        void flush();

        /// Flushes and closes the client.
        void close();
    }

    /// Factory for analytics clients; overridable in tests.
    interface AnalyticsClientFactory {
        /// Creates a client for the given API key.
        AnalyticsClient create(String apiKey);
    }

    private static final class SdkAnalyticsClient implements AnalyticsClient {
        private final PostHogInterface delegate;

        private SdkAnalyticsClient(String apiKey) {
            PostHogConfig config = PostHogConfig.builder(apiKey)
                    .host(HOST)
                    .sendFeatureFlagEvent(false)
                    .preloadFeatureFlags(false)
                    .build();
            delegate = PostHog.with(config);
        }

        @Override
        public void capture(String distinctId, String event, Map<String, Object> properties) {
            delegate.capture(distinctId, event, PostHogCaptureOptions.builder()
                    .properties(properties)
                    .build());
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /// Monotonic session reporter that tracks the last successfully queued usage event.
    static final class SessionTimer {
        private Instant lastReportedAt;

        /// Creates a timer starting at the current launcher start time.
        SessionTimer(Instant startedAt) {
            this.lastReportedAt = startedAt;
        }

        /// Returns true when the regular report interval has elapsed.
        boolean isDue(Instant now, Duration interval) {
            return !Duration.between(lastReportedAt, now).minus(interval).isNegative();
        }

        /// Returns elapsed minutes since the last successful usage event.
        double elapsedMinutes(Instant now) {
            return Duration.between(lastReportedAt, now).toMillis() / 1000.0D / 60.0D;
        }

        /// Advances the last-report marker.
        void markReported(Instant now) {
            lastReportedAt = now;
        }
    }

    /// Resolved player identity used for PostHog distinct_id and event properties.
    record PlayerIdentity(
            String distinctId,
            String playerId,
            String playerName,
            @Nullable String playerNickname,
            String installId,
            String source) {
    }

    /// System context attached to every analytics event.
    record SystemSnapshot(
            String osName,
            int memoryMb,
            String javaVersion,
            String cpuArch,
            int cpuCores,
            String locale) {

        /// Reads the current system context through Xenon's platform helpers.
        static SystemSnapshot current() {
            long totalMemory = SystemInfo.getTotalMemorySize();
            int memoryMb = totalMemory <= 0
                    ? 0
                    : (int) Math.min(Integer.MAX_VALUE, Math.round(DataSizeUnit.MEGABYTES.convertFromBytes(totalMemory)));
            CentralProcessor processor = SystemInfo.getCentralProcessor();
            CentralProcessor.Cores cores = processor == null ? null : processor.getCores();
            int logicalCores = cores != null && cores.logical > 0
                    ? cores.logical
                    : Runtime.getRuntime().availableProcessors();
            String osName = OperatingSystem.OS_RELEASE_PRETTY_NAME == null
                    ? OperatingSystem.SYSTEM_NAME
                    : OperatingSystem.OS_RELEASE_PRETTY_NAME;
            return new SystemSnapshot(
                    osName,
                    memoryMb,
                    System.getProperty("java.version", "unknown"),
                    normalizeCpuArch(System.getProperty("os.arch")),
                    logicalCores,
                    Locale.getDefault().toString());
        }
    }
}
