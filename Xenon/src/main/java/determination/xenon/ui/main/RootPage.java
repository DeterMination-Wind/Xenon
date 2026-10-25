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
package determination.xenon.ui.main;

import com.jfoenix.controls.JFXPopup;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.scene.layout.Region;
import determination.xenon.game.Version;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.setting.Accounts;
import determination.xenon.setting.Profile;
import determination.xenon.setting.Profiles;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.account.AccountAdvancedListItem;
import determination.xenon.ui.account.AccountListPopupMenu;
import determination.xenon.ui.animation.AnimationUtils;
import determination.xenon.ui.construct.AdvancedListBox;
import determination.xenon.ui.construct.AdvancedListItem;
import determination.xenon.ui.decorator.DecoratorAnimatedPage;
import determination.xenon.ui.decorator.DecoratorPage;
import determination.xenon.ui.versions.GameAdvancedListItem;
import determination.xenon.ui.versions.GameListPopupMenu;
import determination.xenon.ui.versions.Versions;
import determination.xenon.upgrade.UpdateChecker;
import determination.xenon.util.Lang;
import determination.xenon.util.platform.*;

import java.util.List;
import java.util.Locale;

import static determination.xenon.ui.FXUtils.runInFX;
import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

public class RootPage extends DecoratorAnimatedPage implements DecoratorPage {
    private MainPage mainPage = null;

    public RootPage() {
        getStyleClass().remove("gray-background");
        getLeft().getStyleClass().add("gray-background");
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return getMainPage().stateProperty();
    }

    @Override
    protected Skin createDefaultSkin() {
        return new Skin(this);
    }

    public MainPage getMainPage() {
        if (mainPage == null) {
            MainPage mainPage = new MainPage();
            FXUtils.applyDragListener(mainPage,
                    MindustryImportFlow::isXenonModpackFile,
                    files -> MindustryImportFlow.showInstallModpackDialog(files.get(0)));

            FXUtils.onChangeAndOperate(Profiles.selectedVersionProperty(), mainPage::setCurrentGame);
            mainPage.showUpdateProperty().bind(UpdateChecker.outdatedProperty());
            mainPage.latestVersionProperty().bind(UpdateChecker.latestVersionProperty());

            Profiles.registerVersionsListener(profile -> {
                List<Version> children = new java.util.ArrayList<>();
                try {
                    for (determination.xenon.mindustry.MindustryVersion v :
                            determination.xenon.mindustry.MindustryImportFlow.visibleVersions(profile)) {
                        children.add(new Version(v.getId()));
                    }
                } catch (Throwable ex) {
                    LOG.warning("Failed to load Mindustry versions for MainPage", ex);
                }

                runInFX(() -> {
                    if (profile == Profiles.getSelectedProfile()) {
                        mainPage.initVersions(profile, children);
                    }
                });
            });
            this.mainPage = mainPage;
        }
        return mainPage;
    }

    private static class Skin extends DecoratorAnimatedPageSkin<RootPage> {

