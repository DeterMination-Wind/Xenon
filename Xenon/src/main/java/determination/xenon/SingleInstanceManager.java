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
package determination.xenon;

import determination.xenon.mindustry.ui.MdtbbsJoinIntentHandler;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.WindowUtils;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.util.instance.LauncherInstance;
import determination.xenon.util.instance.LauncherInstanceClient;
import determination.xenon.util.instance.LauncherInstanceClient.Decision;
import determination.xenon.util.instance.LauncherInstanceProtocol;
import determination.xenon.util.instance.LauncherInstanceRegistry;
import determination.xenon.util.instance.LauncherInstanceServer;
import determination.xenon.util.io.JarUtils;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static determination.xenon.ui.FXUtils.runInFX;
import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Single-instance coordination for the launcher.
///
/// Instances that share the same data root (see
/// `Metadata.XENON_GLOBAL_DIRECTORY`) belong to the same instance group.
/// [startup] is called from the entry point before JavaFX starts: it scans
/// the group, negotiates with a running instance and either terminates the
/// process (when asked to focus) or registers this process as a new instance.
///
/// The manager is deliberately fail open: exempt modes, unusable data
/// directories, failed port bindings and every protocol error simply let the
/// launcher start normally, at worst with one extra window.
///
/// Behavior can be controlled with the `xenon.single_instance` system
/// property or the `XENON_SINGLE_INSTANCE` environment variable:
/// - `auto` (default): check, except for class-path runs without a jar
/// - `on`: check even for class-path runs
/// - `off`: disable the feature
/// - `skip`: internal handover child processes started by the updater
@NotNullByDefault
public final class SingleInstanceManager {

    /// Directory holding the instance records inside the shared data root.
    private static final Path INSTANCE_DIRECTORY = Metadata.XENON_GLOBAL_DIRECTORY.resolve(".instances");

    /// System property that selects the single-instance mode.
    public static final String MODE_PROPERTY = "xenon.single_instance";

    /// Environment variable that selects the single-instance mode.
    public static final String MODE_ENV = "XENON_SINGLE_INSTANCE";

    /// Command-line flag that forces an extra window and is removed before startup.
    public static final String NEW_INSTANCE_FLAG = "--new-instance";

    /// Command-line flag of the update handover, which is never a second instance.
    private static final String APPLY_TO_FLAG = "--apply-to";

    /// Maximum time a request waits for the launcher window to become ready.
    private static final long WINDOW_WAIT_MILLIS = 10_000L;

    /// Maximum time the running instance waits for the user's answer.
    private static final long CONFIRM_TIMEOUT_SECONDS = 60L;

    /// Guards [windowReady], [stage] and [WINDOW_WAITERS].
    private static final Object STATE_LOCK = new Object();

    /// Waiters released once the launcher window is ready.
    private static final List<CompletableFuture<Boolean>> WINDOW_WAITERS = new ArrayList<>();

    /// Whether [onWindowReady] has already been called.
    private static boolean windowReady;

    /// Launcher stage recorded by [onWindowReady].
    private static @Nullable Stage stage;

    /// Registry owning this process's record, or `null` while disabled.
    private static @Nullable LauncherInstanceRegistry registry;

    /// Control server of this process, or `null` while disabled.
    private static @Nullable LauncherInstanceServer server;

    /// Token accepted by the control server, or `null` while disabled.
    private static @Nullable String token;

    /// Single-instance mode selected by configuration.
    private enum Mode {
        /// Check normally, but skip class-path runs without a jar.
        AUTO,
        /// Disable the feature completely.
        OFF,
        /// Check even for class-path runs.
        ON,
        /// Skip as an internal update handover.
        SKIP
    }

