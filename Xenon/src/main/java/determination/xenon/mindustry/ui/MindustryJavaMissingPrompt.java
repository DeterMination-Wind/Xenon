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
import determination.xenon.mindustry.MindustryJavaMissingException;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.ui.main.LauncherSettingsPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import static determination.xenon.util.i18n.I18n.i18n;

/// Guides the user to install a Java runtime when a Mindustry launch fails
/// because the selected executable does not exist.
///
/// The dialog names the missing path so a misconfigured instance javaHome is
/// recognizable, and its single action jumps to Settings → Java management and
/// opens the runtime download dialog. It is shown for explicit launch attempts
/// only; background work keeps logging the failure.
@NotNullByDefault
public final class MindustryJavaMissingPrompt {

    private MindustryJavaMissingPrompt() {
    }

    /// Shows the guidance dialog when `throwable` signals a missing runtime.
    ///
    /// Must be called on the JavaFX thread; the caller is responsible for
    /// keeping the generic error dialog away when this returns `true`.
    ///
    /// @param throwable the failure thrown by a launch attempt, or `null`
    /// @return `true` when the guidance dialog was shown
    public static boolean showIfMissing(@Nullable Throwable throwable) {
        MindustryJavaMissingException missing = findMissingJava(throwable);
        if (missing == null) {
            return false;
        }

        JFXButton downloadButton = new JFXButton(i18n("xenon.mindustry.java.missing.action"));
        downloadButton.getStyleClass().add("dialog-accept");
        downloadButton.setOnAction(e -> {
            LauncherSettingsPage page = Controllers.getSettingsPage();
            page.showJavaManagement();
            Controllers.navigate(page);
        });
        Controllers.confirmAction(
                i18n("xenon.mindustry.java.missing.message",
                        missing.getJavaExecutable().toString()),
                i18n("xenon.mindustry.java.missing.title"),
                MessageDialogPane.MessageType.WARNING,
                downloadButton);
        return true;
    }

    /// Walks the cause chain looking for a missing-runtime failure.
    ///
    /// @param throwable the failure to inspect, or `null`
    /// @return the matching exception, or `null` when the chain has none
    static @Nullable MindustryJavaMissingException findMissingJava(@Nullable Throwable throwable) {
        Throwable current = throwable;
        // Guard against self-referencing cause chains with a fixed depth.
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof MindustryJavaMissingException missing) {
                return missing;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }
}
