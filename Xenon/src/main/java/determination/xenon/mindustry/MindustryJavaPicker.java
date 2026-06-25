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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Picks an installed JDK that satisfies a Mindustry version's
 * {@link MindustryVersion#getJavaReq() javaReq}.
 *
 * <p>Hands the heavy lifting (Adoptium download, on-disk catalog,
 * platform-specific binary names) to the existing
 * {@link JavaManager}, so Xenon shares one Java pool across
 * launches and the user's setting page is the single source of truth.</p>
 */
public final class MindustryJavaPicker {
    private MindustryJavaPicker() {
    }

    /**
     * Resolve the Java executable Xenon should spawn for {@code version}.
     * Resolution order:
     * <ol>
     *     <li>If {@link MindustryVersion#getJavaHome() javaHome} is set
     *         and looks valid, use {@code <home>/bin/java(.exe)}.</li>
     *     <li>Otherwise look through {@link JavaManager#getAllJava()}
     *         for the lowest-major runtime that meets {@code javaReq}.</li>
     *     <li>Fall back to the JVM Xenon itself is running on.</li>
     * </ol>
     */
    public static Path resolveJavaExecutable(MindustryVersion version) throws InterruptedException {
        return resolveJavaExecutable(version, Path.of("."));
    }

    /**
     * Resolve the Java executable, allowing relative Java-home overrides to be
     * resolved against the version's effective working directory.
     */
    public static Path resolveJavaExecutable(MindustryVersion version, Path versionRoot) throws InterruptedException {
        // 1. Explicit override.
        Path home = version.resolveJavaHome(versionRoot);
        if (home != null) {
            Path exe = JavaManager.getExecutable(home);
            if (Files.isRegularFile(exe)) {
                return exe;
            }
        }

        // 2. Search the registered Java pool.
        if (JavaManager.isInitialized()) {
            Collection<JavaRuntime> all = JavaManager.getAllJava();
            Optional<JavaRuntime> match = all.stream()
                    .filter(r -> r.getParsedVersion() >= version.getJavaReq())
                    .min(Comparator.comparingInt(JavaRuntime::getParsedVersion));
            if (match.isPresent()) {
                return match.get().getBinary();
            }
        }

        // 3. Fall back to the launcher's own JVM. Better than nothing,
        // and if it fails the user gets an actionable error in the log.
        return Path.of(System.getProperty("java.home"))
                .resolve(OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS ? "bin/java.exe" : "bin/java");
    }
}
