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
package determination.xenon.mindustry;

/**
 * Where the launched Mindustry process should write its data
 * ({@code saves/}, {@code mods/}, {@code maps/}, {@code schematics/},
 * {@code settings.bin}, {@code crashes/}, ...).
 *
 * <p>The chosen policy resolves to a path that is passed to the JVM via
 * {@code -Dmindustry.data.dir=<path>}. Mindustry's
 * {@code ClientLauncher} honours that property and uses it as the data
 * root, so this is how Xenon achieves per-version isolation without
 * patching the game.</p>
 */
public enum DataDirectoryPolicy {
    /**
     * Each version gets its own {@code .data/} directory under
     * {@code <config>/versions/<vid>/.data/}. This is the recommended
     * default — saves, mods and crashes never leak across versions.
     */
    ISOLATED,
    /**
     * Use the OS-default Mindustry data directory (where the official
     * launcher writes). Useful for users who want their existing saves
     * to be available to every Xenon-managed version.
     */
    GLOBAL,
    /**
     * Use a user-supplied custom path. Resolution is the caller's
     * responsibility.
     */
    CUSTOM
}
