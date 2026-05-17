/*
 * Xenon Launcher
 * Copyright (C) 2025-2026  Xenon contributors
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
package determination.xenon.ui.animation;

import javafx.scene.Node;
import determination.xenon.setting.ConfigHolder;
import determination.xenon.util.platform.OperatingSystem;

/**
 * @author Glavo
 */
public final class AnimationUtils {

    private AnimationUtils() {
    }

    /**
     * Trigger initialization of this class.
     * Should be called from {@link determination.xenon.setting.Settings#init()}.
     */
    @SuppressWarnings("JavadocReference")
    public static void init() {
    }

    private static final boolean ENABLED = !ConfigHolder.config().isAnimationDisabled();
    private static final boolean PLAY_WINDOW_ANIMATION = ENABLED && !OperatingSystem.CURRENT_OS.isLinuxOrBSD();

    public static boolean isAnimationEnabled() {
        return ENABLED;
    }

    public static boolean playWindowAnimation() {
        return PLAY_WINDOW_ANIMATION;
    }

    public static void reset(Node node, boolean opaque) {
        node.setTranslateX(0);
        node.setTranslateY(0);
        node.setScaleX(1);
        node.setScaleY(1);
        node.setOpacity(opaque ? 1 : 0);
    }
}
