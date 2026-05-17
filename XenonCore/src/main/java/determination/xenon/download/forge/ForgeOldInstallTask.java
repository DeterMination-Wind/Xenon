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
package determination.xenon.download.forge;

import determination.xenon.download.ArtifactMalformedException;
import determination.xenon.download.DefaultDependencyManager;
import determination.xenon.download.LibraryAnalyzer;
import determination.xenon.game.Library;
import determination.xenon.game.Version;
import determination.xenon.task.Task;
import determination.xenon.util.gson.JsonUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class ForgeOldInstallTask extends Task<Version> {

    private final DefaultDependencyManager dependencyManager;
    private final Version version;
    private final Path installer;
    private final String selfVersion;
    private final List<Task<?>> dependencies = new ArrayList<>(1);

    ForgeOldInstallTask(DefaultDependencyManager dependencyManager, Version version, String selfVersion, Path installer) {
        this.dependencyManager = dependencyManager;
        this.version = version;
        this.installer = installer;
        this.selfVersion = selfVersion;

        setSignificance(TaskSignificance.MAJOR);
    }

    @Override
    public List<Task<?>> getDependencies() {
        return dependencies;
    }

    @Override
    public boolean doPreExecute() {
        return true;
    }

    @Override
    public void execute() throws Exception {
        try (ZipFile zipFile = new ZipFile(installer.toFile())) {
            InputStream stream = zipFile.getInputStream(zipFile.getEntry("install_profile.json"));
            if (stream == null)
                throw new ArtifactMalformedException("Malformed forge installer file, install_profile.json does not exist.");
            ForgeInstallProfile installProfile = JsonUtils.fromNonNullJsonFully(stream, ForgeInstallProfile.class);

            // unpack the universal jar in the installer file.
            Library forgeLibrary = new Library(installProfile.getInstall().getPath());
            Path forgeFile = dependencyManager.getGameRepository().getLibraryFile(version, forgeLibrary);
            Files.createDirectories(forgeFile.getParent());

            ZipEntry forgeEntry = zipFile.getEntry(installProfile.getInstall().getFilePath());
            try (InputStream is = zipFile.getInputStream(forgeEntry);
                 OutputStream os = Files.newOutputStream(forgeFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                is.transferTo(os);
            }

            setResult(installProfile.getVersionInfo()
                    .setPriority(Version.PRIORITY_LOADER)
                    .setId(LibraryAnalyzer.LibraryType.FORGE.getPatchId())
                    .setVersion(selfVersion));
            dependencies.add(dependencyManager.checkLibraryCompletionAsync(installProfile.getVersionInfo(), true));
        } catch (ZipException ex) {
            throw new ArtifactMalformedException("Malformed forge installer file", ex);
        }
    }
}
