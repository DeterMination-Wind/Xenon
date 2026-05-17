/*
 * Xenon Launcher
 * Copyright (C) 2024-2026  Xenon contributors
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
import javafx.beans.InvalidationListener;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import determination.xenon.download.DownloadProvider;
import determination.xenon.download.java.JavaDistribution;
import determination.xenon.download.java.JavaPackageType;
import determination.xenon.download.java.JavaRemoteVersion;
import determination.xenon.download.java.disco.*;
import determination.xenon.game.GameJavaVersion;
import determination.xenon.java.JavaInfo;
import determination.xenon.java.JavaManager;
import determination.xenon.setting.DownloadProviders;
import determination.xenon.task.FileDownloadTask;
import determination.xenon.task.GetTask;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.DialogCloseEvent;
import determination.xenon.ui.construct.DialogPane;
import determination.xenon.ui.construct.JFXHyperlink;
import determination.xenon.ui.construct.SpinnerPane;
import determination.xenon.ui.wizard.SinglePageWizardProvider;
import determination.xenon.util.Lang;
import determination.xenon.util.Result;
import determination.xenon.util.StringUtils;
import determination.xenon.util.TaskCancellationAction;
import determination.xenon.util.gson.JsonUtils;
import determination.xenon.util.platform.Architecture;
import determination.xenon.util.platform.OperatingSystem;
import determination.xenon.util.platform.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import static determination.xenon.ui.FXUtils.onEscPressed;
import static determination.xenon.util.Lang.resolveException;
import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/**
 * @author Glavo
 */
public final class JavaDownloadDialog extends StackPane {

    public static Runnable showDialogAction(DownloadProvider downloadProvider) {
        Platform platform = Platform.SYSTEM_PLATFORM;

        List<GameJavaVersion> supportedVersions = GameJavaVersion.getSupportedVersions(platform);

        EnumSet<DiscoJavaDistribution> distributions = EnumSet.noneOf(DiscoJavaDistribution.class);
        for (DiscoJavaDistribution distribution : DiscoJavaDistribution.values()) {
            if (distribution.isSupport(platform)) {
                distributions.add(distribution);
            }
        }

        return supportedVersions.isEmpty() && distributions.isEmpty()
                ? null
                : () -> Controllers.dialog(new JavaDownloadDialog(downloadProvider, platform, supportedVersions, distributions));
    }

    private final DownloadProvider downloadProvider;
    private final Platform platform;
    private final List<GameJavaVersion> supportedGameJavaVersions;
    private final EnumSet<DiscoJavaDistribution> distributions;

    private JavaDownloadDialog(DownloadProvider downloadProvider, Platform platform, List<GameJavaVersion> supportedGameJavaVersions, EnumSet<DiscoJavaDistribution> distributions) {
        this.downloadProvider = downloadProvider;
        this.platform = platform;
        this.supportedGameJavaVersions = supportedGameJavaVersions;
        this.distributions = distributions;

        if (!supportedGameJavaVersions.isEmpty()) {
            this.getChildren().add(new DownloadMojangJava());
        } else {
            this.getChildren().add(new DownloadDiscoJava());
        }
    }

    private final class DownloadMojangJava extends DialogPane {
        private final List<JFXCheckBox> checkboxes = new ArrayList<>();

        DownloadMojangJava() {
            setTitle(i18n("java.download.title"));

            VBox vbox = new VBox(16);
            Label prompt = new Label(i18n("java.download.prompt"));
            vbox.getChildren().add(prompt);

            setValid(false);
            InvalidationListener updateValidStatus = e -> {
                for (JFXCheckBox box : checkboxes) {
                    if (!box.isDisabled() && box.isSelected()) {
                        setValid(true);
                        return;
                    }
                }
                setValid(false);
            };

            for (GameJavaVersion version : supportedGameJavaVersions) {
                JFXCheckBox button = new JFXCheckBox("Java " + version.majorVersion());
                button.setUserData(version);

                if (JavaManager.REPOSITORY.isInstalled(platform, version)) {
                    button.setDisable(true);
                    button.setSelected(true);
                } else {
                    button.selectedProperty().addListener(updateValidStatus);
                }

                vbox.getChildren().add(button);
                checkboxes.add(button);
            }

            setBody(vbox);

            if (!distributions.isEmpty()) {
                JFXHyperlink more = new JFXHyperlink(i18n("java.download.more"));
                more.setOnAction(event -> JavaDownloadDialog.this.getChildren().setAll(new DownloadDiscoJava()));
                setActions(warningLabel, more, acceptPane, cancelButton);
            } else
                setActions(warningLabel, acceptPane, cancelButton);
        }

