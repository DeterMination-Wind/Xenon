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

import com.jfoenix.controls.*;
import com.jfoenix.validation.base.ValidatorBase;
import javafx.application.Platform;
import javafx.beans.NamedArg;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.*;
import determination.xenon.auth.AccountFactory;
import determination.xenon.auth.CharacterSelector;
import determination.xenon.auth.NoSelectedCharacterException;
import determination.xenon.auth.authlibinjector.AuthlibInjectorAccountFactory;
import determination.xenon.auth.authlibinjector.AuthlibInjectorServer;
import determination.xenon.auth.authlibinjector.BoundAuthlibInjectorAccountFactory;
import determination.xenon.auth.microsoft.MicrosoftAccountFactory;
import determination.xenon.auth.offline.OfflineAccountFactory;
import determination.xenon.auth.yggdrasil.GameProfile;
import determination.xenon.auth.yggdrasil.YggdrasilService;
import determination.xenon.game.TexturesLoader;
import determination.xenon.setting.Accounts;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.task.TaskExecutor;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.*;
import determination.xenon.upgrade.IntegrityChecker;
import determination.xenon.util.StringUtils;
import determination.xenon.util.gson.UUIDTypeAdapter;
import determination.xenon.util.javafx.BindingMapping;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

import static java.util.Collections.emptyList;
import static java.util.Collections.unmodifiableList;
import static javafx.beans.binding.Bindings.bindContent;
import static javafx.beans.binding.Bindings.createBooleanBinding;
import static determination.xenon.setting.ConfigHolder.config;
import static determination.xenon.ui.FXUtils.*;
import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.javafx.ExtendedProperties.classPropertyFor;

public class CreateAccountPane extends JFXDialogLayout implements DialogAware {
    private static final Pattern USERNAME_CHECKER_PATTERN = Pattern.compile("^[A-Za-z0-9_]+$");

    private boolean showMethodSwitcher;
    private AccountFactory<?> factory;

    private final Label lblErrorMessage;
    private final JFXButton btnAccept;
    private final SpinnerPane spinner;
    private final Node body;
    private final HBox actions;
    private Node detailsPane; // AccountDetailsInputPane for Offline / Mojang / authlib-injector, Label for Microsoft
    private final Pane detailsContainer;

    private final BooleanProperty logging = new SimpleBooleanProperty();

    private TaskExecutor loginTask;

    public CreateAccountPane() {
        this((AccountFactory<?>) null);
    }

