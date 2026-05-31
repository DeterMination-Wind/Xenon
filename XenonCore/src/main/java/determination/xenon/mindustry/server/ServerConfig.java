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
package determination.xenon.mindustry.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Editable Mindustry dedicated-server config values supported by the server `config` command.
@NotNullByDefault
public final class ServerConfig {
    private static final @Unmodifiable List<Entry> SCHEMA = List.of(
            new Entry("name", ValueType.STRING, "Server"),
            new Entry("desc", ValueType.STRING, "off"),
            new Entry("port", ValueType.INTEGER, 6567),
            new Entry("locale", ValueType.STRING, "default"),
            new Entry("autoUpdate", ValueType.BOOLEAN, false),
            new Entry("showConnectMessages", ValueType.BOOLEAN, true),
            new Entry("enableVotekick", ValueType.BOOLEAN, true),
            new Entry("startCommands", ValueType.STRING, ""),
            new Entry("logging", ValueType.BOOLEAN, true),
            new Entry("strict", ValueType.BOOLEAN, true),
            new Entry("antiSpam", ValueType.BOOLEAN, true),
            new Entry("interactRateWindow", ValueType.INTEGER, 6),
            new Entry("interactRateLimit", ValueType.INTEGER, 25),
            new Entry("interactRateKick", ValueType.INTEGER, 60),
            new Entry("messageRateLimit", ValueType.INTEGER, 0),
            new Entry("messageSpamKick", ValueType.INTEGER, 3),
            new Entry("packetSpamLimit", ValueType.INTEGER, 300),
            new Entry("chatSpamLimit", ValueType.INTEGER, 20),
            new Entry("socketInput", ValueType.BOOLEAN, false),
            new Entry("socketInputPort", ValueType.INTEGER, 6859),
            new Entry("socketInputAddress", ValueType.STRING, "localhost"),
            new Entry("allowCustomClients", ValueType.BOOLEAN, false),
            new Entry("whitelist", ValueType.BOOLEAN, false),
            new Entry("motd", ValueType.STRING, "off"),
            new Entry("autosave", ValueType.BOOLEAN, false),
            new Entry("autosaveAmount", ValueType.INTEGER, 10),
            new Entry("autosaveSpacing", ValueType.INTEGER, 300),
            new Entry("debug", ValueType.BOOLEAN, false),
            new Entry("snapshotInterval", ValueType.INTEGER, 200),
            new Entry("autoPause", ValueType.BOOLEAN, false),
            new Entry("roundExtraTime", ValueType.INTEGER, 12),
            new Entry("maxLogLength", ValueType.INTEGER, 5_242_880),
            new Entry("logCommands", ValueType.BOOLEAN, true)
    );

    private final Map<String, Object> values = new LinkedHashMap<>();

    /// Ordered list of config entries exposed by Mindustry's `config` command.
    public static @Unmodifiable List<Entry> schema() {
        return SCHEMA;
    }

    /// Reads known config keys from a JSON object and drops unsupported legacy keys.
    public static ServerConfig fromJsonObject(JsonObject json) {
        ServerConfig cfg = new ServerConfig();
        for (Entry entry : SCHEMA) {
            JsonElement element = json.get(entry.key());
            if (element == null && entry.key().equals("desc")) {
                element = json.get("description");
            }
            if (element == null || element.isJsonNull()) {
                continue;
            }
            cfg.set(entry.key(), coerce(entry, element));
        }
        return cfg;
    }

    /// Converts the edited values to the server's `config/config.json` shape.
    public JsonObject toJsonObject() {
        JsonObject json = new JsonObject();
        for (Entry entry : SCHEMA) {
            Object value = values.get(entry.key());
            if (value == null) {
                continue;
            }
            switch (entry.type()) {
                case STRING -> json.addProperty(entry.key(), (String) value);
                case INTEGER -> json.addProperty(entry.key(), (Integer) value);
                case BOOLEAN -> json.addProperty(entry.key(), (Boolean) value);
            }
        }
        return json;
    }

    /// Returns the explicitly stored value, or `null` when it has not been set.
    public @Nullable Object get(String key) {
        return values.get(key);
    }

    /// Returns the stored value or this key's Mindustry default.
    public Object getOrDefault(Entry entry) {
        Object value = values.get(entry.key());
        return value == null ? entry.defaultValue() : value;
    }

    /// Stores a config value after checking that the key and Java value type match the schema.
    public void set(String key, @Nullable Object value) {
        Entry entry = findEntry(key);
        if (entry == null) {
            return;
        }
        if (value == null) {
            values.remove(key);
            return;
        }
        Object normalized = normalize(entry, value);
        values.put(key, normalized);
    }

    /// Returns a copy of values that should be pushed through running-server `config` commands.
    public Map<String, Object> toCommandValues() {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        for (Entry entry : SCHEMA) {
            Object value = values.get(entry.key());
            if (value != null) {
                out.put(entry.key(), value);
            }
        }
        return out;
    }

    private static @Nullable Entry findEntry(String key) {
        for (Entry entry : SCHEMA) {
            if (entry.key().equals(key)) {
                return entry;
            }
        }
        return null;
    }

    private static @Nullable Object coerce(Entry entry, JsonElement element) {
        if (!element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        try {
            return switch (entry.type()) {
                case STRING -> primitive.getAsString();
                case INTEGER -> primitive.getAsInt();
                case BOOLEAN -> primitive.getAsBoolean();
            };
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Object normalize(Entry entry, Object value) {
        return switch (entry.type()) {
            case STRING -> value.toString();
            case INTEGER -> {
                if (value instanceof Integer integer) {
                    yield integer;
                }
                throw new IllegalArgumentException(entry.key() + " expects an integer");
            }
            case BOOLEAN -> {
                if (value instanceof Boolean bool) {
                    yield bool;
                }
                throw new IllegalArgumentException(entry.key() + " expects a boolean");
            }
        };
    }

    /// Type of editor and JSON primitive used by one server config key.
    public enum ValueType {
        STRING,
        INTEGER,
        BOOLEAN
    }

    /// One ordered config key exposed by Mindustry's dedicated-server `config` command.
    public record Entry(String key, ValueType type, Object defaultValue) {
    }
}
