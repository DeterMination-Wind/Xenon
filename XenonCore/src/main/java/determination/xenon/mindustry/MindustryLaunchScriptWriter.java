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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/// Renders a standalone launch script for one instance.
///
/// The script repeats the exact command line, working directory and data
/// directory the launcher would use, including `MINDUSTRY_DATA_DIR`. Quoting is
/// platform-specific; Windows scripts use `"` and Unix scripts use `'`.
@NotNullByDefault
public final class MindustryLaunchScriptWriter {

    /// Writes a `.bat` script.
    ///
    /// @param target script path
    /// @param options launch options
    /// @throws IOException when the script cannot be written
    public static void writeWindows(Path target, LaunchOptions options) throws IOException {
        Files.writeString(target, renderWindows(options), StandardCharsets.UTF_8);
    }

    /// Writes a POSIX shell script.
    ///
    /// @param target script path
    /// @param options launch options
    /// @throws IOException when the script cannot be written
    public static void writeUnix(Path target, LaunchOptions options) throws IOException {
        Files.writeString(target, renderUnix(options), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(target,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows or a non-POSIX file system; the file is still usable.
        }
    }

    /// Renders the Windows script text.
    ///
    /// @param options launch options
    /// @return the script
    public static String renderWindows(LaunchOptions options) {
        StringBuilder script = new StringBuilder();
        script.append("@echo off\r\n");
        script.append("setlocal\r\n");
        script.append("set \"MINDUSTRY_DATA_DIR=")
                .append(escapeWindows(options.getDataDir().toAbsolutePath().toString()))
                .append("\"\r\n");
        script.append("cd /d \"")
                .append(escapeWindows(options.getWorkingDirectory().toAbsolutePath().toString()))
                .append("\"\r\n");
        script.append(joinCommand(options.buildCommandLine(), true)).append("\r\n");
        script.append("exit /b %ERRORLEVEL%\r\n");
        return script.toString();
    }

    /// Renders the POSIX script text.
    ///
    /// @param options launch options
    /// @return the script
    public static String renderUnix(LaunchOptions options) {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/sh\n");
        script.append("export MINDUSTRY_DATA_DIR=")
                .append(quoteUnix(options.getDataDir().toAbsolutePath().toString()))
                .append("\n");
        script.append("cd ")
                .append(quoteUnix(options.getWorkingDirectory().toAbsolutePath().toString()))
                .append(" || exit 1\n");
        script.append("exec ").append(joinCommand(options.buildCommandLine(), false)).append("\n");
        return script.toString();
    }

    /// Quotes every argument for the target shell.
    private static String joinCommand(List<String> command, boolean windows) {
        StringBuilder line = new StringBuilder();
        for (String argument : command) {
            if (line.length() > 0) line.append(' ');
            line.append(windows ? "\"" + escapeWindows(argument) + "\"" : quoteUnix(argument));
        }
        return line.toString();
    }

    /// Escapes a Windows batch argument.
    private static String escapeWindows(String value) {
        return value.replace("\"", "\"\"");
    }

    /// Quotes one POSIX argument.
    private static String quoteUnix(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private MindustryLaunchScriptWriter() {
    }
}