    /// Runs the single-instance check and starts the control server.
    ///
    /// Called from the entry point before JavaFX starts. When a running
    /// instance of the same version exists the current process focuses it and
    /// exits; when only other versions are running the running instance asks
    /// the user whether an extra window should be opened. The returned
    /// arguments always have [NEW_INSTANCE_FLAG] removed so the flag never
    /// reaches the launcher itself.
    ///
    /// @param args command-line arguments of this process
    /// @return the arguments to pass on to the launcher
    public static String[] startup(String[] args) {
        List<String> remaining = new ArrayList<>(Arrays.asList(args == null ? new String[0] : args));
        boolean forceNew = remaining.remove(NEW_INSTANCE_FLAG);

        Mode mode = resolveMode();
        if (mode == Mode.OFF || mode == Mode.SKIP) {
            LOG.info(mode == Mode.SKIP
                    ? "Single-instance check skipped for an internal handover"
                    : "Single-instance check disabled by configuration");
            return remaining.toArray(String[]::new);
        }
        if (remaining.contains(APPLY_TO_FLAG)) {
            LOG.info("Single-instance check skipped for update application");
            return remaining.toArray(String[]::new);
        }
        if (mode == Mode.AUTO && JarUtils.thisJarPath() == null) {
            LOG.info("Single-instance check skipped: not running from a launcher jar");
            return remaining.toArray(String[]::new);
        }

        if (!forceNew) {
            Decision decision = negotiate(remaining);
            if (decision == Decision.EXIT) {
                LOG.info("Another Xenon " + Metadata.VERSION + " is already running; focused it and exiting");
                LOG.shutdown();
                System.exit(0);
            }
        } else {
            LOG.info("Starting an extra launcher window because " + NEW_INSTANCE_FLAG + " was given");
        }

        beginRegistration();
        return remaining.toArray(String[]::new);
    }

    /// Records the launcher stage and releases queued control requests.
    ///
    /// Called on the JavaFX application thread right after the primary stage
    /// is shown. Never blocks: waiting requests are processed by the control
    /// server thread.
    ///
    /// @param primaryStage shown launcher stage
    public static void onWindowReady(Stage primaryStage) {
        List<CompletableFuture<Boolean>> waiters;
        synchronized (STATE_LOCK) {
            stage = primaryStage;
            windowReady = true;
            waiters = new ArrayList<>(WINDOW_WAITERS);
            WINDOW_WAITERS.clear();
        }
        for (CompletableFuture<Boolean> waiter : waiters) {
            waiter.complete(Boolean.TRUE);
        }
    }

    /// Scans the instance group and asks a running instance what to do.
    private static Decision negotiate(List<String> args) {
        try {
            LauncherInstanceRegistry scanner = new LauncherInstanceRegistry(INSTANCE_DIRECTORY);
            List<LauncherInstance> alive = scanner.findAll().stream()
                    .filter(scanner::isAlive)
                    .toList();
            return LauncherInstanceClient.negotiate(alive, Metadata.VERSION, args);
        } catch (RuntimeException e) {
            LOG.warning("Single-instance negotiation failed; starting normally", e);
            return Decision.START;
        }
    }

    /// Starts the control server and registers this process in the group.
    ///
    /// Every failure disables the feature for this process and lets it start
    /// normally.
    private static void beginRegistration() {
        LauncherInstanceRegistry newRegistry = null;
        LauncherInstanceServer newServer = null;
        try {
            newRegistry = new LauncherInstanceRegistry(INSTANCE_DIRECTORY);
            String newToken = UUID.randomUUID().toString().replace("-", "");
            newServer = new LauncherInstanceServer(newToken, SingleInstanceManager::handle);
            newServer.start();

            // Publish the identity of this process before the record becomes
            // discoverable, otherwise a very fast starter could observe the
            // record and get rejected by a not yet initialized handler.
            registry = newRegistry;
            server = newServer;
            token = newToken;

            Path jar = JarUtils.thisJarPath();
            LauncherInstance instance = new LauncherInstance(
                    ProcessHandle.current().pid(),
                    newServer.localPort(),
                    newToken,
                    Metadata.VERSION,
                    jar == null ? null : jar.toString(),
                    System.currentTimeMillis());
            newRegistry.register(instance);

            Runtime.getRuntime().addShutdownHook(new Thread(SingleInstanceManager::shutdown,
                    "xenon-instance-shutdown"));
            LOG.info("Single-instance detection active on loopback port " + newServer.localPort());
        } catch (IOException | RuntimeException e) {
            registry = null;
            server = null;
            token = null;
            if (newServer != null) {
                newServer.close();
            }
            if (newRegistry != null) {
                newRegistry.close();
            }
            LOG.warning("Failed to enable single-instance detection; starting without it", e);
        }
    }

