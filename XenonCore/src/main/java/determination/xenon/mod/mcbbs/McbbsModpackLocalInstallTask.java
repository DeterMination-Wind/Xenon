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
package determination.xenon.mod.mcbbs;

import com.google.gson.JsonParseException;
import determination.xenon.download.DefaultDependencyManager;
import determination.xenon.download.GameBuilder;
import determination.xenon.game.DefaultGameRepository;
import determination.xenon.game.Version;
import determination.xenon.mod.MinecraftInstanceTask;
import determination.xenon.mod.Modpack;
import determination.xenon.mod.ModpackConfiguration;
import determination.xenon.mod.ModpackInstallTask;
import determination.xenon.task.Task;
import determination.xenon.util.gson.JsonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class McbbsModpackLocalInstallTask extends Task<Void> {

    private final DefaultDependencyManager dependencyManager;
    private final Path zipFile;
    private final Modpack modpack;
    private final McbbsModpackManifest manifest;
    private final String name;
    private final boolean update;
    private final DefaultGameRepository repository;
    private final MinecraftInstanceTask<McbbsModpackManifest> instanceTask;
    private final List<Task<?>> dependencies = new ArrayList<>(2);
    private final List<Task<?>> dependents = new ArrayList<>(4);

    public McbbsModpackLocalInstallTask(DefaultDependencyManager dependencyManager, Path zipFile, Modpack modpack, McbbsModpackManifest manifest, String name) {
        this.dependencyManager = dependencyManager;
        this.zipFile = zipFile;
        this.modpack = modpack;
        this.manifest = manifest;
        this.name = name;
        this.repository = dependencyManager.getGameRepository();
        Path run = repository.getRunDirectory(name);

        Path json = repository.getModpackConfiguration(name);
        if (repository.hasVersion(name) && Files.notExists(json))
            throw new IllegalArgumentException("Version " + name + " already exists.");
        this.update = repository.hasVersion(name);


        GameBuilder builder = dependencyManager.gameBuilder().name(name);
        for (McbbsModpackManifest.Addon addon : manifest.getAddons()) {
            builder.version(addon.getId(), addon.getVersion());
        }

        dependents.add(builder.buildAsync());
        onDone().register(event -> {
            if (event.isFailed())
                repository.removeVersionFromDisk(name);
        });

        ModpackConfiguration<McbbsModpackManifest> config = null;
        try {
            if (Files.exists(json)) {
                config = JsonUtils.fromJsonFile(json, ModpackConfiguration.typeOf(McbbsModpackManifest.class));

                if (!McbbsModpackProvider.INSTANCE.getName().equals(config.getType()))
                    throw new IllegalArgumentException("Version " + name + " is not a Mcbbs modpack. Cannot update this version.");
            }
        } catch (JsonParseException | IOException ignore) {
        }
        dependents.add(new ModpackInstallTask<>(zipFile, run, modpack.getEncoding(), Collections.singletonList("/overrides"), any -> true, config).withStage("hmcl.modpack"));
        instanceTask = new MinecraftInstanceTask<>(zipFile, modpack.getEncoding(), Collections.singletonList("/overrides"), manifest, McbbsModpackProvider.INSTANCE, modpack.getName(), modpack.getVersion(), repository.getModpackConfiguration(name));
        dependents.add(instanceTask.withStage("hmcl.modpack"));
    }

    @Override
    public List<Task<?>> getDependents() {
        return dependents;
    }

    @Override
    public List<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public void execute() throws Exception {
        Version version = repository.readVersionJson(name);
        Optional<Version> mcbbsPatch = version.getPatches().stream().filter(patch -> PATCH_NAME.equals(patch.getId())).findFirst();
        if (!update) {
            Version patch = new Version(PATCH_NAME).setLibraries(manifest.getLibraries());
            dependencies.add(repository.saveAsync(version.addPatch(patch)));
        } else if (mcbbsPatch.isPresent()) {
            // This mcbbs modpack was installed by HMCL.
            Version patch = mcbbsPatch.get().setLibraries(manifest.getLibraries());
            dependencies.add(repository.saveAsync(version.addPatch(patch)));
        } else {
            // This mcbbs modpack was installed by other launchers.
            // TODO: maintain libraries.
        }

        dependencies.add(new McbbsModpackCompletionTask(dependencyManager, name, instanceTask.getResult()));
    }

    private static final String PATCH_NAME = "mcbbs";
}
