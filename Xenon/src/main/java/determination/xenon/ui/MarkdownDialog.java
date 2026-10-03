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
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.ui.construct.DialogCloseEvent;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jetbrains.annotations.Nullable;

import static determination.xenon.ui.FXUtils.onEscPressed;
import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Dialog that renders a Markdown document, such as GitHub release notes.
///
/// Rendering runs off the FX thread; images referenced by the document are
/// resolved against `https://github.com/` so relative asset URLs in release
/// notes load correctly through {@link HTMLRenderer}.
public final class MarkdownDialog extends JFXDialogLayout {

    /// Creates a dialog showing `markdown` under `title`.
    ///
    /// @param title    dialog heading; falls back to a generic release-notes
    ///                 title when blank
    /// @param markdown Markdown source to render
    public MarkdownDialog(@Nullable String title, String markdown) {
        maxWidthProperty().bind(Controllers.getScene().widthProperty().multiply(0.7));
        maxHeightProperty().bind(Controllers.getScene().heightProperty().multiply(0.7));

        setHeading(new Label(title == null || title.isBlank()
                ? i18n("download.release_notes")
                : title));
        setBody(new JFXSpinner());

        Task.supplyAsync(Schedulers.io(), () -> {
            if (markdown == null || markdown.isBlank()) {
                return null;
            }
            Document document = Jsoup.parse(Markdown.toHtml(markdown), "https://github.com/");
            HTMLRenderer renderer = new HTMLRenderer(uri -> {
                LOG.info("Open link: " + uri);
                FXUtils.openLink(uri.toString());
            });
            renderer.appendNode(document.body());
            renderer.mergeLineBreaks();
            return renderer.render();
        }).whenComplete(Schedulers.javafx(), (result, exception) -> {
            if (exception == null && result != null) {
                ScrollPane scrollPane = new ScrollPane(result);
                scrollPane.setFitToWidth(true);
                FXUtils.smoothScrolling(scrollPane);
                setBody(scrollPane);
            } else {
                if (exception != null) {
                    LOG.warning("Failed to render Markdown dialog", exception);
                }
                setBody(new Label(i18n("download.release_notes.empty")));
            }
        }).start();

        JFXButton closeButton = new JFXButton(i18n("button.close"));
        closeButton.getStyleClass().add("dialog-accept");
        closeButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));
        setActions(closeButton);
        onEscPressed(this, closeButton::fire);
    }
}
