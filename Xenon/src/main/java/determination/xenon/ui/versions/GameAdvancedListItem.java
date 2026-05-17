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
package determination.xenon.ui.versions;

import javafx.geometry.Pos;
import determination.xenon.event.Event;
import determination.xenon.setting.Profile;
import determination.xenon.setting.Profiles;
import determination.xenon.setting.VersionIconType;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.WeakListenerHolder;
import determination.xenon.ui.construct.AdvancedListItem;
import determination.xenon.ui.construct.ImageContainer;

import java.util.function.Consumer;

import static determination.xenon.util.i18n.I18n.i18n;

public class GameAdvancedListItem extends AdvancedListItem {
    private final ImageContainer imageContainer;
    private final WeakListenerHolder holder = new WeakListenerHolder();
    private Profile profile;
    @SuppressWarnings("unused")
    private Consumer<Event> onVersionIconChangedListener;

    public GameAdvancedListItem() {
        this.imageContainer = new ImageContainer(LEFT_GRAPHIC_SIZE);
        imageContainer.setMouseTransparent(true);
        AdvancedListItem.setAlignment(imageContainer, Pos.CENTER);
        setLeftGraphic(imageContainer);

        holder.add(FXUtils.onWeakChangeAndOperate(Profiles.selectedVersionProperty(), this::loadVersion));
    }

    private void loadVersion(String version) {
        if (Profiles.getSelectedProfile() != profile) {
            profile = Profiles.getSelectedProfile();
            if (profile != null) {
                onVersionIconChangedListener = profile.getRepository().onVersionIconChanged.registerWeak(event -> {
                    this.loadVersion(Profiles.getSelectedVersion());
                });
            }
        }
        // Mindustry instances live outside HMCL's repo — check the Xenon
        // repo first so a Mindustry-only profile doesn't show "no game".
        if (version != null
                && determination.xenon.mindustry.ui.MindustryRoutes.isMindustry(version)) {
            setTitle(i18n("version.manage.manage"));
            setSubtitle(version);
            // No per-version Mindustry icon yet — keep the default art.
            imageContainer.setImage(VersionIconType.DEFAULT.getIcon());
            return;
        }
        if (version != null && Profiles.getSelectedProfile() != null &&
                Profiles.getSelectedProfile().getRepository().hasVersion(version)) {
            setTitle(i18n("version.manage.manage"));
            setSubtitle(version);
            imageContainer.setImage(Profiles.getSelectedProfile().getRepository().getVersionIconImage(version));
        } else {
            setTitle(i18n("version.empty"));
            setSubtitle(i18n("version.empty.add"));
            imageContainer.setImage(VersionIconType.DEFAULT.getIcon());
        }
    }
}
