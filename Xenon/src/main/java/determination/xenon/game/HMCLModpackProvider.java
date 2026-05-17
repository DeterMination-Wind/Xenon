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
package determination.xenon.game;

import com.google.gson.JsonParseException;
import kala.compress.archivers.zip.ZipArchiveReader;
import determination.xenon.download.DefaultDependencyManager;
import determination.xenon.mod.MismatchedModpackTypeException;
import determination.xenon.mod.Modpack;
import determination.xenon.mod.ModpackProvider;
import determination.xenon.mod.ModpackUpdateTask;
import determination.xenon.setting.Profile;
import determination.xenon.task.Task;
import determination.xenon.util.StringUtils;
import determination.xenon.util.gson.JsonUtils;
import determination.xenon.util.io.CompressingUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;

public final class HMCLModpackProvider implements ModpackProvider {
    public static final HMCLModpackProvider INSTANCE = new HMCLModpackProvider();

    @Override
    public String getName() {
        return "HMCL";
    }

    @Override
    public Task<?> createCompletionTask(DefaultDependencyManager dependencyManager, String version) {
        return null;
    }

    @Override
    public Task<?> createUpdateTask(DefaultDependencyManager dependencyManager, String name, Path zipFile, Modpack modpack) throws MismatchedModpackTypeException {
        if (!(modpack.getManifest() instanceof HMCLModpackManifest))
            throw new MismatchedModpackTypeException(getName(), modpack.getManifest().getProvider().getName());

        if (!(dependencyManager.getGameRepository() instanceof HMCLGameRepository repository)) {
            throw new IllegalArgumentException("HMCLModpackProvider requires HMCLGameRepository");
        }

        Profile profile = repository.getProfile();

        return new ModpackUpdateTask(dependencyManager.getGameRepository(), name, new HMCLModpackInstallTask(profile, zipFile, modpack, name));
    }

    @Override
    public Modpack readManifest(ZipArchiveReader file, Path path, Charset encoding) throws IOException, JsonParseException {
        String manifestJson = CompressingUtils.readTextZipEntry(file, "modpack.json");
        Modpack manifest = JsonUtils.fromNonNullJson(manifestJson, HMCLModpack.class).setEncoding(encoding);
        String gameJson = CompressingUtils.readTextZipEntry(file, "minecraft/pack.json");
        Version game = JsonUtils.fromNonNullJson(gameJson, Version.class);
        if (game.getJar() == null)
            if (StringUtils.isBlank(manifest.getVersion()))
                throw new JsonParseException("Cannot recognize the game version of modpack " + file + ".");
            else
                manifest.setManifest(HMCLModpackManifest.INSTANCE);
        else
            manifest.setManifest(HMCLModpackManifest.INSTANCE).setGameVersion(game.getJar());
        return manifest;
    }

    private final static class HMCLModpack extends Modpack {
        @Override
        public Task<?> getInstallTask(DefaultDependencyManager dependencyManager, Path zipFile, String name, String iconUrl) {
            return new HMCLModpackInstallTask(((HMCLGameRepository) dependencyManager.getGameRepository()).getProfile(), zipFile, this, name);
        }
    }

}
