/*
 * Xenon Launcher
 * Copyright (C) 2021-2026  Xenon contributors
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
package determination.xenon;

import determination.xenon.util.StringUtils;
import determination.xenon.util.io.JarUtils;
import determination.xenon.util.platform.Architecture;
import determination.xenon.util.platform.OperatingSystem;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.EnumSet;

/**
 * Stores metadata about Xenon Launcher.
 */
public final class Metadata {
    private Metadata() {
    }

    public static final String NAME = "Xenon";
    public static final String FULL_NAME = "Xenon Launcher";
    public static final String VERSION = System.getProperty("xenon.version.override", JarUtils.getAttribute("xenon.version", "@develop@"));

    public static final String TITLE = NAME + " " + VERSION;
    public static final String FULL_TITLE = FULL_NAME + " v" + VERSION;

    /** Mindustry desktop minimum (build &gt;= 140 needs 17, older builds can use 8). */
    public static final int MINIMUM_REQUIRED_JAVA_VERSION = 17;
    public static final int MINIMUM_SUPPORTED_JAVA_VERSION = 8;
    public static final int RECOMMENDED_JAVA_VERSION = 21;

    public static final String PUBLISH_URL = "https://github.com/TinyLake/Xenon";
    public static final String DOWNLOAD_URL = PUBLISH_URL + "/releases";
    public static final String XENON_UPDATE_URL = System.getProperty(
            "xenon.update_source.override",
            "https://api.github.com/repos/TinyLake/Xenon/releases/latest");
    public static final String MANUAL_UPDATE_URL = PUBLISH_URL + "/releases";

    public static final String DOCS_URL = PUBLISH_URL + "/blob/main/docs";
    public static final String CONTACT_URL = PUBLISH_URL + "/issues";
    public static final String CHANGELOG_URL = PUBLISH_URL + "/blob/main/CHANGELOG.md";
    public static final String EULA_URL = PUBLISH_URL + "/blob/main/LICENSE";
    public static final String GROUPS_URL = "https://space.bilibili.com/1433776051";
    public static final String QQ_GROUP = "188709300";

    /** Mindustry's officially supported public sources. */
    public static final String UPSTREAM_VANILLA_REPO = "Anuken/Mindustry";
    public static final String UPSTREAM_BE_REPO = "Anuken/MindustryBuilds";
    public static final String UPSTREAM_MINDUSTRY_X_REPO = "TinyLake/MindustryX";
    public static final String UPSTREAM_CN_ARC_REPO = "BlueWolf3434/Mindustry-CN-ARC";
    public static final String UPSTREAM_FOO_REPO = "mindustry-antigrief/mindustry-client";
    public static final String UPSTREAM_MOD_INDEX = "https://raw.githubusercontent.com/Anuken/mindustry-mods/master/mods.json";

    public static final String BUILD_CHANNEL = JarUtils.getAttribute("xenon.version.type", "nightly");
    public static final String GITHUB_SHA = JarUtils.getAttribute("xenon.version.hash", null);

    public static final Path CURRENT_DIRECTORY = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    /**
     * The launcher's global config directory.
     * <ul>
     *     <li>Windows: {@code %APPDATA%/Xenon}</li>
     *     <li>Linux/BSD: {@code $XDG_DATA_HOME/Xenon} or {@code ~/.xenon}</li>
     *     <li>macOS: {@code ~/Library/Application Support/Xenon}</li>
     * </ul>
     * Override with {@code xenon.home} system property or {@code XENON_USER_HOME} env var.
     */
    public static final Path XENON_GLOBAL_DIRECTORY;

    /** Local per-project config (alongside Xenon.jar): {@code ./.xenon/}. */
    public static final Path XENON_CURRENT_DIRECTORY;

    public static final Path DEPENDENCIES_DIRECTORY;

