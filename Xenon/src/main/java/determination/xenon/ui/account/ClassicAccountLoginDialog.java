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
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXPasswordField;
import com.jfoenix.controls.JFXProgressBar;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import determination.xenon.auth.AuthInfo;
import determination.xenon.auth.ClassicAccount;
import determination.xenon.auth.NoSelectedCharacterException;
import determination.xenon.setting.Accounts;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.ui.construct.DialogCloseEvent;
import determination.xenon.ui.construct.RequiredValidator;

import java.util.function.Consumer;

import static determination.xenon.ui.FXUtils.onEscPressed;
import static determination.xenon.util.logging.Logger.LOG;
import static determination.xenon.util.i18n.I18n.i18n;

public class ClassicAccountLoginDialog extends StackPane {
    private final ClassicAccount oldAccount;
    private final Consumer<AuthInfo> success;
    private final Runnable failed;

    private final JFXPasswordField txtPassword;
    private final Label lblCreationWarning = new Label();
    private final JFXProgressBar progressBar;

    public ClassicAccountLoginDialog(ClassicAccount oldAccount, Consumer<AuthInfo> success, Runnable failed) {
        this.oldAccount = oldAccount;
        this.success = success;
        this.failed = failed;

        progressBar = new JFXProgressBar();
        StackPane.setAlignment(progressBar, Pos.TOP_CENTER);
        progressBar.setVisible(false);

        JFXDialogLayout dialogLayout = new JFXDialogLayout();

        {
            dialogLayout.setHeading(new Label(i18n("login.enter_password")));
        }

        {
            VBox body = new VBox(15);
            body.setPadding(new Insets(15, 0, 0, 0));

            Label usernameLabel = new Label(oldAccount.getUsername());

            txtPassword = new JFXPasswordField();
            txtPassword.setOnAction(e -> onAccept());
            txtPassword.getValidators().add(new RequiredValidator());
            txtPassword.setLabelFloat(true);
            txtPassword.setPromptText(i18n("account.password"));

            body.getChildren().setAll(usernameLabel, txtPassword);
            dialogLayout.setBody(body);
        }

        {
            JFXButton acceptButton = new JFXButton(i18n("button.ok"));
            acceptButton.setOnAction(e -> onAccept());
            acceptButton.getStyleClass().add("dialog-accept");

            JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
            cancelButton.setOnAction(e -> onCancel());
            cancelButton.getStyleClass().add("dialog-cancel");

            dialogLayout.setActions(lblCreationWarning, acceptButton, cancelButton);
        }

        getChildren().setAll(dialogLayout);

        onEscPressed(this, this::onCancel);
    }

    private void onAccept() {
        String password = txtPassword.getText();
        progressBar.setVisible(true);
        lblCreationWarning.setText("");
        Task.supplyAsync(() -> oldAccount.logInWithPassword(password))
                .whenComplete(Schedulers.javafx(), authInfo -> {
                    success.accept(authInfo);
                    fireEvent(new DialogCloseEvent());
                    progressBar.setVisible(false);
                }, e -> {
                    LOG.info("Failed to login with password: " + oldAccount, e);
                    if (e instanceof NoSelectedCharacterException) {
                        fireEvent(new DialogCloseEvent());
                    } else {
                        lblCreationWarning.setText(Accounts.localizeErrorMessage(e));
                    }
                    progressBar.setVisible(false);
                }).start();
    }

    private void onCancel() {
        failed.run();
        fireEvent(new DialogCloseEvent());
    }
}
