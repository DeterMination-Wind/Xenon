/*
 * Xenon Launcher
 * Copyright (C) 2022-2026  Xenon contributors
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
package determination.xenon.download.legacyfabric;

import determination.xenon.download.DownloadProvider;
import determination.xenon.download.VersionList;
import determination.xenon.mod.RemoteMod;
import determination.xenon.mod.modrinth.ModrinthRemoteModRepository;
import determination.xenon.task.Task;
import determination.xenon.util.Lang;

import java.util.Collections;

public class LegacyFabricAPIVersionList extends VersionList<LegacyFabricAPIRemoteVersion> {

    private final DownloadProvider downloadProvider;

    public LegacyFabricAPIVersionList(DownloadProvider downloadProvider) {
        this.downloadProvider = downloadProvider;
    }

    @Override
    public boolean hasType() {
        return false;
    }

    @Override
    public Task<?> refreshAsync() {
        return Task.runAsync(() -> {
            for (RemoteMod.Version modVersion : Lang.toIterable(ModrinthRemoteModRepository.MODS.getRemoteVersionsById(downloadProvider, "legacy-fabric-api"))) {
                for (String gameVersion : modVersion.getGameVersions()) {
                    versions.put(gameVersion, new LegacyFabricAPIRemoteVersion(gameVersion, modVersion.getVersion(), modVersion.getName(), modVersion.getDatePublished(), modVersion,
                            Collections.singletonList(modVersion.getFile().getUrl())));
                }
            }
        });
    }
}