        protected Skin(RootPage control) {
            super(control);

            // first item in left sidebar
            AccountAdvancedListItem accountListItem = new AccountAdvancedListItem();
            accountListItem.setOnAction(e -> Controllers.navigate(Controllers.getAccountListPage()));
            FXUtils.onSecondaryButtonClicked(accountListItem, () -> AccountListPopupMenu.show(accountListItem, JFXPopup.PopupVPosition.TOP, JFXPopup.PopupHPosition.LEFT, accountListItem.getWidth(), 0));
            accountListItem.accountProperty().bind(Accounts.selectedAccountProperty());

            // second item in left sidebar
            GameAdvancedListItem gameListItem = new GameAdvancedListItem();
            gameListItem.setOnAction(e -> {
                Profile profile = Profiles.getSelectedProfile();
                String version = Profiles.getSelectedVersion();
                if (version == null) {
                    Controllers.navigate(Controllers.getGameListPage());
                } else {
                    Versions.modifyGameSettings(profile, version);
                }
            });
            FXUtils.onScroll(gameListItem, getSkinnable().getMainPage().getVersions(), list -> {
                String currentId = getSkinnable().getMainPage().getCurrentGame();
                return Lang.indexWhere(list, instance -> instance.getId().equals(currentId));
            }, it -> getSkinnable().getMainPage().getProfile().setSelectedVersion(it.getId()));
            FXUtils.onSecondaryButtonClicked(gameListItem, () -> showGameListPopupMenu(gameListItem));

            // third item in left sidebar
            AdvancedListItem gameItem = new AdvancedListItem();
            gameItem.setLeftIcon(SVG.FORMAT_LIST_BULLETED);
            gameItem.setTitle(i18n("version.manage"));
            gameItem.setOnAction(e -> Controllers.navigate(Controllers.getGameListPage()));
            FXUtils.onSecondaryButtonClicked(gameItem, () -> showGameListPopupMenu(gameItem));

            // forth item in left sidebar
            AdvancedListItem downloadItem = new AdvancedListItem();
            downloadItem.setLeftIcon(SVG.DOWNLOAD);
            downloadItem.setTitle(i18n("download"));
            downloadItem.setOnAction(e -> {
                Controllers.getDownloadPage().showGameDownloads();
                Controllers.navigate(Controllers.getDownloadPage());
            });
            FXUtils.installFastTooltip(downloadItem, i18n("download.hint"));
            if (AnimationUtils.isAnimationEnabled()) {
                FXUtils.prepareOnMouseEnter(downloadItem, Controllers::prepareDownloadPage);
            }

            // server manager — Mindustry dedicated-server instances
            AdvancedListItem serverItem = new AdvancedListItem();
            serverItem.setLeftIcon(SVG.PUBLIC);
            serverItem.setTitle(i18n("xenon.mindustry.server.sidebar"));
            serverItem.setOnAction(e -> Controllers.navigate(
                    new determination.xenon.mindustry.ui.server.MindustryServerListPane()));

            // community — native MDTBBS forum page
            AdvancedListItem communityItem = new AdvancedListItem();
            communityItem.setLeftIcon(SVG.EXPLORE);
            communityItem.setTitle(i18n("xenon.community"));
            communityItem.setOnAction(e -> Controllers.navigate(
                    new determination.xenon.mindustry.ui.MindustryCommunityPane()));

            // netplay — public server directory + EasyTier P2P rooms
            AdvancedListItem netplayItem = new AdvancedListItem();
            netplayItem.setLeftIcon(SVG.PUBLIC);
            netplayItem.setTitle(i18n("xenon.netplay.title"));
            netplayItem.setOnAction(e -> Controllers.navigate(
                    new determination.xenon.mindustry.ui.MindustryNetplayPane()));

            // fifth item in left sidebar
            AdvancedListItem launcherSettingsItem = new AdvancedListItem();
            launcherSettingsItem.setLeftIcon(SVG.SETTINGS);
            launcherSettingsItem.setTitle(i18n("settings"));
            launcherSettingsItem.setOnAction(e -> Controllers.navigate(Controllers.getSettingsPage()));
            if (AnimationUtils.isAnimationEnabled()) {
                FXUtils.prepareOnMouseEnter(launcherSettingsItem, Controllers::prepareSettingsPage);
            }

            // the left sidebar
            AdvancedListBox sideBar = new AdvancedListBox()
                    .startCategory(i18n("account").toUpperCase(Locale.ROOT))
                    .add(accountListItem)
                    .startCategory(i18n("version").toUpperCase(Locale.ROOT))
                    .add(gameListItem)
                    .add(gameItem)
                    .add(downloadItem)
                    .add(serverItem)
                    .add(communityItem)
                    .add(netplayItem)
                    .startCategory(i18n("settings.launcher.general").toUpperCase(Locale.ROOT))
                    .add(launcherSettingsItem)
                    .addNavigationDrawerItem(i18n("contact.chat"), SVG.CHAT, () -> {
                        Controllers.getSettingsPage().showFeedback();
                        Controllers.navigate(Controllers.getSettingsPage());
                    });

            // the root page, with the sidebar in left, navigator in center.
            setLeft(sideBar);
            setCenter(getSkinnable().getMainPage());
        }

        public void showGameListPopupMenu(Region gameListItem) {
            GameListPopupMenu.show(gameListItem,
                    JFXPopup.PopupVPosition.TOP,
                    JFXPopup.PopupHPosition.LEFT,
                    gameListItem.getWidth(),
                    0,
                    getSkinnable().getMainPage().getProfile(),
                    getSkinnable().getMainPage().getVersions());
        }
    }
}
