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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
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
    public static final String GAME_JAR = "game.jar";
    private static final int BUFFER_SIZE = 64 * 1024;

    private XenonModpackPacker() {
    }

    /** Pack the given version into {@code zipFile}. Returns the manifest written. */
    public static XenonModpackManifest pack(XenonGameRepository repo,
                                            MindustryVersion version,
                                            Path zipFile) throws IOException {
        return pack(repo, version, zipFile, ExportMonitor.NONE);
    }

    /** Pack the given version into {@code zipFile}, reporting progress and honouring cancellation. */
    public static XenonModpackManifest pack(XenonGameRepository repo,
                                            MindustryVersion version,
                                            Path zipFile,
                                            ExportMonitor monitor) throws IOException {
        Path versionRoot = repo.getVersionRoot(version.getId());
        Path dataDir = version.resolveDataDir(versionRoot);

        XenonModpackManifest manifest = new XenonModpackManifest();
        manifest.name = version.getName();
        manifest.version = "1";
        manifest.variant = version.getVariant();
        manifest.minBuild = version.getBuild();
        manifest.exportedAt = Instant.now().toString();

        List<PendingEntry> entries = new ArrayList<>();
        Path jar = version.resolveJar(versionRoot);
        if (!Files.isRegularFile(jar)) {
            throw new IOException("Mindustry client jar not found: " + jar);
        }
        entries.add(new PendingEntry(jar, GAME_JAR, Files.size(jar)));

        for (String dir : List.of("mods", "saves", "schematics", "maps")) {
            Path src = dataDir.resolve(dir);
            if (!Files.isDirectory(src)) continue;
            try (Stream<Path> files = Files.walk(src)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(Path::toString))
                        .toList()) {
                    String relative = src.relativize(file).toString().replace('\\', '/');
                    entries.add(new PendingEntry(file, dir + "/" + relative, Files.size(file)));
                }
            }
        }

        Path settings = dataDir.resolve("settings.bin");
        if (Files.isRegularFile(settings)) {
            entries.add(new PendingEntry(settings, "settings.bin", Files.size(settings)));
        }

        long totalBytes = entries.stream().mapToLong(PendingEntry::size).sum();
        monitor.update(0, totalBytes);

        Files.createDirectories(zipFile.getParent() != null ? zipFile.getParent() : Path.of("."));
        long written = 0;
        try {
            try (OutputStream os = Files.newOutputStream(zipFile);
                 ZipOutputStream zip = new ZipOutputStream(os)) {
                for (PendingEntry entry : entries) {
                    monitor.checkCancelled();
                    written = addFile(zip, manifest, entry, written, totalBytes, monitor);
                }

                monitor.checkCancelled();
                zip.putNextEntry(new ZipEntry(MANIFEST));
                zip.write(GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                monitor.update(totalBytes, totalBytes);
            }
        } catch (CancellationException ex) {
            try {
                Files.deleteIfExists(zipFile);
            } catch (IOException deleteEx) {
                LOG.warning("Failed to delete cancelled Xenon Modpack export " + zipFile, deleteEx);
            }
            throw ex;
        }

        LOG.info("Xenon Modpack written: " + zipFile + " (" + manifest.files.size() + " entries)");
        return manifest;
    }

    private static long addFile(ZipOutputStream zip,
                                XenonModpackManifest manifest,
                                PendingEntry entry,
                                long written,
                                long totalBytes,
                                ExportMonitor monitor) throws IOException {
        MessageDigest digest = sha1Digest();
        zip.putNextEntry(new ZipEntry(entry.entryName()));
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = new DigestInputStream(Files.newInputStream(entry.file()), digest)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                monitor.checkCancelled();
                zip.write(buffer, 0, read);
                written += read;
                monitor.update(written, totalBytes);
            }
        }
        zip.closeEntry();
        manifest.files.add(new XenonModpackManifest.Entry(
                entry.entryName(), entry.size(), hex(digest.digest()), "bundled"));
        return written;
    }

    private static MessageDigest sha1Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-1 digest is unavailable", ex);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private record PendingEntry(Path file, String entryName, long size) {
    }

    /** Receives byte progress from a streaming modpack export. */
    public interface ExportMonitor {
        ExportMonitor NONE = new ExportMonitor() {
        };

        /** Called whenever the packer has written more payload bytes. */
        default void update(long writtenBytes, long totalBytes) {
        }

        /** Whether the caller has requested cancellation. */
        default boolean isCancelled() {
            return false;
        }

        /** Throw a cancellation exception when the export should stop. */
        default void checkCancelled() {
            if (isCancelled()) {
                throw new CancellationException("Modpack export cancelled");
            }
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
