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
package determination.xenon.mindustry.map;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class MindustryTopMapRepositoryTest {

    @Test
    public void parsesListRowsAndCleansDisplayFields() {
        String json = """
                [{
                  "id":24355,
                  "latest":"24355",
                  "name":"[#EEC591]仙古[#FFFFFF]——生存比赛：谁能活到最后",
                  "desc":"很有精神喵~！\\n\\n[#00000000][@noSkills]",
                  "preview":"https://ipfs.mindustry.top/ipfs/example",
                  "tags":["24355","Pvp§warning","CP§warning","v158","404x502"],
                  "width":404,
                  "height":502,
                  "mode":"Pvp"
                }]
                """;

        List<MindustryRemoteMap> rows = MindustryTopMapRepository.parseList(json);

        assertEquals(1, rows.size());
        MindustryRemoteMap map = rows.get(0);
        assertEquals(24355, map.id());
        assertEquals("24355", map.latestKey());
        assertEquals("仙古——生存比赛：谁能活到最后", map.displayName());
        assertEquals("很有精神喵~！", map.displaySummary());
        assertEquals("Pvp", map.displayMode());
        assertTrue(map.hasCpTag());
        assertEquals("v158", map.versionTag());
        assertEquals(List.of("Pvp", "CP", "v158", "404x502"), map.displayTags());
    }

    @Test
    public void suggestedFileNameFallsBackWhenDisplayNameIsEmptyOrIllegal() {
        MindustryRemoteMap empty = new MindustryRemoteMap(
                42, "", "", "", "", List.of(), 0, 0, "");
        MindustryRemoteMap reserved = new MindustryRemoteMap(
                43, "", "CON", "", "", List.of(), 0, 0, "");

        assertEquals("map-42.msav", empty.suggestedFileName());
        assertEquals("map-43.msav", reserved.suggestedFileName());
    }

    @Test
    public void filenameSanitizerPreservesReadableChineseButDropsIllegalChars() {
        String stem = MindustryRemoteMap.sanitizeFileStem("[red]推进:/?地图*测试.");

        assertEquals("推进___地图_测试", stem);
        assertFalse(stem.contains(":"));
        assertFalse(stem.endsWith("."));
    }
}