        @Override
        protected void onAccept() {
            fireEvent(new DialogCloseEvent());

            List<GameJavaVersion> selectedVersions = new ArrayList<>();
            for (JFXCheckBox box : checkboxes) {
                if (!box.isDisabled() && box.isSelected()) {
                    selectedVersions.add((GameJavaVersion) box.getUserData());
                }
            }

            if (selectedVersions.isEmpty())
                return;

            Task<Void> task = Task.allOf(selectedVersions.stream().map(javaVersion -> JavaManager.getDownloadJavaTask(downloadProvider, platform, javaVersion).wrapResult()).toList())
                    .whenComplete(Schedulers.javafx(), (results, exception) -> {
                        Throwable exceptionToDisplay;
                        if (exception == null) {
                            List<Throwable> exceptions = results.stream()
                                    .filter(Result::isFailure)
                                    .map(Result::getException)
                                    .map(Lang::resolveException)
                                    .filter(it -> !(it instanceof CancellationException))
                                    .toList();
                            if (!exceptions.isEmpty()) {
                                if (exceptions.size() == 1) {
                                    exceptionToDisplay = exceptions.get(0);
                                } else {
                                    exceptionToDisplay = new IOException("Failed to download Java");
                                    for (Throwable e : exceptions)
                                        exceptionToDisplay.addSuppressed(e);
                                }
                            } else {
                                exceptionToDisplay = null;
                            }
                        } else {
                            exceptionToDisplay = exception;
                        }

                        if (exceptionToDisplay != null) {
                            LOG.warning("Failed to download java", exceptionToDisplay);
                            Controllers.dialog(DownloadProviders.localizeErrorMessage(exceptionToDisplay), i18n("install.failed"));
                        }
                    });

            Controllers.taskDialog(task, i18n("download.java.process"), TaskCancellationAction.NORMAL);
        }
    }

    private final class DownloadDiscoJava extends JFXDialogLayout {
        private final JFXComboBox<DiscoJavaDistribution> distributionBox;
        private final JFXComboBox<DiscoJavaRemoteVersion> remoteVersionBox;
        private final JFXComboBox<JavaPackageType> packageTypeBox;
        private final Label warningLabel = new Label();

        private final JFXButton downloadButton;
        private final StackPane downloadButtonPane = new StackPane();

        private final DownloadProvider downloadProvider = DownloadProviders.getDownloadProvider();

        private final Map<DiscoJavaDistribution, DiscoJavaVersionList> javaVersionLists = new HashMap<>();
        private final ObjectProperty<DiscoJavaVersionList> currentJavaVersionList = new SimpleObjectProperty<>();

        DownloadDiscoJava() {
            assert !distributions.isEmpty();

            this.distributionBox = new JFXComboBox<>();
            this.distributionBox.setConverter(FXUtils.stringConverter(JavaDistribution::getDisplayName));

            this.remoteVersionBox = new JFXComboBox<>();
            this.remoteVersionBox.setConverter(FXUtils.stringConverter(JavaRemoteVersion::getDistributionVersion));

            this.packageTypeBox = new JFXComboBox<>(FXCollections.observableArrayList());

            this.downloadButton = new JFXButton(i18n("download"));
            downloadButton.setOnAction(e -> onDownload());
            downloadButton.getStyleClass().add("dialog-accept");
            downloadButton.disableProperty().bind(Bindings.isNull(remoteVersionBox.getSelectionModel().selectedItemProperty()));

            JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
            cancelButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));
            cancelButton.getStyleClass().add("dialog-cancel");
            onEscPressed(this, cancelButton::fire);

            GridPane body = new GridPane();
            body.getColumnConstraints().setAll(new ColumnConstraints(), FXUtils.getColumnHgrowing());
            body.setVgap(8);
            body.setHgap(16);

            body.addRow(0, new Label(i18n("java.download.distribution")), distributionBox);
            body.addRow(1, new Label(i18n("java.download.version")), remoteVersionBox);
            body.addRow(2, new Label(i18n("java.download.packageType")), packageTypeBox);

            distributionBox.setItems(FXCollections.observableList(new ArrayList<>(distributions)));

