/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
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
package determination.xenon.mindustry.ui;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.dev.MindustryDevBuilder;
import determination.xenon.mindustry.dev.MindustryDevWatcher;
import determination.xenon.mindustry.mod.MindustryModManager;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Development workflow tab: build a Mod project, deploy it and optionally
/// rebuild/restart on source changes.
///
/// All settings are stored on the instance manifest. Builds run one at a time;
/// changes that arrive during a build schedule exactly one follow-up build.
@NotNullByDefault
public final class MindustryDevPane extends VBox {

    private final MindustryVersion version;
    private final Path dataDir;
    private final TextField projectField = new TextField();
    private final TextField commandField = new TextField();
    private final TextField globField = new TextField();
    private final JFXCheckBox autoRebuild = new JFXCheckBox(i18n("xenon.dev.auto_rebuild"));
    private final JFXCheckBox autoRestart = new JFXCheckBox(i18n("xenon.dev.auto_restart"));
    private final TextArea logArea = new TextArea();
    private final Label status = new Label();
    private final AtomicBoolean building = new AtomicBoolean();
    private final AtomicBoolean rebuildPending = new AtomicBoolean();
    private volatile @Nullable MindustryDevWatcher watcher;

    /// Builds the development pane for one instance.
    ///
    /// @param version instance descriptor
    /// @param dataDir effective data directory
    public MindustryDevPane(MindustryVersion version, Path dataDir) {
        this.version = version;
        this.dataDir = dataDir;
        setSpacing(8);
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.dev.title"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.dev.hint"));
        hint.getStyleClass().add("subtitle-label");
        hint.setWrapText(true);

        projectField.setText(version.getDevProjectDir());
        projectField.setPromptText(i18n("xenon.dev.project.prompt"));
        commandField.setText(version.getDevBuildCommand());
        commandField.setPromptText(i18n("xenon.dev.command.prompt"));
        globField.setText(version.getDevArtifactGlob());
        globField.setPromptText(MindustryDevBuilder.DEFAULT_GLOB);
        autoRebuild.setSelected(version.isDevAutoRebuild());
        autoRestart.setSelected(version.isDevAutoRestart());
        autoRebuild.selectedProperty().addListener((observable, was, enabled) -> {
            version.setDevAutoRebuild(enabled);
            refreshWatcher();
        });

        JFXButton save = FXUtils.newRaisedButton(i18n("xenon.dev.save"));
        save.setOnAction(e -> saveSettings());
        JFXButton build = FXUtils.newRaisedButton(i18n("xenon.dev.build"));
        build.setOnAction(e -> runBuild(true));
        HBox actions = new HBox(8, save, build);
        actions.setAlignment(Pos.CENTER_LEFT);

        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setPrefRowCount(18);
        VBox.setVgrow(logArea, Priority.ALWAYS);
        status.setWrapText(true);

        getChildren().addAll(title, hint,
                labeled(i18n("xenon.dev.project"), projectField),
                labeled(i18n("xenon.dev.command"), commandField),
                labeled(i18n("xenon.dev.glob"), globField),
                autoRebuild, autoRestart, actions, status, logArea);
        refreshWatcher();
    }

    /// Wraps one text field with a label.
    private static VBox labeled(String label, TextField field) {
        Label caption = new Label(label);
        caption.getStyleClass().add("subtitle-label");
        HBox.setHgrow(field, Priority.ALWAYS);
        return new VBox(2, caption, field);
    }

    /// Persists the form values on the instance manifest.
    private void saveSettings() {
        version.setDevProjectDir(projectField.getText());
        version.setDevBuildCommand(commandField.getText());
        version.setDevArtifactGlob(globField.getText());
        version.setDevAutoRebuild(autoRebuild.isSelected());
        version.setDevAutoRestart(autoRestart.isSelected());
        Schedulers.io().execute(() -> {
            try {
                MindustryImportFlow.repositoryForVersion(version).save(version);
                Platform.runLater(() -> {
                    status.setText(i18n("xenon.dev.saved"));
                    refreshWatcher();
                });
            } catch (IOException e) {
                Platform.runLater(() -> status.setText(
                        i18n("xenon.dev.save_failed", e.getMessage())));
            }
        });
    }

    /// Starts or stops the watcher to match the current settings.
    private void refreshWatcher() {
        MindustryDevWatcher current = watcher;
        if (autoRebuild.isSelected() && version.getDevProjectDir() != null
                && !version.getDevProjectDir().isBlank()
                && Files.isDirectory(Path.of(version.getDevProjectDir()))) {
            if (current != null) {
                return;
            }
            try {
                watcher = new MindustryDevWatcher(Path.of(version.getDevProjectDir()),
                        () -> runBuild(false));
                status.setText(i18n("xenon.dev.watching"));
            } catch (IOException e) {
                status.setText(i18n("xenon.dev.watch_failed", e.getMessage()));
            }
            return;
        }
        if (current != null) {
            current.close();
            watcher = null;
        }
    }

    /// Runs one build and deploys its artifact.
    ///
    /// @param manual whether the user clicked the build button
    private void runBuild(boolean manual) {
        saveSettings();
        if (!building.compareAndSet(false, true)) {
            rebuildPending.set(true);
            return;
        }
        String project = projectField.getText() == null ? "" : projectField.getText().trim();
        if (project.isBlank() || !Files.isDirectory(Path.of(project))) {
            building.set(false);
            status.setText(i18n("xenon.dev.project.missing"));
            return;
        }
        status.setText(i18n("xenon.dev.building"));
        appendLog("$ " + (commandField.getText() == null || commandField.getText().isBlank()
                ? i18n("xenon.dev.command.default") : commandField.getText()));
        Schedulers.io().execute(() -> {
            try {
                var result = MindustryDevBuilder.build(new MindustryDevBuilder.Options(
                                Path.of(project), commandField.getText(), globField.getText(),
                                Duration.ofMinutes(10)),
                        this::appendLog, null);
                if (!result.succeeded() || result.artifact() == null) {
                    Platform.runLater(() -> status.setText(result.succeeded()
                            ? i18n("xenon.dev.no_artifact")
                            : i18n("xenon.dev.failed", result.exitCode())));
                    return;
                }
                Path artifact = result.artifact();
                new MindustryModManager(dataDir.resolve("mods"), dataDir).install(artifact);
                Platform.runLater(() -> {
                    status.setText(i18n("xenon.dev.deployed", artifact.getFileName().toString()));
                    if (autoRestart.isSelected()) {
                        MindustryRoutes.restartForDevelopment(version);
                    }
                });
            } catch (IOException | RuntimeException e) {
                LOG.warning("Development build failed", e);
                Platform.runLater(() -> status.setText(
                        i18n("xenon.dev.failed.detail", e.getMessage())));
            } finally {
                building.set(false);
                if (rebuildPending.getAndSet(false)) {
                    Platform.runLater(() -> runBuild(false));
                }
            }
        });
    }

    /// Appends one build log line.
    private void appendLog(String line) {
        Platform.runLater(() -> {
            logArea.appendText(line + System.lineSeparator());
            // Keep the log bounded so a chatty Gradle build cannot grow the UI
            // without limit.
            if (logArea.getLength() > 400_000) {
                logArea.deleteText(0, logArea.getLength() - 300_000);
            }
        });
    }
}
