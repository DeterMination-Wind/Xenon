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
package determination.xenon.mindustry.save;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.time.Instant;

/// Metadata for one launcher-managed Mindustry data archive.
@NotNullByDefault
public record MindustrySaveArchive(
        /// Archive file under an instance's `save-archives/` directory.
        Path file,
        /// Archive size in bytes.
        long size,
        /// Last modified timestamp of the archive file.
        Instant lastModified,
        /// True when the archive contains `settings.bin` at its root.
        boolean hasSettings,
        /// Number of `.msav` map files under `maps/`.
        int maps,
        /// Number of `.msav` sector/save files under `saves/`.
        int sectors,
        /// Number of `.mrep` replay files under `saves/`.
        int replays,
        /// Number of mod archives under `mods/`.
        int mods
) {
}
