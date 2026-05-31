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
import determination.xenon.mindustry.mod.MindustryLocalMod;
import determination.xenon.mindustry.mod.MindustryModManager;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.AdvancedListItem;
import determination.xenon.ui.construct.MessageDialogPane;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/** Per-instance Mindustry mod manager — replaces HMCL's ModListPage for Mindustry instances. */
public final class MindustryModListPane extends BorderPane {

    private final Path modsDir;
    private final MindustryModManager manager;
    private final Label status = new Label();
    private final VBox listBox = new VBox(2);
    private final ScrollPane scroll = new ScrollPane(listBox);

    public MindustryModListPane(Path dataDir) {
        this.modsDir = dataDir.resolve("mods");
        this.manager = new MindustryModManager(modsDir);
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.mindustry.modlist.title"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.mindustry.modlist.hint", modsDir.toString()));
        hint.setWrapText(true);

        JFXButton refresh = FXUtils.newRaisedButton(i18n("button.refresh"));
        refresh.setOnAction(e -> reload());
        JFXButton install = FXUtils.newRaisedButton(i18n("xenon.mindustry.modlist.install"));
        install.setOnAction(e -> chooseAndInstall());
        JFXButton openFolder = FXUtils.newRaisedButton(i18n("folder.mod"));
        openFolder.setOnAction(e -> FXUtils.openFolder(modsDir));

        HBox toolbar = new HBox(8, refresh, install, openFolder);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, title, hint, toolbar, status);
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        FXUtils.smoothScrolling(scroll);
        setCenter(scroll);

        reload();
    }

    private void reload() {
        double keepV = scroll.getVvalue();
        status.setText(i18n("xenon.mindustry.modlist.loading"));
        listBox.getChildren().clear();
        Schedulers.io().execute(() -> {
            List<MindustryLocalMod> mods = manager.scan();
            Platform.runLater(() -> {
                populate(mods);
                Platform.runLater(() -> scroll.setVvalue(keepV));
            });
        });
    }

    private void populate(List<MindustryLocalMod> mods) {
        listBox.getChildren().clear();
        status.setText(i18n("xenon.mindustry.modlist.count", mods.size()));
        if (mods.isEmpty()) {
            Label empty = new Label(i18n("xenon.mindustry.modlist.empty"));
            empty.setPadding(new Insets(8));
            listBox.getChildren().add(empty);
            return;
        }
        for (MindustryLocalMod mod : mods) {
            listBox.getChildren().add(buildRow(mod));
        }
    }

    private AdvancedListItem buildRow(MindustryLocalMod mod) {
        AdvancedListItem item = new AdvancedListItem();
        item.setLeftIcon(mod.isJava() ? SVG.DEPLOYED_CODE : SVG.EXTENSION);
        String label = mod.displayName();
        if (mod.getVersion() != null && !mod.getVersion().isBlank()) label += "  v" + mod.getVersion();
        if (!mod.isEnabled()) label += "  [" + i18n("xenon.mindustry.modlist.disabled") + "]";
        item.setTitle(label);

        StringBuilder subtitle = new StringBuilder();
        if (mod.getAuthor() != null && !mod.getAuthor().isBlank()) subtitle.append(mod.getAuthor());
        if (mod.getDescription() != null && !mod.getDescription().isBlank()) {
            if (subtitle.length() > 0) subtitle.append("  —  ");
            String desc = mod.getDescription().replaceAll("\\s+", " ").trim();
            if (desc.length() > 100) desc = desc.substring(0, 100) + "...";
            subtitle.append(desc);
        }
        item.setSubtitle(subtitle.toString());

        HBox actions = new HBox(4);
        JFXButton toggle = FXUtils.newRaisedButton(
                mod.isEnabled() ? i18n("xenon.mindustry.modlist.disable") : i18n("xenon.mindustry.modlist.enable"));
        toggle.setOnAction(e -> {
            try {
                if (mod.isEnabled()) manager.disable(mod); else manager.enable(mod);
                reload();
            } catch (IOException ex) {
                showError(ex);
            }
        });
        JFXButton delete = FXUtils.newRaisedButton(i18n("button.delete"));
        delete.setOnAction(e -> {
            try {
                manager.delete(mod);
                reload();
            } catch (IOException ex) {
                showError(ex);
            }
        });
        actions.getChildren().setAll(toggle, delete);
        item.setRightGraphic(actions);
        return item;
    }

    private void chooseAndInstall() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("xenon.mindustry.modlist.install"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Mindustry mod (*.jar, *.zip)", "*.jar", "*.zip"));
        java.io.File f = FXUtils.showOpenDialog(chooser, Controllers.getStage());
        if (f == null) return;
        Schedulers.io().execute(() -> {
            try {
                Files.createDirectories(modsDir);
                manager.install(f.toPath());
                Platform.runLater(this::reload);
            } catch (IOException ex) {
                LOG.warning("Failed to install mod " + f, ex);
                Platform.runLater(() -> showError(ex));
            }
        });
    }

    private void showError(Throwable ex) {
        Controllers.dialog(ex.getMessage(), i18n("message.error"), MessageDialogPane.MessageType.ERROR);
    }
}
