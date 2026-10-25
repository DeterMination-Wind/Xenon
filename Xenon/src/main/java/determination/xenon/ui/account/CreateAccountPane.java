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

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextField;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import determination.xenon.auth.offline.OfflineAccount;
import determination.xenon.mindustry.steam.SteamAccount;
import determination.xenon.mindustry.steam.SteamAccountDiscovery;
import determination.xenon.mindustry.uuid.UuidGenerator;
import determination.xenon.setting.Accounts;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.construct.DialogCloseEvent;
import determination.xenon.ui.construct.DialogPane;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.util.StringUtils;
import determination.xenon.util.gson.UUIDTypeAdapter;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Creates a Mindustry UID profile: a nickname, an optional explicit UID and a
/// Steam import shortcut.
///
/// Leaving the UID blank derives it from the nickname. A pasted UID may use
/// Mindustry's base64 form or a legacy UUID string; both are accepted.
public final class CreateAccountPane extends DialogPane {

    private final JFXTextField usernameField = new JFXTextField();
    private final JFXTextField uuidField = new JFXTextField();

    public CreateAccountPane() {
        setTitle(i18n("account.create"));

        usernameField.setPromptText(i18n("account.methods.offline.name.special_characters"));
        uuidField.setPromptText(i18n("account.methods.offline.uuid"));

        JFXButton importSteam = new JFXButton(i18n("account.steam.import"));
        importSteam.getStyleClass().add("dialog-cancel");
        importSteam.setOnAction(event -> importFromSteam(importSteam));

        HBox uuidBox = new HBox(8, uuidField, importSteam);
        HBox.setHgrow(uuidField, Priority.ALWAYS);

        GridPane body = new GridPane();
        body.setHgap(16);
        body.setVgap(8);
        body.addRow(0, new Label(i18n("account.create.offline")), usernameField);
        body.addRow(1, new Label(i18n("account.methods.offline.uuid")), uuidBox);
        body.add(new Label(i18n("account.methods.offline.uuid.hint")), 0, 2, 2, 1);

        setBody(body);

        usernameField.textProperty().addListener(observable -> updateValidStatus());
        uuidField.textProperty().addListener(observable -> updateValidStatus());
        updateValidStatus();
    }

    private void updateValidStatus() {
        String username = usernameField.getText();
        String uid = uuidField.getText();
        setValid(username != null && !username.isBlank() && (uid == null || uid.isBlank() || parseUuid(uid) != null));
    }

    private static @Nullable UUID parseUuid(String raw) {
        String value = raw.trim();
        if (UuidGenerator.isValid(value)) {
            return UuidGenerator.toUuid(value);
        }
        try {
            return UUIDTypeAdapter.fromString(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    protected void onAccept() {
        String username = usernameField.getText();
        if (username == null || username.isBlank()) {
            return;
        }

        String uid = uuidField.getText();
        UUID uuid = uid == null || uid.isBlank() ? null : parseUuid(uid);
        if (uuid == null && uid != null && !uid.isBlank()) {
            return;
        }

        fireEvent(new DialogCloseEvent());
        Accounts.createAccount(username.trim(), uuid);
    }

    /// Reads local Steam account data off the FX thread and offers the
    /// discovered accounts to the user.
    private void importFromSteam(JFXButton button) {
        button.setDisable(true);
        Task.supplyAsync(SteamAccountDiscovery::discoverAccounts)
                .whenComplete(Schedulers.javafx(),
                        accounts -> {
                            button.setDisable(false);
                            handleSteamAccounts(accounts);
                        },
                        exception -> {
                            button.setDisable(false);
                            LOG.warning("Failed to read Steam accounts", exception);
                            Controllers.dialog(i18n("account.steam.import.failed"),
                                    i18n("account.steam.import"), MessageDialogPane.MessageType.ERROR);
                        })
                .start();
    }

    private void handleSteamAccounts(List<SteamAccount> accounts) {
        if (accounts.isEmpty()) {
            Controllers.dialog(i18n("account.steam.import.empty"),
                    i18n("account.steam.import"), MessageDialogPane.MessageType.WARNING);
            return;
        }

        if (accounts.size() == 1) {
            applySteamAccount(accounts.get(0));
            return;
        }

        ChoiceDialog<SteamAccount> chooser = new ChoiceDialog<>(accounts.get(0), accounts);
        chooser.setTitle(i18n("account.steam.import"));
        chooser.setHeaderText(i18n("account.steam.import.choose"));
        chooser.setContentText(i18n("account.steam.import"));
        chooser.showAndWait().ifPresent(this::applySteamAccount);
    }

    /// Writes one Steam account's Mindustry UID into the form. The nickname is
    /// only filled when the Steam persona name is a plausible player name.
    private void applySteamAccount(SteamAccount account) {
        uuidField.setText(account.mindustryUid());
        if (StringUtils.isBlank(usernameField.getText())) {
            String personaName = account.personaName();
            if (personaName != null && personaName.length() <= 16) {
                usernameField.setText(personaName);
            }
        }
        Controllers.showToast(i18n("account.steam.import.done", account.displayName()));
    }
}
