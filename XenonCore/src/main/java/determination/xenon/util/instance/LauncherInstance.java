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
package determination.xenon.util.instance;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// One running launcher process registered in a shared instance directory.
///
/// Instances that share the same data root write their records into the same
/// directory, so every process can discover the others and decide whether a
/// newly started launcher should focus an existing window or open a new one.
///
/// @param pid process id of the launcher process
/// @param port loopback port of the instance control server
/// @param token shared secret used to authenticate control requests
/// @param version launcher version of the process
/// @param jarPath launcher jar of the process, or `null` for class-path runs
/// @param startedAt epoch millis when the process registered itself, used to
///                  order instances by startup time
@NotNullByDefault
public record LauncherInstance(long pid, int port, String token, String version,
                               @Nullable String jarPath, long startedAt) {
}
