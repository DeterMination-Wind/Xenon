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
package determination.xenon.mindustry.cloud;

import determination.xenon.mindustry.download.GitHubReleaseClient;
import determination.xenon.mindustry.download.ProgressCallback;
import determination.xenon.mindustry.mod.GitHubDirectInstaller;
import determination.xenon.mindustry.mod.MindustryModManager;
import determination.xenon.mindustry.mod.MindustryModsIndexRepository;
import determination.xenon.mindustry.mod.MindustryRemoteMod;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Re-downloads Mods that an instance archive listed but did not pack.
///
/// Only Mods present in the public `Anuken/Mindustry-mods` index can be filled;
/// everything else is returned in [FillResult#unresolved] so the UI can explain
/// the gap. Failures are per-Mod and never abort the whole fill.
@NotNullByDefault
public final class MindustryModFillService {

    /// One fill attempt.
    ///
    /// @param installed  Mod ids that were restored from the public index
    /// @param unresolved Mod ids that could not be restored
    public record FillResult(List<String> installed, List<String> unresolved) {
        /// Copies both lists.
        public FillResult {
            installed = List.copyOf(installed);
            unresolved = List.copyOf(unresolved);
        }
    }

    /// Fills the missing Mods of one instance.
    ///
    /// @param dataDir  instance data directory
    /// @param missing  Mod ids reported by the archive installer
    /// @param cacheRoot launcher cache root for the index and GitHub downloads
    /// @param progress optional download progress receiver
    /// @return which Mods were installed and which remain missing
    public FillResult fill(Path dataDir, List<String> missing, Path cacheRoot,
                           @Nullable ProgressCallback progress) {
        if (missing.isEmpty()) {
            return new FillResult(List.of(), List.of());
        }
        Map<String, MindustryRemoteMod> index = loadIndex(cacheRoot);
        MindustryModManager manager = new MindustryModManager(
                dataDir.resolve("mods"), dataDir);
        GitHubDirectInstaller installer = new GitHubDirectInstaller(
                new GitHubReleaseClient(cacheRoot), manager);
        List<String> installed = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (String id : missing) {
            MindustryRemoteMod remote = index.get(id.toLowerCase(Locale.ROOT));
            if (remote == null || remote.getRepo() == null || remote.getRepo().isBlank()) {
                unresolved.add(id);
                continue;
            }
            try {
                installer.installLatest(remote.getRepo(), progress);
                installed.add(id);
            } catch (IOException | RuntimeException e) {
                Logger.LOG.info("Cannot restore Mod " + id + " from "
                        + remote.getRepo() + ": " + e.getMessage());
                unresolved.add(id);
            }
        }
        return new FillResult(installed, unresolved);
    }

    /// Loads the public index, preferring a refresh and falling back to cache.
    private static Map<String, MindustryRemoteMod> loadIndex(Path cacheRoot) {
        MindustryModsIndexRepository repository = new MindustryModsIndexRepository(cacheRoot);
        List<MindustryRemoteMod> mods;
        try {
            mods = repository.refresh();
        } catch (IOException | RuntimeException e) {
            Logger.LOG.info("Mod index refresh failed, using the cached copy: " + e.getMessage());
            try {
                mods = repository.loadCache();
            } catch (RuntimeException cached) {
                mods = List.of();
            }
        }
        Map<String, MindustryRemoteMod> result = new HashMap<>();
        for (MindustryRemoteMod mod : mods) {
            if (mod.getName() != null && !mod.getName().isBlank()) {
                result.putIfAbsent(mod.getName().toLowerCase(Locale.ROOT), mod);
            }
        }
        return result;
    }
}