    public CreateAccountPane(AccountFactory<?> factory) {
        if (factory == null) {
            showMethodSwitcher = false;
            factory = Accounts.FACTORY_OFFLINE;
        } else {
            showMethodSwitcher = false;
        }
        this.factory = factory;

        {
            String title;
            if (showMethodSwitcher) {
                title = "account.create";
            } else {
                title = "account.create." + Accounts.getLoginType(factory);
            }
            setHeading(new Label(i18n(title)));
        }

        {
            lblErrorMessage = new Label();
            lblErrorMessage.setWrapText(true);
            lblErrorMessage.setMaxWidth(400);

            btnAccept = new JFXButton(i18n("account.login"));
            btnAccept.getStyleClass().add("dialog-accept");
            btnAccept.setOnAction(e -> onAccept());

            spinner = new SpinnerPane();
            spinner.getStyleClass().add("small-spinner-pane");
            spinner.setContent(btnAccept);

            JFXButton btnCancel = new JFXButton(i18n("button.cancel"));
            btnCancel.getStyleClass().add("dialog-cancel");
            btnCancel.setOnAction(e -> onCancel());
            onEscPressed(this, btnCancel::fire);

            actions = new HBox(spinner, btnCancel);
            actions.setAlignment(Pos.CENTER_RIGHT);

            setActions(lblErrorMessage, actions);
        }

        if (showMethodSwitcher) {
            TabControl.Tab<?>[] tabs = new TabControl.Tab[Accounts.FACTORIES.size()];
            TabControl.Tab<?> selected = null;
            for (int i = 0; i < tabs.length; i++) {
                AccountFactory<?> f = Accounts.FACTORIES.get(i);
                tabs[i] = new TabControl.Tab<>(Accounts.getLoginType(f), Accounts.getLocalizedLoginTypeName(f));
                tabs[i].setUserData(f);
                if (factory == f) {
                    selected = tabs[i];
                }
            }

            TabHeader tabHeader = new TabHeader(tabs);
            tabHeader.getStyleClass().add("add-account-tab-header");
            tabHeader.setMinWidth(USE_PREF_SIZE);
            tabHeader.setMaxWidth(USE_PREF_SIZE);
            tabHeader.getSelectionModel().select(selected);
            onChange(tabHeader.getSelectionModel().selectedItemProperty(),
                    newItem -> {
                        if (newItem == null)
                            return;
                        AccountFactory<?> newMethod = (AccountFactory<?>) newItem.getUserData();
                        config().setPreferredLoginType(Accounts.getLoginType(newMethod));
                        this.factory = newMethod;
                        initDetailsPane();
                    });

            detailsContainer = new StackPane();
            detailsContainer.setPadding(new Insets(15, 0, 0, 0));

            VBox boxBody = new VBox(tabHeader, detailsContainer);
            boxBody.setAlignment(Pos.CENTER);
            body = boxBody;
            setBody(body);

        } else {
            detailsContainer = new StackPane();
            detailsContainer.setPadding(new Insets(10, 0, 0, 0));
            body = detailsContainer;
            setBody(body);
        }
        initDetailsPane();

        setPrefWidth(560);
    }

    public CreateAccountPane(AuthlibInjectorServer authServer) {
        this(Accounts.getAccountFactoryByAuthlibInjectorServer(authServer));
    }

    private void onAccept() {
        spinner.showSpinner();
        lblErrorMessage.setText("");

        if (!(factory instanceof MicrosoftAccountFactory)) {
            body.setDisable(true);
        }

        String username;
        String password;
        Object additionalData;
        if (detailsPane instanceof AccountDetailsInputPane) {
            AccountDetailsInputPane details = (AccountDetailsInputPane) detailsPane;
            username = details.getUsername();
            password = details.getPassword();
            additionalData = details.getAdditionalData();
        } else {
            username = null;
            password = null;
            additionalData = null;
        }

        Runnable doCreate = () -> {
            logging.set(true);

            loginTask = Task.supplyAsync(() -> factory.create(new DialogCharacterSelector(), username, password, null, additionalData))
                    .whenComplete(Schedulers.javafx(), account -> {
                        int oldIndex = Accounts.getAccounts().indexOf(account);
                        if (oldIndex == -1) {
                            Accounts.getAccounts().add(account);
                        } else {
                            // adding an already-added account
                            // instead of discarding the new account, we first remove the existing one then add the new one
                            Accounts.getAccounts().remove(oldIndex);
                            Accounts.getAccounts().add(oldIndex, account);
                        }

                        // select the new account
                        Accounts.setSelectedAccount(account);

                        spinner.hideSpinner();
                        fireEvent(new DialogCloseEvent());
                    }, exception -> {
                        if (exception instanceof NoSelectedCharacterException) {
                            fireEvent(new DialogCloseEvent());
                        } else {
                            lblErrorMessage.setText(Accounts.localizeErrorMessage(exception));
                        }
                        body.setDisable(false);
                        spinner.hideSpinner();
                    }).executor(true);
        };

        if (factory instanceof OfflineAccountFactory && username != null && (!USERNAME_CHECKER_PATTERN.matcher(username).matches() || username.length() > 16)) {
            Controllers.confirmWithCountdown(i18n("account.methods.offline.name.invalid"), i18n("message.warning"), 10,
                    MessageDialogPane.MessageType.WARNING,
                    doCreate, () -> {
                        body.setDisable(false);
                        spinner.hideSpinner();
                    });
        } else {
            doCreate.run();
        }
    }

