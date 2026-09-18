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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import determination.xenon.util.io.FileUtils;
import determination.xenon.util.logging.Logger;
import determination.xenon.util.platform.OperatingSystem;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * On-disk registry of Mindustry client versions installed under
 * {@code <config>/versions/<vid>/}.
 *
 * <p>Each subdirectory normally contains a {@code version.json} that
 * deserialises into a {@link MindustryVersion}; older global installs may
 * still use {@code <id>/<id>.json}. Minecraft manifests are ignored by
 * checking for Mindustry-specific fields before deserialisation.</p>
 *
 * <p>This is intentionally <em>parallel to</em> HMCL's
 * {@code HMCLGameRepository} rather than replacing it — the HMCL UI keeps
 * its repository for legacy code paths, and Mindustry-aware UI screens
 * read from this class instead.</p>
 */
public final class XenonGameRepository {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String VERSION_JSON = "version.json";
    /** Ids accepted for Mindustry instances; an id doubles as a directory name. */
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]+");

    private final Path versionsRoot;
    private final Map<String, MindustryVersion> versions = new LinkedHashMap<>();

    public XenonGameRepository(Path versionsRoot) {
        this.versionsRoot = versionsRoot.toAbsolutePath().normalize();
    }

    public Path getVersionsRoot() {
        return versionsRoot;
    }

    /**
     * Returns whether {@code id} is usable as a Mindustry instance id. The id
     * is also the version directory name and the default jar file name, so it
     * must not contain path separators or other special characters.
     *
     * @param id the candidate instance id
     * @return {@code true} when the id consists of letters, digits, dots,
     *         dashes and underscores only
     */
    public static boolean isValidId(@Nullable String id) {
        return id != null && !id.isBlank() && VALID_ID.matcher(id).matches();
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
                    if (!isMindustryManifest(text)) {
                        continue;
                    }
                    MindustryVersion v = GSON.fromJson(text, MindustryVersion.class);
                    if (v == null) continue;
                    if (v.getId() == null || v.getId().isBlank()) {
                        v.setId(dirName);
                    } else if (!dirName.equals(v.getId())) {
                        // The directory name is authoritative. Renaming an
                        // instance used to rewrite only the legacy <id>.json
                        // scraped by HMCL, leaving id/jarPath pointing at the
                        // previous directory; adopt the directory name and
                        // repair the jar path so the instance stays usable.
                        String staleId = v.getId();
                        String displayName = v.getName();
                        Logger.LOG.warning("Mindustry instance directory " + dirName
                                + " contains manifest id " + staleId + "; adopting the directory name");
                        v.setId(dirName);
                        if (displayName.equals(staleId)) {
                            v.setName(dirName);
                        }
                        repairJarPath(dir, v, staleId);
                    }
                    v.setRepositoryRoot(versionsRoot);
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
        if (versions.containsKey(id)) {
            return true;
        }
        return OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS
                && versions.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(id));
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

    /**
     * Resolve a version using its owning repository marker when available.
     * This keeps instances loaded from another cached repository on their own
     * disk root even if a caller also has a current-profile repository.
     */
    public Path getVersionRoot(MindustryVersion version) {
        if (version == null || version.getId() == null || version.getId().isBlank()) {
            throw new IllegalArgumentException("Mindustry version id must be set");
        }
        Path ownerRoot = version.getRepositoryRoot();
        return (ownerRoot == null ? versionsRoot : ownerRoot).resolve(version.getId());
    }

    public Path getDataDir(String id) throws VersionNotFoundException {
        MindustryVersion v = versions.get(id);
        if (v == null) throw new VersionNotFoundException(id);
        return v.resolveDataDir(getVersionRoot(v));
    }

    public Path getJar(String id) throws VersionNotFoundException {
        MindustryVersion v = versions.get(id);
        if (v == null) throw new VersionNotFoundException(id);
        return v.resolveJar(getVersionRoot(v));
    }

    /**
     * Persist a version (creating its directory if needed) and refresh
     * the in-memory cache. Returns the resolved version-root directory.
     */
    public synchronized Path save(MindustryVersion version) throws IOException {
        if (version.getId() == null || version.getId().isBlank()) {
            throw new IllegalArgumentException("Mindustry version id must be set");
        }
        version.setRepositoryRoot(versionsRoot);
        Path root = getVersionRoot(version);
        Files.createDirectories(root);
        Path json = root.resolve(VERSION_JSON);
        Files.writeString(json, GSON.toJson(version), StandardCharsets.UTF_8);
        versions.put(version.getId(), version);
        return root;
    }

    /**
     * Renames an installed instance: moves {@code versionsRoot/<from>} to
     * {@code versionsRoot/<to>}, rewrites {@code version.json} and keeps the jar
     * file name in sync. Stale {@code <from>.json} copies that HMCL's Minecraft
     * repository scan used to write into Mindustry directories are removed.
     *
     * <p>Nothing is changed when the target id is invalid or taken, when the
     * instance directory cannot be moved (for example because the game is
     * running) or when the manifest cannot be rewritten; a partially completed
     * rename is rolled back.</p>
     *
     * @param from the current instance id
     * @param to the new instance id
     * @return {@code true} when the instance was renamed
     */
    public synchronized boolean renameVersion(@Nullable String from, @Nullable String to) {
        if (from == null || to == null || from.isBlank() || to.isBlank()
                || from.equals(to) || !isValidId(to)) {
            return false;
        }

        String sourceId = from;
        MindustryVersion version = versions.get(sourceId);
        if (version == null) {
            for (Map.Entry<String, MindustryVersion> entry : versions.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(from)) {
                    sourceId = entry.getKey();
                    version = entry.getValue();
                    break;
                }
            }
        }
        if (version == null) {
            Logger.LOG.warning("Cannot rename unknown Mindustry instance " + from);
            return false;
        }

        // A pure case change touches the same directory, so the regular
        // conflict check must not reject it.
        boolean caseOnlyRename = to.equalsIgnoreCase(sourceId) && !to.equals(sourceId);
        if (!caseOnlyRename && has(to)) {
            return false;
        }

        Path sourceRoot = versionsRoot.resolve(sourceId);
        Path targetRoot = versionsRoot.resolve(to);
        if (!Files.isDirectory(sourceRoot) || (!caseOnlyRename && Files.exists(targetRoot))) {
            Logger.LOG.warning("Cannot rename Mindustry instance " + sourceId + ": "
                    + sourceRoot + " is not a directory or " + targetRoot + " already exists");
            return false;
        }

        String previousId = version.getId();
        String previousName = version.getName();
        String previousJarPath = version.getJarPath();

        try {
            Files.move(sourceRoot, targetRoot);
        } catch (IOException | RuntimeException ex) {
            Logger.LOG.warning("Unable to move Mindustry instance " + sourceId + " to " + to, ex);
            return false;
        }

        try {
            version.setId(to);
            if (previousName.isBlank() || previousName.equals(previousId) || previousName.equals(sourceId)) {
                version.setName(to);
            }
            renameJarIfNeeded(targetRoot, version, sourceId, to);
            deleteLegacyManifestCopy(targetRoot, sourceId);
            save(version);
            versions.remove(sourceId);
            versions.put(to, version);
            return true;
        } catch (IOException | RuntimeException ex) {
            Logger.LOG.warning("Unable to rewrite the manifest after renaming "
                    + sourceId + " to " + to, ex);
            version.setId(previousId);
            version.setName(previousName);
            version.setJarPath(previousJarPath);
            try {
                if (Files.exists(targetRoot) && !Files.exists(sourceRoot)) {
                    Files.move(targetRoot, sourceRoot);
                }
            } catch (IOException restoreFailure) {
                Logger.LOG.warning("Unable to restore " + sourceRoot + " after a failed rename", restoreFailure);
            }
            return false;
        }
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

    /**
     * Repoints a manifest whose relative jar path no longer exists at the jar
     * file that is actually present in {@code directory}. This repairs entries
     * that were renamed while their manifest still referenced the old id.
     */
    private static void repairJarPath(Path directory, MindustryVersion version, String staleId) {
        String jarPath = version.getJarPath();
        if (jarPath == null || jarPath.isBlank()) {
            Path defaultJar = directory.resolve(version.getId() + ".jar");
            if (Files.isRegularFile(defaultJar)) {
                version.setJarPath(directory.relativize(defaultJar).toString());
            }
            return;
        }

        Path declared;
        try {
            declared = Path.of(jarPath);
        } catch (InvalidPathException ex) {
            return;
        }
        if (declared.isAbsolute() || Files.isRegularFile(directory.resolve(declared).normalize())) {
            return;
        }

        Path fallback = directory.resolve(version.getId() + ".jar");
        if (Files.isRegularFile(fallback)) {
            Logger.LOG.warning("Mindustry instance " + version.getId() + " referenced the missing jar "
                    + jarPath + " (renamed from " + staleId + "); using " + fallback.getFileName());
            version.setJarPath(directory.relativize(fallback).toString());
        }
    }

    /** Moves {@code <fromId>.jar} to {@code <toId>.jar} when the manifest uses the default naming. */
    private static void renameJarIfNeeded(Path directory, MindustryVersion version,
                                          String fromId, String toId) throws IOException {
        String jarPath = version.getJarPath();
        if (jarPath == null || jarPath.isBlank()) {
            renameJar(directory.resolve(fromId + ".jar"), directory.resolve(toId + ".jar"),
                    version, directory);
            return;
        }

        Path declared;
        try {
            declared = Path.of(jarPath);
        } catch (InvalidPathException ex) {
            return;
        }
        if (declared.isAbsolute()) {
            return;
        }
        Path sourceJar = directory.resolve(declared).normalize();
        if (!sourceJar.startsWith(directory) || sourceJar.getFileName() == null
                || !sourceJar.getFileName().toString().equals(fromId + ".jar")) {
            return;
        }
        renameJar(sourceJar, sourceJar.resolveSibling(toId + ".jar"), version, directory);
    }

    private static void renameJar(Path sourceJar, Path targetJar,
                                  MindustryVersion version, Path directory) throws IOException {
        if (!Files.isRegularFile(sourceJar) || Files.exists(targetJar)) {
            return;
        }
        Files.move(sourceJar, targetJar);
        version.setJarPath(directory.relativize(targetJar).toString());
    }

    /** Removes the legacy {@code <id>.json} copy of a Mindustry manifest, when present. */
    private static void deleteLegacyManifestCopy(Path directory, String id) {
        Path legacy = directory.resolve(id + ".json");
        if (!isMindustryManifestFile(legacy)) {
            return;
        }
        try {
            Files.deleteIfExists(legacy);
        } catch (IOException ex) {
            Logger.LOG.warning("Unable to delete the stale Mindustry manifest " + legacy, ex);
        }
    }

    /**
     * Returns whether {@code directory} holds a Xenon-managed Mindustry
     * instance. HMCL's Minecraft repository shares the same {@code versions}
     * directory, so it has to recognize and skip these entries before its scan
     * renames {@code version.json} or the instance jar.
     *
     * @param directory a candidate {@code versions/<id>} directory
     * @return {@code true} when the directory contains a Mindustry manifest
     */
    public static boolean isMindustryVersionDirectory(@Nullable Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return false;
        }
        if (isMindustryManifestFile(directory.resolve(VERSION_JSON))) {
            return true;
        }
        Path name = directory.getFileName();
        return name != null && isMindustryManifestFile(directory.resolve(name + ".json"));
    }

    /** Returns whether {@code json} exists and contains Mindustry-specific metadata. */
    public static boolean isMindustryManifestFile(@Nullable Path json) {
        if (json == null || !Files.isRegularFile(json)) {
            return false;
        }
        try {
            return isMindustryManifest(Files.readString(json));
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Returns whether a manifest contains Mindustry-specific metadata. */
    public static boolean isMindustryManifest(@Nullable String text) {
        if (text == null) {
            return false;
        }
        try {
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                return false;
            }
            JsonObject object = element.getAsJsonObject();
            return object.has("jarPath")
                    || object.has("variant")
                    || object.has("buildType")
                    || object.has("dataDirPolicy")
                    || object.has("javaReq")
                    || object.has("workingDirectory");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Thrown when a caller asks for a Mindustry version id that isn't installed. */
    public static final class VersionNotFoundException extends Exception {
        public VersionNotFoundException(String id) {
            super("Mindustry version not found: " + id);
        }
    }
}
