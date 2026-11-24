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
package determination.xenon.ui.construct;

import javafx.scene.control.Labeled;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.List;

/// Reserves the width of the widest label a control will ever show.
///
/// A JavaFX button or label sizes to its current text, so a control that cycles
/// through `Install` → `Update` → `Installed`, or a progress line that grows
/// from `999 B/s` to `1.2 MiB/s · 0:32`, makes its neighbours shift on every
/// update. Measuring all candidate strings once and pinning the control to the
/// widest one keeps the layout still without truncating any of them.
///
/// The measurement uses a throw-away [Text] node, which is text-only and does
/// not need a scene.
@NotNullByDefault
public final class StableTextWidth {

    /// Gap added to the measured width so a theme's padding never clips a glyph.
    private static final double PADDING = 6;

    /// Prevents instantiation of this utility class.
    private StableTextWidth() {}

    /// Pins `control` to the width of its widest candidate string.
    ///
    /// Call this after the control's font is final (a style class that changes
    /// the font must be applied before this runs).
    ///
    /// @param control    label or button whose width must stop changing
    /// @param candidates every text the control may display
    public static void reserve(Labeled control, List<String> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        Font font = control.getFont();
        double widest = 0;
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Text probe = new Text(candidate);
            probe.setFont(font);
            widest = Math.max(widest, probe.getLayoutBounds().getWidth());
        }
        if (widest <= 0) {
            return;
        }
        control.setMinWidth(widest + PADDING);
        control.setPrefWidth(widest + PADDING);
    }
}
