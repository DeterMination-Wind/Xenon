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

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Source of remote {@link MindustryRemoteMod} listings — typically the
 * community {@code mindustry-mods} index, but the abstraction leaves room
 * for self-hosted aggregators or local fixtures used in tests.
 *
 * <p>Implementations are expected to be idempotent: {@link #refresh()}
 * may hit the network and update an internal cache, while
 * {@link #findByName(String)} should serve from the most recent snapshot
 * without re-fetching.</p>
 */
public abstract class MindustryModRepository {

    /**
     * Pull the latest listing from the upstream source. Implementations
     * are free to persist results on disk and return the cached copy on
     * subsequent failures, but every call is allowed to perform I/O.
     *
     * @return all mods known to the repository, in upstream order
     * @throws IOException if neither the network nor a usable cache is
     *     available
     */
    public abstract List<MindustryRemoteMod> refresh() throws IOException;

    /**
     * Look up a single entry by its short {@code name}. Matching is
     * case-insensitive on the {@code name} field; implementations may
     * additionally match on {@code repo} for convenience.
     *
     * @return the matching mod or {@link Optional#empty()} if no entry
     *     matches the most recent snapshot
     */
    public abstract Optional<MindustryRemoteMod> findByName(String name);
}
