/*
 * Xenon Launcher
 * Copyright (C) 2020-2026  Xenon contributors
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

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.Skin;
import determination.xenon.game.World;
import determination.xenon.task.Task;
import determination.xenon.ui.wizard.WizardSinglePage;
import determination.xenon.util.i18n.I18n;

import java.nio.file.Path;
import java.nio.file.Paths;

import static determination.xenon.util.i18n.I18n.i18n;

public class WorldExportPage extends WizardSinglePage {
    private final StringProperty path = new SimpleStringProperty();
    private final StringProperty gameVersion = new SimpleStringProperty();
    private final StringProperty worldName = new SimpleStringProperty();
    private final World world;

    public WorldExportPage(World world, Path export, Runnable onFinish) {
        super(onFinish);

        this.world = world;

        path.set(export.toString());
        if (world.getGameVersion() != null)
            gameVersion.set(I18n.getDisplayVersion(world.getGameVersion()));
        worldName.set(world.getWorldName());
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new WorldExportPageSkin(this);
    }

    public StringProperty pathProperty() {
        return path;
    }

    public StringProperty gameVersionProperty() {
        return gameVersion;
    }

    public StringProperty worldNameProperty() {
        return worldName;
    }

    public void export() {
        onFinish.run();
    }

    @Override
    public String getTitle() {
        return i18n("world.export.wizard", world.getFileName());
    }

    @Override
    protected Object finish() {
        return Task.runAsync(i18n("world.export.wizard", worldName.get()), () -> world.export(Paths.get(path.get()), worldName.get()));
    }
}
