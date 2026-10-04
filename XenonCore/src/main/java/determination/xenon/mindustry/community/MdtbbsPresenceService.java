/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package determination.xenon.mindustry.community;

import com.google.gson.JsonObject;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/// Keeps one MDTBBS presence connection alive while the launcher runs.
///
/// The service creates a `launcher` presence connection after login, sends
/// heartbeats at the server-requested interval, publishes Rich Activity while
/// a game process is running and clears it when the process exits. Connection
/// failures are logged and retried on the next heartbeat; the server expires
/// stale connections after roughly 90 seconds.
@NotNullByDefault
public final class MdtbbsPresenceService {
    /// Fallback heartbeat interval when the server omits one.
    private static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(30);

    /// Account coordinator supplying login state and the API transport.
    private final MdtbbsCommunity community;
    /// Social client used for presence calls.
    private final MdtbbsSocialClient social;
    /// Daemon scheduler driving heartbeats.
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MDTbbs-presence");
        thread.setDaemon(true);
        return thread;
    });
    /// Guards connection state.
    private final Object lock = new Object();
    /// Active connection id, or `null` when disconnected.
    private @Nullable String connectionId;
    /// Scheduled heartbeat task, or `null`.
    private @Nullable ScheduledFuture<?> heartbeatTask;
    /// Last published activity, re-applied after a reconnect.
    private @Nullable JsonObject pendingActivity;

    /// Creates the service for one account coordinator.
    ///
    /// @param community account coordinator
    public MdtbbsPresenceService(MdtbbsCommunity community) {
        this.community = community;
        this.social = new MdtbbsSocialClient(community.api());
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "MDTbbs-presence-shutdown"));
    }

    /// Connects when the user is logged in and a connection is not active yet.
    public void start() {
        if (!community.isLoggedIn()) return;
        synchronized (lock) {
            if (connectionId != null) return;
        }
        scheduler.execute(this::connect);
    }

    /// Disconnects and stops heartbeats.
    public void stop() {
        String id;
        synchronized (lock) {
            id = connectionId;
            connectionId = null;
            if (heartbeatTask != null) {
                heartbeatTask.cancel(false);
                heartbeatTask = null;
            }
        }
        if (id != null) {
            try {
                social.clearActivity(id);
            } catch (IOException e) {
                Logger.LOG.info("MDTbbs presence activity clear failed: " + e.getMessage());
            }
            try {
                social.disconnect(id);
            } catch (IOException e) {
                Logger.LOG.info("MDTbbs presence disconnect failed: " + e.getMessage());
            }
        }
    }

    /// Publishes a Rich Activity describing the running game.
    ///
    /// @param name      game name shown to friends
    /// @param version   game version, or `null`
    /// @param sessionId multiplayer session id, or `null`
    public void publishPlaying(String name, @Nullable String version, @Nullable String sessionId) {
        JsonObject activity = new JsonObject();
        activity.addProperty("type", "playing");
        activity.addProperty("name", trimTo(name, 160));
        if (version != null && !version.isBlank()) {
            JsonObject game = new JsonObject();
            // `game.id` is required whenever `game` is present, otherwise the
            // server rejects the whole activity with HTTP 400.
            game.addProperty("id", "mindustry");
            game.addProperty("version", trimTo(version, 64));
            activity.add("game", game);
        }
        if (sessionId != null && !sessionId.isBlank()) {
            JsonObject join = new JsonObject();
            join.addProperty("session_id", sessionId);
            activity.add("join", join);
        }
        String id;
        synchronized (lock) {
            pendingActivity = activity;
            id = connectionId;
        }
        if (id == null) {
            start();
            return;
        }
        scheduler.execute(() -> pushActivity(id, activity));
    }

    /// Clears the published Rich Activity.
    public void clearPlaying() {
        String id;
        synchronized (lock) {
            pendingActivity = null;
            id = connectionId;
        }
        if (id == null) return;
        scheduler.execute(() -> {
            try {
                social.clearActivity(id);
            } catch (IOException e) {
                Logger.LOG.info("MDTbbs presence activity clear failed: " + e.getMessage());
            }
        });
    }

    /// Creates the connection and schedules heartbeats.
    private void connect() {
        if (!community.isLoggedIn()) return;
        try {
            MdtbbsSocialClient.PresenceConnection connection = social.connect("launcher", "online");
            if (connection.connectionId().isBlank()) return;
            JsonObject activity;
            synchronized (lock) {
                connectionId = connection.connectionId();
                activity = pendingActivity;
                long intervalSeconds = Math.max(10, connection.heartbeatSeconds() > 0
                        ? connection.heartbeatSeconds() : DEFAULT_HEARTBEAT.toSeconds());
                heartbeatTask = scheduler.scheduleAtFixedRate(this::heartbeat,
                        intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
            }
            if (activity != null) pushActivity(connection.connectionId(), activity);
        } catch (IOException e) {
            Logger.LOG.info("MDTbbs presence connect failed: " + e.getMessage());
        }
    }

    /// Sends one heartbeat, dropping the connection on failure.
    private void heartbeat() {
        String id;
        synchronized (lock) {
            id = connectionId;
        }
        if (id == null) return;
        try {
            social.heartbeat(id);
        } catch (IOException e) {
            Logger.LOG.info("MDTbbs presence heartbeat failed: " + e.getMessage());
            synchronized (lock) {
                connectionId = null;
                if (heartbeatTask != null) {
                    heartbeatTask.cancel(false);
                    heartbeatTask = null;
                }
            }
            if (community.isLoggedIn()) {
                scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
            }
        }
    }

    /// Publishes an activity, reconnecting when the connection is gone.
    private void pushActivity(String id, JsonObject activity) {
        try {
            social.updateActivity(id, activity);
        } catch (IOException e) {
            Logger.LOG.info("MDTbbs presence activity update failed: " + e.getMessage());
        }
    }

    /// Truncates a string to the server-side length limit.
    private static String trimTo(@Nullable String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