            FXUtils.onChange(packageTypeBox.getSelectionModel().selectedItemProperty(), packageType -> {

                ObservableList<DiscoJavaRemoteVersion> versions;
                if (packageType == null
                        || currentJavaVersionList.get() == null
                        || (versions = currentJavaVersionList.get().versions.get(packageType)) == null) {
                    remoteVersionBox.setItems(null);
                    return;
                }

                DiscoJavaRemoteVersion oldVersion = remoteVersionBox.getSelectionModel().getSelectedItem();
                remoteVersionBox.setItems(versions);

                if (oldVersion != null) {
                    for (int i = 0; i < versions.size(); i++) {
                        DiscoJavaRemoteVersion version = versions.get(i);
                        if (Objects.equals(version.getDistributionVersion(), oldVersion.getDistributionVersion())) {
                            remoteVersionBox.getSelectionModel().select(i);
                            return;
                        }
                    }
                    for (int i = 0; i < versions.size(); i++) {
                        DiscoJavaRemoteVersion version = versions.get(i);
                        if (version.getJdkVersion() == oldVersion.getJdkVersion()) {
                            remoteVersionBox.getSelectionModel().select(i);
                            return;
                        }
                    }
                }

                for (int i = 0; i < versions.size(); i++) {
                    DiscoJavaRemoteVersion version = versions.get(i);
                    if (version.getJdkVersion() == GameJavaVersion.LATEST.majorVersion()) {
                        remoteVersionBox.getSelectionModel().select(i);
                        return;
                    }
                }

                for (int i = 0; i < versions.size(); i++) {
                    DiscoJavaRemoteVersion version = versions.get(i);
                    if (version.isLTS()) {
                        remoteVersionBox.getSelectionModel().select(i);
                        return;
                    }
                }

                remoteVersionBox.getSelectionModel().selectFirst();
            });

            SpinnerPane spinnerPane = new SpinnerPane();
            spinnerPane.getStyleClass().add("small-spinner-pane");
            spinnerPane.setContent(downloadButton);
            downloadButtonPane.getChildren().setAll(spinnerPane);

            Consumer<DiscoJavaVersionList> updateListStatus = list -> {
                remoteVersionBox.setItems(null);
                packageTypeBox.getItems().clear();
                remoteVersionBox.setDisable(true);
                packageTypeBox.setDisable(true);
                warningLabel.setText(null);

                if (list == null || (list.versions != null && list.versions.isEmpty()))
                    spinnerPane.hideSpinner();
                else if (list.status == DiscoJavaVersionList.Status.LOADING)
                    spinnerPane.showSpinner();
                else {
                    spinnerPane.hideSpinner();

                    if (list.status == DiscoJavaVersionList.Status.SUCCESS) {
                        packageTypeBox.getItems().setAll(list.versions.keySet());
                        packageTypeBox.getSelectionModel().selectFirst();

                        remoteVersionBox.setDisable(false);
                        packageTypeBox.setDisable(false);
                    } else
                        warningLabel.setText(i18n("java.download.load_list.failed"));
                }
            };

            currentJavaVersionList.addListener((observable, oldValue, newValue) -> {
                if (oldValue != null)
                    oldValue.listener = null;

                if (newValue != null) {
                    updateListStatus.accept(newValue);

                    if (newValue.status == DiscoJavaVersionList.Status.LOADING)
                        newValue.listener = updateListStatus;
                } else {
                    currentJavaVersionList.set(null);
                    updateListStatus.accept(null);
                }
            });

            FXUtils.onChange(distributionBox.getSelectionModel().selectedItemProperty(),
                    it -> currentJavaVersionList.set(getJavaVersionList(it)));

