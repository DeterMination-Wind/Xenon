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
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.XenonGameRepository;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.animation.TransitionPane;
import determination.xenon.ui.construct.AdvancedListBox;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.ui.construct.TabHeader;
import determination.xenon.ui.decorator.DecoratorAnimatedPage;
import determination.xenon.ui.decorator.DecoratorPage;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Path;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/**
 * Per-instance management page for a Mindustry version. Mirrors HMCL's
 * {@code VersionPage} layout (left sidebar + transition pane) but every
 * tab is Mindustry-shaped: Mod / Save / Schematic / Crash. The HMCL
 * VersionPage stays in place for legacy MC instances.
 */
public final class MindustryVersionPage extends DecoratorAnimatedPage implements DecoratorPage {

    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>();

    private final TransitionPane transitionPane = new TransitionPane();
    private final TabHeader tab;
    private final TabHeader.Tab<MindustryModListPane> modTab = new TabHeader.Tab<>("modTab");
    private final TabHeader.Tab<MindustrySaveListPane> saveTab = new TabHeader.Tab<>("saveTab");
    private final TabHeader.Tab<MindustrySchematicListPane> schematicTab = new TabHeader.Tab<>("schematicTab");
    private final TabHeader.Tab<MindustryCrashListPane> crashTab = new TabHeader.Tab<>("crashTab");

    private final MindustryVersion version;
    private final Path versionRoot;
    private final Path workingDirectory;
    private final Path dataDir;

    public MindustryVersionPage(MindustryVersion version) {
        this.version = version;
        XenonGameRepository repo = MindustryImportFlow.repository();
        this.versionRoot = repo.getVersionRoot(version.getId());
        this.workingDirectory = version.resolveWorkingDirectory(versionRoot);
        this.dataDir = version.resolveDataDir(versionRoot);

        modTab.setNodeSupplier(() -> new MindustryModListPane(dataDir));
        saveTab.setNodeSupplier(() -> new MindustrySaveListPane(repo, version, versionRoot));
        schematicTab.setNodeSupplier(() -> new MindustrySchematicListPane(dataDir));
        crashTab.setNodeSupplier(() -> new MindustryCrashListPane(dataDir, version.getVariant()));

        tab = new TabHeader(transitionPane, modTab, saveTab, schematicTab, crashTab);
        tab.select(modTab);

        AdvancedListBox sideBar = new AdvancedListBox()
                .startCategory(version.getName())
                .addNavigationDrawerTab(tab, modTab, i18n("xenon.mindustry.modlist.title"), SVG.EXTENSION, SVG.EXTENSION_FILL)
                .addNavigationDrawerTab(tab, saveTab, i18n("xenon.mindustry.save.title"), SVG.ARCHIVE, SVG.ARCHIVE_FILL)
                .addNavigationDrawerTab(tab, schematicTab, i18n("xenon.mindustry.schematic.title"), SVG.SCHEMA, SVG.SCHEMA_FILL)
                .addNavigationDrawerTab(tab, crashTab, i18n("xenon.mindustry.crash.title"), SVG.SCRIPT);
        VBox.setVgrow(sideBar, Priority.ALWAYS);

        AdvancedListBox toolbar = new AdvancedListBox()
                .addNavigationDrawerItem(i18n("version.launch.test"), SVG.ROCKET_LAUNCH, this::launch)
                .addNavigationDrawerItem(i18n("settings.game.exploration"), SVG.FOLDER_OPEN, () -> FXUtils.openFolder(workingDirectory))
                .addNavigationDrawerItem(i18n("modpack.export"), SVG.OUTPUT, () -> MindustryRoutes.exportModpack(version))
                .addNavigationDrawerItem(i18n("version.manage.remove"), SVG.DELETE, this::deleteVersion);
        toolbar.getStyleClass().add("advanced-list-box-clear-padding");
        FXUtils.setLimitHeight(toolbar, 40 * 4 + 12 * 2);

        setLeft(sideBar, toolbar);
        setCenter(transitionPane);

        state.set(State.fromTitle(i18n("version.manage.manage.title", version.getName()), -1));
    }

    @Override
    public ReadOnlyObjectProperty<State> stateProperty() {
        return state.getReadOnlyProperty();
    }

    private void launch() {
        MindustryRoutes.launch(version);
    }

    private void deleteVersion() {
        JFXButton confirm = new JFXButton(i18n("button.delete"));
        confirm.getStyleClass().add("dialog-error");
        confirm.setOnAction(e -> Schedulers.io().execute(() -> {
            try {
                MindustryImportFlow.repository().delete(version.getId());
                Platform.runLater(() -> {
                    Controllers.showToast(i18n("message.success"));
                    fireEvent(new determination.xenon.ui.construct.PageCloseEvent());
                });
            } catch (Throwable ex) {
                LOG.warning("Failed to delete Mindustry version " + version.getId(), ex);
                Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        }));
        Controllers.confirmAction(
                i18n("xenon.mindustry.versions.delete.confirm", version.getName()),
                i18n("message.warning"), MessageDialogPane.MessageType.WARNING, confirm);
    }
}
