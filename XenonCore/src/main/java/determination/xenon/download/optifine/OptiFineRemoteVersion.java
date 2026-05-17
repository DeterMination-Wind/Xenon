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
package determination.xenon.download.optifine;

import determination.xenon.download.DefaultDependencyManager;
import determination.xenon.download.LibraryAnalyzer;
import determination.xenon.download.RemoteVersion;
import determination.xenon.game.Version;
import determination.xenon.task.Task;

import java.util.List;

public class OptiFineRemoteVersion extends RemoteVersion {

    public OptiFineRemoteVersion(String gameVersion, String selfVersion, List<String> urls, boolean snapshot) {
        super(LibraryAnalyzer.LibraryType.OPTIFINE.getPatchId(), gameVersion, selfVersion, null, snapshot ? Type.SNAPSHOT : Type.RELEASE, urls);
    }

    @Override
    public String getFullVersion() {
        return getGameVersion() + "_" + getSelfVersion();
    }

    @Override
    public Task<Version> getInstallTask(DefaultDependencyManager dependencyManager, Version baseVersion) {
        return new OptiFineInstallTask(dependencyManager, baseVersion, this);
    }
}
