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
package determination.xenon.mindustry.mod;

import determination.xenon.mindustry.uuid.MindustrySettingsBin;
import determination.xenon.util.io.FileUtils;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Manages the {@code mods/} folder of one Mindustry data directory:
 * scans local archives, toggles their enabled state via the
 * {@code .disabled} suffix convention used by Mindustry, deletes them,
 * and installs new ones by copy.
 *
 * <p>This class does not cache scan results; callers should
 * {@link #scan()} again after any mutation.</p>
 */
public final class MindustryModManager {

    /// Maximum number of wrapper archives {@link #install(Path, String)} unwraps.
    private static final int UNWRAP_DEPTH_LIMIT = 3;

    private final Path modsDir;
    private final Path dataDir;
    private final SettingsWriter settingsWriter;

    /// Persists one coalesced batch of `mod-<name>-enabled` flags.
    @FunctionalInterface
    interface SettingsWriter {
        /// Merges `values` into the instance's `settings.bin`.
        ///
        /// @param dataDir Mindustry data directory that owns `settings.bin`
        /// @param values entry set to merge
        /// @throws IOException when the file cannot be read or written
        void write(Path dataDir, Map<String, Object> values) throws IOException;
    }

    public MindustryModManager(Path modsDir) {
        this(modsDir, inferDataDir(modsDir));
    }

    public MindustryModManager(Path modsDir, Path dataDir) {
        this(modsDir, dataDir, MindustrySettingsBin::putValuesOrThrow);
    }

    /// Creates a manager with an injectable settings writer.
    ///
    /// Visible for tests so batch operations can be proven to coalesce their
    /// `settings.bin` writes.
    MindustryModManager(Path modsDir, Path dataDir, SettingsWriter settingsWriter) {
        this.modsDir = Objects.requireNonNull(modsDir, "modsDir").toAbsolutePath().normalize();
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        this.settingsWriter = Objects.requireNonNull(settingsWriter, "settingsWriter");
    }

    public Path getModsDir() { return modsDir; }

    public Path getDataDir() { return dataDir; }

    /**
     * Walk {@link #getModsDir()} (non-recursive) and parse every
     * {@code .jar} / {@code .zip} (optionally suffixed {@code .disabled}).
     * Archives that fail to parse are logged and skipped, never thrown.
     */
    public synchronized List<MindustryLocalMod> scan() {
        return scan(false);
    }

    /**
     * Like {@link #scan()}, but keeps archives whose descriptor cannot be
     * read as {@link MindustryLocalMod#isUnparsable() unparsable} entries
     * instead of dropping them. Callers that mirror the mods folder for the
     * user should use this so a folder full of broken archives never looks
     * empty.
     */
    public synchronized List<MindustryLocalMod> scanAll() {
        return scan(true);
    }

    private List<MindustryLocalMod> scan(boolean includeUnparsable) {
        List<MindustryLocalMod> result = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) return result;
        Map<String, Object> settings = MindustrySettingsBin.readValues(dataDir);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;
                if (!isModArchive(p)) continue;
                try {
                    result.add(applyGameEnabledSetting(MindustryModParser.parse(p), settings));
                } catch (IOException ex) {
                    Logger.LOG.log(System.Logger.Level.WARNING,
                            "Failed to parse Mindustry mod " + p + ": " + ex.getMessage());
                    if (includeUnparsable) {
                        result.add(MindustryLocalMod.unparsable(p, ex.getMessage()));
                    }
                }
            }
        } catch (IOException ex) {
            Logger.LOG.log(System.Logger.Level.WARNING,
                    "Failed to list Mindustry mods dir " + modsDir, ex);
        }
        result = markDuplicateWinners(result);
        result.sort(MindustryModManager::compareDisplayOrder);
        return result;
    }

    private static Path inferDataDir(Path modsDir) {
        Path normalized = Objects.requireNonNull(modsDir, "modsDir").toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        return parent == null ? normalized : parent;
    }

    private static MindustryLocalMod applyGameEnabledSetting(MindustryLocalMod mod,
                                                             Map<String, Object> settings) {
        String key = enabledSettingKey(mod);
        if (key == null) {
            return mod;
        }
        boolean archiveEnabled = !mod.getFile().getFileName().toString()
                .toLowerCase(Locale.ROOT).endsWith(".disabled");
        boolean gameEnabled = MindustrySettingsBin.getBool(settings, key, true);
        return mod.withEnabled(archiveEnabled && gameEnabled);
    }

    private static int compareDisplayOrder(MindustryLocalMod a, MindustryLocalMod b) {
        int enabled = Boolean.compare(b.isEnabled(), a.isEnabled());
        if (enabled != 0) {
            return enabled;
        }
        // Readable mods come first; broken archives are listed after them so
        // the installable content stays at the top of the mod list.
        int unparsable = Boolean.compare(a.isUnparsable(), b.isUnparsable());
        if (unparsable != 0) {
            return unparsable;
        }
        int displayName = a.displayName().compareToIgnoreCase(b.displayName());
        if (displayName != 0) {
            return displayName;
        }
        return a.getFile().getFileName().toString()
                .compareToIgnoreCase(b.getFile().getFileName().toString());
    }

    private static List<MindustryLocalMod> markDuplicateWinners(List<MindustryLocalMod> mods) {
        List<MindustryLocalMod> loadOrder = new ArrayList<>(mods);
        loadOrder.sort(MindustryModManager::compareLoadOrder);

        Map<String, MindustryLocalMod> winners = new HashMap<>();
        for (MindustryLocalMod mod : loadOrder) {
            if (mod.isEnabled() && !mod.getInternalName().isBlank()) {
                winners.put(mod.getInternalName(), mod);
            }
        }

        List<MindustryLocalMod> result = new ArrayList<>(mods.size());
        for (MindustryLocalMod mod : mods) {
            MindustryLocalMod winner = winners.get(mod.getInternalName());
            if (winner != null && winner != mod && mod.isEnabled()) {
                result.add(mod.ignoredBy(winner));
            } else {
                result.add(mod);
            }
        }
        return result;
    }

    private static int compareLoadOrder(MindustryLocalMod a, MindustryLocalMod b) {
        int modified = Long.compare(lastModifiedMillis(a.getFile()), lastModifiedMillis(b.getFile()));
        if (modified != 0) {
            return modified;
        }
        return compareNatural(archiveFileName(a.getFile()), archiveFileName(b.getFile()));
    }

    private static long lastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static String archiveFileName(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".disabled") ? name.substring(0, name.length() - ".disabled".length()) : name;
    }

    private static int compareNatural(String a, String b) {
        int ai = 0;
        int bi = 0;
        while (ai < a.length() && bi < b.length()) {
            char ac = a.charAt(ai);
            char bc = b.charAt(bi);
            if (Character.isDigit(ac) && Character.isDigit(bc)) {
                int aStart = ai;
                int bStart = bi;
                while (ai < a.length() && Character.isDigit(a.charAt(ai))) ai++;
                while (bi < b.length() && Character.isDigit(b.charAt(bi))) bi++;
                String an = a.substring(aStart, ai);
                String bn = b.substring(bStart, bi);
                String av = stripLeadingZeroes(an);
                String bv = stripLeadingZeroes(bn);
                int number = Integer.compare(av.length(), bv.length());
                if (number != 0) {
                    return number;
                }
                number = av.compareTo(bv);
                if (number != 0) {
                    return number;
                }
                int length = Integer.compare(an.length(), bn.length());
                if (length != 0) {
                    return length;
                }
                continue;
            }
            int chars = Character.compare(ac, bc);
            if (chars != 0) {
                return chars;
            }
            ai++;
            bi++;
        }
        return Integer.compare(a.length(), b.length());
    }

    private static String stripLeadingZeroes(String value) {
        int index = 0;
        while (index + 1 < value.length() && value.charAt(index) == '0') {
            index++;
        }
        return value.substring(index);
    }

    /** Re-enable a disabled mod by stripping the {@code .disabled} suffix. */
    public synchronized void enable(MindustryLocalMod mod) throws IOException {
        applyOrThrow(List.of(mod), true);
    }

    /** Disable a mod by appending the {@code .disabled} suffix. */
    public synchronized void disable(MindustryLocalMod mod) throws IOException {
        applyOrThrow(List.of(mod), false);
    }

    /**
     * Enables or disables several mods at once.
     *
     * <p>The operation is transactional per phase:</p>
     * <ol>
     *   <li>every {@code mod-<name>-enabled} flag is persisted with one
     *       coalesced, atomic {@code settings.bin} write before any file is
     *       touched, so a settings failure leaves the mods folder unchanged;</li>
     *   <li>archive suffixes are renamed one by one (a failure only removes
     *       that mod from the batch);</li>
     *   <li>the flags of mods whose rename failed are restored to their
     *       previous values.</li>
     * </ol>
     *
     * @param mods mods to toggle
     * @param enabled desired state
     * @return applied mods and per-file failure reasons; never {@code null}
     */
    public synchronized BatchResult setEnabled(List<MindustryLocalMod> mods, boolean enabled) {
        return applyState(mods, enabled);
    }

    /// Runs one single-mod state change and rethrows its failure reason.
    private void applyOrThrow(List<MindustryLocalMod> mods, boolean enabled) throws IOException {
        BatchResult result = applyState(mods, enabled);
        if (!result.failures().isEmpty()) {
            throw new IOException(result.failures().values().iterator().next());
        }
    }

    /// Transactional body shared by the single and batch toggle paths.
    private BatchResult applyState(List<MindustryLocalMod> mods, boolean enabled) {
        List<MindustryLocalMod> targets = new ArrayList<>();
        for (MindustryLocalMod mod : mods) {
            if (mod != null) {
                targets.add(mod);
            }
        }
        if (targets.isEmpty()) {
            return new BatchResult(List.of(), Map.of());
        }

        Map<String, String> failures = new LinkedHashMap<>();

        // Phase 1 — persist every game-side enabled flag in one write. Writing
        // settings first keeps a settings failure from leaving renamed files
        // behind, because no file has been touched yet.
        LinkedHashMap<String, Object> pendingSettings = new LinkedHashMap<>();
        Map<String, Object> previousSettings = new LinkedHashMap<>();
        Map<String, String> keyOwners = new LinkedHashMap<>();
        for (MindustryLocalMod mod : targets) {
            String key = enabledSettingKey(mod);
            if (key == null) {
                continue;
            }
            pendingSettings.put(key, enabled);
            keyOwners.put(key, mod.getFile().getFileName().toString());
        }
        boolean settingsPhaseFailed = false;
        if (!pendingSettings.isEmpty()) {
            try {
                LinkedHashMap<String, Object> current = MindustrySettingsBin.readForUpdate(dataDir);
                for (String key : pendingSettings.keySet()) {
                    Object previous = current.get(key);
                    // A missing key means "enabled" for the game; restoring it
                    // as an explicit true keeps the effective state identical.
                    previousSettings.put(key, previous instanceof Boolean ? previous : Boolean.TRUE);
                }
                settingsWriter.write(dataDir, pendingSettings);
            } catch (IOException | RuntimeException ex) {
                settingsPhaseFailed = true;
                String message = messageOf(ex);
                for (String key : pendingSettings.keySet()) {
                    failures.put(keyOwners.get(key), message);
                }
            }
        }

        // Phase 2 — rename archives one by one; only per-file failures are
        // reported, the rest of the batch still applies.
        List<MindustryLocalMod> applied = new ArrayList<>();
        Map<String, Object> restoreSettings = new LinkedHashMap<>();
        for (MindustryLocalMod mod : targets) {
            String fileName = mod.getFile().getFileName().toString();
            if (settingsPhaseFailed && failures.containsKey(fileName)) {
                // The game-side flag could not be written; a suffix rename
                // alone would leave the two signals inconsistent.
                continue;
            }
            try {
                renameArchive(mod, enabled);
                applied.add(mod);
            } catch (IOException | RuntimeException ex) {
                failures.put(fileName, messageOf(ex));
                String key = enabledSettingKey(mod);
                if (key != null && previousSettings.containsKey(key)) {
                    restoreSettings.put(key, previousSettings.get(key));
                }
            }
        }

        // Phase 3 — undo the flags of mods whose rename failed.
        if (!restoreSettings.isEmpty()) {
            try {
                settingsWriter.write(dataDir, restoreSettings);
            } catch (IOException | RuntimeException ex) {
                String message = messageOf(ex);
                for (String key : restoreSettings.keySet()) {
                    String owner = keyOwners.get(key);
                    failures.computeIfPresent(owner,
                            (file, reason) -> reason + " (settings restore failed: " + message + ")");
                }
            }
        }
        return new BatchResult(List.copyOf(applied), Collections.unmodifiableMap(failures));
    }

    /**
     * Deletes several mods, preferring the platform trash for each file.
     *
     * @param mods mods to remove
     * @return deleted mods and per-file failure reasons; never {@code null}
     */
    public synchronized BatchResult deleteAll(List<MindustryLocalMod> mods) {
        List<MindustryLocalMod> applied = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (MindustryLocalMod mod : mods) {
            if (mod == null) continue;
            try {
                delete(mod);
                applied.add(mod);
            } catch (IOException | RuntimeException ex) {
                failures.put(mod.getFile().getFileName().toString(), messageOf(ex));
            }
        }
        return new BatchResult(List.copyOf(applied), Collections.unmodifiableMap(failures));
    }

    /** Remove the underlying archive, preferring the platform trash. */
    public synchronized void delete(MindustryLocalMod mod) throws IOException {
        FileUtils.deleteSafely(mod.getFile());
    }

    /**
     * Copy a {@code .jar} / {@code .zip} into {@link #getModsDir()},
     * creating the directory if needed. The destination keeps the source
     * file name; existing mods with that name are overwritten and older
     * archives of the same mod are removed.
     */
    public synchronized InstallResult install(Path zipOrJar) throws IOException {
        Objects.requireNonNull(zipOrJar, "zipOrJar");
        return install(zipOrJar, zipOrJar.getFileName().toString());
    }

    /**
     * Copy a mod archive into {@link #getModsDir()} under
     * {@code preferredFileName}.
     *
     * <p>Downloads are staged under temporary names, so callers pass the
     * release asset name (or the picked file name) to keep the installed
     * archive recognizable. Archives that provide the same mod - the same
     * internal name - are removed first, so installing an update replaces
     * the previous copy instead of leaving two archives of which Mindustry
     * silently loads only one.</p>
     *
     * <p>When {@code preferredFileName} would overwrite an archive of a
     * <em>different</em> mod, the existing file is kept and a numbered
     * sibling name is used instead.</p>
     *
     * @param zipOrJar archive to copy; must exist and end in {@code .jar} or {@code .zip}
     * @param preferredFileName destination file name; falls back to the source name when blank
     * @return the installed file together with the archives removed as part of the install
     * @throws IOException if the source cannot be read or the copy fails
     */
    public synchronized InstallResult install(Path zipOrJar, String preferredFileName) throws IOException {
        Objects.requireNonNull(zipOrJar, "zipOrJar");
        if (!Files.isRegularFile(zipOrJar)) {
            throw new IOException("Not a regular file: " + zipOrJar);
        }
        if (!isModArchive(zipOrJar)) {
            throw new IOException("Not a Mindustry mod archive: " + zipOrJar);
        }
        List<Path> temporaryArchives = new ArrayList<>();
        try {
            Path archive = zipOrJar;
            String fileName = preferredFileName;
            if (!MindustryModParser.containsDescriptor(archive)) {
                // Release bundles wrap the real mod jar inside an outer zip;
                // unwrap single-child archives recursively, but never guess
                // between several candidates.
                Unwrapped unwrapped = unwrapNestedArchive(archive, UNWRAP_DEPTH_LIMIT, temporaryArchives);
                if (unwrapped == null) {
                    throw new MindustryModParseException("No mod.json / mod.hjson / plugin.json / plugin.hjson in "
                            + zipOrJar);
                }
                archive = unwrapped.path();
                fileName = unwrapped.fileName();
            }
            return installArchive(archive, fileName);
        } finally {
            for (Path temporary : temporaryArchives) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Temp files live in the system temp dir; a failed cleanup
                    // must not fail an otherwise successful install.
                }
            }
        }
    }

    /// Copies a validated archive into `mods/` and removes older copies of the same mod.
    ///
    /// @param archive archive containing the mod descriptor
    /// @param preferredFileName destination file name, falling back to the archive name
    /// @return the installed file together with the archives removed as part of the install
    /// @throws IOException if the archive cannot be read or the copy fails
    private InstallResult installArchive(Path archive, String preferredFileName) throws IOException {
        Files.createDirectories(modsDir);

        String internalName = internalNameOf(archive);
        String desiredName = destinationName(preferredFileName, archive.getFileName().toString());

        List<Path> replaced = new ArrayList<>();
        if (!internalName.isEmpty()) {
            for (MindustryLocalMod existing : scan()) {
                Path file = existing.getFile();
                if (!internalName.equals(existing.getInternalName())) continue;
                // The destination itself is overwritten in place below.
                if (file.getFileName().toString().equalsIgnoreCase(desiredName)) continue;
                if (FileUtils.deleteSafely(file)) {
                    replaced.add(file);
                }
            }
        }

        Path dst = modsDir.resolve(desiredName);
        if (Files.exists(dst) && !internalName.isEmpty()
                && !internalName.equals(internalNameOf(dst))) {
            // Same file name, different mod: never clobber the other archive.
            dst = numberedSibling(dst);
        }
        Files.copy(archive, dst, StandardCopyOption.REPLACE_EXISTING);
        return new InstallResult(dst, List.copyOf(replaced));
    }

    /// Result of unwrapping a bundle archive.
    ///
    /// @param path extracted archive that contains the mod descriptor
    /// @param fileName the nested entry's bare file name, used as the install name
    private record Unwrapped(Path path, String fileName) {
    }

    /// Recursively unwraps archives that contain exactly one nested archive.
    ///
    /// A bundle may contain a jar (`zip` → `jar`) or another zip
    /// (`zip` → `zip` → `jar`). Anything else — zero entries, several entries,
    /// a non-archive child or a chain deeper than the limit — returns `null` so
    /// the caller can report the original parse error.
    ///
    /// @param archive archive whose descriptor lookup failed
    /// @param remaining number of unwrap levels still allowed
    /// @param temporaryArchives collector for extracted files the caller must delete
    /// @return the extracted archive carrying the descriptor, or `null`
    private static @Nullable Unwrapped unwrapNestedArchive(Path archive,
                                                           int remaining,
                                                           List<Path> temporaryArchives) throws IOException {
        if (remaining <= 0) return null;
        String nestedEntry = singleNestedArchiveEntry(archive);
        if (nestedEntry == null) return null;
        Path extracted = extractEntry(archive, nestedEntry, temporaryArchives);
        boolean hasDescriptor;
        try {
            hasDescriptor = MindustryModParser.containsDescriptor(extracted);
        } catch (IOException ex) {
            // The child archive is unreadable (not a zip, truncated, ...);
            // report "not a mod bundle" rather than leaking the inner error.
            return null;
        }
        if (hasDescriptor) {
            return new Unwrapped(extracted, Path.of(nestedEntry).getFileName().toString());
        }
        return unwrapNestedArchive(extracted, remaining - 1, temporaryArchives);
    }

    /// Returns the only non-directory entry when it names a `.jar` / `.zip` archive.
    ///
    /// @param archive archive to inspect
    /// @return the entry name, or `null` when the archive is not a single-archive wrapper
    private static @Nullable String singleNestedArchiveEntry(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            String found = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (found != null) return null;
                found = entry.getName();
            }
            if (found == null) return null;
            String lower = found.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".jar") && !lower.endsWith(".zip")) return null;
            return found;
        } catch (ZipException ex) {
            // Extracted children are not guaranteed to be archives; treat an
            // unreadable child as "no nested archive" so the caller keeps its
            // original, user-facing parse error.
            return null;
        }
    }

    /// Extracts one entry into a temporary file recorded for later cleanup.
    private static Path extractEntry(Path archive,
                                     String entryName,
                                     List<Path> temporaryArchives) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new IOException("Missing ZIP entry " + entryName + " in " + archive);
            }
            String suffix = entryName.toLowerCase(Locale.ROOT).endsWith(".jar") ? ".jar" : ".zip";
            Path temporary = Files.createTempFile("xenon-mod-unwrap-", suffix);
            temporaryArchives.add(temporary);
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            return temporary;
        }
    }

    /**
     * Reads the archive's internal mod name without keeping the parsed metadata.
     *
     * @param archive archive to inspect
     * @return the normalized internal name, or an empty string when unreadable
     */
    private static String internalNameOf(Path archive) {
        try {
            return MindustryModParser.parse(archive).getInternalName();
        } catch (IOException | RuntimeException ex) {
            return "";
        }
    }

    /**
     * Builds a safe destination file name for an installed archive.
     *
     * @param preferredName name requested by the caller; may be blank
     * @param sourceName name of the staged download used as fallback
     * @return a sanitized name that still looks like a mod archive
     */
    private static String destinationName(String preferredName, String sourceName) {
        String name = sanitizeFileName(preferredName);
        if (name.isEmpty()) name = sanitizeFileName(sourceName);
        if (name.isEmpty()) name = "mod";
        return ensureArchiveExtension(name, sourceName);
    }

    /**
     * Removes path fragments and characters Windows refuses in a file name.
     *
     * @param raw candidate name, possibly a path or {@code null}
     * @return the bare file name, or an empty string when nothing usable remains
     */
    private static String sanitizeFileName(String raw) {
        if (raw == null) return "";
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            out.append(c < 0x20 || "<>:\"/\\|?*".indexOf(c) >= 0 ? '_' : c);
        }
        String cleaned = out.toString().trim();
        while (cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned.equals(".") || cleaned.equals("..") ? "" : cleaned;
    }

    /**
     * Guarantees the installed file is a mod archive by name, because the
     * game only scans {@code .jar} and {@code .zip} entries.
     *
     * @param name sanitized destination name
     * @param sourceName staged download name used to pick the extension
     * @return {@code name}, with the source extension appended when it has none
     */
    private static String ensureArchiveExtension(String name, String sourceName) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar") || lower.endsWith(".zip")) return name;
        return name + (sourceName.toLowerCase(Locale.ROOT).endsWith(".jar") ? ".jar" : ".zip");
    }

    /**
     * Finds a free {@code <stem>-<n><ext>} sibling for a taken destination.
     *
     * @param destination desired destination that already exists
     * @return the first free sibling name, or the destination itself when every candidate is taken
     */
    private static Path numberedSibling(Path destination) {
        String fileName = destination.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int index = 2; index < 1000; index++) {
            Path candidate = destination.resolveSibling(stem + "-" + index + extension);
            if (!Files.exists(candidate)) return candidate;
        }
        return destination;
    }

    /// Returns whether the file name selects a Mindustry mod archive.
    ///
    /// A trailing `.disabled` is ignored; both `.jar` and `.zip` archives count.
    ///
    /// @param file file to classify by name
    /// @return whether `file` is a mod archive, enabled or disabled
    public static boolean isModArchive(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".disabled")) {
            name = name.substring(0, name.length() - ".disabled".length());
        }
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    /// Renames the archive so its `.disabled` suffix reflects `enabled`.
    private static void renameArchive(MindustryLocalMod mod, boolean enabled) throws IOException {
        Path src = mod.getFile();
        String name = src.getFileName().toString();
        boolean disabled = name.toLowerCase(Locale.ROOT).endsWith(".disabled");
        if (enabled == !disabled) {
            return;
        }
        Path dst = enabled
                ? src.resolveSibling(name.substring(0, name.length() - ".disabled".length()))
                : src.resolveSibling(name + ".disabled");
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    /// Returns a displayable failure text for a batch result.
    private static String messageOf(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private static String enabledSettingKey(MindustryLocalMod mod) {
        String internalName = mod.getInternalName();
        if (internalName == null || internalName.isBlank()) {
            return null;
        }
        return "mod-" + internalName + "-enabled";
    }

    /**
     * Outcome of one archive install.
     *
     * @param installedFile file that now holds the mod
     * @param replacedFiles archives removed during the install, usually the previous version
     */
    public record InstallResult(Path installedFile, List<Path> replacedFiles) {
    }

    /**
     * Outcome of one batch operation.
     *
     * @param applied mods whose requested state was applied
     * @param failures per-file failure reason, keyed by file name; failed mods never appear in `applied`
     */
    public record BatchResult(List<MindustryLocalMod> applied, Map<String, String> failures) {
    }
}
