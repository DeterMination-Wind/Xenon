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

import determination.xenon.mindustry.VersionVariant;
import determination.xenon.mindustry.install.XenonInstallWizardProvider;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.AdvancedListItem;
import determination.xenon.ui.construct.ClassTitle;
import javafx.geometry.Pos;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.Locale;

import static determination.xenon.util.i18n.I18n.i18n;

/**
 * Five-card Mindustry variant picker.
 *
 * <p>Each card uses the upstream project's official icon (bundled under
 * {@code /assets/img/mindustry/}); BE / CN-ARC are upstream forks that
 * don't ship their own icon, so they reuse the Vanilla art.</p>
 */
public final class MindustryVariantPickerPane extends ScrollPane {

    public MindustryVariantPickerPane() {
        VBox content = new VBox();
        content.getStyleClass().add("advanced-list-box-content");

        content.getChildren().add(new ClassTitle(i18n("xenon.install.variant.title").toUpperCase(Locale.ROOT)));

        for (VersionVariant variant : VersionVariant.values()) {
            if (variant == VersionVariant.CUSTOM) continue;
            AdvancedListItem item = new AdvancedListItem();
            item.setLeftGraphic(buildIconNode(variant));
            item.setTitle(variant.getDisplayName());
            item.setSubtitle(i18n("xenon.install.variant." + variant.name().toLowerCase(Locale.ROOT) + ".desc"));
            item.setOnAction(e -> {
                XenonInstallWizardProvider provider = new XenonInstallWizardProvider();
                provider.preseedVariant(variant);
                Controllers.getDecorator().startWizard(provider, variant.getDisplayName());
            });
            content.getChildren().add(item);
        }

        // Custom variant — links to the import flow; keeps its + glyph
        // since there's no upstream project to take an icon from.
        AdvancedListItem importItem = new AdvancedListItem();
        importItem.setLeftIcon(SVG.ADD);
        importItem.setTitle(i18n("xenon.mindustry.import.title"));
        importItem.setSubtitle(i18n("xenon.install.variant.custom.desc"));
        importItem.setOnAction(e -> determination.xenon.mindustry.MindustryImportFlow.showImportAndLaunchDialog());
        content.getChildren().add(importItem);

        AdvancedListItem modpackItem = new AdvancedListItem();
        modpackItem.setLeftIcon(SVG.ARCHIVE);
        modpackItem.setTitle(i18n("modpack.task.install"));
        modpackItem.setSubtitle(i18n("modpack.choose.local.detail"));
        modpackItem.setOnAction(e -> determination.xenon.mindustry.MindustryImportFlow.showInstallModpackFileChooser());
        content.getChildren().add(modpackItem);

        setContent(content);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
        FXUtils.smoothScrolling(this);
    }

    /**
     * Wrap the variant's PNG icon in a fixed-size StackPane so the row
     * lines up with the SVG-based items elsewhere in the launcher
     * ({@link AdvancedListItem#LEFT_GRAPHIC_SIZE}).
     */
    private static javafx.scene.Node buildIconNode(VersionVariant v) {
        ImageView iv = new ImageView(iconImage(v));
        iv.setFitWidth(AdvancedListItem.LEFT_ICON_SIZE);
        iv.setFitHeight(AdvancedListItem.LEFT_ICON_SIZE);
        iv.setPreserveRatio(true);
        iv.setSmooth(true);
        StackPane wrap = new StackPane(iv);
        wrap.setPrefSize(AdvancedListItem.LEFT_GRAPHIC_SIZE, AdvancedListItem.LEFT_GRAPHIC_SIZE);
        wrap.setMinSize(AdvancedListItem.LEFT_GRAPHIC_SIZE, AdvancedListItem.LEFT_GRAPHIC_SIZE);
        wrap.setMaxSize(AdvancedListItem.LEFT_GRAPHIC_SIZE, AdvancedListItem.LEFT_GRAPHIC_SIZE);
        wrap.setMouseTransparent(true);
        BorderPane.setMargin(wrap, AdvancedListItem.LEFT_ICON_MARGIN);
        BorderPane.setAlignment(wrap, Pos.CENTER);
        return wrap;
    }

    private static Image iconImage(VersionVariant v) {
        String path;
        switch (v) {
            case VANILLA:
                path = "/assets/img/mindustry/vanilla.png";
                break;
            case BE:
                path = "/assets/img/mindustry/be.png";
                break;
            case MINDUSTRY_X:
                path = "/assets/img/mindustry/mindustry_x.png";
                break;
            case CN_ARC:
                path = "/assets/img/mindustry/cn_arc.png";
                break;
            case FOO:
                path = "/assets/img/mindustry/foo.png";
                break;
            default:
                path = "/assets/img/mindustry/vanilla.png";
                break;
        }
        return FXUtils.newBuiltinImage(path);
    }
}
