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
package determination.xenon.mindustry.ui.server;

import determination.xenon.mindustry.server.ServerInstance;
import determination.xenon.mindustry.server.ServerInstanceManager;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.animation.TransitionPane;
import determination.xenon.ui.construct.AdvancedListBox;
import determination.xenon.ui.construct.TabHeader;
import determination.xenon.ui.decorator.DecoratorAnimatedPage;
import determination.xenon.ui.decorator.DecoratorPage;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import static determination.xenon.util.i18n.I18n.i18n;

/**
 * Detail page for a single Mindustry dedicated-server instance. Mirrors
 * {@code MindustryVersionPage} for clients: left sidebar with five tabs
 * (Console / Config / Maps / Mods / Settings), center {@link TransitionPane}.
 *
 * <p>The {@link MindustryServerConsolePane} owns the live process — opening
 * a new detail page does <em>not</em> spawn the server; the user has to
 * click "Start" inside the Console tab. This is so navigating back and
 * forth in the UI doesn't accidentally restart a running server.</p>
 */
public final class MindustryServerDetailPage extends DecoratorAnimatedPage implements DecoratorPage {

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>();

    private final TransitionPane transitionPane = new TransitionPane();
    private final TabHeader tab;
    private final TabHeader.Tab<MindustryServerConsolePane> consoleTab = new TabHeader.Tab<>("consoleTab");
    private final TabHeader.Tab<MindustryServerConfigPane> configTab = new TabHeader.Tab<>("configTab");
    private final TabHeader.Tab<MindustryServerMapsPane> mapsTab = new TabHeader.Tab<>("mapsTab");
    private final TabHeader.Tab<MindustryServerModsPane> modsTab = new TabHeader.Tab<>("modsTab");
    private final TabHeader.Tab<MindustryServerSettingsPane> settingsTab = new TabHeader.Tab<>("settingsTab");
    private final TabHeader.Tab<MindustryServerScriptAgentPane> scriptAgentTab = new TabHeader.Tab<>("scriptAgentTab");

    public MindustryServerDetailPage(ServerInstance inst, ServerInstanceManager manager) {
        consoleTab.setNodeSupplier(() -> new MindustryServerConsolePane(inst, manager));
        configTab.setNodeSupplier(() -> new MindustryServerConfigPane(inst, manager));
        mapsTab.setNodeSupplier(() -> new MindustryServerMapsPane(inst, manager));
        modsTab.setNodeSupplier(() -> new MindustryServerModsPane(inst, manager));
        settingsTab.setNodeSupplier(() -> new MindustryServerSettingsPane(inst, manager));
        scriptAgentTab.setNodeSupplier(() -> new MindustryServerScriptAgentPane(inst, manager));

        tab = new TabHeader(transitionPane, consoleTab, configTab, mapsTab, modsTab, settingsTab, scriptAgentTab);
        tab.select(consoleTab);

        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(inst.getName())
                .addNavigationDrawerTab(tab, consoleTab, i18n("xenon.mindustry.server.tab.console"), SVG.SCRIPT)
                .addNavigationDrawerTab(tab, configTab, i18n("xenon.mindustry.server.tab.config"), SVG.SETTINGS)
                .addNavigationDrawerTab(tab, mapsTab, i18n("xenon.mindustry.server.tab.maps"), SVG.PUBLIC)
                .addNavigationDrawerTab(tab, modsTab, i18n("xenon.mindustry.server.tab.mods"), SVG.EXTENSION, SVG.EXTENSION_FILL)
                .addNavigationDrawerTab(tab, settingsTab, i18n("xenon.mindustry.server.tab.settings"), SVG.ROCKET_LAUNCH)
                .addNavigationDrawerTab(tab, scriptAgentTab, i18n("xenon.server.scriptagent"), SVG.SCRIPT);
        VBox.setVgrow(sideBar, Priority.ALWAYS);

        AdvancedListBox toolbar = new AdvancedListBox()
                .addNavigationDrawerItem(i18n("xenon.mindustry.server.open_folder"), SVG.FOLDER_OPEN,
                        () -> FXUtils.openFolder(manager.getServerRoot(inst.getId())));
        toolbar.getStyleClass().add("advanced-list-box-clear-padding");
        FXUtils.setLimitHeight(toolbar, 40 + 12);

        setLeft(sideBar, toolbar);
        setCenter(transitionPane);

        state.set(State.fromTitle(i18n("xenon.mindustry.server.detail.title", inst.getName()), -1));
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }
}
