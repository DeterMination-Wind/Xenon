/*
 * Xenon Launcher
 * Copyright (C) 2021-2026  Xenon contributors
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
package determination.xenon.ui.download;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import determination.xenon.mindustry.ui.MindustryModBrowserPane;
import determination.xenon.mindustry.ui.MindustryVariantPickerPane;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.animation.TransitionPane;
import determination.xenon.ui.construct.AdvancedListBox;
import determination.xenon.ui.construct.TabHeader;
import determination.xenon.ui.decorator.DecoratorAnimatedPage;
import determination.xenon.ui.decorator.DecoratorPage;

import java.util.Locale;

import static determination.xenon.util.i18n.I18n.i18n;

/**
 * Xenon-flavoured rewrite of HMCL's {@code DownloadPage}: the Minecraft
 * download UI is gone, replaced by two tabs for Mindustry.
 *
 * <ul>
 *     <li><b>Game</b> — five-card variant picker
 *     ({@link MindustryVariantPickerPane}). Clicking a card hands off to
 *     the Xenon install wizard for that variant.</li>
 *     <li><b>Mods</b> — community mod browser
 *     ({@link MindustryModBrowserPane}) backed by the
 *     {@code Anuken/mindustry-mods} index. Per the project decision,
 *     Mindustry resource packs are treated as ordinary mods, so there's
 *     no separate resource-pack / shader / world / modpack tab.</li>
 * </ul>
 *
 * <p>The HMCL chrome (sidebar + transition pane) is preserved so the
 * page still feels like part of the launcher.</p>
 */
public class DownloadPage extends DecoratorAnimatedPage implements DecoratorPage {
    private final ReadOnlyObjectWrapper<DecoratorPage.State> state =
            new ReadOnlyObjectWrapper<>(DecoratorPage.State.fromTitle(i18n("download"), -1));

    private final TabHeader tab;
    private final TabHeader.Tab<MindustryVariantPickerPane> newGameTab = new TabHeader.Tab<>("newGameTab");
    private final TabHeader.Tab<MindustryModBrowserPane> modTab = new TabHeader.Tab<>("modTab");
    private final TransitionPane transitionPane = new TransitionPane();

    public DownloadPage() {
        this(null);
    }

    /**
     * @param uploadVersion legacy parameter kept for ABI parity with the
     *                      HMCL caller; ignored by Xenon.
     */
    @SuppressWarnings("unused")
    public DownloadPage(String uploadVersion) {
        newGameTab.setNodeSupplier(MindustryVariantPickerPane::new);
        modTab.setNodeSupplier(MindustryModBrowserPane::new);

        tab = new TabHeader(transitionPane, newGameTab, modTab);
        tab.select(newGameTab);

        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(i18n("download.game").toUpperCase(Locale.ROOT))
                .addNavigationDrawerTab(tab, newGameTab, i18n("game"),
                        SVG.STADIA_CONTROLLER, SVG.STADIA_CONTROLLER_FILL)
                .startCategory(i18n("download.content").toUpperCase(Locale.ROOT))
                .addNavigationDrawerTab(tab, modTab, i18n("mods"),
                        SVG.EXTENSION, SVG.EXTENSION_FILL);
        FXUtils.setLimitWidth(sideBar, 200);
        setLeft(sideBar);
        setCenter(transitionPane);
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    public void showGameDownloads() {
        tab.select(newGameTab, false);
    }

    public void showModDownloads() {
        tab.select(modTab, false);
    }

    // ---- HMCL ABI shims (no-ops on Xenon) ---------------------------------

    /** No-op on Xenon: there is no Mindustry modpack tab. */
    public void showModpackDownloads() {
        tab.select(newGameTab, false);
    }

    /** Resource packs are treated as ordinary mods on Xenon. */
    public void showResourcepackDownloads() {
        tab.select(modTab, false);
    }

    /** No-op on Xenon: Mindustry has no shipped world catalog. */
    public void showWorldDownloads() {
        tab.select(newGameTab, false);
    }
}
