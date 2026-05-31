/*
 * Xenon Launcher
 * Copyright (C) 2026-2026  Xenon contributors
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
package determination.xenon.util.skin;

import javafx.scene.image.Image;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

public class NormalizedSkinTest {
    private static NormalizedSkin getSkin(String name, boolean slim) throws InvalidSkinException {
        URL resource = NormalizedSkinTest.class.getResource(
                String.format("/assets/img/skin/%s/%s.png", slim ? "slim" : "wide", name));
        assertNotNull(resource);
        return new NormalizedSkin(new Image(resource.toExternalForm()));
    }

    @Test
    @EnabledIf("determination.xenon.JavaFXLauncher#isStarted")
    public void testIsSlim() throws Exception {
        String[] names = {"alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};

        for (String skin : names) {
            assertTrue(getSkin(skin, true).isSlim(), "slim/" + skin);
            assertFalse(getSkin(skin, false).isSlim(), "wide/" + skin);
        }
    }
}
