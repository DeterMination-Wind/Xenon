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

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.ComponentList;
import determination.xenon.ui.construct.JFXHyperlink;
import determination.xenon.ui.construct.SpinnerPane;

public final class HelpPage extends SpinnerPane {
    private static final String HELP_GROUP_LINK = "https://qm.qq.com/q/9t6yPU8Tjq";

    public HelpPage() {
        VBox content = new VBox();
        content.getStyleClass().add("spinner-pane-content");
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        FXUtils.smoothScrolling(scrollPane);
        setContent(scrollPane);

        ImageView groupImage = new ImageView(FXUtils.newBuiltinImage("/assets/img/help-qq.png"));
        groupImage.setFitWidth(420);
        groupImage.setPreserveRatio(true);
        groupImage.setSmooth(true);

        JFXHyperlink link = new JFXHyperlink(HELP_GROUP_LINK);
        link.setExternalLink(HELP_GROUP_LINK);
        link.setFocusTraversable(false);

        VBox cardContent = new VBox(14, groupImage, link);
        cardContent.setAlignment(Pos.CENTER);
        cardContent.setPadding(new Insets(24));

        ComponentList help = new ComponentList();
        help.getContent().setAll(cardContent);
        content.getChildren().setAll(help);
    }
}
