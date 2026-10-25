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

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXRadioButton;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.SkinBase;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import determination.xenon.auth.Account;
import determination.xenon.mindustry.ui.MindustryGammaAvatar;
import determination.xenon.setting.Accounts;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.construct.SpinnerPane;

import static determination.xenon.util.i18n.I18n.i18n;

/// Row skin for [AccountListItem]: Gamma avatar, name, portable/global toggle,
/// UUID copy and delete.
final class AccountListItemSkin extends SkinBase<AccountListItem> {

    AccountListItemSkin(AccountListItem skinnable) {
        super(skinnable);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("md-list-cell");
        root.setPadding(new javafx.geometry.Insets(8, 8, 8, 0));

        JFXRadioButton chkSelected = new JFXRadioButton();
        chkSelected.setMouseTransparent(true);
        BorderPane.setAlignment(chkSelected, Pos.CENTER);
        chkSelected.selectedProperty().bind(skinnable.selectedProperty());
        root.setLeft(chkSelected);

        HBox center = new HBox();
        center.setSpacing(8);
        center.setAlignment(Pos.CENTER_LEFT);

        Canvas canvas = new Canvas(32, 32);
        MindustryGammaAvatar.draw(canvas);

        Label title = new Label();
        title.getStyleClass().add("title");
        title.textProperty().bind(skinnable.titleProperty());
        Label subtitle = new Label();
        subtitle.getStyleClass().add("subtitle");
        subtitle.textProperty().bind(skinnable.subtitleProperty());

        VBox item = new VBox(title, subtitle);
        item.getStyleClass().add("two-line-list-item");
        BorderPane.setAlignment(item, Pos.CENTER);

        center.getChildren().setAll(canvas, item);
        root.setCenter(center);

        HBox right = new HBox();
        right.setAlignment(Pos.CENTER_RIGHT);

        JFXButton btnMove = new JFXButton();
        SpinnerPane spinnerMove = new SpinnerPane();
        spinnerMove.getStyleClass().add("small-spinner-pane");
        btnMove.setOnAction(e -> {
            Account account = skinnable.getAccount();
            Accounts.getAccounts().remove(account);
            if (account.isPortable()) {
                account.setPortable(false);
                if (!Accounts.getAccounts().contains(account))
                    Accounts.getAccounts().add(account);
            } else {
                account.setPortable(true);
                if (!Accounts.getAccounts().contains(account)) {
                    int idx = 0;
                    for (int i = Accounts.getAccounts().size() - 1; i >= 0; i--) {
                        if (Accounts.getAccounts().get(i).isPortable()) {
                            idx = i + 1;
                            break;
                        }
                    }
                    Accounts.getAccounts().add(idx, account);
                }
            }
        });
        btnMove.getStyleClass().add("toggle-icon4");
        if (skinnable.getAccount().isPortable()) {
            btnMove.setGraphic(SVG.PUBLIC.createIcon());
            FXUtils.installFastTooltip(btnMove, i18n("account.move_to_global"));
        } else {
            btnMove.setGraphic(SVG.OUTPUT.createIcon());
            FXUtils.installFastTooltip(btnMove, i18n("account.move_to_portable"));
        }
        spinnerMove.setContent(btnMove);
        right.getChildren().add(spinnerMove);

        JFXButton btnCopyUUID = FXUtils.newToggleButton4(SVG.CONTENT_COPY);
        SpinnerPane spinnerCopyUUID = new SpinnerPane();
        spinnerCopyUUID.getStyleClass().add("small-spinner-pane");
        btnCopyUUID.setOnAction(e -> FXUtils.copyText(skinnable.getAccount().getUUID().toString()));
        FXUtils.installFastTooltip(btnCopyUUID, i18n("account.copy_uuid"));
        spinnerCopyUUID.setContent(btnCopyUUID);
        right.getChildren().add(spinnerCopyUUID);

        JFXButton btnRemove = FXUtils.newToggleButton4(SVG.DELETE_FOREVER);
        btnRemove.setOnAction(e -> Controllers.confirm(
                i18n("button.remove.confirm"), i18n("button.remove"), skinnable::remove, null));
        BorderPane.setAlignment(btnRemove, Pos.CENTER);
        FXUtils.installFastTooltip(btnRemove, i18n("button.delete"));
        right.getChildren().add(btnRemove);
        root.setRight(right);

        root.getStyleClass().add("card");
        root.setStyle("-fx-padding: 8 8 8 0;");
        com.jfoenix.effects.JFXDepthManager.setDepth(root, 1);

        getChildren().setAll(root);
    }
}