    static {
        String xenonHome = System.getProperty("xenon.home", System.getenv("XENON_USER_HOME"));
        if (StringUtils.isBlank(xenonHome)) {
            if (OperatingSystem.CURRENT_OS.isLinuxOrBSD()) {
                String xdgData = System.getenv("XDG_DATA_HOME");
                if (StringUtils.isNotBlank(xdgData)) {
                    XENON_GLOBAL_DIRECTORY = Path.of(xdgData, "Xenon").toAbsolutePath().normalize();
                } else {
                    XENON_GLOBAL_DIRECTORY = Path.of(System.getProperty("user.home"), ".xenon").toAbsolutePath().normalize();
                }
            } else if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS) {
                String appdata = System.getenv("APPDATA");
                XENON_GLOBAL_DIRECTORY = Path.of(appdata == null ? System.getProperty("user.home", ".") : appdata, "Xenon")
                        .toAbsolutePath().normalize();
            } else if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS) {
                XENON_GLOBAL_DIRECTORY = Path.of(System.getProperty("user.home"), "Library", "Application Support", "Xenon")
                        .toAbsolutePath().normalize();
            } else {
                XENON_GLOBAL_DIRECTORY = Path.of(System.getProperty("user.home", "."), "Xenon").toAbsolutePath().normalize();
            }
        } else {
            XENON_GLOBAL_DIRECTORY = Path.of(xenonHome).toAbsolutePath().normalize();
        }

        String xenonCurrentDir = System.getProperty("xenon.dir", System.getenv("XENON_LOCAL_HOME"));
        XENON_CURRENT_DIRECTORY = StringUtils.isNotBlank(xenonCurrentDir)
                ? Path.of(xenonCurrentDir).toAbsolutePath().normalize()
                : CURRENT_DIRECTORY.resolve(".xenon");

        String xenonDependencies = System.getProperty("xenon.dependencies.dir", System.getenv("XENON_DEPENDENCIES_DIR"));
        DEPENDENCIES_DIRECTORY = StringUtils.isNotBlank(xenonDependencies)
                ? Path.of(xenonDependencies).toAbsolutePath().normalize()
                : XENON_CURRENT_DIRECTORY.resolve("dependencies");
    }

    /** {@code <XENON_GLOBAL_DIRECTORY>/versions} — Mindustry client jars. */
    public static Path getVersionsDirectory() {
        return XENON_GLOBAL_DIRECTORY.resolve("versions");
    }

    /** {@code <XENON_GLOBAL_DIRECTORY>/servers} — Mindustry server instances. */
    public static Path getServersDirectory() {
        return XENON_GLOBAL_DIRECTORY.resolve("servers");
    }

    /** {@code <XENON_GLOBAL_DIRECTORY>/java} — auto-downloaded JDKs. */
    public static Path getJavaDirectory() {
        return XENON_GLOBAL_DIRECTORY.resolve("java");
    }

    /** {@code <XENON_GLOBAL_DIRECTORY>/caches} — download/version metadata caches. */
    public static Path getCachesDirectory() {
        return XENON_GLOBAL_DIRECTORY.resolve("caches");
    }

    /** {@code <XENON_GLOBAL_DIRECTORY>/modpacks} — Xenon Modpack imports/exports. */
    public static Path getModpacksDirectory() {
        return XENON_GLOBAL_DIRECTORY.resolve("modpacks");
    }

    public static boolean isStable() {
        return "stable".equals(BUILD_CHANNEL);
    }

    public static boolean isDev() {
        return "dev".equals(BUILD_CHANNEL);
    }

    public static boolean isNightly() {
        return !isStable() && !isDev();
    }

    public static @Nullable String getSuggestedJavaDownloadLink() {
        EnumSet<Architecture> supported;
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS)
            supported = EnumSet.of(Architecture.X86_64, Architecture.X86, Architecture.ARM64);
        else if (OperatingSystem.CURRENT_OS == OperatingSystem.LINUX)
            supported = EnumSet.of(Architecture.X86_64, Architecture.X86, Architecture.ARM64, Architecture.ARM32, Architecture.RISCV64, Architecture.LOONGARCH64);
        else if (OperatingSystem.CURRENT_OS == OperatingSystem.MACOS)
            supported = EnumSet.of(Architecture.X86_64, Architecture.ARM64);
        else
            supported = EnumSet.noneOf(Architecture.class);
        if (supported.contains(Architecture.SYSTEM_ARCH))
            return "https://adoptium.net/temurin/releases/?version=" + RECOMMENDED_JAVA_VERSION;
        return null;
    }
}
