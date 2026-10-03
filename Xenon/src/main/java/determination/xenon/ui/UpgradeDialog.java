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
package determination.xenon.ui;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXSpinner;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import determination.xenon.Metadata;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.ui.construct.DialogCloseEvent;
import determination.xenon.ui.construct.JFXHyperlink;
import determination.xenon.upgrade.RemoteVersion;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import static determination.xenon.ui.FXUtils.onEscPressed;
import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

public final class UpgradeDialog extends JFXDialogLayout {

    public UpgradeDialog(RemoteVersion remoteVersion, Runnable updateRunnable) {
        maxWidthProperty().bind(Controllers.getScene().widthProperty().multiply(0.7));
        maxHeightProperty().bind(Controllers.getScene().heightProperty().multiply(0.7));

        setHeading(new Label(i18n("update.changelog")));
        setBody(new JFXSpinner());

        String releaseUrl = Metadata.DOWNLOAD_URL;

        Task.supplyAsync(Schedulers.io(), () -> {
            String body = remoteVersion.body();
            if (body == null || body.isBlank())
                return null;

            String html = Markdown.toHtml(body);
            // GitHub release notes use repo-relative image/link URLs; a base
            // URI lets HTMLRenderer resolve them to absolute ones.
            Document document = Jsoup.parse(html, "https://github.com/");
            HTMLRenderer renderer = new HTMLRenderer(uri -> {
                LOG.info("Open link: " + uri);
                FXUtils.openLink(uri.toString());
            });
            renderer.appendNode(document.body());
            renderer.mergeLineBreaks();
            return renderer.render();
        }).whenComplete(Schedulers.javafx(), (result, exception) -> {
            if (exception == null) {
                if (result != null) {
                    ScrollPane scrollPane = new ScrollPane(result);
                    scrollPane.setFitToWidth(true);
                    FXUtils.smoothScrolling(scrollPane);
                    setBody(scrollPane);
                }
            } else {
                LOG.warning("Failed to render changelog", exception);
            }
        }).start();

        JFXHyperlink openInBrowser = new JFXHyperlink(i18n("web.view_in_browser"));
        openInBrowser.setExternalLink(releaseUrl);

        JFXButton updateButton = new JFXButton(i18n("update.accept"));
        updateButton.getStyleClass().add("dialog-accept");
        updateButton.setOnAction(e -> updateRunnable.run());

        JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
        cancelButton.getStyleClass().add("dialog-cancel");
        cancelButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));

        setActions(openInBrowser, updateButton, cancelButton);
        onEscPressed(this, cancelButton::fire);
    }
}
