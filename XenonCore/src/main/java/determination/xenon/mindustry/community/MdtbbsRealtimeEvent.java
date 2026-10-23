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

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.Set;

/// One realtime event delivered by MDTBBS `/realtime/v1`.
///
/// @param id        per-stream monotonic cursor
/// @param event     event name such as `multiplayer.join_request.created`
/// @param timestamp server timestamp in milliseconds
/// @param data      event payload
public record MdtbbsRealtimeEvent(String id, String event, long timestamp, JsonObject data) {

    /// Parses one `event` frame.
    ///
    /// Unknown fields are ignored and malformed frames return `null` so a
    /// protocol addition can never kill the listener.
    ///
    /// @param frame frame object
    /// @return the parsed event, or `null` when the frame is not a valid event
    public static @Nullable MdtbbsRealtimeEvent parse(JsonObject frame) {
        if (!"event".equals(MdtbbsJson.stringOf(frame, "type"))) {
            return null;
        }
        String id = MdtbbsJson.stringOf(frame, "id");
        String event = MdtbbsJson.stringOf(frame, "event");
        if (id.isBlank() || event.isBlank()) {
            return null;
        }
        JsonObject data = MdtbbsJson.objectOf(frame, "data");
        return new MdtbbsRealtimeEvent(id, event, MdtbbsJson.longOf(frame, "timestamp", 0L),
                data == null ? new JsonObject() : data);
    }

    /// Reads a string field of the payload.
    ///
    /// @param key field name
    /// @return the value, or an empty string
    public String string(String key) {
        return MdtbbsJson.stringOf(data, key);
    }

    /// Reads a long field of the payload.
    ///
    /// @param key      field name
    /// @param fallback value used when the field is missing
    /// @return the value or the fallback
    public long number(String key, long fallback) {
        return MdtbbsJson.longOf(data, key, fallback);
    }

    /// The session id a session-scoped event belongs to, or empty.
    ///
    /// @return the session id, or an empty string
    public String sessionId() {
        String direct = MdtbbsJson.stringOf(data, "session_id");
        if (!direct.isBlank()) {
            return direct;
        }
        JsonObject session = MdtbbsJson.objectOf(data, "session");
        return session == null ? "" : MdtbbsJson.stringOf(session, "id");
    }

    /// Whether this event belongs to a session stream rather than the user stream.
    ///
    /// @return whether the event is session scoped
    public boolean sessionScoped() {
        return event.startsWith("session.")
                || event.startsWith("peer.")
                || event.startsWith("candidate.")
                || event.startsWith("relay.")
                || !sessionId().isBlank();
    }
}

/// Bounded de-duplicator for realtime event ids.
///
/// Events can be replayed after a resume, and the same id must be delivered
/// once. The set keeps the newest [#CAPACITY] ids; older ids are evicted so a
/// long session cannot grow without bound.
@NotNullByDefault
final class MdtbbsRealtimeDeduper {

    /// Number of event ids remembered.
    public static final int CAPACITY = 512;

    private final Set<String> seen = new LinkedHashSet<>();

    /// Records one event id.
    ///
    /// @param id event id
    /// @return `true` when the id is new and should be delivered
    public synchronized boolean accept(String id) {
        if (id == null || id.isBlank() || !seen.add(id)) {
            return false;
        }
        while (seen.size() > CAPACITY) {
            var iterator = seen.iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    /// Forgets every remembered id; used after `resume_failed`.
    public synchronized void reset() {
        seen.clear();
    }

    /// Number of remembered ids.
    public synchronized int size() {
        return seen.size();
    }
}
