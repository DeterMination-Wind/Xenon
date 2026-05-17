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
package determination.xenon.setting;

import javafx.beans.binding.Bindings;
import determination.xenon.Metadata;
import determination.xenon.game.HMCLCacheRepository;
import determination.xenon.ui.animation.AnimationUtils;
import determination.xenon.util.CacheRepository;
import determination.xenon.util.io.FileUtils;

import static determination.xenon.setting.ConfigHolder.config;

public final class Settings {

    private static Settings instance;

    public static Settings instance() {
        if (instance == null) {
            throw new IllegalStateException("Settings hasn't been initialized");
        }
        return instance;
    }

    /**
     * Should be called from {@link ConfigHolder#init()}.
     */
    static void init() {
        instance = new Settings();
    }

    private Settings() {
        DownloadProviders.init();
        ProxyManager.init();
        Accounts.init();
        Profiles.init();
        AuthlibInjectorServers.init();
        AnimationUtils.init();

        CacheRepository.setInstance(HMCLCacheRepository.REPOSITORY);
        HMCLCacheRepository.REPOSITORY.directoryProperty().bind(Bindings.createStringBinding(() -> {
            if (FileUtils.canCreateDirectory(getCommonDirectory())) {
                return getCommonDirectory();
            } else {
                return getDefaultCommonDirectory();
            }
        }, config().commonDirectoryProperty(), config().commonDirTypeProperty()));
    }

    public static String getDefaultCommonDirectory() {
        return Metadata.XENON_GLOBAL_DIRECTORY.toString();
    }

    public String getCommonDirectory() {
        switch (config().getCommonDirType()) {
            case DEFAULT:
                return getDefaultCommonDirectory();
            case CUSTOM:
                return config().getCommonDirectory();
            default:
                return null;
        }
    }
}
