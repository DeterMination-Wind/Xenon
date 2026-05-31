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
package determination.xenon.ui.versions;

import com.jfoenix.controls.JFXPopup;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.scene.Node;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import determination.xenon.event.EventBus;
import determination.xenon.event.EventPriority;
import determination.xenon.event.RefreshedVersionsEvent;
import determination.xenon.game.GameRepository;
import determination.xenon.setting.Profile;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.WeakListenerHolder;
import determination.xenon.ui.animation.TransitionPane;
import determination.xenon.ui.construct.*;
import determination.xenon.ui.decorator.DecoratorAnimatedPage;
import determination.xenon.ui.decorator.DecoratorPage;
import determination.xenon.util.StringUtils;
import determination.xenon.util.io.FileUtils;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

import static determination.xenon.ui.FXUtils.runInFX;
import static determination.xenon.util.i18n.I18n.i18n;

public class VersionPage extends DecoratorAnimatedPage implements DecoratorPage {
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>();
    private final TabHeader tab;
    private final TabHeader.Tab<VersionSettingsPage> versionSettingsTab = new TabHeader.Tab<>("versionSettingsTab");
    private final TabHeader.Tab<ModListPage> modListTab = new TabHeader.Tab<>("modListTab");
    private final TabHeader.Tab<SchematicsPage> schematicsTab = new TabHeader.Tab<>("schematicsTab");
    private final TransitionPane transitionPane = new TransitionPane();
    private final BooleanProperty currentVersionUpgradable = new SimpleBooleanProperty();
    private final ObjectProperty<Profile.ProfileVersion> version = new SimpleObjectProperty<>();
    private final WeakListenerHolder listenerHolder = new WeakListenerHolder();

    private String preferredVersionName = null;

    public static class WorkingDirChangedEvent extends Event {
        public static final EventType<WorkingDirChangedEvent> EVENT_TYPE = new EventType<>(Event.ANY, "WORKING_DIR_CHANGED");
        public WorkingDirChangedEvent() {
            super(EVENT_TYPE);
        }
    }

    public VersionPage() {
        versionSettingsTab.setNodeSupplier(loadVersionFor(() -> new VersionSettingsPage(false)));
        modListTab.setNodeSupplier(loadVersionFor(ModListPage::new));
        schematicsTab.setNodeSupplier(loadVersionFor(SchematicsPage::new));

        tab = new TabHeader(transitionPane, versionSettingsTab, modListTab, schematicsTab);
        tab.select(versionSettingsTab);

        addEventHandler(Navigator.NavigationEvent.NAVIGATED, this::onNavigated);

        addEventHandler(WorkingDirChangedEvent.EVENT_TYPE, event -> {
            if (this.version.get() != null) {
                if (modListTab.isInitialized())
                    modListTab.getNode().loadVersion(getProfile(), getVersion());
                if (schematicsTab.isInitialized())
                    schematicsTab.getNode().loadVersion(getProfile(), getVersion());
            }
        });
        
        listenerHolder.add(EventBus.EVENT_BUS.channel(RefreshedVersionsEvent.class).registerWeak(event -> checkSelectedVersion(), EventPriority.HIGHEST));
    }

    private void checkSelectedVersion() {
        runInFX(() -> {
            if (this.version.get() == null) return;
            GameRepository repository = this.version.get().getProfile().getRepository();
            if (!repository.hasVersion(this.version.get().getVersion())) {
                if (preferredVersionName != null) {
                    loadVersion(preferredVersionName, this.version.get().getProfile());
                } else {
                    fireEvent(new PageCloseEvent());
                }
            }
        });
    }

    private <T extends Node> Supplier<T> loadVersionFor(Supplier<T> nodeSupplier) {
        return () -> {
            T node = nodeSupplier.get();
            if (version.get() != null) {
                if (node instanceof VersionPage.VersionLoadable) {
                    ((VersionLoadable) node).loadVersion(version.get().getProfile(), version.get().getVersion());
                }
            }
            return node;
        };
    }

    public void showInstanceSettings() {
        tab.select(versionSettingsTab, false);
    }

    public void setVersion(String version, Profile profile) {
        this.version.set(new Profile.ProfileVersion(profile, version));
    }

