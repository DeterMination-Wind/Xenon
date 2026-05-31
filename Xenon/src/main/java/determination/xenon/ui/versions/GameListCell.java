/*
 * Xenon Launcher
 * Copyright (C) 2026-2026  Xenon contributors
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
package determination.xenon.ui.versions;

import com.jfoenix.controls.*;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.ListCell;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.*;
import determination.xenon.util.StringUtils;

import static determination.xenon.ui.FXUtils.determineOptimalPopupPosition;
import static determination.xenon.util.i18n.I18n.i18n;

public final class GameListCell extends ListCell<GameListItem> {

    private final Region graphic;

    private final ImageContainer imageView;
    private final TwoLineListItem content;

    private final JFXRadioButton chkSelected;
    private final JFXButton btnUpgrade;
    private final JFXButton btnLaunch;
    private final JFXButton btnManage;

    private final HBox right;

    private final StringProperty tag = new SimpleStringProperty();

    public GameListCell() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("md-list-cell");
        root.setPadding(new Insets(8, 8, 8, 0));

        RipplerContainer container = new RipplerContainer(root);
        this.graphic = container;

        {
            this.chkSelected = new JFXRadioButton() {
                @Override
                public void fire() {
                    if (!isDisable() && !isSelected()) {
                        fireEvent(new ActionEvent());
                        GameListItem item = GameListCell.this.getItem();
                        if (item != null) {
                            item.getProfile().setSelectedVersion(item.getId());
                        }
                    }
                }
            };
            root.setLeft(chkSelected);
            BorderPane.setAlignment(chkSelected, Pos.CENTER);
        }

        {
            HBox center = new HBox();
            center.setMouseTransparent(true);
            root.setCenter(center);
            center.setPrefWidth(Region.USE_PREF_SIZE);
            center.setSpacing(8);
            center.setAlignment(Pos.CENTER_LEFT);

            this.imageView = new ImageContainer(32);

            this.content = new TwoLineListItem();
            BorderPane.setAlignment(content, Pos.CENTER);

            FXUtils.onChangeAndOperate(tag, tag -> {
                content.getTags().clear();
                if (StringUtils.isNotBlank(tag))
                    content.addTag(tag);
            });

            center.getChildren().setAll(imageView, content);
        }

        {
            this.right = new HBox();
            root.setRight(right);

            right.setAlignment(Pos.CENTER_RIGHT);

            this.btnUpgrade = FXUtils.newToggleButton4(SVG.UPDATE);
            btnUpgrade.setOnAction(e -> {
                GameListItem item = this.getItem();
                if (item != null)
                    item.update();
            });
            FXUtils.installFastTooltip(btnUpgrade, i18n("version.update"));
            right.getChildren().add(btnUpgrade);

            this.btnLaunch = FXUtils.newToggleButton4(SVG.ROCKET_LAUNCH);
            btnLaunch.setOnAction(e -> {
                GameListItem item = this.getItem();
                if (item != null)
                    item.testGame();
            });
            BorderPane.setAlignment(btnLaunch, Pos.CENTER);
            FXUtils.installFastTooltip(btnLaunch, i18n("version.launch.test"));
            right.getChildren().add(btnLaunch);

            this.btnManage = FXUtils.newToggleButton4(SVG.MORE_VERT);
            btnManage.setOnAction(e -> {
                GameListItem item = this.getItem();
                if (item == null)
                    return;

                JFXPopup popup = getPopup(item);
                JFXPopup.PopupVPosition vPosition = determineOptimalPopupPosition(root, popup);
                popup.show(root, vPosition, JFXPopup.PopupHPosition.RIGHT, 0, vPosition == JFXPopup.PopupVPosition.TOP ? root.getHeight() : -root.getHeight());
            });
            BorderPane.setAlignment(btnManage, Pos.CENTER);
            FXUtils.installFastTooltip(btnManage, i18n("settings.game.management"));
            right.getChildren().add(btnManage);
        }

        root.setCursor(Cursor.HAND);
        container.setOnMouseClicked(e -> {
            GameListItem item = getItem();
            if (item == null)
                return;

            if (e.getButton() == MouseButton.PRIMARY) {
                if (e.getClickCount() == 1) {
                    item.modifyGameSettings();
                }
            } else if (e.getButton() == MouseButton.SECONDARY) {
                JFXPopup popup = getPopup(item);
                JFXPopup.PopupVPosition vPosition = determineOptimalPopupPosition(root, popup);
                popup.show(root, vPosition, JFXPopup.PopupHPosition.LEFT, e.getX(), vPosition == JFXPopup.PopupVPosition.TOP ? e.getY() : e.getY() - root.getHeight());
            }
        });
    }

    @Override
    public void updateItem(GameListItem item, boolean empty) {
        super.updateItem(item, empty);

        this.imageView.imageProperty().unbind();
        this.content.titleProperty().unbind();
        this.content.subtitleProperty().unbind();
        this.tag.unbind();
        this.right.getChildren().clear();
        this.chkSelected.selectedProperty().unbind();

        if (empty || item == null) {
            setGraphic(null);
        } else {
            setGraphic(this.graphic);

            this.chkSelected.selectedProperty().bind(item.selectedProperty());
            this.imageView.imageProperty().bind(item.imageProperty());
            this.content.titleProperty().bind(item.titleProperty());
            this.content.subtitleProperty().bind(item.subtitleProperty());
            this.tag.bind(item.tagProperty());
            if (item.canUpdate())
                this.right.getChildren().add(btnUpgrade);
            this.right.getChildren().addAll(btnLaunch, btnManage);
        }
    }

    private static JFXPopup getPopup(GameListItem item) {
        PopupMenu menu = new PopupMenu();
        JFXPopup popup = new JFXPopup(menu);

        java.util.List<javafx.scene.Node> entries = new java.util.ArrayList<>();
        entries.add(new IconedMenuItem(SVG.ROCKET_LAUNCH, i18n("version.launch.test"), item::testGame, popup));
        entries.add(new IconedMenuItem(SVG.SCRIPT, i18n("version.launch_script"), item::generateLaunchScript, popup));
        entries.add(new MenuSeparator());
        entries.add(new IconedMenuItem(SVG.SETTINGS, i18n("version.manage.manage"), item::modifyGameSettings, popup));
        entries.add(new MenuSeparator());
        entries.add(new IconedMenuItem(SVG.EDIT, i18n("version.manage.rename"), item::rename, popup));
        entries.add(new IconedMenuItem(SVG.FOLDER_COPY, i18n("version.manage.duplicate"), item::duplicate, popup));
        entries.add(new IconedMenuItem(SVG.DELETE, i18n("version.manage.remove"), item::remove, popup));
        if (item.isMindustry()) {
            entries.add(new IconedMenuItem(SVG.OUTPUT, i18n("modpack.export"), item::export, popup));
        }
        entries.add(new MenuSeparator());
        entries.add(new IconedMenuItem(SVG.FOLDER_OPEN, i18n("folder.game"), item::browse, popup));
        menu.getContent().setAll(entries);
        return popup;
    }
}
