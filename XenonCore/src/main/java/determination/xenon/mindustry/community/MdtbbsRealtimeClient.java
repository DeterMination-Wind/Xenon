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
package determination.xenon.mindustry.community;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/// WebSocket client for MDTBBS realtime events.
///
/// A one-time ticket from `POST /realtime/tickets` authorises the connection;
/// the access token never appears in the URL. The client subscribes to the
/// user stream and up to 32 sessions, acknowledges each delivered event, and
/// resumes from the last cursor after a reconnect. When the server answers
/// `resume_failed` the listener is told to re-read a snapshot; events are then
/// delivered from a fresh cursor.
///
/// Every callback runs on WebSocket/scheduler threads, so listeners must hop to
/// the UI thread themselves.
@NotNullByDefault
public final class MdtbbsRealtimeClient implements AutoCloseable {

    /// Receives realtime events and connection state changes.
    public interface Listener {
        /// Handles one de-duplicated event.
        ///
        /// @param event parsed event
        void onEvent(MdtbbsRealtimeEvent event);

        /// Called when the server could not resume the stream.
        default void onResumeFailed() {
        }

        /// Reports a connection state such as `connected` or `resume_failed`.
        ///
        /// @param state  stable state token
        /// @param detail human-readable detail, or `null`
        default void onState(String state, @Nullable String detail) {
        }
    }

    /// How long the WebSocket handshake may take.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /// Largest reconnect delay.
    private static final long MAX_RECONNECT_SECONDS = 30L;

    /// Maximum sessions the server allows in one subscribe frame.
    private static final int MAX_SUBSCRIPTIONS = 32;

    /// Query parameter carrying the one-shot realtime ticket.
    private static final Pattern TICKET_PATTERN = Pattern.compile("(?i)ticket=[^&\\s]+");

