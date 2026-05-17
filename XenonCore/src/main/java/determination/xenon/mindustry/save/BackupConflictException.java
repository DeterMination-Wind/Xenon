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

import java.io.IOException;
import java.nio.file.Path;

/**
 * Raised by {@link SaveBackupService#rename(Path, String)} (and any other
 * mutation that would clobber an existing on-disk file) when the target path
 * already exists.
 *
 * <p>The exception carries the absolute conflicting path so the UI / caller
 * can surface a useful diagnostic without re-deriving it.</p>
 */
public class BackupConflictException extends IOException {

    private final Path conflictingPath;

    public BackupConflictException(Path conflictingPath, String message) {
        super(message);
        this.conflictingPath = conflictingPath;
    }

    public BackupConflictException(Path conflictingPath, String message, Throwable cause) {
        super(message, cause);
        this.conflictingPath = conflictingPath;
    }

    /** The path that already exists and caused the conflict. May be {@code null}. */
    public Path getConflictingPath() {
        return conflictingPath;
    }
}
