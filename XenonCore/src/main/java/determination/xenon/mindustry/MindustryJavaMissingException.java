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

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/// Signals that the Java executable selected for a Mindustry launch does not
/// exist on disk.
///
/// Xenon used to hand a stale runtime path straight to {@link ProcessBuilder},
/// which surfaced as the opaque `CreateProcess error=2` message. Throwing this
/// type instead lets the UI recognize the failure and offer to install a Java
/// runtime, and lets tests assert the exact missing path.
///
/// The exception is an [IOException] so existing launch plumbing keeps
/// compiling; callers that want the guidance dialog inspect the cause chain for
/// this type before falling back to a generic error dialog.
@NotNullByDefault
public final class MindustryJavaMissingException extends IOException {

    /// Java executable Xenon tried to spawn.
    private final Path javaExecutable;

    /// Creates an exception for a missing Java executable.
    ///
    /// @param javaExecutable the path that does not exist
    public MindustryJavaMissingException(Path javaExecutable) {
        this(javaExecutable, null);
    }

    /// Creates an exception that mentions the instance or operation context.
    ///
    /// @param javaExecutable the path that does not exist
    /// @param context        optional human-readable context, or `null`
    public MindustryJavaMissingException(Path javaExecutable, @Nullable String context) {
        super(message(Objects.requireNonNull(javaExecutable, "javaExecutable"), context));
        this.javaExecutable = javaExecutable;
    }

    /// Returns the Java executable that was not found.
    ///
    /// @return the missing executable path
    public Path getJavaExecutable() {
        return javaExecutable;
    }

    /// Builds the message shown in logs and generic error dialogs.
    private static String message(Path javaExecutable, @Nullable String context) {
        String detail = "Java executable not found: " + javaExecutable;
        return context == null || context.isBlank() ? detail : context + ": " + detail;
    }
}