    private void onCancel() {
        if (loginTask != null) {
            loginTask.cancel();
        }
        fireEvent(new DialogCloseEvent());
    }

    private void initDetailsPane() {
        if (detailsPane != null) {
            btnAccept.disableProperty().unbind();
            detailsContainer.getChildren().remove(detailsPane);
            lblErrorMessage.setText("");
            setActions(lblErrorMessage, actions);
        }

        if (factory == Accounts.FACTORY_MICROSOFT) {
            detailsPane = new MicrosoftAccountLoginPane(true);
            setActions();
        } else {
            detailsPane = new AccountDetailsInputPane(factory, btnAccept::fire);
            btnAccept.disableProperty().bind(((AccountDetailsInputPane) detailsPane).validProperty().not());
            setActions(lblErrorMessage, actions);
        }

        detailsContainer.getChildren().add(detailsPane);
    }

    private static class AccountDetailsInputPane extends GridPane {

        // ==== authlib-injector hyperlinks ====
        private static final String[] ALLOWED_LINKS = {"homepage", "register"};

        private static List<Hyperlink> createHyperlinks(AuthlibInjectorServer server) {
            if (server == null) {
                return emptyList();
            }

            Map<String, String> links = server.getLinks();
            List<Hyperlink> result = new ArrayList<>();
            for (String key : ALLOWED_LINKS) {
                String value = links.get(key);
                if (value != null) {
                    JFXHyperlink link = new JFXHyperlink(i18n("account.injector.link." + key));
                    FXUtils.installSlowTooltip(link, value);
                    link.setOnAction(e -> FXUtils.openLink(value));
                    result.add(link);
                }
            }
            return unmodifiableList(result);
        }
        // =====

        private final AccountFactory<?> factory;
        private @Nullable AuthlibInjectorServer server;
        private @Nullable JFXComboBox<AuthlibInjectorServer> cboServers;
        private @Nullable JFXTextField txtUsername;
        private @Nullable JFXPasswordField txtPassword;
        private @Nullable JFXTextField txtUUID;
        private final BooleanBinding valid;