    public void loadVersion(String version, Profile profile) {
        // If this id is a Mindustry instance, the HMCL VersionPage has the
        // wrong tabs (Installer / ModListPage with "install a modloader"
        // hint / MC test-launch). Detour to the Mindustry page and bail.
        if (determination.xenon.mindustry.ui.MindustryRoutes.isMindustry(version)) {
            String captured = version;
            Platform.runLater(() -> {
                fireEvent(new PageCloseEvent());
                determination.xenon.mindustry.ui.MindustryRoutes.get(captured)
                        .ifPresent(determination.xenon.mindustry.ui.MindustryRoutes::openVersionPage);
            });
            return;
        }

        // If we jumped to game list page and deleted this version
        // and back to this page, we should return to main page.
        if (this.version.get() != null && (!getProfile().getRepository().isLoaded() ||
                !getProfile().getRepository().hasVersion(version))) {
            Platform.runLater(() -> fireEvent(new PageCloseEvent()));
            return;
        }

        setVersion(version, profile);
        preferredVersionName = version;

        if (versionSettingsTab.isInitialized())
            versionSettingsTab.getNode().loadVersion(profile, version);
        if (modListTab.isInitialized())
            modListTab.getNode().loadVersion(profile, version);
        if (schematicsTab.isInitialized())
            schematicsTab.getNode().loadVersion(profile, version);
        currentVersionUpgradable.set(profile.getRepository().isModpack(version));
    }

    private void onNavigated(Navigator.NavigationEvent event) {
        if (this.version.get() == null)
            throw new IllegalStateException();

        // Mindustry instances must never render in the HMCL VersionPage —
        // its tabs (installer / mods.not_modded / MC test-launch) are all
        // wrong for them. Detour to the Mindustry page on the very next
        // FX tick.
        String currentId = getVersion();
        if (determination.xenon.mindustry.ui.MindustryRoutes.isMindustry(currentId)) {
            Platform.runLater(() -> {
                fireEvent(new PageCloseEvent());
                determination.xenon.mindustry.ui.MindustryRoutes.get(currentId)
                        .ifPresent(determination.xenon.mindustry.ui.MindustryRoutes::openVersionPage);
            });
            return;
        }

        // If we jumped to game list page and deleted this version
        // and back to this page, we should return to main page.
        if (!getProfile().getRepository().isLoaded() ||
                !getProfile().getRepository().hasVersion(getVersion())) {
            Platform.runLater(() -> fireEvent(new PageCloseEvent()));
            return;
        }

        loadVersion(getVersion(), getProfile());
    }

    private void onBrowse(String sub) {
        FXUtils.openFolder(getProfile().getRepository().getRunDirectory(getVersion()).resolve(sub));
    }

    private void redownloadAssetIndex() {
        Versions.updateGameAssets(getProfile(), getVersion());
    }

