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
package determination.xenon.ui.main;

import com.jfoenix.controls.*;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;

import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.*;
import determination.xenon.Metadata;
import determination.xenon.mindustry.download.GitHubAuth;
import determination.xenon.setting.EnumCommonDirectory;
import determination.xenon.setting.Settings;
import determination.xenon.task.FetchTask;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.WeakListenerHolder;
import determination.xenon.ui.construct.*;
import determination.xenon.util.io.FileUtils;
import determination.xenon.util.javafx.SafeStringConverter;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static determination.xenon.setting.ConfigHolder.config;
import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

public class DownloadSettingsPage extends StackPane {

    private final WeakListenerHolder holder = new WeakListenerHolder();

    /// GitHub account sublist, filled while the download section builds and
    /// wired to `downloadList` afterwards.
    private ComponentSublist githubSublist;

    public DownloadSettingsPage() {
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        content.setFillWidth(true);
        ScrollPane scrollPane = new ScrollPane(content);
        FXUtils.smoothScrolling(scrollPane);
        scrollPane.setFitToWidth(true);
        getChildren().setAll(scrollPane);

        {
            var downloadList = new ComponentList();

            VBox downloadThreads = new VBox(16);

            ComponentSublist fileCommonLocationSublist = new ComponentSublist();

            {
                {
                    MultiFileItem<EnumCommonDirectory> fileCommonLocation = new MultiFileItem<>();
                    List<MultiFileItem.Option<EnumCommonDirectory>> commonDirectoryOptions = new ArrayList<>();
                    commonDirectoryOptions.add(new MultiFileItem.Option<>(
                            i18n("launcher.cache_directory.default"), EnumCommonDirectory.DEFAULT));
                    commonDirectoryOptions.add(new MultiFileItem.FileOption<>(i18n("settings.custom"), EnumCommonDirectory.CUSTOM)
                            .setChooserTitle(i18n("launcher.cache_directory.choose"))
                            .setSelectionMode(FileSelector.SelectionMode.DIRECTORY)
                            .bindBidirectional(config().commonDirectoryProperty()));
                    if (!Metadata.isInstalledBuild()) {
                        // Portable mode is meaningful for the portable zip only;
                        // installed packages own their program directory.
                        commonDirectoryOptions.add(new MultiFileItem.Option<>(
                                i18n("launcher.cache_directory.portable"), EnumCommonDirectory.PORTABLE));
                    }
                    fileCommonLocation.loadChildren(commonDirectoryOptions);
                    fileCommonLocation.selectedDataProperty().bindBidirectional(config().commonDirTypeProperty());
                    holder.onWeakChangeAndOperate(config().commonDirTypeProperty(),
                            DownloadSettingsPage::applyPortableSelection);

                    fileCommonLocationSublist.getContent().add(fileCommonLocation);
                    fileCommonLocationSublist.setTitle(i18n("launcher.cache_directory"));
                    fileCommonLocationSublist.setHasSubtitle(true);
                    fileCommonLocationSublist.subtitleProperty().bind(
                            Bindings.createObjectBinding(() -> Optional.ofNullable(Settings.instance().getCommonDirectory())
                                            .orElse(i18n("launcher.cache_directory.disabled")),
                                    config().commonDirectoryProperty(), config().commonDirTypeProperty()));

                    JFXButton cleanButton = FXUtils.newBorderButton(i18n("launcher.cache_directory.clean"));
                    cleanButton.setOnAction(e -> clearCacheDirectory());
                    fileCommonLocationSublist.setHeaderRight(cleanButton);
                }

                {
                    JFXCheckBox chkAutoDownloadThreads = new JFXCheckBox(i18n("settings.launcher.download.threads.auto"));
                    VBox.setMargin(chkAutoDownloadThreads, new Insets(8, 0, 0, 0));
                    chkAutoDownloadThreads.selectedProperty().bindBidirectional(config().autoDownloadThreadsProperty());
                    downloadThreads.getChildren().add(chkAutoDownloadThreads);

                    chkAutoDownloadThreads.selectedProperty().addListener((a, b, newValue) -> {
                        if (newValue) {
                            config().downloadThreadsProperty().set(FetchTask.DEFAULT_CONCURRENCY);
                        }
                    });
                }

                {
                    HBox hbox = new HBox(8);
                    hbox.setStyle("-fx-view-order: -1;"); // prevent the indicator from being covered by the hint
                    hbox.setAlignment(Pos.CENTER);
                    hbox.setPadding(new Insets(0, 0, 0, 30));
                    hbox.disableProperty().bind(config().autoDownloadThreadsProperty());
                    Label label = new Label(i18n("settings.launcher.download.threads"));

                    JFXSlider slider = new JFXSlider(1, 256, 64);
                    HBox.setHgrow(slider, Priority.ALWAYS);

                    JFXTextField threadsField = new JFXTextField();
                    FXUtils.setLimitWidth(threadsField, 60);
                    FXUtils.bind(threadsField, config().downloadThreadsProperty(), SafeStringConverter.fromInteger()
                            .restrict(it -> it > 0)
                            .fallbackTo(FetchTask.DEFAULT_CONCURRENCY)
                            .asPredicate(Validator.addTo(threadsField)));

                    AtomicBoolean changedByTextField = new AtomicBoolean(false);
                    FXUtils.onChangeAndOperate(config().downloadThreadsProperty(), value -> {
                        changedByTextField.set(true);
                        slider.setValue(value.intValue());
                        changedByTextField.set(false);
                    });
                    slider.valueProperty().addListener((value, oldVal, newVal) -> {
                        if (changedByTextField.get()) return;
                        config().downloadThreadsProperty().set(value.getValue().intValue());
                    });

                    hbox.getChildren().setAll(label, slider, threadsField);
                    downloadThreads.getChildren().add(hbox);
                }

                {
                    HintPane hintPane = new HintPane(MessageDialogPane.MessageType.INFO);
                    VBox.setMargin(hintPane, new Insets(0, 0, 0, 30));
                    hintPane.disableProperty().bind(config().autoDownloadThreadsProperty());
                    hintPane.setText(i18n("settings.launcher.download.threads.hint"));
                    downloadThreads.getChildren().add(hintPane);
                }

                {
                    githubSublist = new ComponentSublist();
                    githubSublist.setTitle(i18n("download.github.title"));
                    githubSublist.setHasSubtitle(true);
                    githubSublist.subtitleProperty().set(i18n("download.github.token.hint"));

                    VBox githubPane = new VBox(8);
                    githubPane.setPadding(new Insets(8, 0, 0, 0));

                    HBox tokenRow = new HBox(8);
                    tokenRow.setAlignment(Pos.CENTER_LEFT);
                    JFXTextField tokenField = new JFXTextField();
                    FXUtils.setLimitWidth(tokenField, 320);
                    FXUtils.bindString(tokenField, config().githubTokenProperty());
                    tokenField.setPromptText(i18n("download.github.token.prompt"));

                    Label status = new Label();
                    status.setWrapText(true);

                    JFXButton verify = FXUtils.newBorderButton(i18n("download.github.verify"));
                    verify.setOnAction(e -> verifyGithubToken(status));

                    tokenRow.getChildren().setAll(tokenField, verify);
                    githubPane.getChildren().setAll(tokenRow, status);
                    githubSublist.getContent().add(githubPane);

                    // Prime the status line without blocking the FX thread.
                    refreshGithubStatus(status);
                }
            }

            downloadList.getContent().addAll(fileCommonLocationSublist, githubSublist, downloadThreads);
            content.getChildren().addAll(ComponentList.createComponentListTitle(i18n("download")), downloadList);
        }

        {
            VBox proxyList = new VBox(10);
            proxyList.getStyleClass().add("card-non-transparent");

            HBox proxyTypePane = new HBox();
            {
                proxyTypePane.setPadding(new Insets(10, 0, 0, 0));

                ToggleGroup proxyConfigurationGroup = new ToggleGroup();

                JFXRadioButton chkProxyDefault = new JFXRadioButton(i18n("settings.launcher.proxy.default"));
                chkProxyDefault.setUserData(null);
                chkProxyDefault.setToggleGroup(proxyConfigurationGroup);

                JFXRadioButton chkProxyNone = new JFXRadioButton(i18n("settings.launcher.proxy.none"));
                chkProxyNone.setUserData(Proxy.Type.DIRECT);
                chkProxyNone.setToggleGroup(proxyConfigurationGroup);

                JFXRadioButton chkProxyHttp = new JFXRadioButton(i18n("settings.launcher.proxy.http"));
                chkProxyHttp.setUserData(Proxy.Type.HTTP);
                chkProxyHttp.setToggleGroup(proxyConfigurationGroup);


                JFXRadioButton chkProxySocks = new JFXRadioButton(i18n("settings.launcher.proxy.socks"));
                chkProxySocks.setUserData(Proxy.Type.SOCKS);
                chkProxySocks.setToggleGroup(proxyConfigurationGroup);

                if (config().hasProxy()) {
                    Proxy.Type proxyType = config().getProxyType();
                    if (proxyType == Proxy.Type.DIRECT) {
                        chkProxyNone.setSelected(true);
                    } else if (proxyType == Proxy.Type.HTTP) {
                        chkProxyHttp.setSelected(true);
                    } else if (proxyType == Proxy.Type.SOCKS) {
                        chkProxySocks.setSelected(true);
                    } else {
                        chkProxyNone.setSelected(true);
                    }
                } else {
                    chkProxyDefault.setSelected(true);
                }

                holder.add(FXUtils.onWeakChange(proxyConfigurationGroup.selectedToggleProperty(), toggle -> {
                    Proxy.Type proxyType = toggle != null ? (Proxy.Type) toggle.getUserData() : null;

                    if (proxyType == null) {
                        config().setHasProxy(false);
                        config().setProxyType(null);
                    } else {
                        config().setHasProxy(true);
                        config().setProxyType(proxyType);
                    }
                }));

                proxyTypePane.getChildren().setAll(chkProxyDefault, chkProxyNone, chkProxyHttp, chkProxySocks);
                proxyList.getChildren().add(proxyTypePane);
            }

            VBox proxyPane = new VBox();
            {
                proxyPane.disableProperty().bind(
                        Bindings.createBooleanBinding(() ->
                                        !config().hasProxy() || config().getProxyType() == null || config().getProxyType() == Proxy.Type.DIRECT,
                                config().hasProxyProperty(),
                                config().proxyTypeProperty()));

                ColumnConstraints colHgrow = new ColumnConstraints();
                colHgrow.setHgrow(Priority.ALWAYS);

                {
                    GridPane gridPane = new GridPane();
                    gridPane.setPadding(new Insets(0, 0, 0, 30));
                    gridPane.setHgap(20);
                    gridPane.setVgap(10);
                    gridPane.getColumnConstraints().setAll(new ColumnConstraints(), colHgrow);
                    gridPane.getRowConstraints().setAll(new RowConstraints(), new RowConstraints());

                    {
                        Label host = new Label(i18n("settings.launcher.proxy.host"));
                        GridPane.setRowIndex(host, 1);
                        GridPane.setColumnIndex(host, 0);
                        gridPane.getChildren().add(host);
                    }

                    {
                        JFXTextField txtProxyHost = new JFXTextField();
                        GridPane.setRowIndex(txtProxyHost, 1);
                        GridPane.setColumnIndex(txtProxyHost, 1);
                        gridPane.getChildren().add(txtProxyHost);
                        FXUtils.bindString(txtProxyHost, config().proxyHostProperty());
                    }

                    {
                        Label port = new Label(i18n("settings.launcher.proxy.port"));
                        GridPane.setRowIndex(port, 2);
                        GridPane.setColumnIndex(port, 0);
                        gridPane.getChildren().add(port);
                    }

                    {
                        JFXTextField txtProxyPort = new JFXTextField();
                        GridPane.setFillWidth(txtProxyPort, false);
                        txtProxyPort.setMaxWidth(200);
                        GridPane.setRowIndex(txtProxyPort, 2);
                        GridPane.setColumnIndex(txtProxyPort, 1);
                        FXUtils.setValidateWhileTextChanged(txtProxyPort, true);
                        gridPane.getChildren().add(txtProxyPort);

                        FXUtils.bind(txtProxyPort, config().proxyPortProperty(), SafeStringConverter.fromInteger()
                                .restrict(it -> it >= 0 && it <= 0xFFFF)
                                .fallbackTo(0)
                                .asPredicate(Validator.addTo(txtProxyPort)));
                    }
                    proxyPane.getChildren().add(gridPane);
                }

                VBox chkProxyAuthenticationPane = new VBox();
                {
                    chkProxyAuthenticationPane.setPadding(new Insets(20, 0, 20, 5));

                    JFXCheckBox chkProxyAuthentication = new JFXCheckBox(i18n("settings.launcher.proxy.authentication"));
                    chkProxyAuthenticationPane.getChildren().add(chkProxyAuthentication);
                    chkProxyAuthentication.selectedProperty().bindBidirectional(config().hasProxyAuthProperty());

                    proxyPane.getChildren().add(chkProxyAuthenticationPane);
                }

                GridPane authPane = new GridPane();
                {
                    authPane.setPadding(new Insets(0, 0, 0, 30));
                    authPane.setHgap(20);
                    authPane.setVgap(10);
                    authPane.getColumnConstraints().setAll(new ColumnConstraints(), colHgrow);
                    authPane.getRowConstraints().setAll(new RowConstraints(), new RowConstraints());
                    authPane.disableProperty().bind(config().hasProxyAuthProperty().not());

                    {
                        Label username = new Label(i18n("settings.launcher.proxy.username"));
                        GridPane.setRowIndex(username, 0);
                        GridPane.setColumnIndex(username, 0);
                        authPane.getChildren().add(username);
                    }

                    {
                        JFXTextField txtProxyUsername = new JFXTextField();
                        GridPane.setRowIndex(txtProxyUsername, 0);
                        GridPane.setColumnIndex(txtProxyUsername, 1);
                        authPane.getChildren().add(txtProxyUsername);
                        FXUtils.bindString(txtProxyUsername, config().proxyUserProperty());
                    }

                    {
                        Label password = new Label(i18n("settings.launcher.proxy.password"));
                        GridPane.setRowIndex(password, 1);
                        GridPane.setColumnIndex(password, 0);
                        authPane.getChildren().add(password);
                    }

                    {
                        JFXPasswordField txtProxyPassword = new JFXPasswordField();
                        GridPane.setRowIndex(txtProxyPassword, 1);
                        GridPane.setColumnIndex(txtProxyPassword, 1);
                        authPane.getChildren().add(txtProxyPassword);
                        txtProxyPassword.textProperty().bindBidirectional(config().proxyPassProperty());
                    }

                    proxyPane.getChildren().add(authPane);
                    proxyList.getChildren().add(proxyPane);
                }
            }
            content.getChildren().addAll(ComponentList.createComponentListTitle(i18n("settings.launcher.proxy")), proxyList);
        }

    }

