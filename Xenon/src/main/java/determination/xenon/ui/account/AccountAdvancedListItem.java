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
package determination.xenon.ui.account;

import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Tooltip;
import determination.xenon.auth.Account;
import determination.xenon.auth.authlibinjector.AuthlibInjectorAccount;
import determination.xenon.auth.authlibinjector.AuthlibInjectorServer;
import determination.xenon.auth.offline.OfflineAccount;
import determination.xenon.auth.yggdrasil.YggdrasilAccount;
import determination.xenon.game.TexturesLoader;
import determination.xenon.mindustry.ui.MindustryGammaAvatar;
import determination.xenon.setting.Accounts;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.AdvancedListItem;
import determination.xenon.util.javafx.BindingMapping;

import static javafx.beans.binding.Bindings.createStringBinding;
import static determination.xenon.setting.Accounts.getAccountFactory;
import static determination.xenon.setting.Accounts.getLocalizedLoginTypeName;
import static determination.xenon.util.i18n.I18n.i18n;

public class AccountAdvancedListItem extends AdvancedListItem {
    private final Tooltip tooltip;
    private final Canvas canvas;

    private final ObjectProperty<Account> account = new SimpleObjectProperty<Account>() {

        @Override
        protected void invalidated() {
            Account account = get();
            if (account == null || !(account instanceof OfflineAccount)) {
                titleProperty().unbind();
                subtitleProperty().unbind();
                tooltip.textProperty().unbind();
                setTitle(i18n("account.missing"));
                setSubtitle(i18n("account.missing.add"));
                tooltip.setText(i18n("account.create"));

                TexturesLoader.unbindAvatar(canvas);
                // Default Xenon avatar = Mindustry Gamma core machine, drawn
                // procedurally instead of the HMCL Steve skin.
                MindustryGammaAvatar.draw(canvas);

            } else {
                // Xenon offline accounts represent a Mindustry UID + nickname,
                // not a Minecraft player — show the Gamma sprite.
                titleProperty().bind(BindingMapping.of(account, Account::getCharacter));
                subtitleProperty().bind(accountSubtitle(account));
                tooltip.textProperty().bind(accountTooltip(account));
                TexturesLoader.unbindAvatar(canvas);
                MindustryGammaAvatar.draw(canvas);
            }
        }
    };

    public AccountAdvancedListItem() {
        this(null);
    }

    public AccountAdvancedListItem(Account account) {
        tooltip = new Tooltip();
        FXUtils.installFastTooltip(this, tooltip);

        canvas = new Canvas(32, 32);
        canvas.setMouseTransparent(true);
        AdvancedListItem.setAlignment(canvas, Pos.CENTER);

        setLeftGraphic(canvas);

        if (account != null) {
            this.accountProperty().set(account);
        } else {
            FXUtils.onScroll(this, Accounts.getAccounts(),
                    accounts -> accounts.indexOf(accountProperty().get()),
                    Accounts::setSelectedAccount);
        }
    }

    public ObjectProperty<Account> accountProperty() {
        return account;
    }

    private static ObservableValue<String> accountSubtitle(Account account) {
        if (account instanceof AuthlibInjectorAccount) {
            return BindingMapping.of(((AuthlibInjectorAccount) account).getServer(), AuthlibInjectorServer::getName);
        } else {
            return createStringBinding(() -> getLocalizedLoginTypeName(getAccountFactory(account)));
        }
    }

    private static ObservableValue<String> accountTooltip(Account account) {
        if (account instanceof AuthlibInjectorAccount) {
            AuthlibInjectorServer server = ((AuthlibInjectorAccount) account).getServer();
            return Bindings.format("%s (%s) (%s)",
                    BindingMapping.of(account, Account::getCharacter),
                    account.getUsername(),
                    BindingMapping.of(server, AuthlibInjectorServer::getName));
        } else if (account instanceof YggdrasilAccount) {
            return Bindings.format("%s (%s)",
                    BindingMapping.of(account, Account::getCharacter),
                    account.getUsername());
        } else {
            return BindingMapping.of(account, Account::getCharacter);
        }
    }

}