    private final MdtbbsApiClient api;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "xenon-mdtbbs-realtime");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();
    private final MdtbbsRealtimeDeduper deduper = new MdtbbsRealtimeDeduper();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean connecting = new AtomicBoolean();

    private volatile @Nullable Listener listener;
    private volatile @Nullable WebSocket socket;
    private volatile @Nullable ScheduledFuture<?> heartbeat;
    private volatile long heartbeatSeconds = 30L;
    private volatile String lastEventId = "";
    private volatile int reconnectAttempt;

    /// Creates a client over the shared transport.
    ///
    /// @param api API transport carrying the bearer token
    public MdtbbsRealtimeClient(MdtbbsApiClient api) {
        this.api = api;
    }

    /// Replaces the listener.
    ///
    /// @param listener listener, or `null` to drop events
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    /// Connects and subscribes to the user stream.
    ///
    /// Calling this again after a successful connection is a no-op.
    public void start() {
        if (closed.get()) {
            return;
        }
        connect();
    }

    /// Adds one session subscription and re-subscribes when connected.
    ///
    /// @param sessionId session id, or empty to ignore
    public void subscribe(@Nullable String sessionId) {
        if (sessionId == null || sessionId.isBlank() || closed.get()) {
            return;
        }
        if (sessions.size() >= MAX_SUBSCRIPTIONS && !sessions.contains(sessionId)) {
            Logger.LOG.info("MDTBBS realtime subscription limit reached");
            return;
        }
        if (sessions.add(sessionId)) {
            sendSubscribe();
        }
    }

    /// Removes one session subscription.
    ///
    /// The protocol has no unsubscribe frame, so the id is only dropped from
    /// the local set and will not be re-sent after a reconnect.
    ///
    /// @param sessionId session id
    public void unsubscribe(@Nullable String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    /// Whether the WebSocket is open.
    public boolean isConnected() {
        WebSocket current = socket;
        return current != null && !current.isOutputClosed() && !closed.get();
    }

    /// Stops the client and closes the socket.
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopHeartbeat();
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, "closed");
            } catch (RuntimeException ignored) {
                // The socket may already be broken.
            }
            current.abort();
        }
        scheduler.shutdownNow();
    }

    /// Opens one connection, optionally as a reconnect.
    private void connect() {
        if (closed.get() || !connecting.compareAndSet(false, true)) {
            return;
        }
        scheduler.execute(() -> {
            String ticket;
            String path;
            try {
                JsonObject data = MdtbbsJson.dataObject(
                        api.post("/realtime/tickets", new JsonObject()));
                ticket = MdtbbsJson.stringOf(data, "ticket");
                path = MdtbbsJson.stringOf(data, "websocket_path");
            } catch (IOException | RuntimeException e) {
                connecting.set(false);
                scheduleReconnect(e.getMessage());
                return;
            }
            if (ticket.isBlank()) {
                connecting.set(false);
                scheduleReconnect("MDTBBS returned no realtime ticket");
                return;
            }
            URI uri = websocketUri(path.isBlank() ? "/realtime/v1" : path, ticket);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            client.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(uri, new SocketListener())
                    .whenComplete((webSocket, throwable) -> {
                        connecting.set(false);
                        if (throwable != null) {
                            scheduleReconnect(throwable.getMessage());
                            return;
                        }
                        if (closed.get()) {
                            webSocket.abort();
                            return;
                        }
                        socket = webSocket;
                    });
        });
    }

    /// Builds `wss://host/realtime/v1?ticket=…` from the API origin.
    private URI websocketUri(String path, String ticket) {
        URI base = URI.create(api.baseUrl());
        String scheme = "https".equalsIgnoreCase(base.getScheme()) ? "wss" : "ws";
        int port = base.getPort();
        String authority = port > 0 ? base.getHost() + ":" + port : base.getHost();
        String normalized = path.startsWith("/") ? path : "/" + path;
        return URI.create(scheme + "://" + authority + normalized + "?ticket=" + ticket);
    }

    /// Handles one complete text frame.
    private void handleText(String text) {
        if (closed.get()) {
            return;
        }
        JsonObject frame;
        try {
            frame = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            Logger.LOG.info("MDTBBS realtime returned invalid JSON");
            return;
        }
        String type = MdtbbsJson.stringOf(frame, "type");
        switch (type) {
            case "hello" -> {
                long interval = MdtbbsJson.longOf(frame, "heartbeat_interval", 30L);
                heartbeatSeconds = Math.max(5L, interval);
                reconnectAttempt = 0;
                notifyState("connected", null);
                startHeartbeat();
                sendSubscribe();
                if (!lastEventId.isBlank()) {
                    sendResume();
                }
            }
            case "event" -> handleEvent(frame);
            case "resume_failed" -> {
                // The cursor expired or the stream was trimmed; events are
                // replayed from a fresh cursor and the listener refreshes from
                // a REST snapshot instead of trusting a gap.
                lastEventId = "";
                deduper.reset();
                notifyState("resume_failed", MdtbbsJson.stringOf(frame, "reason"));
                Listener current = listener;
                if (current != null) {
                    current.onResumeFailed();
                }
                sendSubscribe();
            }
            case "resumed" -> notifyState("resumed", null);
            case "subscribed" -> notifyState("subscribed", null);
            case "heartbeat_ack", "ack" -> {
                // Nothing to do; the connection is alive.
            }
            case "error" -> {
                String code = MdtbbsJson.stringOf(frame, "code");
                notifyState("error", code.isBlank() ? MdtbbsJson.stringOf(frame, "message") : code);
            }
            default -> Logger.LOG.debug("Ignoring unknown realtime frame " + type);
        }
    }

    /// De-duplicates and dispatches one event, then acknowledges it.
    private void handleEvent(JsonObject frame) {
        MdtbbsRealtimeEvent event = MdtbbsRealtimeEvent.parse(frame);
        if (event == null || !deduper.accept(event.id())) {
            return;
        }
        lastEventId = event.id();
        Listener current = listener;
        if (current != null) {
            current.onEvent(event);
        }
        JsonObject ack = new JsonObject();
        ack.addProperty("type", "ack");
        ack.addProperty("stream", event.sessionScoped() ? "session" : "user");
        ack.addProperty("id", event.id());
        sendText(ack.toString());
    }

    /// Sends the subscribe frame for the current session set.
    private void sendSubscribe() {
        if (sessions.isEmpty() && lastEventId.isBlank()) {
            // The user stream is implicit; still send a subscribe so the
            // server starts replaying it after a reconnect.
            JsonObject subscribe = new JsonObject();
            subscribe.addProperty("type", "subscribe");
            subscribe.add("sessions", new JsonArray());
            sendText(subscribe.toString());
            return;
        }
        JsonObject subscribe = new JsonObject();
        subscribe.addProperty("type", "subscribe");
        JsonArray array = new JsonArray();
        for (String session : sessions) {
            array.add(session);
        }
        subscribe.add("sessions", array);
        sendText(subscribe.toString());
    }

    /// Sends the resume frame for the last delivered cursor.
    private void sendResume() {
        JsonObject resume = new JsonObject();
        resume.addProperty("type", "resume");
        resume.addProperty("last_event_id", lastEventId);
        sendText(resume.toString());
    }

    /// Starts the heartbeat loop for the negotiated interval.
    private void startHeartbeat() {
        stopHeartbeat();
        heartbeat = scheduler.scheduleAtFixedRate(this::sendHeartbeat,
                heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }

    /// Stops the heartbeat loop.
    private void stopHeartbeat() {
        ScheduledFuture<?> current = heartbeat;
        heartbeat = null;
        if (current != null) {
            current.cancel(false);
        }
    }

    /// Sends one heartbeat frame.
    private void sendHeartbeat() {
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "heartbeat");
        sendText(frame.toString());
    }

    /// Serialises one text frame.
    private void sendText(String text) {
        WebSocket current = socket;
        if (current == null || current.isOutputClosed() || closed.get()) {
            return;
        }
        try {
            current.sendText(text, true);
        } catch (RuntimeException e) {
            Logger.LOG.debug("MDTBBS realtime send failed: " + e.getMessage());
        }
    }

    /// Schedules a reconnect with exponential backoff.
    private void scheduleReconnect(@Nullable String detail) {
        if (closed.get()) {
            return;
        }
        stopHeartbeat();
        socket = null;
        int attempt = ++reconnectAttempt;
        long delay = Math.min(MAX_RECONNECT_SECONDS, 1L << Math.min(5, attempt));
        notifyState("reconnecting", detail);
        try {
            scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
        } catch (RuntimeException ignored) {
            // The client is closing.
        }
    }

    /// Notifies the listener of a state change with the one-shot ticket removed.
    private void notifyState(String state, @Nullable String detail) {
        Listener current = listener;
        if (current != null) {
            current.onState(state, redact(detail));
        }
    }

    /// Removes the one-shot ticket from exception details before they leave
    /// the client.
    static @Nullable String redact(@Nullable String detail) {
        return detail == null ? null
                : TICKET_PATTERN.matcher(detail).replaceAll("ticket=***");
    }

    /// WebSocket listener that accumulates fragmented text frames.
    private final class SocketListener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String complete = buffer.toString();
                buffer.setLength(0);
                handleText(complete);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (!closed.get()) {
                scheduleReconnect("closed " + statusCode);
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!closed.get()) {
                scheduleReconnect(error.getMessage());
            }
        }
    }
}
