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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
    private final Path modsDir;
    private final Path dataDir;

    public MindustryModManager(Path modsDir) {
        this(modsDir, inferDataDir(modsDir));
    }

    public MindustryModManager(Path modsDir, Path dataDir) {
        this.modsDir = Objects.requireNonNull(modsDir, "modsDir").toAbsolutePath().normalize();
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
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
    public void enable(MindustryLocalMod mod) throws IOException {
        Path src = mod.getFile();
        String name = src.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".disabled")) {
            String stripped = name.substring(0, name.length() - ".disabled".length());
            Path dst = src.resolveSibling(stripped);
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        writeEnabledSetting(mod, true);
    }

    /** Disable a mod by appending the {@code .disabled} suffix. */
    public void disable(MindustryLocalMod mod) throws IOException {
        Path src = mod.getFile();
        String name = src.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".disabled")) {
            Path dst = src.resolveSibling(name + ".disabled");
            Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
        writeEnabledSetting(mod, false);
    }

    /** Remove the underlying archive, preferring the platform trash. */
    public void delete(MindustryLocalMod mod) throws IOException {
        FileUtils.deleteSafely(mod.getFile());
    }

    /**
     * Copy a {@code .jar} / {@code .zip} into {@link #getModsDir()},
     * creating the directory if needed. The destination keeps the source
     * file name; existing mods with that name are overwritten and older
     * archives of the same mod are removed.
     */
    public void install(Path zipOrJar) throws IOException {
        Objects.requireNonNull(zipOrJar, "zipOrJar");
        install(zipOrJar, zipOrJar.getFileName().toString());
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
    public InstallResult install(Path zipOrJar, String preferredFileName) throws IOException {
        Objects.requireNonNull(zipOrJar, "zipOrJar");
        if (!Files.isRegularFile(zipOrJar)) {
            throw new IOException("Not a regular file: " + zipOrJar);
        }
        if (!isModArchive(zipOrJar)) {
            throw new IOException("Not a Mindustry mod archive: " + zipOrJar);
        }
        if (!MindustryModParser.containsDescriptor(zipOrJar)) {
            // Release bundles (Mod.zip wrapping Mod.jar) and source archives
            // look like mods by name, but Mindustry never loads them.
            throw new MindustryModParseException("No mod.json / mod.hjson / plugin.json / plugin.hjson in "
                    + zipOrJar);
        }
        Files.createDirectories(modsDir);

        String internalName = internalNameOf(zipOrJar);
        String desiredName = destinationName(preferredFileName, zipOrJar.getFileName().toString());

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
        Files.copy(zipOrJar, dst, StandardCopyOption.REPLACE_EXISTING);
        return new InstallResult(dst, List.copyOf(replaced));
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

    private void writeEnabledSetting(MindustryLocalMod mod, boolean enabled) throws IOException {
        String key = enabledSettingKey(mod);
        if (key == null) {
            return;
        }
        MindustrySettingsBin.putValuesOrThrow(dataDir, Collections.singletonMap(key, enabled));
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
}