        public AccountDetailsInputPane(AccountFactory<?> factory, Runnable onAction) {
            this.factory = factory;

            setVgap(22);
            setHgap(15);
            setAlignment(Pos.CENTER);

            ColumnConstraints col0 = new ColumnConstraints();
            col0.setMinWidth(USE_PREF_SIZE);
            getColumnConstraints().add(col0);
            ColumnConstraints col1 = new ColumnConstraints();
            col1.setHgrow(Priority.ALWAYS);
            getColumnConstraints().add(col1);

            int rowIndex = 0;

            if (!IntegrityChecker.isOfficial() && !(factory instanceof OfflineAccountFactory)) {
                HintPane hintPane = new HintPane(MessageDialogPane.MessageType.WARNING);
                hintPane.setSegment(i18n("unofficial.hint"));
                GridPane.setColumnSpan(hintPane, 2);
                add(hintPane, 0, rowIndex);

                rowIndex++;
            }

            if (factory instanceof BoundAuthlibInjectorAccountFactory) {
                this.server = ((BoundAuthlibInjectorAccountFactory) factory).getServer();

                Label lblServers = new Label(i18n("account.injector.server"));
                setHalignment(lblServers, HPos.LEFT);
                add(lblServers, 0, rowIndex);

                Label lblServerName = new Label(this.server.getName());
                lblServerName.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(lblServerName, Priority.ALWAYS);

                HBox linksContainer = new HBox();
                linksContainer.setAlignment(Pos.CENTER);
                linksContainer.getChildren().setAll(createHyperlinks(this.server));
                linksContainer.setMinWidth(USE_PREF_SIZE);

                HBox boxServers = new HBox(lblServerName, linksContainer);
                boxServers.setAlignment(Pos.CENTER_LEFT);
                add(boxServers, 1, rowIndex);

                rowIndex++;
            } else if (factory instanceof AuthlibInjectorAccountFactory) {
                Label lblServers = new Label(i18n("account.injector.server"));
                setHalignment(lblServers, HPos.LEFT);
                add(lblServers, 0, rowIndex);

                cboServers = new JFXComboBox<>();
                cboServers.setCellFactory(jfxListCellFactory(server -> new TwoLineListItem(server.getName(), server.getUrl())));
                cboServers.setConverter(stringConverter(AuthlibInjectorServer::getName));
                bindContent(cboServers.getItems(), config().getAuthlibInjectorServers());
                cboServers.getItems().addListener(onInvalidating(
                        () -> Platform.runLater( // the selection will not be updated as expected if we call it immediately
                                cboServers.getSelectionModel()::selectFirst)));
                cboServers.getSelectionModel().selectFirst();
                cboServers.setPromptText(i18n("account.injector.empty"));
                BooleanBinding noServers = createBooleanBinding(cboServers.getItems()::isEmpty, cboServers.getItems());
                classPropertyFor(cboServers, "jfx-combo-box-warning").bind(noServers);
                classPropertyFor(cboServers, "jfx-combo-box").bind(noServers.not());
                HBox.setHgrow(cboServers, Priority.ALWAYS);
                HBox.setMargin(cboServers, new Insets(0, 10, 0, 0));
                cboServers.setMaxWidth(Double.MAX_VALUE);

                HBox linksContainer = new HBox();
                linksContainer.setAlignment(Pos.CENTER);
                onChangeAndOperate(cboServers.valueProperty(), server -> {
                    this.server = server;
                    linksContainer.getChildren().setAll(createHyperlinks(server));

                    if (txtUsername != null)
                        txtUsername.validate();
                });
                linksContainer.setMinWidth(USE_PREF_SIZE);

                JFXButton btnAddServer = FXUtils.newToggleButton4(SVG.ADD, 20);
                btnAddServer.setOnAction(e -> {
                    Controllers.dialog(new AddAuthlibInjectorServerPane());
                });

                HBox boxServers = new HBox(cboServers, linksContainer, btnAddServer);
                add(boxServers, 1, rowIndex);

                rowIndex++;
            }

            if (factory.getLoginType().requiresUsername) {
                Label lblUsername = new Label(i18n("account.username"));
                setHalignment(lblUsername, HPos.LEFT);
                add(lblUsername, 0, rowIndex);

                txtUsername = new JFXTextField();
                txtUsername.setValidators(
                        new RequiredValidator(),
                        new Validator(i18n("input.email"), username -> {
                            if (requiresEmailAsUsername()) {
                                return username.contains("@");
                            } else {
                                return true;
                            }
                        }));
                setValidateWhileTextChanged(txtUsername, true);
                txtUsername.setOnAction(e -> onAction.run());
                add(txtUsername, 1, rowIndex);

                rowIndex++;
            }

            if (factory.getLoginType().requiresPassword) {
                Label lblPassword = new Label(i18n("account.password"));
                setHalignment(lblPassword, HPos.LEFT);
                add(lblPassword, 0, rowIndex);

                txtPassword = new JFXPasswordField();
                txtPassword.setValidators(new RequiredValidator());
                setValidateWhileTextChanged(txtPassword, true);
                txtPassword.setOnAction(e -> onAction.run());
                add(txtPassword, 1, rowIndex);

                rowIndex++;
            }

            if (factory instanceof OfflineAccountFactory) {
                txtUsername.setPromptText(i18n("account.methods.offline.name.special_characters"));
                FXUtils.installFastTooltip(txtUsername, i18n("account.methods.offline.name.special_characters"));

                // Xenon: no Mindustry "buy" link — the game ships outside this dialog.

                HBox box = new HBox();
                MenuUpDownButton advancedButton = new MenuUpDownButton();
                box.getChildren().setAll(advancedButton);
                advancedButton.setText(i18n("settings.advanced"));
                GridPane.setColumnSpan(box, 2);
                add(box, 0, rowIndex);

                rowIndex++;

                Label lblUUID = new Label(i18n("account.methods.offline.uuid"));
                lblUUID.managedProperty().bind(advancedButton.selectedProperty());
                lblUUID.visibleProperty().bind(advancedButton.selectedProperty());
                setHalignment(lblUUID, HPos.LEFT);
                add(lblUUID, 0, rowIndex);

                txtUUID = new JFXTextField();
                txtUUID.managedProperty().bind(advancedButton.selectedProperty());
                txtUUID.visibleProperty().bind(advancedButton.selectedProperty());
                // Mindustry UID validator — accepts the 22-char URL-safe base64 form.
                txtUUID.setValidators(new MindustryUidValidator());
                // Prompt: a deterministic Mindustry-format UID derived from the nickname,
                // so users see what's auto-generated without having to type anything.
                txtUUID.promptTextProperty().bind(BindingMapping.of(txtUsername.textProperty())
                        .map(name -> determination.xenon.mindustry.uuid.UuidGenerator.fromUuid(
                                OfflineAccountFactory.getUUIDFromUserName(name))));
                txtUUID.setOnAction(e -> onAction.run());
                add(txtUUID, 1, rowIndex);

                rowIndex++;

                HintPane hintPane = new HintPane(MessageDialogPane.MessageType.WARNING);
                hintPane.managedProperty().bind(advancedButton.selectedProperty());
                hintPane.visibleProperty().bind(advancedButton.selectedProperty());
                hintPane.setText(i18n("account.methods.offline.uuid.hint"));
                GridPane.setColumnSpan(hintPane, 2);
                add(hintPane, 0, rowIndex);

                rowIndex++;
            }

            valid = new BooleanBinding() {
                {
                    if (cboServers != null)
                        bind(cboServers.valueProperty());
                    if (txtUsername != null)
                        bind(txtUsername.textProperty());
                    if (txtPassword != null)
                        bind(txtPassword.textProperty());
                    if (txtUUID != null)
                        bind(txtUUID.textProperty());
                }

                @Override
                protected boolean computeValue() {
                    if (cboServers != null && cboServers.getValue() == null)
                        return false;
                    if (txtUsername != null && !txtUsername.validate())
                        return false;
                    if (txtPassword != null && !txtPassword.validate())
                        return false;
                    if (txtUUID != null && !txtUUID.validate())
                        return false;
                    return true;
                }
            };
        }

