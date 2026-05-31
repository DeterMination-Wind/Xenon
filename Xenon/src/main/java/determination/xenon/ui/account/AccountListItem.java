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
import javafx.beans.binding.ObjectBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Skin;
import javafx.scene.image.Image;
import javafx.stage.FileChooser;
import determination.xenon.auth.Account;
import determination.xenon.auth.AuthenticationException;
import determination.xenon.auth.CredentialExpiredException;
import determination.xenon.auth.authlibinjector.AuthlibInjectorAccount;
import determination.xenon.auth.authlibinjector.AuthlibInjectorServer;
import determination.xenon.auth.offline.OfflineAccount;
import determination.xenon.auth.yggdrasil.CompleteGameProfile;
import determination.xenon.auth.yggdrasil.TextureType;
import determination.xenon.setting.Accounts;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.DialogController;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.MessageDialogPane.MessageType;
import determination.xenon.util.io.FileUtils;
import determination.xenon.util.skin.InvalidSkinException;
import determination.xenon.util.skin.NormalizedSkin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static java.util.Collections.emptySet;
import static javafx.beans.binding.Bindings.createBooleanBinding;
import static determination.xenon.util.logging.Logger.LOG;
import static determination.xenon.util.i18n.I18n.i18n;

public class AccountListItem extends RadioButton {

    private final Account account;
    private final StringProperty title = new SimpleStringProperty();
    private final StringProperty subtitle = new SimpleStringProperty();

    public AccountListItem(Account account) {
        this.account = account;
        getStyleClass().clear();
        setUserData(account);

        String loginTypeName = Accounts.getLocalizedLoginTypeName(Accounts.getAccountFactory(account));
        String portableSuffix = account.isPortable() ? ", " + i18n("account.portable") : "";
        if (account instanceof AuthlibInjectorAccount) {
            AuthlibInjectorServer server = ((AuthlibInjectorAccount) account).getServer();
            subtitle.bind(Bindings.concat(
                    loginTypeName, ", ", i18n("account.injector.server"), ": ",
                    Bindings.createStringBinding(server::getName, server), portableSuffix));
        } else {
            subtitle.set(loginTypeName + portableSuffix);
        }

        StringBinding characterName = Bindings.createStringBinding(account::getCharacter, account);
        if (account instanceof OfflineAccount) {
            title.bind(characterName);
        } else {
            title.bind(
                    account.getUsername().isEmpty() ? characterName :
                            Bindings.concat(account.getUsername(), " - ", characterName));
        }
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new AccountListItemSkin(this);
    }

    public Task<?> refreshAsync() {
        return Task.runAsync(() -> {
            account.clearCache();
            try {
                account.logIn();
            } catch (CredentialExpiredException e) {
                try {
                    DialogController.logIn(account);
                } catch (CancellationException e1) {
                    // ignore cancellation
                } catch (Exception e1) {
                    LOG.warning("Failed to refresh " + account + " with password", e1);
                    throw e1;
                }
            } catch (AuthenticationException e) {
                LOG.warning("Failed to refresh " + account + " with token", e);
                throw e;
            }
        });
    }

    public ObservableBooleanValue canUploadSkin() {
        if (account instanceof AuthlibInjectorAccount aiAccount) {
            ObjectBinding<Optional<CompleteGameProfile>> profile = aiAccount.getYggdrasilService().getProfileRepository().binding(aiAccount.getUUID());
            return createBooleanBinding(() -> {
                Set<TextureType> uploadableTextures = profile.get()
                        .map(AuthlibInjectorAccount::getUploadableTextures)
                        .orElse(emptySet());
                return uploadableTextures.contains(TextureType.SKIN);
            }, profile);
        } else if (account instanceof OfflineAccount || account.canUploadSkin()) {
            return createBooleanBinding(() -> true);
        } else {
            return createBooleanBinding(() -> false);
        }
    }

    /**
     * @return the skin upload task, null if no file is selected
     */
    @Nullable
    public Task<?> uploadSkin() {
        if (account instanceof OfflineAccount) {
            Controllers.dialog(new OfflineAccountSkinPane((OfflineAccount) account));
            return null;
        }
        if (!account.canUploadSkin()) {
            return null;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("account.skin.upload"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(i18n("account.skin.file"), "*.png"));
        Path selectedFile = FileUtils.toPath(FXUtils.showOpenDialog(chooser, Controllers.getStage()));
        if (selectedFile == null) {
            return null;
        }

        return refreshAsync()
                .thenRunAsync(() -> {
                    Image skinImg;
                    try (var input = Files.newInputStream(selectedFile)) {
                        skinImg = new Image(input);
                    } catch (IOException e) {
                        throw new InvalidSkinException("Failed to read skin image", e);
                    }
                    if (skinImg.isError()) {
                        throw new InvalidSkinException("Failed to read skin image", skinImg.getException());
                    }
                    if (skinImg.getWidth() != 64 || (skinImg.getHeight() != 32 && skinImg.getHeight() != 64)) {
                        throw new InvalidSkinException("Invalid skin size");
                    }
                    NormalizedSkin skin = new NormalizedSkin(skinImg);
                    String model = skin.isSlim() ? "slim" : "";
                    LOG.info("Uploading skin [" + selectedFile + "], model [" + model + "]");
                    account.uploadSkin(skin.isSlim(), selectedFile);
                })
                .thenComposeAsync(refreshAsync())
                .whenComplete(Schedulers.javafx(), e -> {
                    if (e != null) {
                        Controllers.dialog(Accounts.localizeErrorMessage(e), i18n("account.skin.upload.failed"), MessageType.ERROR);
                    }
                });
    }

    public void remove() {
        Accounts.getAccounts().remove(account);
    }

    public Account getAccount() {
        return account;
    }

    public String getTitle() {
        return title.get();
    }

    public void setTitle(String title) {
        this.title.set(title);
    }

    public StringProperty titleProperty() {
        return title;
    }

    public String getSubtitle() {
        return subtitle.get();
    }

    public void setSubtitle(String subtitle) {
        this.subtitle.set(subtitle);
    }

    public StringProperty subtitleProperty() {
        return subtitle;
    }
}
