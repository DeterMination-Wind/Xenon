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

import com.jfoenix.controls.JFXCheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import determination.xenon.java.JavaManager;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.construct.DialogCloseEvent;
import determination.xenon.ui.construct.DialogPane;
import determination.xenon.util.TaskCancellationAction;
import determination.xenon.util.platform.Platform;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Offers Eclipse Temurin JDKs from the Adoptium API.
///
/// Mindustry needs Java 17 or newer. Only the LTS lines Xenon supports are
/// listed, and runtimes already present in the managed pool are shown disabled.
public final class JavaDownloadDialog extends DialogPane {
    private static final List<Integer> SUPPORTED_VERSIONS = List.of(17, 21, 25);

    public static @Nullable Runnable showDialogAction() {
        Platform platform = Platform.SYSTEM_PLATFORM;
        return () -> Controllers.dialog(new JavaDownloadDialog(platform));
    }

    private final Platform platform;
    private final List<JFXCheckBox> checkboxes = new ArrayList<>();

    private JavaDownloadDialog(Platform platform) {
        this.platform = platform;

        setTitle(i18n("java.download.title"));

        VBox body = new VBox(16);
        body.getChildren().add(new Label(i18n("java.download.prompt")));

        for (int majorVersion : SUPPORTED_VERSIONS) {
            JFXCheckBox box = new JFXCheckBox("Java " + majorVersion);
            box.setUserData(majorVersion);
            if (JavaManager.REPOSITORY.isInstalled(platform, majorVersion)) {
                box.setSelected(true);
                box.setDisable(true);
            } else {
                box.selectedProperty().addListener(observable -> updateValidStatus());
            }
            checkboxes.add(box);
            body.getChildren().add(box);
        }

        setBody(body);
        updateValidStatus();
    }

    private void updateValidStatus() {
        setValid(checkboxes.stream().anyMatch(box -> !box.isDisabled() && box.isSelected()));
    }

    @Override
    protected void onAccept() {
        fireEvent(new DialogCloseEvent());

        List<Task<?>> downloads = new ArrayList<>();
        for (JFXCheckBox box : checkboxes) {
            if (!box.isDisabled() && box.isSelected()) {
                downloads.add(JavaManager.getDownloadJavaTask(platform, (Integer) box.getUserData()));
            }
        }
        if (downloads.isEmpty()) {
            return;
        }

        Task<?> task = Task.allOf(downloads).whenComplete(Schedulers.javafx(), (result, exception) -> {
            if (exception != null) {
                LOG.warning("Failed to download Java", exception);
                Controllers.dialog(exception.getMessage() == null ? exception.toString() : exception.getMessage(),
                        i18n("install.failed"));
            }
        });

        Controllers.taskDialog(task, i18n("download.java.process"), TaskCancellationAction.NORMAL);
    }
}
