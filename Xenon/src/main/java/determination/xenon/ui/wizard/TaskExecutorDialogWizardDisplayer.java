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
package determination.xenon.ui.wizard;

import javafx.beans.property.StringProperty;
import determination.xenon.task.Task;
import determination.xenon.task.TaskExecutor;
import determination.xenon.task.TaskListener;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.construct.DialogCloseEvent;
import determination.xenon.ui.construct.MessageDialogPane.MessageType;
import determination.xenon.ui.construct.TaskExecutorDialogPane;
import determination.xenon.util.SettingsMap;
import determination.xenon.util.StringUtils;
import determination.xenon.util.TaskCancellationAction;

import java.util.Queue;
import java.util.concurrent.CancellationException;

import static determination.xenon.ui.FXUtils.runInFX;
import static determination.xenon.util.i18n.I18n.i18n;

public abstract class TaskExecutorDialogWizardDisplayer extends AbstractWizardDisplayer {

    public TaskExecutorDialogWizardDisplayer(Queue<Object> cancelQueue) {
        super(cancelQueue);
    }

    @Override
    public void handleTask(SettingsMap settings, Task<?> task) {
        TaskExecutorDialogPane pane = new TaskExecutorDialogPane(new TaskCancellationAction(it -> {
            it.fireEvent(new DialogCloseEvent());
            onEnd();
        }));

        pane.setTitle(i18n("message.doing"));
        if (settings.containsKey("title")) {
            Object title = settings.get("title");
            if (title instanceof StringProperty titleProperty)
                pane.titleProperty().bind(titleProperty);
            else if (title instanceof String titleMessage)
                pane.setTitle(titleMessage);
        }

        runInFX(() -> {
            TaskExecutor executor = task.executor(new TaskListener() {
                @Override
                public void onStop(boolean success, TaskExecutor executor) {
                    runInFX(() -> {
                        if (success) {
                            if (settings.get("success_message") instanceof String successMessage)
                                Controllers.dialog(successMessage, null, MessageType.SUCCESS, () -> onEnd());
                            else if (!settings.containsKey("forbid_success_message"))
                                Controllers.dialog(i18n("message.success"), null, MessageType.SUCCESS, () -> onEnd());
                        } else {
                            if (executor.getException() == null)
                                return;

                            if (executor.getException() instanceof CancellationException) {
                                onEnd();
                                return;
                            }

                            String appendix = StringUtils.getStackTrace(executor.getException());
                            if (settings.get(WizardProvider.FailureCallback.KEY) != null)
                                settings.get(WizardProvider.FailureCallback.KEY).onFail(settings, executor.getException(), () -> onEnd());
                            else if (settings.get("failure_message") instanceof String failureMessage)
                                Controllers.dialog(appendix, failureMessage, MessageType.ERROR, () -> onEnd());
                            else if (!settings.containsKey("forbid_failure_message"))
                                Controllers.dialog(appendix, i18n("wizard.failed"), MessageType.ERROR, () -> onEnd());
                        }

                    });
                }
            });
            pane.setExecutor(executor);
            Controllers.dialog(pane);
            executor.start();
        });
    }
}
