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
package determination.xenon.netplay;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the official server list parser.
@NotNullByDefault
public final class PublicServerCatalogTest {

    /// The upstream `[{name, address}]` shape parses into entries.
    @Test
    public void parsesUpstreamShape() {
        String json = """
                [
                  {"name": "gods field", "address": ["110.42.62.234:20524", "110.42.62.234:20525"]},
                  {"name": "Powerline Monitor", "address": ["39.107.34.48"]}
                ]
                """;
        List<PublicServerCatalog.Server> servers = PublicServerCatalog.parse(json, false);
        assertEquals(2, servers.size());
        assertEquals("gods field", servers.get(0).name());
        assertEquals("110.42.62.234:20524", servers.get(0).primaryAddress());
        assertEquals(2, servers.get(0).addresses().size());
        assertEquals("39.107.34.48", servers.get(1).primaryAddress());
    }

    /// Rows without a name or without addresses are dropped.
    @Test
    public void skipsIncompleteRows() {
        String json = """
                [
                  {"name": "", "address": ["1.2.3.4:6567"]},
                  {"name": "no address", "address": []},
                  {"address": ["1.2.3.4"]},
                  {"name": "valid", "address": ["5.6.7.8:6567"]}
                ]
                """;
        List<PublicServerCatalog.Server> servers = PublicServerCatalog.parse(json, true);
        assertEquals(1, servers.size());
        assertEquals("valid", servers.get(0).name());
        assertTrue(servers.get(0).bleedingEdge());
    }

    /// Broken JSON yields an empty list instead of throwing.
    @Test
    public void malformedJsonIsEmpty() {
        assertTrue(PublicServerCatalog.parse("not json", false).isEmpty());
        assertTrue(PublicServerCatalog.parse(null, false).isEmpty());
        assertTrue(PublicServerCatalog.parse("{}", false).isEmpty());
    }
}
