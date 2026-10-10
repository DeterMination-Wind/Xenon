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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Single-line JSON wire format of the launcher instance control protocol.
///
/// The protocol is only spoken between Xenon processes over the loopback
/// interface. Requests carry the registration token so unrelated local
/// processes cannot focus windows or answer for the launcher. Decoding is
/// deliberately tolerant: unknown fields are ignored and blank, malformed or
/// partially written lines decode to `null`, which lets every caller fail
/// open by starting normally.
@NotNullByDefault
public final class LauncherInstanceProtocol {

    /// Action requested from (or answered by) a launcher instance.
    public enum Action {
        /// Focus the running launcher window; the starting process must exit.
        FOCUS,
        /// The starting process must keep running and open another window.
        SPAWN
    }

    /// Request sent by a newly started launcher process to a running instance.
    ///
    /// @param token registration token of the target instance
    /// @param version version of the starting process
    /// @param pid process id of the starting process
    /// @param jarPath launcher jar of the starting process, or `null` when it
    ///                runs from a class path
    /// @param args command-line arguments of the starting process
    public record Request(String token, String version, long pid,
                          @Nullable String jarPath, List<String> args) {
    }

    /// Reply sent by a running instance back to the starting process.
    ///
    /// @param action decision the starting process must follow
    public record Reply(Action action) {
    }

    /// JSON codec shared by all messages.
    private static final Gson GSON = new Gson();

    /// Encodes a request as one line of JSON without a trailing newline.
    ///
    /// The result never contains a literal newline because Gson escapes all
    /// control characters, so it can be written directly into a line-oriented
    /// socket stream.
    public static String encodeRequest(Request request) {
        JsonObject json = new JsonObject();
        json.addProperty("token", request.token());
        json.addProperty("version", request.version());
        json.addProperty("pid", request.pid());
        if (request.jarPath() != null) {
            json.addProperty("jarPath", request.jarPath());
        }
        JsonArray args = new JsonArray();
        for (String arg : request.args()) {
            args.add(arg);
        }
        json.add("args", args);
        return GSON.toJson(json);
    }

    /// Decodes one request line.
    ///
    /// @return the parsed request, or `null` when the line is blank, malformed
    ///         or misses mandatory fields
    public static @Nullable Request decodeRequest(@Nullable String line) {
        JsonObject json = parseObject(line);
        if (json == null) {
            return null;
        }
        try {
            String token = getString(json, "token");
            String version = getString(json, "version");
            long pid = getLong(json, "pid");
            if (token == null || token.isBlank() || version == null || version.isBlank() || pid <= 0L) {
                return null;
            }
            List<String> args = new ArrayList<>();
            JsonElement argsElement = json.get("args");
            if (argsElement != null && argsElement.isJsonArray()) {
                for (JsonElement element : argsElement.getAsJsonArray()) {
                    if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                        args.add(element.getAsString());
                    }
                }
            }
            return new Request(token, version, pid, getString(json, "jarPath"), List.copyOf(args));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /// Encodes a reply as one line of JSON without a trailing newline.
    public static String encodeReply(Reply reply) {
        JsonObject json = new JsonObject();
        json.addProperty("action", reply.action().name().toLowerCase(Locale.ROOT));
        return GSON.toJson(json);
    }

    /// Decodes one reply line.
    ///
    /// The action is matched case-insensitively, but only the two defined
    /// actions are accepted.
    ///
    /// @return the parsed reply, or `null` when the line is blank, malformed or
    ///         carries an unknown action
    public static @Nullable Reply decodeReply(@Nullable String line) {
        JsonObject json = parseObject(line);
        if (json == null) {
            return null;
        }
        try {
            String action = getString(json, "action");
            if (action == null) {
                return null;
            }
            return switch (action.trim().toLowerCase(Locale.ROOT)) {
                case "focus" -> new Reply(Action.FOCUS);
                case "spawn" -> new Reply(Action.SPAWN);
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /// Parses a JSON object from one line, ignoring anything else.
    private static @Nullable JsonObject parseObject(@Nullable String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(line);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (JsonParseException | IllegalStateException e) {
            return null;
        }
    }

    /// Reads a string property, treating missing or non-string values as absent.
    private static @Nullable String getString(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            return null;
        }
        return element.getAsString();
    }

    /// Reads a numeric property, returning `-1` when it is missing or not a number.
    private static long getLong(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return -1L;
        }
        return element.getAsLong();
    }

    private LauncherInstanceProtocol() {
    }
}
