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
package determination.xenon.download.liteloader;

import determination.xenon.download.DefaultDependencyManager;
import determination.xenon.download.LibraryAnalyzer;
import determination.xenon.download.RemoteVersion;
import determination.xenon.game.Library;
import determination.xenon.game.Version;
import determination.xenon.task.Task;

import java.util.Collection;
import java.util.List;

public class LiteLoaderRemoteVersion extends RemoteVersion {
    private final String tweakClass;
    private final Collection<Library> libraries;

    /**
     * Constructor.
     *
     * @param gameVersion the Minecraft version that this remote version suits.
     * @param selfVersion the version string of the remote version.
     * @param urls        the installer or universal jar original URL.
     */
    LiteLoaderRemoteVersion(String gameVersion, String selfVersion, Type type, List<String> urls, String tweakClass, Collection<Library> libraries) {
        super(LibraryAnalyzer.LibraryType.LITELOADER.getPatchId(), gameVersion, selfVersion, null, type, urls);

        this.tweakClass = tweakClass;
        this.libraries = libraries;
    }

    public Collection<Library> getLibraries() {
        return libraries;
    }

    public String getTweakClass() {
        return tweakClass;
    }

    @Override
    public Task<Version> getInstallTask(DefaultDependencyManager dependencyManager, Version baseVersion) {
        return new LiteLoaderInstallTask(dependencyManager, baseVersion, this);
    }
}
