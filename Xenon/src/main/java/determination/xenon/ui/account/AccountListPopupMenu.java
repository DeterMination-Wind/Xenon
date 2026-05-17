/*
 * Xenon Launcher
 * Copyright (C) 2026-2026  Xenon contributors
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

import com.jfoenix.controls.JFXPopup;
import javafx.beans.InvalidationListener;
import javafx.beans.WeakInvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import determination.xenon.auth.Account;
import determination.xenon.setting.Accounts;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.AdvancedListBox;

import static determination.xenon.util.i18n.I18n.i18n;

public final class AccountListPopupMenu extends StackPane {
    public static void show(Node owner, JFXPopup.PopupVPosition vAlign, JFXPopup.PopupHPosition hAlign,
                            double initOffsetX, double initOffsetY) {
        var menu = new AccountListPopupMenu();
        JFXPopup popup = new JFXPopup(menu);
        popup.show(owner, vAlign, hAlign, initOffsetX, initOffsetY);
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final BooleanBinding isEmpty = Bindings.isEmpty(Accounts.getAccounts());
    @SuppressWarnings("FieldCanBeLocal")
    private final InvalidationListener listener;

    public AccountListPopupMenu() {
        AdvancedListBox box = new AdvancedListBox();
        box.getStyleClass().add("no-padding");
        box.setPrefWidth(220);
        box.setPrefHeight(-1);
        box.setMaxHeight(260);

        listener = o -> {
            box.clear();

            for (Account account : Accounts.getAccounts()) {
                AccountAdvancedListItem item = new AccountAdvancedListItem(account);
                item.setOnAction(e -> {
                    Accounts.setSelectedAccount(account);
                    if (getScene().getWindow() instanceof JFXPopup popup)
                        popup.hide();
                });
                box.add(item);
            }
        };
        listener.invalidated(null);
        Accounts.getAccounts().addListener(new WeakInvalidationListener(listener));

        Label placeholder = new Label(i18n("account.empty"));
        placeholder.setStyle("-fx-padding: 10px; -fx-text-fill: -monet-on-surface-variant; -fx-font-style: italic;");

        FXUtils.onChangeAndOperate(isEmpty, empty -> {
            getChildren().setAll(empty ? placeholder : box);
        });
    }

}
