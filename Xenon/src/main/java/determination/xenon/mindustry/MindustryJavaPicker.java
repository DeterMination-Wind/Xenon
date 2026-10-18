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

import determination.xenon.java.JavaManager;
import determination.xenon.java.JavaRuntime;
import determination.xenon.util.platform.OperatingSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;

/// Picks an installed JDK that satisfies a Mindustry version's
/// {@link MindustryVersion#getJavaReq() javaReq}.
///
/// Hands the heavy lifting (Adoptium download, on-disk catalog,
/// platform-specific binary names) to the existing
/// {@link JavaManager}, so Xenon shares one Java pool across
/// launches and the user's setting page is the single source of truth.
///
/// Candidates whose executable disappeared from disk are skipped instead of
/// being handed to [ProcessBuilder]: a stale Java entry used to surface as the
/// opaque `CreateProcess error=2`. Only when no candidate is usable does the
/// picker throw [MindustryJavaMissingException], which the UI turns into the
/// "install Java" guidance dialog.
@NotNullByDefault
public final class MindustryJavaPicker {
    private MindustryJavaPicker() {
    }

    /// Resolve the Java executable Xenon should spawn for `version`.
    ///
    /// Resolution order:
    /// <ol>
    ///     <li>If {@link MindustryVersion#getJavaHome() javaHome} is set
    ///         and {@code <home>/bin/java(.exe)} exists, use it.</li>
    ///     <li>Otherwise look through {@link JavaManager#getAllJava()}
    ///         for the lowest-major runtime that meets {@code javaReq} and
    ///         still exists on disk.</li>
    ///     <li>Fall back to the JVM Xenon itself is running on.</li>
    /// </ol>
    ///
    /// @param version the instance about to launch
    /// @return an existing Java executable
    /// @throws MindustryJavaMissingException when every candidate is missing
    public static Path resolveJavaExecutable(MindustryVersion version)
            throws InterruptedException, MindustryJavaMissingException {
        return resolveJavaExecutable(version, Path.of("."));
    }

    /// Resolve the Java executable, allowing relative Java-home overrides to be
    /// resolved against the version's effective working directory.
    ///
    /// @param version     the instance about to launch
    /// @param versionRoot the instance root used to resolve relative paths
    /// @return an existing Java executable
    /// @throws MindustryJavaMissingException when every candidate is missing
    public static Path resolveJavaExecutable(MindustryVersion version, Path versionRoot)
            throws InterruptedException, MindustryJavaMissingException {
        @Nullable Path home = version.resolveJavaHome(versionRoot);
        @Nullable Path explicitJava = home == null ? null : JavaManager.getExecutable(home);
        @Nullable Collection<JavaRuntime> pool = JavaManager.isInitialized()
                ? JavaManager.getAllJava()
                : null;
        return chooseJavaExecutable(explicitJava, pool, version.getJavaReq(), launcherJava(),
                Files::isRegularFile);
    }

    /// Pure resolution rule behind [#resolveJavaExecutable(MindustryVersion, Path)].
    ///
    /// The inputs are passed in instead of read from [JavaManager] so the
    /// decision can be unit tested without touching the on-disk Java catalog.
    ///
    /// @param explicitJava  instance-specific executable, or `null`
    /// @param pool          registered runtimes, or `null` when the catalog is not ready
    /// @param javaReq       minimum Java major version the instance needs
    /// @param launcherJava  executable of the JVM currently running Xenon
    /// @param exists        existence check applied to every candidate
    /// @return the first usable candidate
    /// @throws MindustryJavaMissingException when no candidate exists; it carries
    ///         the instance-specific path when one was configured, otherwise the
    ///         launcher JVM path
    static Path chooseJavaExecutable(@Nullable Path explicitJava,
                                     @Nullable Collection<JavaRuntime> pool,
                                     int javaReq,
                                     Path launcherJava,
                                     Predicate<Path> exists) throws MindustryJavaMissingException {
        if (explicitJava != null && exists.test(explicitJava)) {
            return explicitJava;
        }

        if (pool != null) {
            Optional<JavaRuntime> match = pool.stream()
                    .filter(runtime -> runtime.getParsedVersion() >= javaReq)
                    .filter(runtime -> exists.test(runtime.getBinary()))
                    .min(Comparator.comparingInt(JavaRuntime::getParsedVersion));
            if (match.isPresent()) {
                return match.get().getBinary();
            }
        }

        if (exists.test(launcherJava)) {
            return launcherJava;
        }

        throw new MindustryJavaMissingException(explicitJava != null ? explicitJava : launcherJava);
    }

    /// The Java executable of the JVM Xenon is currently running on.
    private static Path launcherJava() {
        return Path.of(System.getProperty("java.home"))
                .resolve(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? "bin/java.exe" : "bin/java");
    }
}
