/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
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
package determination.xenon.mindustry.modpack;

import com.google.gson.Gson;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.XenonGameRepository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static determination.xenon.util.logging.Logger.LOG;

/**
 * Replay a {@link XenonModpackPacker}-produced zip onto a fresh
 * Mindustry version directory. Manifest-listed files are extracted
 * verbatim; the launcher hands the user back a ready-to-launch
 * {@link MindustryVersion}.
 */
public final class XenonModpackInstaller {
    private static final Gson GSON = new Gson();

    private XenonModpackInstaller() {
    }

    /**
     * Install {@code zipFile} into {@code versions/<targetId>/}. The
     * caller-provided {@link XenonGameRepository} is updated with a
     * fresh {@link MindustryVersion} entry.
     */
    public static MindustryVersion install(XenonGameRepository repo,
                                           Path zipFile,
                                           String targetId) throws IOException {
        Path versionRoot = repo.getVersionRoot(targetId);
        Files.createDirectories(versionRoot);
        Path dataDir = versionRoot.resolve(".data");
        Files.createDirectories(dataDir);

        XenonModpackPacker.XenonModpackManifest manifest = null;

        try (InputStream is = Files.newInputStream(zipFile);
             ZipInputStream zip = new ZipInputStream(is)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (name.equals(XenonModpackPacker.MANIFEST)) {
                    String json = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    manifest = GSON.fromJson(json, XenonModpackPacker.XenonModpackManifest.class);
                    continue;
                }
                Path dst = dataDir.resolve(name).normalize();
                if (!dst.startsWith(dataDir)) {
                    LOG.warning("Skipping zip-slip entry: " + name);
                    continue;
                }
                Files.createDirectories(dst.getParent());
                Files.copy(zip, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        if (manifest == null) {
            throw new IOException("Modpack missing " + XenonModpackPacker.MANIFEST);
        }

        MindustryVersion v = new MindustryVersion();
        v.setId(targetId);
        v.setName(manifest.name == null ? targetId : manifest.name);
        v.setVariant(manifest.variant);
        v.setBuild(manifest.minBuild);
        v.setBuildType("custom");
        v.setJarPath(targetId + ".jar"); // user must drop a jar in afterwards
        v.setJavaReq(manifest.minBuild > 0 && manifest.minBuild < 140 ? 8 : 17);
        repo.save(v);
        return v;
    }
}