        private boolean requiresEmailAsUsername() {
            if ((factory instanceof AuthlibInjectorAccountFactory) && this.server != null) {
                return !server.isNonEmailLogin();
            }
            if (factory instanceof BoundAuthlibInjectorAccountFactory bound) {
                return !bound.getServer().isNonEmailLogin();
            }
            return false;
        }

        public Object getAdditionalData() {
            if (factory instanceof AuthlibInjectorAccountFactory) {
                return getAuthServer();
            } else if (factory instanceof OfflineAccountFactory) {
                UUID uuid;
                if (txtUUID == null || StringUtils.isBlank(txtUUID.getText())) {
                    uuid = null;
                } else {
                    String raw = txtUUID.getText().trim();
                    // Accept Mindustry's 22/24-char URL-safe base64 form first;
                    // fall back to legacy MC-format UUID strings for users who
                    // paste an old value.
                    if (determination.xenon.mindustry.uuid.UuidGenerator.isValid(raw)) {
                        uuid = determination.xenon.mindustry.uuid.UuidGenerator.toUuid(raw);
                    } else {
                        uuid = UUIDTypeAdapter.fromString(raw);
                    }
                }
                return new OfflineAccountFactory.AdditionalData(uuid, null);
            } else {
                return null;
            }
        }

        public @Nullable AuthlibInjectorServer getAuthServer() {
            return this.server;
        }

