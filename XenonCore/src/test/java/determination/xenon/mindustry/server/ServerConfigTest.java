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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests that Xenon's editable server config keys match Mindustry keys.
@NotNullByDefault
public final class ServerConfigTest {
    /// Serializes the display name and description using Mindustry's real config keys.
    @Test
    public void serializesMindustryConfigKeys() {
        ServerConfig cfg = new ServerConfig();
        cfg.set("name", "Server");
        cfg.set("desc", "A server");

        String json = cfg.toJsonObject().toString();

        assertTrue(json.contains("\"name\""));
        assertTrue(json.contains("\"desc\""));
        assertFalse(json.contains("\"description\""));
        assertFalse(json.contains("\"public\""));
        assertFalse(json.contains("\"roundLimit\""));
    }

    /// Reads keys written by older Xenon builds and normalizes them on the next save.
    @Test
    public void readsLegacyXenonKeys() {
        JsonObject json = JsonParser.parseString(
                "{\"name\":\"Legacy\",\"description\":\"Old desc\"}").getAsJsonObject();
        ServerConfig cfg = ServerConfig.fromJsonObject(json);

        assertEquals("Legacy", cfg.get("name"));
        assertEquals("Old desc", cfg.get("desc"));
    }
}