    private void clearLibraries() {
        var libraries = getProfile().getRepository().getBaseDirectory().resolve("libraries");
        Task.runAsync(Schedulers.io(), () -> {
            FileUtils.deleteDirectoryQuietly(libraries);
        }).whenComplete(Schedulers.javafx(), (exception) -> {
            if (exception != null) {
                Controllers.dialog(i18n("message.failed") + "\n" + StringUtils.getStackTrace(exception), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
            }
        }).start();
    }

    private void clearAssets() {
        Path assetsDir = getProfile().getRepository().getBaseDirectory().resolve("assets");

        Profile.ProfileVersion currentVersion = version.get();
        Path resourcesDir = currentVersion != null
                ? getProfile().getRepository().getRunDirectory(currentVersion.getVersion()).resolve("resources")
                : null;

        Task.runAsync(Schedulers.io(), () -> {
            FileUtils.deleteDirectoryQuietly(assetsDir);
            if (resourcesDir != null) {
                FileUtils.deleteDirectoryQuietly(resourcesDir);
            }
        }).whenComplete(Schedulers.javafx(), (exception) -> {
            if (exception != null) {
                Controllers.dialog(i18n("message.failed") + "\n" + StringUtils.getStackTrace(exception), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
            }
        }).start();
    }

    private void clearJunkFiles() {
        Versions.cleanVersion(getProfile(), getVersion());
    }

    private void testGame() {
        Versions.testGame(getProfile(), getVersion());
    }

    private void updateGame() {
        Versions.updateVersion(getProfile(), getVersion());
    }

    private void generateLaunchScript() {
        Versions.generateLaunchScript(getProfile(), getVersion());
    }

    private void export() {
        Versions.exportVersion(getProfile(), getVersion());
    }

    private void rename() {
        Versions.renameVersion(getProfile(), getVersion())
                .thenApply(newVersionName -> this.preferredVersionName = newVersionName);
    }

    private void remove() {
        Versions.deleteVersion(getProfile(), getVersion());
    }

    private void duplicate() {
        Versions.duplicateVersion(getProfile(), getVersion());
    }

    public Profile getProfile() {
        return Optional.ofNullable(version.get()).map(Profile.ProfileVersion::getProfile).orElse(null);
    }

    public String getVersion() {
        return Optional.ofNullable(version.get()).map(Profile.ProfileVersion::getVersion).orElse(null);
    }

    @Override
    protected Skin createDefaultSkin() {
        return new Skin(this);
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    public static class Skin extends DecoratorAnimatedPageSkin<VersionPage> {

        /**
         * Constructor for all SkinBase instances.
         *
         * @param control The control for which this Skin should attach to.
         */
        protected Skin(VersionPage control) {
            super(control);

            {
                AdvancedListBox sideBar = new AdvancedListBox()
                        .addNavigationDrawerTab(control.tab, control.versionSettingsTab, i18n("settings.game"), SVG.SETTINGS, SVG.SETTINGS_FILL)
                        .addNavigationDrawerTab(control.tab, control.modListTab, i18n("mods.manage"), SVG.EXTENSION, SVG.EXTENSION_FILL)
                        .addNavigationDrawerTab(control.tab, control.schematicsTab, i18n("schematics.manage"), SVG.SCHEMA, SVG.SCHEMA_FILL);
                VBox.setVgrow(sideBar, Priority.ALWAYS);

                PopupMenu browseList = new PopupMenu();
                JFXPopup browsePopup = new JFXPopup(browseList);
                browseList.getContent().setAll(
                        new IconedMenuItem(SVG.STADIA_CONTROLLER, i18n("folder.game"), () -> control.onBrowse(""), browsePopup),
                        new IconedMenuItem(SVG.EXTENSION, i18n("folder.mod"), () -> control.onBrowse("mods"), browsePopup),
                        new IconedMenuItem(SVG.SCHEMA, i18n("folder.schematics"), () -> control.onBrowse("schematics"), browsePopup),
                        new IconedMenuItem(SVG.SCREENSHOT_MONITOR, i18n("folder.screenshots"), () -> control.onBrowse("screenshots"), browsePopup),
                        new IconedMenuItem(SVG.SETTINGS, i18n("folder.config"), () -> control.onBrowse("config"), browsePopup),
                        new IconedMenuItem(SVG.SCRIPT, i18n("folder.logs"), () -> control.onBrowse("logs"), browsePopup)
                );

                PopupMenu managementList = new PopupMenu();
                JFXPopup managementPopup = new JFXPopup(managementList);
                managementList.getContent().setAll(
                        new IconedMenuItem(SVG.ROCKET_LAUNCH, i18n("version.launch.test"), control::testGame, managementPopup),
                        new IconedMenuItem(SVG.SCRIPT, i18n("version.launch_script"), control::generateLaunchScript, managementPopup),
                        new MenuSeparator(),
                        new IconedMenuItem(SVG.EDIT, i18n("version.manage.rename"), control::rename, managementPopup),
                        new IconedMenuItem(SVG.FOLDER_COPY, i18n("version.manage.duplicate"), control::duplicate, managementPopup),
                        new IconedMenuItem(SVG.DELETE, i18n("version.manage.remove"), control::remove, managementPopup),
                        new MenuSeparator(),
                        new IconedMenuItem(null, i18n("version.manage.clean"), control::clearJunkFiles, managementPopup).addTooltip(i18n("version.manage.clean.tooltip"))
                );

                AdvancedListBox toolbar = new AdvancedListBox()
                        .addNavigationDrawerItem(i18n("version.update"), SVG.UPDATE, control::updateGame, upgradeItem -> {
                            upgradeItem.visibleProperty().bind(control.currentVersionUpgradable);
                        })
                        .addNavigationDrawerItem(i18n("version.launch.test"), SVG.ROCKET_LAUNCH, control::testGame)
                        .addNavigationDrawerItem(i18n("settings.game.exploration"), SVG.FOLDER_OPEN, null, browseMenuItem -> {
                            browseMenuItem.setOnAction(e -> browsePopup.show(browseMenuItem, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.LEFT, browseMenuItem.getWidth(), 0));
                        })
                        .addNavigationDrawerItem(i18n("settings.game.management"), SVG.MENU, null, managementItem -> {
                            managementItem.setOnAction(e -> managementPopup.show(managementItem, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.LEFT, managementItem.getWidth(), 0));
                        });
                toolbar.getStyleClass().add("advanced-list-box-clear-padding");
                FXUtils.setLimitHeight(toolbar, 40 * 4 + 12 * 2);

                setLeft(sideBar, toolbar);
            }

            control.state.bind(Bindings.createObjectBinding(() ->
                            State.fromTitle(i18n("version.manage.manage.title", getSkinnable().getVersion()), -1),
                    getSkinnable().version));

            //control.transitionPane.getStyleClass().add("gray-background");
            //FXUtils.setOverflowHidden(control.transitionPane, 8);
            setCenter(control.transitionPane);
        }
    }

    public interface VersionLoadable {
        void loadVersion(Profile profile, String version);
    }
}
