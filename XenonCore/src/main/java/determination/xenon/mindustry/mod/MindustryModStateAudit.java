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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Audits the two mod signals a Mindustry instance carries.
///
/// <p>The game only honors {@code mod-<name>-enabled} settings keys, while the
/// launcher additionally uses the {@code .disabled} file suffix. Operations
/// outside the launcher (renames, manual settings edits, crash recovery) can
/// desynchronize the two; this audit lists the disagreements so the health
/// page can surface and repair them instead of silently vetoing one side.</p>
@NotNullByDefault
public final class MindustryModStateAudit {

    /// Folder-mod descriptor names, mirroring the game's archive-time lookup.
    private static final List<String> FOLDER_DESCRIPTORS =
            List.of("mod.json", "mod.hjson", "plugin.json", "plugin.hjson");

    /// One archive whose file suffix and settings key disagree.
    ///
    /// @param file archive the mismatch belongs to
    /// @param internalName game-normalized mod name
    /// @param suffixEnabled whether the file name lacks the {@code .disabled} suffix
    /// @param keyEnabled whether the settings key says the mod is enabled
    public record Mismatch(Path file, String internalName,
                           boolean suffixEnabled, boolean keyEnabled) {

        /// File name shown in the UI.
        public String fileName() {
            return file.getFileName().toString();
        }
    }

    /// Prevents instantiation of this utility class.
    private MindustryModStateAudit() {}

    /// Lists every archive whose suffix and settings flag disagree.
    ///
    /// @param dataDir Mindustry data directory
    /// @return mismatches in directory order; unreadable archives are skipped
    public static @Unmodifiable List<Mismatch> mismatches(Path dataDir) {
        Path modsDir = dataDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            return List.of();
        }
        Map<String, Object> settings = MindustrySettingsBin.readValues(dataDir);
        List<Mismatch> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file) || !MindustryModManager.isModArchive(file)) {
                    continue;
                }
                try {
                    MindustryLocalMod mod = MindustryModParser.parse(file);
                    String name = mod.getInternalName();
                    if (name.isBlank()) {
                        continue;
                    }
                    boolean suffixEnabled = !file.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(".disabled");
                    boolean keyEnabled = MindustrySettingsBin.getBool(settings,
                            "mod-" + name + "-enabled", true);
                    if (suffixEnabled != keyEnabled) {
                        result.add(new Mismatch(file, name, suffixEnabled, keyEnabled));
                    }
                } catch (IOException ignored) {
                    // Unreadable archives cannot be keyed; skip them.
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return List.copyOf(result);
    }

    /// Lists folder mods the game would load.
    ///
    /// <p>Renaming a folder to {@code *.disabled} does not hide it from the
    /// game, so these entries can only be toggled through settings keys.</p>
    ///
    /// @param dataDir Mindustry data directory
    /// @return folder names containing a descriptor at their root
    public static @Unmodifiable List<String> folderMods(Path dataDir) {
        Path modsDir = dataDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path dir : stream) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                for (String descriptor : FOLDER_DESCRIPTORS) {
                    if (Files.isRegularFile(dir.resolve(descriptor))) {
                        result.add(dir.getFileName().toString());
                        break;
                    }
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return List.copyOf(result);
    }

    /// Repairs one mismatch by forcing both signals to one side.
    ///
    /// @param dataDir Mindustry data directory
    /// @param mismatch mismatch to repair
    /// @param fileWins {@code true} to adopt the suffix state, {@code false} to
    ///     adopt the settings-key state
    /// @throws IOException when the archive cannot be parsed or written
    public static void sync(Path dataDir, Mismatch mismatch, boolean fileWins) throws IOException {
        MindustryLocalMod mod = MindustryModParser.parse(mismatch.file());
        boolean target = fileWins ? mismatch.suffixEnabled() : mismatch.keyEnabled();
        MindustryModManager.BatchResult result =
                new MindustryModManager(dataDir.resolve("mods"), dataDir).setEnabled(List.of(mod), target);
        if (!result.failures().isEmpty()) {
            throw new IOException(result.failures().values().iterator().next());
        }
    }
}
