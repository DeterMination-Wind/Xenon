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
package determination.xenon.ui.account;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Skin;
import determination.xenon.auth.Account;
import determination.xenon.setting.Accounts;

import static determination.xenon.util.i18n.I18n.i18n;

/// One row in the Mindustry UID list.
public class AccountListItem extends RadioButton {

    private final Account account;
    private final StringProperty title = new SimpleStringProperty();
    private final StringProperty subtitle = new SimpleStringProperty();

    public AccountListItem(Account account) {
        this.account = account;
        getStyleClass().clear();
        setUserData(account);

        String portableSuffix = account.isPortable() ? ", " + i18n("account.portable") : "";
        subtitle.set(i18n("account.methods.offline") + portableSuffix);

        StringBinding characterName = Bindings.createStringBinding(account::getCharacter, account);
        title.bind(characterName);
    }

    public Account getAccount() {
        return account;
    }

    public StringProperty titleProperty() {
        return title;
    }

    public StringProperty subtitleProperty() {
        return subtitle;
    }

    public void remove() {
        Accounts.getAccounts().remove(account);
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new AccountListItemSkin(this);
    }
}
