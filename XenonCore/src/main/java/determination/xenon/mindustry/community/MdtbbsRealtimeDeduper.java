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
package determination.xenon.mindustry.community;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.LinkedHashSet;
import java.util.Set;

/// Bounded de-duplicator for realtime event ids.
///
/// Events can be replayed after a resume, and the same id must be delivered
/// once. The set keeps the newest [#CAPACITY] ids; older ids are evicted so a
/// long session cannot grow without bound.
@NotNullByDefault
final class MdtbbsRealtimeDeduper {

    /// Number of event ids remembered.
    public static final int CAPACITY = 512;

    private final Set<String> seen = new LinkedHashSet<>();

    /// Records one event id.
    ///
    /// @param id event id
    /// @return `true` when the id is new and should be delivered
    public synchronized boolean accept(String id) {
        if (id == null || id.isBlank() || !seen.add(id)) {
            return false;
        }
        while (seen.size() > CAPACITY) {
            var iterator = seen.iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    /// Forgets every remembered id; used after `resume_failed`.
    public synchronized void reset() {
        seen.clear();
    }

    /// Number of remembered ids.
    public synchronized int size() {
        return seen.size();
    }
}
