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
package determination.xenon.download.quilt;

import determination.xenon.download.DownloadProvider;
import determination.xenon.download.VersionList;
import determination.xenon.task.Task;
import determination.xenon.util.gson.JsonUtils;
import determination.xenon.util.io.NetworkUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static determination.xenon.util.gson.JsonUtils.listTypeOf;

public final class QuiltVersionList extends VersionList<QuiltRemoteVersion> {
    private final DownloadProvider downloadProvider;

    public QuiltVersionList(DownloadProvider downloadProvider) {
        this.downloadProvider = downloadProvider;
    }

    @Override
    public boolean hasType() {
        return false;
    }

    @Override
    public Task<?> refreshAsync() {
        return Task.runAsync(() -> {
            List<String> gameVersions = getGameVersions(GAME_META_URL);
            List<String> loaderVersions = getGameVersions(LOADER_META_URL);

            lock.writeLock().lock();

            try {
                for (String gameVersion : gameVersions)
                    for (String loaderVersion : loaderVersions)
                        versions.put(gameVersion, new QuiltRemoteVersion(gameVersion, loaderVersion,
                                Collections.singletonList(getLaunchMetaUrl(gameVersion, loaderVersion))));
            } finally {
                lock.writeLock().unlock();
            }
        });
    }

    private static final String LOADER_META_URL = "https://meta.quiltmc.org/v3/versions/loader";
    private static final String GAME_META_URL = "https://meta.quiltmc.org/v3/versions/game";

    private List<String> getGameVersions(String metaUrl) throws IOException {
        String json = NetworkUtils.doGet(downloadProvider.injectURLWithCandidates(metaUrl));
        return JsonUtils.GSON.fromJson(json, listTypeOf(GameVersion.class))
                .stream().map(GameVersion::getVersion).collect(Collectors.toList());
    }

    private static String getLaunchMetaUrl(String gameVersion, String loaderVersion) {
        return String.format("https://meta.quiltmc.org/v3/versions/loader/%s/%s", gameVersion, loaderVersion);
    }

    private static class GameVersion {
        private final String version;
        private final String maven;
        private final boolean stable;

        public GameVersion() {
            this("", null, false);
        }

        public GameVersion(String version, String maven, boolean stable) {
            this.version = version;
            this.maven = maven;
            this.stable = stable;
        }

        public String getVersion() {
            return version;
        }

        @Nullable
        public String getMaven() {
            return maven;
        }

        public boolean isStable() {
            return stable;
        }
    }
}
