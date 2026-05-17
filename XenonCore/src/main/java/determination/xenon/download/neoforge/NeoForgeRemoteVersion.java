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
package determination.xenon.download.neoforge;

import determination.xenon.download.DefaultDependencyManager;
import determination.xenon.download.LibraryAnalyzer;
import determination.xenon.download.RemoteVersion;
import determination.xenon.game.Version;
import determination.xenon.task.Task;

import java.util.List;

public class NeoForgeRemoteVersion extends RemoteVersion {
    public NeoForgeRemoteVersion(String gameVersion, String selfVersion, List<String> urls) {
        super(LibraryAnalyzer.LibraryType.NEO_FORGE.getPatchId(), gameVersion, selfVersion, null, getType(selfVersion), urls);
    }

    @Override
    public Task<Version> getInstallTask(DefaultDependencyManager dependencyManager, Version baseVersion) {
        return new NeoForgeInstallTask(dependencyManager, baseVersion, this);
    }

    private static Type getType(String version) {
        return version.contains("beta") || version.contains("alpha") ? Type.SNAPSHOT : Type.RELEASE;
    }

    public static String normalize(String version) {
        if (version.startsWith("1.20.1-")) {
            if (version.startsWith("forge-", "1.20.1-".length())) {
                return version.substring("1.20.1-forge-".length());
            } else {
                return version.substring("1.20.1-".length());
            }
        } else {
            return version;
        }
    }
}
