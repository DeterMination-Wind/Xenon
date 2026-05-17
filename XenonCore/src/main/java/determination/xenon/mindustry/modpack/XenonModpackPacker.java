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
import com.google.gson.GsonBuilder;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.VersionVariant;
import determination.xenon.mindustry.XenonGameRepository;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static determination.xenon.util.logging.Logger.LOG;

/**
 * Pack a Mindustry instance (jar metadata + mods/ + saves/ + schematics/
 * + settings.bin) into a single zip described by a top-level
 * {@code xenon-modpack.json}. The accompanying
 * {@link XenonModpackInstaller} can replay the zip onto a fresh
 * version-root.
 *
 * <p>The format is intentionally simpler than HMCL's: no Curse / Modrinth
 * indirection, all bundled bytes live inside the zip. {@code source}
 * values in the JSON are advisory only — if a future installer wants to
 * skip downloading bundled blobs and pull from GitHub instead, it can.
 * </p>
 */
public final class XenonModpackPacker {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static final String MANIFEST = "xenon-modpack.json";

    private XenonModpackPacker() {
    }

    /** Pack the given version into {@code zipFile}. Returns the manifest written. */
    public static XenonModpackManifest pack(XenonGameRepository repo,
                                            MindustryVersion version,
                                            Path zipFile) throws IOException {
        Path versionRoot = repo.getVersionRoot(version.getId());
        Path dataDir = version.resolveDataDir(versionRoot);

        XenonModpackManifest manifest = new XenonModpackManifest();
        manifest.name = version.getName();
        manifest.version = "1";
        manifest.variant = version.getVariant();
        manifest.minBuild = version.getBuild();
        manifest.exportedAt = Instant.now().toString();

        Files.createDirectories(zipFile.getParent() != null ? zipFile.getParent() : Path.of("."));
        try (OutputStream os = Files.newOutputStream(zipFile);
             ZipOutputStream zip = new ZipOutputStream(os)) {

            // Manifest is written last with all collected files; for now
            // reserve space by skipping the entry at the start.

            for (String dir : List.of("mods", "saves", "schematics", "maps")) {
                Path src = dataDir.resolve(dir);
                if (!Files.isDirectory(src)) continue;
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) {
                    for (Path file : ds) {
                        if (Files.isRegularFile(file)) {
                            byte[] bytes = Files.readAllBytes(file);
                            String entryName = dir + "/" + file.getFileName();
                            zip.putNextEntry(new ZipEntry(entryName));
                            zip.write(bytes);
                            zip.closeEntry();
                            manifest.files.add(new XenonModpackManifest.Entry(
                                    entryName, bytes.length, sha1(bytes), "bundled"));
                        }
                    }
                }
            }

            Path settings = dataDir.resolve("settings.bin");
            if (Files.isRegularFile(settings)) {
                byte[] bytes = Files.readAllBytes(settings);
                zip.putNextEntry(new ZipEntry("settings.bin"));
                zip.write(bytes);
                zip.closeEntry();
                manifest.files.add(new XenonModpackManifest.Entry(
                        "settings.bin", bytes.length, sha1(bytes), "bundled"));
            }

            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        LOG.info("Xenon Modpack written: " + zipFile + " (" + manifest.files.size() + " entries)");
        return manifest;
    }

    private static String sha1(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] dig = md.digest(bytes);
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception ex) {
            return "";
        }
    }

    /** POJO mirroring {@code xenon-modpack.json}. Public for Gson + UI. */
    public static final class XenonModpackManifest {
        public String name;
        public String version;
        public VersionVariant variant;
        public int minBuild;
        public String exportedAt;
        public final List<Entry> files = new ArrayList<>();
        public final Map<String, String> settings = new LinkedHashMap<>();

        public static final class Entry {
            public String path;
            public long size;
            public String sha1;
            public String source; // "bundled" | "github" | "url"

            public Entry() {
            }

            public Entry(String path, long size, String sha1, String source) {
                this.path = path;
                this.size = size;
                this.sha1 = sha1;
                this.source = source;
            }
        }
    }
}
