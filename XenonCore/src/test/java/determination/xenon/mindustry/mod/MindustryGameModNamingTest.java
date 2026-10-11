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
package determination.xenon.mindustry.mod;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests the game-faithful mod name derivation used for settings keys.
@NotNullByDefault
public final class MindustryGameModNamingTest {

    /// Color tags with known names and hex payloads are stripped like Arc does.
    @Test
    public void stripsNamedAndHexColorTags() {
        assertEquals("My Mod", MindustryGameModNaming.stripColors("[red]My [#00ff00]Mod"));
        assertEquals("Name", MindustryGameModNaming.stripColors("[]Name"));
        assertEquals("[", MindustryGameModNaming.stripColors("[[]"));
        assertEquals("[notacolor]Name", MindustryGameModNaming.stripColors("[notacolor]Name"));
        assertEquals("keep[", MindustryGameModNaming.stripColors("keep["));
    }

    /// The normalized key name lowercases and replaces spaces exactly like ModMeta.cleanup.
    @Test
    public void normalizesNamesLikeTheGame() {
        assertEquals("my-mod", MindustryGameModNaming.keyName("My Mod"));
        assertEquals("lightning", MindustryGameModNaming.keyName("[gold]Lightning"));
        assertEquals("a--b", MindustryGameModNaming.keyName("A  B"));
        assertEquals("氖", MindustryGameModNaming.keyName("氖"));
        assertEquals("", MindustryGameModNaming.keyName("   "));
        assertEquals("", MindustryGameModNaming.keyName(null));
    }

    /// The settings key mirrors the game's `mod-<internalName>-enabled` scheme.
    @Test
    public void buildsEnabledSettingKey() {
        assertEquals("mod-my-mod-enabled", MindustryGameModNaming.enabledSettingKey("My Mod"));
        assertEquals("mod-lightning-enabled", MindustryGameModNaming.enabledSettingKey("[gold]Lightning"));
        assertNull(MindustryGameModNaming.enabledSettingKey("  "));
        assertNull(MindustryGameModNaming.enabledSettingKey(null));
    }
}
