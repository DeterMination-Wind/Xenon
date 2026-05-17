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
package determination.xenon.mindustry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import determination.xenon.util.io.FileUtils;
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * On-disk registry of Mindustry client versions installed under
 * {@code <config>/versions/<vid>/}.
 *
 * <p>Each subdirectory must contain a {@code version.json} that
 * deserialises into a {@link MindustryVersion}; the directory name and
 * the {@code id} field must match (the id wins on conflict).</p>
 *
 * <p>This is intentionally <em>parallel to</em> HMCL's
 * {@code HMCLGameRepository} rather than replacing it — the HMCL UI keeps
 * its repository for legacy code paths, and Mindustry-aware UI screens
 * read from this class instead.</p>
 */
public final class XenonGameRepository {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String VERSION_JSON = "version.json";

    private final Path versionsRoot;
    private final Map<String, MindustryVersion> versions = new LinkedHashMap<>();

    public XenonGameRepository(Path versionsRoot) {
        this.versionsRoot = versionsRoot;
    }

    public Path getVersionsRoot() {
        return versionsRoot;
    }

    /** Re-read all version manifests under {@code versionsRoot}. Safe to call repeatedly. */
    public synchronized void refresh() {
        versions.clear();
        if (!Files.isDirectory(versionsRoot)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(versionsRoot)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) continue;
                String dirName = dir.getFileName().toString();
                // Accept both manifest layouts: the canonical
                // <id>/version.json and the legacy <id>/<id>.json that
                // earlier Xenon installs wrote out.
                Path json = dir.resolve(VERSION_JSON);
                if (!Files.isRegularFile(json)) {
                    Path legacy = dir.resolve(dirName + ".json");
                    if (Files.isRegularFile(legacy)) {
                        json = legacy;
                    } else {
                        continue;
                    }
                }
                try {
                    String text = Files.readString(json);
                    MindustryVersion v = GSON.fromJson(text, MindustryVersion.class);
                    if (v == null) continue;
                    if (v.getId() == null || v.getId().isBlank()) {
                        v.setId(dirName);
                    }
                    versions.put(v.getId(), v);
                } catch (Exception ex) {
                    Logger.LOG.warning("Failed to load Mindustry version " + dirName + ": " + ex);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public synchronized boolean has(String id) {
        return versions.containsKey(id);
    }

    public synchronized Optional<MindustryVersion> get(String id) {
        return Optional.ofNullable(versions.get(id));
    }

    public synchronized Collection<MindustryVersion> all() {
        return new ArrayList<>(versions.values());
    }

    public synchronized int size() {
        return versions.size();
    }

    public Path getVersionRoot(String id) {
        return versionsRoot.resolve(id);
    }

    public Path getDataDir(String id) throws VersionNotFoundException {
        MindustryVersion v = versions.get(id);
        if (v == null) throw new VersionNotFoundException(id);
        return v.resolveDataDir(getVersionRoot(id));
    }

    public Path getJar(String id) throws VersionNotFoundException {
        MindustryVersion v = versions.get(id);
        if (v == null) throw new VersionNotFoundException(id);
        return v.resolveJar(getVersionRoot(id));
    }

    /**
     * Persist a version (creating its directory if needed) and refresh
     * the in-memory cache. Returns the resolved version-root directory.
     */
    public synchronized Path save(MindustryVersion version) throws IOException {
        if (version.getId() == null || version.getId().isBlank()) {
            throw new IllegalArgumentException("Mindustry version id must be set");
        }
        Path root = getVersionRoot(version.getId());
        Files.createDirectories(root);
        Path json = root.resolve(VERSION_JSON);
        Files.writeString(json, GSON.toJson(version), StandardCharsets.UTF_8);
        versions.put(version.getId(), version);
        return root;
    }

    /** Remove a version entry on disk. Returns true if anything was deleted. */
    public synchronized boolean delete(String id) throws IOException {
        Path root = getVersionRoot(id);
        boolean removed = versions.remove(id) != null;
        if (Files.isDirectory(root)) {
            FileUtils.deleteDirectory(root);
            removed = true;
        }
        return removed;
    }

    public synchronized List<String> ids() {
        return new ArrayList<>(versions.keySet());
    }

    /** Thrown when a caller asks for a Mindustry version id that isn't installed. */
    public static final class VersionNotFoundException extends Exception {
        public VersionNotFoundException(String id) {
            super("Mindustry version not found: " + id);
        }
    }
}