    /// Handles one authenticated control request on the server thread.
    ///
    /// Waits up to ten seconds for the window to become ready, then focuses
    /// the existing window. A same-version starter is answered with
    /// [LauncherInstanceProtocol.Action#FOCUS] after its deep-link arguments
    /// are forwarded to this instance; an other-version starter makes the
    /// user choose between [LauncherInstanceProtocol.Action#SPAWN] and
    /// [LauncherInstanceProtocol.Action#FOCUS].
    private static @Nullable LauncherInstanceProtocol.Reply handle(LauncherInstanceProtocol.Request request) {
        String currentToken = token;
        if (currentToken == null || !currentToken.equals(request.token())) {
            return null;
        }
        if (!awaitWindowReady()) {
            LOG.warning("Launcher window is not ready; letting the new instance start");
            return new LauncherInstanceProtocol.Reply(LauncherInstanceProtocol.Action.SPAWN);
        }

        Stage currentStage = currentStage();
        if (currentStage != null) {
            runInFX(() -> WindowUtils.bringToFront(currentStage));
        }

        if (Metadata.VERSION.equals(request.version())) {
            // Same version: the deep link that caused the second launch would
            // be lost when the process exits, so forward it to this instance.
            runInFX(() -> {
                MdtbbsJoinIntentHandler.captureArguments(request.args().toArray(String[]::new));
                MdtbbsJoinIntentHandler.processPending();
            });
            LOG.info("Another same-version Xenon is starting; focused the existing window");
            return new LauncherInstanceProtocol.Reply(LauncherInstanceProtocol.Action.FOCUS);
        }

        CompletableFuture<LauncherInstanceProtocol.Action> answer = new CompletableFuture<>();
        runInFX(() -> Controllers.confirm(
                i18n("launcher.single_instance.message", "v" + request.version()),
                i18n("launcher.single_instance.title"),
                MessageDialogPane.MessageType.QUESTION,
                () -> answer.complete(LauncherInstanceProtocol.Action.SPAWN),
                () -> answer.complete(LauncherInstanceProtocol.Action.FOCUS)));
        try {
            return new LauncherInstanceProtocol.Reply(
                    answer.get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            LOG.warning("No answer for an other-version launch; letting the new instance start");
            return new LauncherInstanceProtocol.Reply(LauncherInstanceProtocol.Action.SPAWN);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LauncherInstanceProtocol.Reply(LauncherInstanceProtocol.Action.SPAWN);
        } catch (ExecutionException e) {
            LOG.warning("Failed to ask about an other-version launch", e);
            return new LauncherInstanceProtocol.Reply(LauncherInstanceProtocol.Action.SPAWN);
        }
    }

    /// Waits for the launcher window to become ready.
    ///
    /// @return `false` when the window is still missing after the timeout
    private static boolean awaitWindowReady() {
        CompletableFuture<Boolean> waiter;
        synchronized (STATE_LOCK) {
            if (windowReady) {
                return true;
            }
            waiter = new CompletableFuture<>();
            WINDOW_WAITERS.add(waiter);
        }
        try {
            return Boolean.TRUE.equals(waiter.get(WINDOW_WAIT_MILLIS, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            synchronized (STATE_LOCK) {
                WINDOW_WAITERS.remove(waiter);
                return windowReady;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            return false;
        }
    }

    /// Returns the recorded launcher stage, or `null` when not ready yet.
    private static @Nullable Stage currentStage() {
        synchronized (STATE_LOCK) {
            return stage;
        }
    }

    /// Resolves the configured single-instance mode.
    private static Mode resolveMode() {
        String value = System.getProperty(MODE_PROPERTY);
        if (value == null || value.isBlank()) {
            value = System.getenv(MODE_ENV);
        }
        if (value == null || value.isBlank()) {
            return Mode.AUTO;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "off" -> Mode.OFF;
            case "on" -> Mode.ON;
            case "skip" -> Mode.SKIP;
            case "auto" -> Mode.AUTO;
            default -> {
                LOG.warning("Unknown single-instance mode \"" + value + "\"; using auto");
                yield Mode.AUTO;
            }
        };
    }

    /// Closes the control server and removes this process's record.
    private static void shutdown() {
        LauncherInstanceServer currentServer = server;
        LauncherInstanceRegistry currentRegistry = registry;
        if (currentServer != null) {
            currentServer.close();
        }
        if (currentRegistry != null) {
            currentRegistry.close();
        }
    }

    private SingleInstanceManager() {
    }
}
