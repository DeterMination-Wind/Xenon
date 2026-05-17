/*
 * Xenon Launcher
 * Copyright (C) 2020-2026  Xenon contributors
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

import com.jfoenix.controls.JFXPopup;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;

public final class IconedMenuItem extends IconedItem {

    public IconedMenuItem(SVG icon, String text, Runnable action, JFXPopup popup) {
        super(icon != null ? icon.createIcon(14) : null, text);

        getStyleClass().setAll("iconed-menu-item");

        if (popup == null) {
            FXUtils.onClicked(this, action);
        } else {
            FXUtils.onClicked(this, () -> {
                action.run();
                popup.hide();
            });
        }
    }

    public IconedMenuItem addTooltip(String tooltip) {
        FXUtils.installFastTooltip(this, tooltip);
        return this;
    }
}
