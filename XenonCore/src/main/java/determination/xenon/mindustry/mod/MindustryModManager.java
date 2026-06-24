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

import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
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

    public MindustryModManager(Path modsDir) {
        this.modsDir = Objects.requireNonNull(modsDir, "modsDir").toAbsolutePath().normalize();
    }

    public Path getModsDir() { return modsDir; }

    /**
     * Walk {@link #getModsDir()} (non-recursive) and parse every
     * {@code .jar} / {@code .zip} (optionally suffixed {@code .disabled}).
     * Archives that fail to parse are logged and skipped, never thrown.
     */
    public synchronized List<MindustryLocalMod> scan() {
        List<MindustryLocalMod> result = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) return result;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p)) continue;
                if (!isModArchive(p)) continue;
                try {
                    result.add(MindustryModParser.parse(p));
                } catch (IOException ex) {
                    Logger.LOG.log(System.Logger.Level.WARNING,
                            "Failed to parse Mindustry mod " + p + ": " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            Logger.LOG.log(System.Logger.Level.WARNING,
                    "Failed to list Mindustry mods dir " + modsDir, ex);
        }
        result = markDuplicateWinners(result);
        result.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
        return result;
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
        if (!name.toLowerCase(Locale.ROOT).endsWith(".disabled")) return;
        String stripped = name.substring(0, name.length() - ".disabled".length());
        Path dst = src.resolveSibling(stripped);
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Disable a mod by appending the {@code .disabled} suffix. */
    public void disable(MindustryLocalMod mod) throws IOException {
        Path src = mod.getFile();
        String name = src.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".disabled")) return;
        Path dst = src.resolveSibling(name + ".disabled");
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Permanently remove the underlying archive from disk. */
    public void delete(MindustryLocalMod mod) throws IOException {
        Files.deleteIfExists(mod.getFile());
    }

    /**
     * Copy a {@code .jar} / {@code .zip} into {@link #getModsDir()},
     * creating the directory if needed. The destination keeps the source
     * file name; existing mods with that name are overwritten.
     */
    public void install(Path zipOrJar) throws IOException {
        Objects.requireNonNull(zipOrJar, "zipOrJar");
        if (!Files.isRegularFile(zipOrJar)) {
            throw new IOException("Not a regular file: " + zipOrJar);
        }
        if (!isModArchive(zipOrJar)) {
            throw new IOException("Not a Mindustry mod archive: " + zipOrJar);
        }
        Files.createDirectories(modsDir);
        Path dst = modsDir.resolve(zipOrJar.getFileName().toString());
        Files.copy(zipOrJar, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean isModArchive(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".disabled")) {
            name = name.substring(0, name.length() - ".disabled".length());
        }
        return name.endsWith(".jar") || name.endsWith(".zip");
    }
}
