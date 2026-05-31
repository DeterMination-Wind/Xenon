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
package determination.xenon.mindustry.server;

import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.mod.MindustryLocalMod;
import determination.xenon.mindustry.mod.MindustryModManager;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Adapter that hands the existing W5.1 {@link MindustryModManager} a
 * server-scoped {@code mods/} directory.
 *
 * <p>Dedicated servers store their mods at {@code <dataDir>/mods}
 * (Mindustry treats client and server mod folders identically), so the
 * bridge simply resolves the data directory for the given
 * {@link ServerInstance}, ensures {@code mods/} exists, and returns a
 * fresh manager pointed at it. Each call returns a new manager
 * instance — the manager itself holds no state worth caching.</p>
 */
public final class ServerModsBridge {

    private ServerModsBridge() {
    }

    /**
     * Build a {@link MindustryModManager} that operates on
     * {@code <dataDir>/mods} for {@code inst}. The directory is created
     * if it does not exist yet.
     *
     * @throws UncheckedIOException if the {@code mods/} directory cannot
     *         be created
     */
    public static MindustryModManager forServer(ServerInstance inst,
                                                ServerInstanceManager mgr) {
        Objects.requireNonNull(inst, "inst");
        Objects.requireNonNull(mgr, "mgr");
        Path modsDir = inst.resolveDataDir(mgr.getServerRoot(inst.getId()))
                .resolve("mods");
        try {
            Files.createDirectories(modsDir);
        } catch (IOException ex) {
            Logger.LOG.warning("Failed to create server mods dir "
                    + modsDir + ": " + ex.getMessage());
            throw new UncheckedIOException(ex);
        }
        return new MindustryModManager(modsDir);
    }

    /// Copy the current mod set from a client instance into `inst`.
    ///
    /// Existing files in the target `mods/` directory are removed first so the
    /// server ends up mirroring the chosen client instance as closely as the
    /// on-disk archive set allows.
    public static void syncFromClient(ServerInstance inst,
                                      ServerInstanceManager mgr,
                                      MindustryVersion clientVersion,
                                      Path clientVersionRoot) throws IOException {
        Objects.requireNonNull(inst, "inst");
        Objects.requireNonNull(mgr, "mgr");
        Objects.requireNonNull(clientVersion, "clientVersion");
        Objects.requireNonNull(clientVersionRoot, "clientVersionRoot");

        Path sourceModsDir = clientVersion.resolveDataDir(clientVersionRoot).resolve("mods");
        Path targetModsDir = inst.resolveDataDir(mgr.getServerRoot(inst.getId())).resolve("mods");
        Files.createDirectories(targetModsDir);

        try (var stream = Files.list(targetModsDir)) {
            for (Path file : stream.toList()) {
                if (Files.isRegularFile(file)) {
                    Files.deleteIfExists(file);
                }
            }
        }

        if (!Files.isDirectory(sourceModsDir)) {
            return;
        }
        try (var stream = Files.list(sourceModsDir)) {
            for (Path file : stream.toList()) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                Files.copy(file, targetModsDir.resolve(file.getFileName().toString()),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /// Snapshot of local mods currently installed for `inst`.
    public static @UnmodifiableView List<MindustryLocalMod> listServerMods(ServerInstance inst,
                                                                           ServerInstanceManager mgr) {
        MindustryModManager manager = forServer(inst, mgr);
        return List.copyOf(new ArrayList<>(manager.scan()));
    }
}
