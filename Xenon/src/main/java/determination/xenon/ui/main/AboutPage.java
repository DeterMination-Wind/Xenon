/*
 * Xenon Launcher
 * Copyright (C) 2022-2026  Xenon contributors
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

import com.google.gson.*;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.layout.VBox;
import determination.xenon.Metadata;
import determination.xenon.theme.Themes;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.WeakListenerHolder;
import determination.xenon.ui.construct.ComponentList;
import determination.xenon.ui.construct.LineButton;
import determination.xenon.ui.construct.SpinnerPane;
import determination.xenon.util.gson.JsonUtils;

import java.io.IOException;
import java.io.InputStream;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

public final class AboutPage extends SpinnerPane {

    private final WeakListenerHolder holder = new WeakListenerHolder();

    public AboutPage() {
        VBox content = new VBox();
        content.getStyleClass().add("spinner-pane-content");
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        FXUtils.smoothScrolling(scrollPane);
        setContent(scrollPane);

        ComponentList about = new ComponentList();
        {
            var launcher = LineButton.createExternalLinkButton(Metadata.PUBLISH_URL);
            launcher.setLargeTitle(true);
            launcher.setLeading(FXUtils.newBuiltinImage("/assets/img/icon.png"));
            launcher.setTitle(Metadata.FULL_NAME);
            launcher.setSubtitle(Metadata.VERSION);

            var bilibili = LineButton.createExternalLinkButton(Metadata.GROUPS_URL);
            bilibili.setLargeTitle(true);
            bilibili.setLeading(FXUtils.newBuiltinImage("/assets/img/yellow_fish.png"));
            bilibili.setTitle("Xenon Launcher");
            bilibili.setSubtitle(i18n("xenon.about.bilibili"));

            var qq = new LineButton();
            qq.setLargeTitle(true);
            qq.setTitle(i18n("xenon.about.qq_group"));
            qq.setSubtitle(Metadata.QQ_GROUP);

            var github = LineButton.createExternalLinkButton(Metadata.PUBLISH_URL);
            github.setLargeTitle(true);
            github.setTitle(i18n("xenon.about.github"));
            github.setSubtitle(Metadata.PUBLISH_URL);

            var upstream = LineButton.createExternalLinkButton("https://github.com/HMCL-dev/HMCL");
            upstream.setLargeTitle(true);
            upstream.setTitle("HMCL");
            upstream.setSubtitle(i18n("xenon.about.upstream"));

            about.getContent().setAll(launcher, bilibili, qq, github, upstream);
        }

        ComponentList thanks = loadIconedTwoLineList("/assets/about/thanks.json");

        ComponentList deps = loadIconedTwoLineList("/assets/about/deps.json");

        ComponentList legal = new ComponentList();
        {
            var copyright = new LineButton();
            copyright.setLargeTitle(true);
            copyright.setTitle(i18n("about.copyright"));
            copyright.setSubtitle(i18n("about.copyright.statement"));

            var claim = LineButton.createExternalLinkButton(Metadata.EULA_URL);
            claim.setLargeTitle(true);
            claim.setTitle(i18n("about.claim"));
            claim.setSubtitle(i18n("about.claim.statement"));

            var openSource = LineButton.createExternalLinkButton(Metadata.PUBLISH_URL);
            openSource.setLargeTitle(true);
            openSource.setTitle(i18n("about.open_source"));
            openSource.setSubtitle(i18n("about.open_source.statement"));

            legal.getContent().setAll(copyright, claim, openSource);
        }

        content.getChildren().setAll(
                ComponentList.createComponentListTitle(i18n("about")),
                about,
                ComponentList.createComponentListTitle(i18n("about.thanks_to")),
                thanks,
                ComponentList.createComponentListTitle(i18n("about.dependency")),
                deps,
                ComponentList.createComponentListTitle(i18n("about.legal")),
                legal
        );
    }

    private static Image loadImage(String url) {
        return url.startsWith("/")
                ? FXUtils.newBuiltinImage(url)
                : new Image(url);
    }

    private ComponentList loadIconedTwoLineList(String path) {
        ComponentList componentList = new ComponentList();

        InputStream input = FXUtils.class.getResourceAsStream(path);
        if (input == null) {
            LOG.warning("Resources not found: " + path);
            return componentList;
        }

        try {
            JsonArray array = JsonUtils.fromJsonFully(input, JsonArray.class);

            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();

                var button = new LineButton();
                button.setLargeTitle(true);

                if (obj.get("externalLink") instanceof JsonPrimitive externalLink) {
                    button.setTrailingIcon(SVG.OPEN_IN_NEW);

                    String link = externalLink.getAsString();
                    button.setOnAction(event -> FXUtils.openLink(link));
                }

                if (obj.has("image")) {
                    JsonElement image = obj.get("image");
                    if (image.isJsonPrimitive()) {
                        button.setLeading(loadImage(image.getAsString()));
                    } else if (image.isJsonObject()) {
                        holder.add(FXUtils.onWeakChangeAndOperate(Themes.darkModeProperty(), darkMode -> {
                            button.setLeading(darkMode
                                    ? loadImage(image.getAsJsonObject().get("dark").getAsString())
                                    : loadImage(image.getAsJsonObject().get("light").getAsString())
                            );
                        }));
                    }
                }

                if (obj.get("title") instanceof JsonPrimitive title)
                    button.setTitle(title.getAsString());
                else if (obj.get("titleLocalized") instanceof JsonPrimitive titleLocalized)
                    button.setTitle(i18n(titleLocalized.getAsString()));

                if (obj.get("subtitle") instanceof JsonPrimitive subtitle)
                    button.setSubtitle(subtitle.getAsString());
                else if (obj.get("subtitleLocalized") instanceof JsonPrimitive subtitleLocalized)
                    button.setSubtitle(i18n(subtitleLocalized.getAsString()));

                componentList.getContent().add(button);
            }
        } catch (IOException | JsonParseException e) {
            LOG.warning("Failed to load list: " + path, e);
        }

        return componentList;
    }
}
