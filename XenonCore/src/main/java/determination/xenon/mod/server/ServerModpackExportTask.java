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
package determination.xenon.mod.server;

import determination.xenon.download.LibraryAnalyzer;
import determination.xenon.game.DefaultGameRepository;
import determination.xenon.mod.ModAdviser;
import determination.xenon.mod.Modpack;
import determination.xenon.mod.ModpackConfiguration;
import determination.xenon.mod.ModpackExportInfo;
import determination.xenon.task.Task;
import determination.xenon.util.DigestUtils;
import determination.xenon.util.StringUtils;
import determination.xenon.util.gson.JsonUtils;
import determination.xenon.util.io.Zipper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static determination.xenon.download.LibraryAnalyzer.LibraryType.*;
import static determination.xenon.util.logging.Logger.LOG;

public class ServerModpackExportTask extends Task<Void> {
    private final DefaultGameRepository repository;
    private final String versionId;
    private final ModpackExportInfo exportInfo;
    private final Path modpackFile;

    public ServerModpackExportTask(DefaultGameRepository repository, String version, ModpackExportInfo exportInfo, Path modpackFile) {
        this.repository = repository;
        this.versionId = version;
        this.exportInfo = exportInfo.validate();
        this.modpackFile = modpackFile;

        onDone().register(event -> {
            if (event.isFailed()) {
                try {
                    Files.deleteIfExists(modpackFile);
                } catch (IOException e) {
                    LOG.warning("Failed to delete modpack file: " + modpackFile, e);
                }
            }
        });
    }

    @Override
    public void execute() throws Exception {
        ArrayList<String> blackList = new ArrayList<>(ModAdviser.MODPACK_BLACK_LIST);
        blackList.add(versionId + ".jar");
        blackList.add(versionId + ".json");
        LOG.info("Compressing game files without some files in blacklist, including files or directories: usernamecache.json, asm, logs, backups, versions, assets, usercache.json, libraries, crash-reports, launcher_profiles.json, NVIDIA, TCNodeTracker");
        try (Zipper zip = new Zipper(modpackFile)) {
            Path runDirectory = repository.getRunDirectory(versionId);
            List<ModpackConfiguration.FileInformation> files = new ArrayList<>();
            zip.putDirectory(runDirectory, "overrides", path -> {
                if (Modpack.acceptFile(path, blackList, exportInfo.getWhitelist())) {
                    Path file = runDirectory.resolve(path);
                    if (Files.isRegularFile(file)) {
                        String relativePath = runDirectory.relativize(file).normalize().toString().replace(File.separatorChar, '/');
                        files.add(new ModpackConfiguration.FileInformation(relativePath, DigestUtils.digestToString("SHA-1", file)));
                    }
                    return true;
                } else {
                    return false;
                }
            });

            String gameVersion = repository.getGameVersion(versionId)
                    .orElseThrow(() -> new IOException("Cannot parse the version of " + versionId));
            LibraryAnalyzer analyzer = LibraryAnalyzer.analyze(repository.getResolvedPreservingPatchesVersion(versionId), gameVersion);
            List<ServerModpackManifest.Addon> addons = new ArrayList<>();
            addons.add(new ServerModpackManifest.Addon(MINECRAFT.getPatchId(), gameVersion));
            analyzer.getVersion(FORGE).ifPresent(forgeVersion ->
                    addons.add(new ServerModpackManifest.Addon(FORGE.getPatchId(), forgeVersion)));
            analyzer.getVersion(NEO_FORGE).ifPresent(neoForgeVersion ->
                    addons.add(new ServerModpackManifest.Addon(NEO_FORGE.getPatchId(), neoForgeVersion)));
            analyzer.getVersion(LITELOADER).ifPresent(liteLoaderVersion ->
                    addons.add(new ServerModpackManifest.Addon(LITELOADER.getPatchId(), liteLoaderVersion)));
            analyzer.getVersion(OPTIFINE).ifPresent(optifineVersion ->
                    addons.add(new ServerModpackManifest.Addon(OPTIFINE.getPatchId(), optifineVersion)));
            analyzer.getVersion(FABRIC).ifPresent(fabricVersion ->
                    addons.add(new ServerModpackManifest.Addon(FABRIC.getPatchId(), fabricVersion)));
            analyzer.getVersion(QUILT).ifPresent(quiltVersion ->
                    addons.add(new ServerModpackManifest.Addon(QUILT.getPatchId(), quiltVersion)));
            ServerModpackManifest manifest = new ServerModpackManifest(exportInfo.getName(), exportInfo.getAuthor(), exportInfo.getVersion(), exportInfo.getDescription(), StringUtils.removeSuffix(exportInfo.getFileApi(), "/"), files, addons);
            zip.putTextFile(JsonUtils.GSON.toJson(manifest), "server-manifest.json");
        }
    }

    public static final ModpackExportInfo.Options OPTION = new ModpackExportInfo.Options()
            .requireAuthor()
            .requireFileApi(false);
}
