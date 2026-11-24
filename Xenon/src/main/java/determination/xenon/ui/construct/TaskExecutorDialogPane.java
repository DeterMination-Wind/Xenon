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
package determination.xenon.ui.construct;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import determination.xenon.mindustry.download.DownloadControl;
import determination.xenon.mindustry.download.MindustryDownloadTask;
import determination.xenon.task.*;
import determination.xenon.ui.FXUtils;
import determination.xenon.util.TaskCancellationAction;
import determination.xenon.util.i18n.I18n;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

import static determination.xenon.ui.FXUtils.onEscPressed;
import static determination.xenon.ui.FXUtils.runInFX;
import static determination.xenon.util.i18n.I18n.i18n;

public class TaskExecutorDialogPane extends BorderPane {
    private TaskExecutor executor;
    private TaskCancellationAction onCancel;
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private final Consumer<FetchTask.SpeedEvent> speedEventHandler;

    private final Label lblTitle;
    private final Label lblProgress;
    private final JFXButton btnPause;
    private final JFXButton btnCancel;
    private final TaskListPane taskListPane;

    public TaskExecutorDialogPane(@NotNull TaskCancellationAction cancel) {
        this.getStyleClass().add("task-executor-dialog-layout");

        FXUtils.setLimitWidth(this, 500);
        FXUtils.setLimitHeight(this, 300);

        VBox center = new VBox();
        this.setCenter(center);
        center.setPadding(new Insets(16));
        {
            lblTitle = new Label();
            lblTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: BOLD;");

            taskListPane = new TaskListPane();
            VBox.setVgrow(taskListPane, Priority.ALWAYS);

            center.getChildren().setAll(lblTitle, taskListPane);
        }

        BorderPane bottom = new BorderPane();
        this.setBottom(bottom);
        bottom.setPadding(new Insets(0, 8, 8, 8));
        {
            lblProgress = new Label();
            // The progress line alternates between "1.2 MiB/s" and
            // "1.2 MiB/s · 0:32", and the dialog sizes to its content: pin the
            // label to the widest shape so the window does not jitter.
            StableTextWidth.reserve(lblProgress, java.util.List.of(
                    I18n.formatSpeed(999),
                    I18n.formatSpeed(1024L * 1024L * 999L),
                    i18n("download.progress.eta",
                            I18n.formatSpeed(1024L * 1024L * 999L), "9:59:59")));
            bottom.setLeft(lblProgress);

            // The pause button only appears for tasks that publish a download
            // control; every other task keeps the plain cancel dialog.
            btnPause = new JFXButton(i18n("download.pause"));
            btnPause.getStyleClass().add("dialog-cancel");
            btnPause.setManaged(false);
            btnPause.setVisible(false);

            btnCancel = new JFXButton(i18n("button.cancel"));
            btnCancel.getStyleClass().add("dialog-cancel");

            HBox actions = new HBox(8, btnPause, btnCancel);
            actions.setAlignment(Pos.CENTER_RIGHT);
            bottom.setRight(actions);
        }

        setCancel(cancel);

        btnCancel.setDisable(onCancel.getCancellationAction() == null);
        btnCancel.setOnAction(e -> {
            if (executor != null)
                executor.cancel();
            onCancel.getCancellationAction().accept(this);
        });

        speedEventHandler = FetchTask.SPEED_EVENT.registerWeak(speedEvent -> {
            String message = describeProgress(speedEvent.getSpeed());
            Platform.runLater(() -> lblProgress.setText(message));
        });

        onEscPressed(this, btnCancel::fire);
    }

    /// Builds the progress line: speed, plus the remaining time when the
    /// running task knows its total size.
    ///
    /// The estimate is recomputed on every speed tick instead of using an
    /// EWMA: the launcher-wide speed counter is already smoothed, and a stale
    /// estimate is worse than a slightly jumpy one.
    ///
    /// @param bytesPerSecond current launcher-wide download speed
    /// @return text for the progress label
    private String describeProgress(long bytesPerSecond) {
        String speed = I18n.formatSpeed(bytesPerSecond);
        if (bytesPerSecond <= 0 || executor == null) {
            return speed;
        }
        var task = executor.getFirstTask();
        if (!(task instanceof determination.xenon.task.ByteProgressTask bytes)) {
            return speed;
        }
        long total = bytes.totalBytes();
        long done = bytes.transferredBytes();
        if (total <= 0 || done >= total) {
            return speed;
        }
        long remainingSeconds = (total - done + bytesPerSecond - 1) / bytesPerSecond;
        return i18n("download.progress.eta", speed,
                I18n.formatDuration(remainingSeconds));
    }

    public void setExecutor(TaskExecutor executor) {
        setExecutor(executor, true);
    }

    public void setExecutor(TaskExecutor executor, boolean autoClose) {
        this.executor = executor;

        if (executor != null) {
            taskListPane.setExecutor(executor);
            setupPauseButton(executor);

            if (autoClose)
                executor.addTaskListener(new TaskListener() {
                    @Override
                    public void onStop(boolean success, TaskExecutor executor) {
                        Platform.runLater(() -> fireEvent(new DialogCloseEvent()));
                    }
                });
        }
    }

    /// Shows and wires the pause button when the executor's task publishes a
    /// download control; hides it for every other task type.
    private void setupPauseButton(TaskExecutor executor) {
        Object value = executor.getFirstTask().getProperties().get(MindustryDownloadTask.CONTROL_PROPERTY);
        if (!(value instanceof DownloadControl control)) {
            btnPause.setVisible(false);
            btnPause.setManaged(false);
            return;
        }
        btnPause.setText(i18n("download.pause"));
        btnPause.setVisible(true);
        btnPause.setManaged(true);
        btnPause.setOnAction(e -> {
            if (control.isPaused()) {
                control.resume();
                btnPause.setText(i18n("download.pause"));
            } else {
                control.pause();
                btnPause.setText(i18n("download.resume"));
            }
        });
    }

    public StringProperty titleProperty() {
        return lblTitle.textProperty();
    }

    public String getTitle() {
        return lblTitle.getText();
    }

    public void setTitle(String currentState) {
        lblTitle.setText(currentState);
    }

    public void setCancel(TaskCancellationAction onCancel) {
        this.onCancel = onCancel;

        runInFX(() -> btnCancel.setDisable(onCancel == null));
    }
}
