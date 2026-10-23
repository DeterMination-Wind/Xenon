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
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests realtime event parsing and de-duplication.
@NotNullByDefault
public final class MdtbbsRealtimeEventTest {

    /// A documented event frame parses into its fields.
    @Test
    public void parsesEventFrame() {
        JsonObject frame = JsonParser.parseString("""
                {"type":"event","id":"1790758200000-1",
                 "event":"multiplayer.join_request.created","timestamp":1790758200000,
                 "data":{"session_id":"ses_1","join_request_id":"jr_1"}}
                """).getAsJsonObject();
        MdtbbsRealtimeEvent event = MdtbbsRealtimeEvent.parse(frame);
        assertNotNull(event);
        assertEquals("1790758200000-1", event.id());
        assertEquals("multiplayer.join_request.created", event.event());
        assertEquals(1790758200000L, event.timestamp());
        assertEquals("ses_1", event.sessionId());
        assertEquals("jr_1", event.string("join_request_id"));
        assertTrue(event.sessionScoped());
    }

    /// Frames without a valid type or cursor are ignored.
    @Test
    public void rejectsMalformedFrames() {
        assertNull(MdtbbsRealtimeEvent.parse(JsonParser.parseString(
                "{\"type\":\"hello\",\"heartbeat_interval\":30}").getAsJsonObject()));
        assertNull(MdtbbsRealtimeEvent.parse(JsonParser.parseString(
                "{\"type\":\"event\",\"event\":\"x\"}").getAsJsonObject()));
        assertNull(MdtbbsRealtimeEvent.parse(JsonParser.parseString(
                "{\"type\":\"event\",\"id\":\"1\"}").getAsJsonObject()));
    }

    /// User events are not session scoped.
    @Test
    public void marksUserEvents() {
        MdtbbsRealtimeEvent event = MdtbbsRealtimeEvent.parse(JsonParser.parseString(
                "{\"type\":\"event\",\"id\":\"1\",\"event\":\"friend.request.created\",\"data\":{}}")
                .getAsJsonObject());
        assertNotNull(event);
        assertFalse(event.sessionScoped());
    }

    /// Duplicate ids are delivered once.
    @Test
    public void dedupesIds() {
        MdtbbsRealtimeDeduper deduper = new MdtbbsRealtimeDeduper();
        assertTrue(deduper.accept("1-0"));
        assertFalse(deduper.accept("1-0"));
        assertTrue(deduper.accept("1-1"));
        assertEquals(2, deduper.size());
    }

    /// The de-duplicator is bounded and evicts the oldest ids.
    @Test
    public void deduperIsBounded() {
        MdtbbsRealtimeDeduper deduper = new MdtbbsRealtimeDeduper();
        for (int i = 0; i < MdtbbsRealtimeDeduper.CAPACITY + 10; i++) {
            assertTrue(deduper.accept("id-" + i));
        }
        assertEquals(MdtbbsRealtimeDeduper.CAPACITY, deduper.size());
        assertTrue(deduper.accept("id-0"));
    }

    /// Resetting forgets every id after a failed resume.
    @Test
    public void resetForgetsIds() {
        MdtbbsRealtimeDeduper deduper = new MdtbbsRealtimeDeduper();
        assertTrue(deduper.accept("1-0"));
        deduper.reset();
        assertTrue(deduper.accept("1-0"));
    }
}
