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
package determination.xenon.ui.terracotta;

import com.jfoenix.controls.JFXPopup;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import determination.xenon.setting.Accounts;
import determination.xenon.setting.Profile;
import determination.xenon.setting.Profiles;
import determination.xenon.terracotta.TerracottaMetadata;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.account.AccountAdvancedListItem;
import determination.xenon.ui.account.AccountListPopupMenu;
import determination.xenon.ui.animation.TransitionPane;
import determination.xenon.ui.construct.*;
import determination.xenon.ui.decorator.DecoratorAnimatedPage;
import determination.xenon.ui.decorator.DecoratorPage;
import determination.xenon.ui.main.MainPage;
import determination.xenon.ui.versions.GameListPopupMenu;
import determination.xenon.ui.versions.Versions;
import determination.xenon.util.Lang;
import determination.xenon.util.StringUtils;

import static determination.xenon.setting.ConfigHolder.globalConfig;
import static determination.xenon.util.i18n.I18n.i18n;

public class TerracottaPage extends DecoratorAnimatedPage implements DecoratorPage, PageAware {
    private static final int TERRACOTTA_AGREEMENT_VERSION = 2;

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle(i18n("terracotta.terracotta")));
    private final TabHeader tab;
    private final TabHeader.Tab<TerracottaControllerPage> statusPage = new TabHeader.Tab<>("statusPage");
    private final TransitionPane transitionPane = new TransitionPane();

    @SuppressWarnings("unused")
    private ChangeListener<String> instanceChangeListenerHolder;

    public TerracottaPage() {
        statusPage.setNodeSupplier(TerracottaControllerPage::new);
        tab = new TabHeader(transitionPane, statusPage);
        tab.select(statusPage);

        BorderPane left = new BorderPane();
        FXUtils.setLimitWidth(left, 200);
        VBox.setVgrow(left, Priority.ALWAYS);
        setLeft(left);

        AdvancedListBox sideBar = new AdvancedListBox()
                .addNavigationDrawerTab(tab, statusPage, i18n("terracotta.status"), SVG.TUNE);
        left.setTop(sideBar);

        AccountAdvancedListItem accountListItem = new AccountAdvancedListItem();
        accountListItem.setOnAction(e -> Controllers.navigate(Controllers.getAccountListPage()));
        accountListItem.accountProperty().bind(Accounts.selectedAccountProperty());
        FXUtils.onSecondaryButtonClicked(accountListItem, () -> AccountListPopupMenu.show(accountListItem, JFXPopup.PopupVPosition.BOTTOM, JFXPopup.PopupHPosition.LEFT, accountListItem.getWidth(), 0));

        AdvancedListBox toolbar = new AdvancedListBox()
                .add(accountListItem)
                .addNavigationDrawerItem(i18n("version.launch"), SVG.ROCKET_LAUNCH, () -> {
                    Profile profile = Profiles.getSelectedProfile();
                    Versions.launch(profile, profile.getSelectedVersion(), launcherHelper -> {
                        launcherHelper.setKeep();
                        launcherHelper.setDisableOfflineSkin();
                    });
                }, item -> {
                    instanceChangeListenerHolder = FXUtils.onWeakChangeAndOperate(Profiles.selectedVersionProperty(),
                            instanceName -> item.setSubtitle(StringUtils.isNotBlank(instanceName) ? instanceName : i18n("version.empty"))
                    );

                    MainPage mainPage = Controllers.getRootPage().getMainPage();
                    FXUtils.onScroll(item, mainPage.getVersions(), list -> {
                        String currentId = mainPage.getCurrentGame();
                        return Lang.indexWhere(list, instance -> instance.getId().equals(currentId));
                    }, it -> mainPage.getProfile().setSelectedVersion(it.getId()));

                    FXUtils.onSecondaryButtonClicked(item, () -> GameListPopupMenu.show(item,
                            JFXPopup.PopupVPosition.BOTTOM,
                            JFXPopup.PopupHPosition.LEFT,
                            item.getWidth(),
                            0,
                            mainPage.getProfile(), mainPage.getVersions()));
                })
                .addNavigationDrawerItem(i18n("terracotta.feedback.title"), SVG.FEEDBACK, () -> FXUtils.openLink(TerracottaMetadata.FEEDBACK_LINK));
        BorderPane.setMargin(toolbar, new Insets(0, 0, 12, 0));
        left.setBottom(toolbar);

        setCenter(transitionPane);
    }

    @Override
    public void onPageShown() {
        tab.onPageShown();

        if (globalConfig().getTerracottaAgreementVersion() < TERRACOTTA_AGREEMENT_VERSION) {
            Controllers.confirmWithCountdown(i18n("terracotta.confirm.desc"), i18n("terracotta.confirm.title"), 5, MessageDialogPane.MessageType.INFO, () -> {
                globalConfig().setTerracottaAgreementVersion(TERRACOTTA_AGREEMENT_VERSION);
            }, () -> fireEvent(new PageCloseEvent()));
        }
    }

    @Override
    public void onPageHidden() {
        tab.onPageHidden();
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }
}