    /// Applies a portable-mode selection by creating or removing the marker file.
    ///
    /// The metadata layer resolves the data root before the config loads, so a
    /// switch only takes effect after a restart; the dialog says so. When
    /// writing the marker fails (read-only installation), the selection rolls
    /// back to the state the running launcher actually uses.
    private static void applyPortableSelection(EnumCommonDirectory type) {
        boolean wantPortable = type == EnumCommonDirectory.PORTABLE;
        if (wantPortable == Metadata.isPortable()) {
            return;
        }
        try {
            if (wantPortable) {
                Files.createDirectories(Metadata.getPortableDirectory());
                Files.writeString(Metadata.getPortableMarker(),
                        "Xenon portable mode marker.\n"
                                + "Delete this file to return to the default data directory.\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } else {
                Files.deleteIfExists(Metadata.getPortableMarker());
            }
            Controllers.dialog(i18n(wantPortable
                            ? "launcher.cache_directory.portable.enabled"
                            : "launcher.cache_directory.portable.disabled"),
                    i18n("message.info"), MessageDialogPane.MessageType.INFO);
        } catch (IOException e) {
            LOG.warning("Failed to switch portable mode", e);
            config().setCommonDirType(Metadata.isPortable()
                    ? EnumCommonDirectory.PORTABLE : EnumCommonDirectory.DEFAULT);
            Controllers.dialog(i18n("launcher.cache_directory.portable.failed", e.getMessage()),
                    i18n("message.error"), MessageDialogPane.MessageType.ERROR);
        }
    }

    /// Validates the configured token and reports the account and quota.
    private static void verifyGithubToken(Label status) {
        String token = config().getGithubToken();
        if (token == null || token.isBlank()) {
            status.setText(i18n("download.github.anonymous"));
            return;
        }
        status.setText(i18n("message.doing"));
        Schedulers.io().execute(() -> {
            try {
                GitHubAuth.TokenCheck check = GitHubAuth.verify(token);
                Platform.runLater(() -> status.setText(
                        formatGithubStatus(check.login(), check.rateLimit())));
            } catch (IOException e) {
                LOG.warning("GitHub token verification failed", e);
                Platform.runLater(() -> status.setText(
                        i18n("download.github.invalid", e.getMessage())));
            }
        });
    }

    /// Refreshes the rate limit for the already configured token.
    private static void refreshGithubStatus(Label status) {
        if (!GitHubAuth.hasToken()) {
            status.setText(i18n("download.github.anonymous"));
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                GitHubAuth.RateLimit limit = GitHubAuth.refreshRateLimit();
                Platform.runLater(() -> status.setText(formatGithubStatus(null, limit)));
            } catch (IOException e) {
                LOG.warning("Failed to refresh the GitHub rate limit", e);
                Platform.runLater(() -> status.setText(i18n("download.github.offline")));
            }
        });
    }

    /// Formats the account / quota status line.
    private static String formatGithubStatus(@Nullable String login, GitHubAuth.RateLimit limit) {
        String reset = RESET_FORMAT.format(limit.reset().atZone(ZoneId.systemDefault()));
        String rate = i18n("download.github.rate_limit", limit.remaining(), limit.limit(), reset);
        return login == null || login.isBlank()
                ? rate
                : i18n("download.github.verified", login) + "  ·  " + rate;
    }

    /// Formatter for the rate-limit reset instant.
    private static final DateTimeFormatter RESET_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private void clearCacheDirectory() {
        String commonDirectory = Settings.instance().getCommonDirectory();
        if (commonDirectory != null) {
            FileUtils.cleanDirectoryQuietly(Path.of(commonDirectory, "cache"));
        }
    }
}