        public @Nullable String getUsername() {
            return txtUsername == null ? null : txtUsername.getText();
        }

        public @Nullable String getPassword() {
            return txtPassword == null ? null : txtPassword.getText();
        }

        public BooleanBinding validProperty() {
            return valid;
        }

        public void focus() {
            if (txtUsername != null) {
                txtUsername.requestFocus();
            }
        }
    }

    public static class DialogCharacterSelector extends JFXDialogLayout implements CharacterSelector {

        private final AdvancedListBox listBox = new AdvancedListBox();
        private final JFXButton cancel = new JFXButton();

        private final CountDownLatch latch = new CountDownLatch(1);
        private GameProfile selectedProfile = null;

        public DialogCharacterSelector() {
            setStyle("-fx-padding: 8px;");

            cancel.setText(i18n("button.cancel"));
            cancel.setOnAction(e -> latch.countDown());
            cancel.getStyleClass().add("dialog-cancel");

            listBox.startCategory(i18n("account.choose").toUpperCase(Locale.ROOT));

            setBody(listBox);

            HBox hbox = new HBox();
            hbox.setAlignment(Pos.CENTER_RIGHT);
            hbox.getChildren().add(cancel);
            setActions(hbox);

            onEscPressed(this, cancel::fire);
        }

        @Override
        public GameProfile select(YggdrasilService service, List<GameProfile> profiles) throws NoSelectedCharacterException {
            Platform.runLater(() -> {
                for (GameProfile profile : profiles) {
                    Canvas portraitCanvas = new Canvas(32, 32);
                    TexturesLoader.bindAvatar(portraitCanvas, service, profile.getId());

                    IconedItem accountItem = new IconedItem(portraitCanvas, profile.getName());
                    FXUtils.onClicked(accountItem, () -> {
                        selectedProfile = profile;
                        latch.countDown();
                    });
                    listBox.add(accountItem);
                }
                Controllers.dialog(this);
            });

            try {
                latch.await();

                if (selectedProfile == null)
                    throw new NoSelectedCharacterException();

                return selectedProfile;
            } catch (InterruptedException ignored) {
                throw new NoSelectedCharacterException();
            } finally {
                Platform.runLater(() -> fireEvent(new DialogCloseEvent()));
            }
        }
    }

    @Override
    public void onDialogShown() {
        if (detailsPane instanceof AccountDetailsInputPane) {
            ((AccountDetailsInputPane) detailsPane).focus();
        }
    }

    /**
     * Validator for the Mindustry-format player UID — 22-character (or
     * 24-with-padding) URL-safe base64. Legacy MC-format UUID strings
     * are still accepted for users pasting in old values.
     */
    private static class MindustryUidValidator extends ValidatorBase {

        public MindustryUidValidator() {
            this(i18n("account.methods.offline.uuid.malformed"));
        }

        public MindustryUidValidator(@NamedArg("message") String message) {
            super(message);
        }

        @Override
        protected void eval() {
            if (srcControl.get() instanceof TextInputControl) {
                evalTextInputField();
            }
        }

        private void evalTextInputField() {
            TextInputControl textField = ((TextInputControl) srcControl.get());
            String text = textField.getText();
            if (StringUtils.isBlank(text)) {
                hasErrors.set(false);
                return;
            }

            // Try Mindustry first, then fall back to legacy MC-format UUID.
            if (determination.xenon.mindustry.uuid.UuidGenerator.isValid(text.trim())) {
                hasErrors.set(false);
                return;
            }
            try {
                UUIDTypeAdapter.fromString(text);
                hasErrors.set(false);
            } catch (IllegalArgumentException ignored) {
                hasErrors.set(true);
            }
        }
    }

    private static final String MICROSOFT_ACCOUNT_EDIT_PROFILE_URL = "https://support.microsoft.com/account-billing/837badbc-999e-54d2-2617-d19206b9540a";
}
