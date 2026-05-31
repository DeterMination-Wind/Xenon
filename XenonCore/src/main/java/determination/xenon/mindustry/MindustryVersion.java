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

import determination.xenon.util.platform.OperatingSystem;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Persistent metadata for one installed Mindustry client jar that Xenon
 * manages. Lives at {@code <config>/versions/<id>/version.json}.
 *
 * <p>This is the launcher-side analogue of HMCL's {@code Version}: a
 * lightweight POJO serialised by Gson, with no Minecraft-specific fields.
 * It is intentionally <em>not</em> a {@code Version} subclass because the
 * MC inheritance chain (libraries / asset index / quick-play / ...) does
 * not apply to Mindustry.</p>
 */
public final class MindustryVersion {
    /** Stable identifier picked by the user (also the directory name). */
    private String id;
    /** Display name (defaults to {@link #id} if absent). */
    private String name;
    private VersionVariant variant = VersionVariant.CUSTOM;
    /** Mindustry build number, or 0 for unknown / custom imports. */
    private int build;
    /** {@code stable}, {@code be} or {@code custom}. */
    private String buildType = "custom";
    /**
     * Jar path, relative to the version root if it lives there,
     * otherwise an absolute path.
     */
    private String jarPath;
    /**
     * Minimum Java major version required. Build &gt;= 140 needs 17,
     * older builds can use 8.
     */
    private int javaReq = 17;
    /**
     * Optional explicit Java home override. {@code null} = pick automatically
     * based on {@link #javaReq}.
     */
    private String javaHome;
    /** How to resolve {@code -Dmindustry.data.dir}. */
    private DataDirectoryPolicy dataDirPolicy = DataDirectoryPolicy.ISOLATED;
    /** Used only when {@link #dataDirPolicy} is {@link DataDirectoryPolicy#CUSTOM}. */
    private String customDataDir;
    /** Free-form JVM args appended after Xenon's defaults. */
    private String jvmArgs = "";
    /** Free-form game arguments appended after the jar. */
    private String gameArgs = "";

    /** Resolve the actual jar file given this version's root directory. */
    public Path resolveJar(Path versionRoot) {
        Objects.requireNonNull(versionRoot, "versionRoot");
        if (jarPath == null || jarPath.isEmpty()) {
            return versionRoot.resolve(id + ".jar");
        }
        Path p = Path.of(jarPath);
        return p.isAbsolute() ? p : versionRoot.resolve(jarPath);
    }

    /** Resolve the effective data directory for this version. */
    public Path resolveDataDir(Path versionRoot) {
        switch (dataDirPolicy) {
            case ISOLATED:
                return versionRoot.resolve(".data");
            case GLOBAL:
                return defaultMindustryDataDir();
            case CUSTOM:
                if (customDataDir == null || customDataDir.isEmpty()) {
                    return versionRoot.resolve(".data");
                }
                return Path.of(customDataDir).toAbsolutePath().normalize();
            default:
                throw new IllegalStateException("Unknown policy " + dataDirPolicy);
        }
    }

    /**
     * Mindustry's default per-OS data dir, mirroring
     * {@code ClientLauncher.dataDir} fall-back logic.
     */
    public static Path defaultMindustryDataDir() {
        switch (OperatingSystem.CURRENT_OS) {
            case WINDOWS:
                String appdata = System.getenv("APPDATA");
                Path winRoot = appdata != null
                        ? Path.of(appdata)
                        : Path.of(System.getProperty("user.home", "."));
                return winRoot.resolve("Mindustry").toAbsolutePath().normalize();
            case MACOS:
                return Path.of(System.getProperty("user.home", "."),
                        "Library", "Application Support", "Mindustry").toAbsolutePath().normalize();
            default:
                return Path.of(System.getProperty("user.home", "."), ".local", "share", "Mindustry")
                        .toAbsolutePath().normalize();
        }
    }

    // ---------- getters / setters ----------

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name == null || name.isEmpty() ? id : name; }
    public void setName(String name) { this.name = name; }

    public VersionVariant getVariant() { return variant == null ? VersionVariant.CUSTOM : variant; }
    public void setVariant(VersionVariant variant) { this.variant = variant; }

    public int getBuild() { return build; }
    public void setBuild(int build) { this.build = build; }

    public String getBuildType() { return buildType; }
    public void setBuildType(String buildType) { this.buildType = buildType; }

    public String getJarPath() { return jarPath; }
    public void setJarPath(String jarPath) { this.jarPath = jarPath; }

    public int getJavaReq() { return javaReq; }
    public void setJavaReq(int javaReq) { this.javaReq = javaReq; }

    public String getJavaHome() { return javaHome; }
    public void setJavaHome(String javaHome) { this.javaHome = javaHome; }

    public DataDirectoryPolicy getDataDirPolicy() { return dataDirPolicy == null ? DataDirectoryPolicy.ISOLATED : dataDirPolicy; }
    public void setDataDirPolicy(DataDirectoryPolicy dataDirPolicy) { this.dataDirPolicy = dataDirPolicy; }

    public String getCustomDataDir() { return customDataDir; }
    public void setCustomDataDir(String customDataDir) { this.customDataDir = customDataDir; }

    public String getJvmArgs() { return jvmArgs == null ? "" : jvmArgs; }
    public void setJvmArgs(String jvmArgs) { this.jvmArgs = jvmArgs; }

    public String getGameArgs() { return gameArgs == null ? "" : gameArgs; }
    public void setGameArgs(String gameArgs) { this.gameArgs = gameArgs; }
}
