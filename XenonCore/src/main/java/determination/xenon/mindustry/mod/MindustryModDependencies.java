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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/// Dependency-graph helpers for the mods installed in one instance.
///
/// A dependency loop makes Mindustry's loader fail with a generic crash rather
/// than a "mod X requires Y" message, and nothing in the game lists the loop
/// before launch. This helper walks the graph Xenon already parses from the
/// descriptors and reports each loop once.
@NotNullByDefault
public final class MindustryModDependencies {

    /// Built-in dependency names that always resolve.
    private static final Set<String> BUILT_IN = Set.of("mindustry", "arc", "mindustryx");

    /// Prevents instantiation of this utility class.
    private MindustryModDependencies() {}

    /// Finds every dependency loop among `mods`.
    ///
    /// Each loop is returned in dependency order with the first name repeated at
    /// the end (`alpha → beta → alpha`), and every loop is reported once no
    /// matter which mod the walk started from.
    ///
    /// @param mods mods to inspect; entries without an internal name are ignored
    /// @return one name list per loop, empty when the graph is acyclic
    public static @Unmodifiable List<java.util.List<String>> findCycles(List<MindustryLocalMod> mods) {
        Map<String, MindustryLocalMod> byName = new LinkedHashMap<>();
        for (MindustryLocalMod mod : mods) {
            String name = mod.getInternalName();
            if (name == null || name.isBlank()) {
                continue;
            }
            byName.putIfAbsent(name.toLowerCase(Locale.ROOT), mod);
        }

        List<java.util.List<String>> cycles = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();
        for (String start : byName.keySet()) {
            Deque<String> path = new ArrayDeque<>();
            Set<String> pathSet = new LinkedHashSet<>();
            walk(start, byName, path, pathSet, reported, cycles);
        }
        return List.copyOf(cycles);
    }

    /// Depth-first walk from one node, recording the loops it closes.
    ///
    /// @param current  lowercase internal name being visited
    /// @param byName   installed mods keyed by lowercase internal name
    /// @param path     current DFS path
    /// @param pathSet  set view of `path`
    /// @param reported canonical loop keys already reported
    /// @param out      cycle sink
    private static void walk(String current, Map<String, MindustryLocalMod> byName,
                             Deque<String> path, Set<String> pathSet,
                             Set<String> reported, List<java.util.List<String>> out) {
        MindustryLocalMod mod = byName.get(current);
        if (mod == null) {
            return;
        }
        if (pathSet.contains(current)) {
            List<String> loop = new ArrayList<>();
            boolean inLoop = false;
            for (String node : path) {
                if (node.equals(current)) {
                    inLoop = true;
                }
                if (inLoop) {
                    loop.add(node);
                }
            }
            if (loop.isEmpty()) {
                return;
            }
            loop.add(current);
            if (reported.add(canonicalLoop(loop))) {
                out.add(List.copyOf(loop));
            }
            return;
        }
        pathSet.add(current);
        path.addLast(current);
        for (String dependency : mod.getDependencies()) {
            if (dependency == null || dependency.isBlank()) {
                continue;
            }
            String depKey = dependency.toLowerCase(Locale.ROOT);
            if (BUILT_IN.contains(depKey) || !byName.containsKey(depKey)) {
                continue;
            }
            walk(depKey, byName, path, pathSet, reported, out);
        }
        path.removeLast();
        pathSet.remove(current);
    }

    /// Rotates a loop so the same cycle always produces the same key.
    ///
    /// @param loop nodes of the loop, with the first node repeated at the end
    /// @return canonical key for the cycle
    private static String canonicalLoop(List<String> loop) {
        int size = loop.size() - 1;
        if (size <= 0) {
            return String.join("->", loop);
        }
        int smallest = 0;
        for (int i = 1; i < size; i++) {
            if (loop.get(i).compareTo(loop.get(smallest)) < 0) {
                smallest = i;
            }
        }
        List<String> rotated = new ArrayList<>(size + 1);
        for (int i = 0; i < size; i++) {
            rotated.add(loop.get((smallest + i) % size));
        }
        rotated.add(rotated.get(0));
        return String.join("->", rotated);
    }
}
