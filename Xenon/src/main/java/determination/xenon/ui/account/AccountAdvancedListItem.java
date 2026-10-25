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

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Tooltip;
import determination.xenon.auth.Account;
import determination.xenon.mindustry.ui.MindustryGammaAvatar;
import determination.xenon.setting.Accounts;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.AdvancedListItem;
import determination.xenon.util.javafx.BindingMapping;

import static determination.xenon.util.i18n.I18n.i18n;

/// Sidebar entry showing the selected Mindustry UID profile.
public class AccountAdvancedListItem extends AdvancedListItem {
    private final Tooltip tooltip;
    private final Canvas canvas;

    private final ObjectProperty<Account> account = new SimpleObjectProperty<Account>() {
        @Override
        protected void invalidated() {
            Account account = get();
            if (account == null) {
                titleProperty().unbind();
                subtitleProperty().unbind();
                tooltip.textProperty().unbind();
                setTitle(i18n("account.missing"));
                setSubtitle(i18n("account.missing.add"));
                tooltip.setText(i18n("account.create"));
            } else {
                titleProperty().bind(BindingMapping.of(account, Account::getCharacter));
                subtitleProperty().bind(accountSubtitle(account));
                tooltip.textProperty().bind(accountTooltip(account));
            }
            MindustryGammaAvatar.draw(canvas);
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
        return javafx.beans.binding.Bindings.createStringBinding(() -> i18n("account.methods.offline"));
    }

    private static ObservableValue<String> accountTooltip(Account account) {
        return BindingMapping.of(account, Account::getCharacter);
    }
}