            setHeading(new Label(i18n("java.download.title")));
            setBody(body);
            setActions(warningLabel, downloadButtonPane, cancelButton);
            if (platform.getOperatingSystem() == OperatingSystem.LINUX && platform.getArchitecture() == Architecture.RISCV64) {
                JFXHyperlink hyperlink = new JFXHyperlink(i18n("java.download.banshanjdk-8"));
                hyperlink.setExternalLink("https://www.zthread.cn/#product");
                getActions().add(0, hyperlink);
            }
        }

        private void onDownload() {
            fireEvent(new DialogCloseEvent());

            DiscoJavaDistribution distribution = distributionBox.getSelectionModel().getSelectedItem();
            DiscoJavaRemoteVersion version = remoteVersionBox.getSelectionModel().getSelectedItem();
            JavaPackageType packageType = packageTypeBox.getSelectionModel().getSelectedItem();

            if (version == null)
                return;

            Controllers.taskDialog(new GetTask(downloadProvider.injectURLWithCandidates(version.getLinks().getPkgInfoUri()))
                    .setExecutor(Schedulers.io())
                    .thenComposeAsync(json -> {
                        DiscoResult<DiscoRemoteFileInfo> result = JsonUtils.fromNonNullJson(json, DiscoResult.typeOf(DiscoRemoteFileInfo.class));
                        if (result.getResult().size() != 1)
                            throw new IOException("Illegal result: " + json);

                        DiscoRemoteFileInfo fileInfo = result.getResult().get(0);
                        if (!fileInfo.getChecksumType().equals("sha1") && !fileInfo.getChecksumType().equals("sha256"))
                            throw new IOException("Unsupported checksum type: " + fileInfo.getChecksumType());
                        if (StringUtils.isBlank(fileInfo.getDirectDownloadUri()))
                            throw new IOException("Missing download URI: " + json);

                        Path targetFile = Files.createTempFile("hmcl-java-", "." + version.getArchiveType());
                        targetFile.toFile().deleteOnExit();

                        Task<FileDownloadTask.IntegrityCheck> getIntegrityCheck;
                        if (StringUtils.isNotBlank(fileInfo.getChecksum()))
                            getIntegrityCheck = Task.completed(new FileDownloadTask.IntegrityCheck(fileInfo.getChecksumType(), fileInfo.getChecksum()));
                        else if (StringUtils.isNotBlank(fileInfo.getChecksumUri()))
                            getIntegrityCheck = new GetTask(downloadProvider.injectURLWithCandidates(fileInfo.getChecksumUri()))
                                    .thenApplyAsync(checksum -> {
                                        checksum = checksum.trim();

                                        int idx = checksum.indexOf(' ');
                                        if (idx > 0)
                                            checksum = checksum.substring(0, idx);

                                        return new FileDownloadTask.IntegrityCheck(fileInfo.getChecksumType(), checksum);
                                    });
                        else
                            throw new IOException("Unable to get checksum for file");

                        return getIntegrityCheck
                                .thenComposeAsync(integrityCheck ->
                                        new FileDownloadTask(downloadProvider.injectURLWithCandidates(fileInfo.getDirectDownloadUri()),
                                                targetFile, integrityCheck).setName(fileInfo.getFileName()))
                                .thenSupplyAsync(() -> targetFile);
                    })
                    .whenComplete(Schedulers.javafx(), ((result, exception) -> {
                        if (exception == null) {
                            String javaVersion = version.getJavaVersion();
                            JavaInfo info = new JavaInfo(platform, javaVersion, distribution.getVendor());

                            Map<String, Object> updateInfo = new LinkedHashMap<>();
                            updateInfo.put("type", "disco");
                            updateInfo.put("info", version);

                            int idx = javaVersion.indexOf('+');
                            if (idx > 0) {
                                javaVersion = javaVersion.substring(0, idx);
                            }
                            String defaultName = distribution.getApiParameter() + "-" + javaVersion + "-" + packageType.name().toLowerCase(Locale.ROOT);
                            Controllers.getDecorator().startWizard(new SinglePageWizardProvider(controller ->
                                    new JavaInstallPage(controller::onFinish, info, version, updateInfo, defaultName, result)));
                        } else {
                            LOG.warning("Failed to download java", exception);
                            Throwable resolvedException = resolveException(exception);
                            if (!(resolvedException instanceof CancellationException)) {
                                Controllers.dialog(DownloadProviders.localizeErrorMessage(resolvedException), i18n("install.failed"));
                            }
                        }
                    })), i18n("java.download"), TaskCancellationAction.NORMAL);

        }

        private DiscoJavaVersionList getJavaVersionList(DiscoJavaDistribution distribution) {
            if (distribution == null)
                return null;
            return javaVersionLists.computeIfAbsent(distribution, it -> {
                DiscoJavaVersionList versionList = new DiscoJavaVersionList(it);
                new DiscoFetchJavaListTask(downloadProvider, it, platform).setExecutor(Schedulers.io()).thenApplyAsync(versions -> {
                    EnumMap<JavaPackageType, ObservableList<DiscoJavaRemoteVersion>> result = new EnumMap<>(JavaPackageType.class);
                    if (versions.isEmpty())
                        return result;

                    for (Map.Entry<JavaPackageType, TreeMap<Integer, DiscoJavaRemoteVersion>> entry : versions.entrySet())
                        for (DiscoJavaRemoteVersion version : entry.getValue().values())
                            if (version.isLTS()
                                    || version.getJdkVersion() == entry.getValue().lastKey() // latest version
                                    || version.getJdkVersion() == 16)
                                result.computeIfAbsent(entry.getKey(), ignored -> FXCollections.observableArrayList())
                                        .add(version);

                    for (List<DiscoJavaRemoteVersion> l : result.values())
                        Collections.reverse(l);
                    return result;
                }).whenComplete(Schedulers.javafx(), ((result, exception) -> {
                    if (exception == null) {
                        versionList.status = DiscoJavaVersionList.Status.SUCCESS;
                        versionList.versions = result;
                    } else {
                        LOG.warning("Failed to load java list", exception);
                        versionList.status = DiscoJavaVersionList.Status.FAILED;
                    }
                    versionList.invalidate();
                })).start();
                return versionList;
            });
        }
    }

    private static final class DiscoJavaVersionList {
        enum Status {
            LOADING, SUCCESS, FAILED
        }

        final DiscoJavaDistribution distribution;

        Status status = Status.LOADING;
        EnumMap<JavaPackageType, ObservableList<DiscoJavaRemoteVersion>> versions;
        Consumer<DiscoJavaVersionList> listener;

        DiscoJavaVersionList(DiscoJavaDistribution distribution) {
            this.distribution = distribution;
        }

        void invalidate() {
            if (listener != null)
                listener.accept(this);
        }
    }
}
