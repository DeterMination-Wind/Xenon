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

import determination.xenon.mindustry.mod.MindustryModManager;
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
