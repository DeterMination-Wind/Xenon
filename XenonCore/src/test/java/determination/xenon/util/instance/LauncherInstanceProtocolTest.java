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
package determination.xenon.util.instance;

import determination.xenon.util.instance.LauncherInstanceProtocol.Action;
import determination.xenon.util.instance.LauncherInstanceProtocol.Reply;
import determination.xenon.util.instance.LauncherInstanceProtocol.Request;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the tolerant encoding and decoding of the instance protocol.
@NotNullByDefault
public final class LauncherInstanceProtocolTest {

    /// A request survives an encode/decode round trip unchanged.
    @Test
    public void requestRoundTrips() {
        Request request = new Request("token-1", "1.15.0", 4242L,
                "C:\\Xenon\\Xenon.jar", List.of("xenon://join?intent=abc", "--flag"));

        Request decoded = LauncherInstanceProtocol.decodeRequest(
                LauncherInstanceProtocol.encodeRequest(request));

        assertEquals(request, decoded);
    }

    /// Unknown JSON fields written by newer versions are ignored.
    @Test
    public void requestIgnoresUnknownFields() {
        String line = "{\"token\":\"t\",\"version\":\"v\",\"pid\":7,\"args\":[],\"future\":{\"x\":1}}";

        Request decoded = LauncherInstanceProtocol.decodeRequest(line);

        assertEquals(new Request("t", "v", 7L, null, List.of()), decoded);
    }

    /// Values with control characters can never break the single-line framing.
    @Test
    public void encodedRequestStaysSingleLine() {
        Request request = new Request("t", "v", 1L, null, List.of("line1\nline2", "tab\t"));

        String encoded = LauncherInstanceProtocol.encodeRequest(request);

        assertFalse(encoded.contains("\n"));
        assertFalse(encoded.contains("\r"));
        assertEquals(request, LauncherInstanceProtocol.decodeRequest(encoded));
    }

    /// Blank, malformed or incomplete request lines decode to null.
    @Test
    public void requestRejectsBadInput() {
        assertNull(LauncherInstanceProtocol.decodeRequest(null));
        assertNull(LauncherInstanceProtocol.decodeRequest("  "));
        assertNull(LauncherInstanceProtocol.decodeRequest("not json"));
        assertNull(LauncherInstanceProtocol.decodeRequest("[1,2,3]"));
        assertNull(LauncherInstanceProtocol.decodeRequest("{\"token\":\"t\"}"));
        assertNull(LauncherInstanceProtocol.decodeRequest("{\"token\":\"\",\"version\":\"v\",\"pid\":1}"));
        assertNull(LauncherInstanceProtocol.decodeRequest("{\"token\":\"t\",\"version\":\"v\",\"pid\":0}"));
    }

    /// Non-string arguments are skipped instead of failing the whole request.
    @Test
    public void requestSkipsNonStringArguments() {
        String line = "{\"token\":\"t\",\"version\":\"v\",\"pid\":1,\"args\":[\"a\",5,null,{\"x\":1},\"b\"]}";

        Request decoded = LauncherInstanceProtocol.decodeRequest(line);

        assertEquals(new Request("t", "v", 1L, null, List.of("a", "b")), decoded);
    }

    /// Replies round trip and accept both lower- and upper-case actions.
    @Test
    public void replyRoundTrips() {
        assertEquals(new Reply(Action.FOCUS), LauncherInstanceProtocol.decodeReply(
                LauncherInstanceProtocol.encodeReply(new Reply(Action.FOCUS))));
        assertEquals(new Reply(Action.SPAWN), LauncherInstanceProtocol.decodeReply(
                LauncherInstanceProtocol.encodeReply(new Reply(Action.SPAWN))));
        assertTrue(LauncherInstanceProtocol.encodeReply(new Reply(Action.FOCUS)).contains("focus"));
        assertEquals(new Reply(Action.SPAWN), LauncherInstanceProtocol.decodeReply("{\"action\":\"SPAWN\"}"));
    }

    /// Unknown or malformed replies decode to null so callers fail open.
    @Test
    public void replyRejectsUnknownActions() {
        assertNull(LauncherInstanceProtocol.decodeReply(null));
        assertNull(LauncherInstanceProtocol.decodeReply(""));
        assertNull(LauncherInstanceProtocol.decodeReply("nope"));
        assertNull(LauncherInstanceProtocol.decodeReply("{\"action\":\"destroy\"}"));
        assertNull(LauncherInstanceProtocol.decodeReply("{\"action\":42}"));
    }
}
