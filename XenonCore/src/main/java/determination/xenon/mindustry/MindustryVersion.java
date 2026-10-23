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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

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
@NotNullByDefault
public final class MindustryVersion {
    /** Folder name of the official Mindustry data directory inside the OS app-data root. */
    public static final String DATA_DIR_NAME = "Mindustry";

    /** Stable identifier picked by the user (also the directory name). */
    private @Nullable String id;
    /** Display name (defaults to {@link #id} if absent). */
    private @Nullable String name;
    private VersionVariant variant = VersionVariant.CUSTOM;
    /** Mindustry build number, or 0 for unknown / custom imports. */
    private int build;
    /** {@code stable}, {@code be} or {@code custom}. */
    private String buildType = "custom";
    /**
     * Jar path, relative to the version root if it lives there,
     * otherwise an absolute path.
     */
    private @Nullable String jarPath;
    /**
     * Minimum Java major version required. Build &gt;= 140 needs 17,
     * older builds can use 8.
     */
    private int javaReq = 17;
    /**
     * Optional explicit Java home override. {@code null} = pick automatically
     * based on {@link #javaReq}.
     */
    private @Nullable String javaHome;
    /**
     * Optional process working directory override. Steam installs keep native
     * libraries and launcher metadata beside the game executable, so launching
     * their bundled jar from Xenon's version directory is not equivalent.
     */
    private @Nullable String workingDirectory;
    /** How to resolve {@code -Dmindustry.data.dir}. */
    private DataDirectoryPolicy dataDirPolicy = DataDirectoryPolicy.ISOLATED;
    /** Used only when {@link #dataDirPolicy} is {@link DataDirectoryPolicy#CUSTOM}. */
    private @Nullable String customDataDir;
    /** Free-form JVM args appended after Xenon's defaults. */
    private @Nullable String jvmArgs = "";
    /** Free-form game arguments appended after the jar. */
    private @Nullable String gameArgs = "";
    /** Optional file name under {@code save-archives/} to use as the launch data archive. */
    private @Nullable String launchSaveFile;
    /**
     * Whether the launcher may run the read-only cloud-save check for this instance.
     *
     * Defaults to {@code true}. Manifests written before this option existed
     * have no such field, and Gson leaves the initialiser value in place, so
     * they opt in automatically. The check only reads local files and cloud
     * metadata; it never uploads, downloads or merges.
     */
    private boolean cloudSaveCheckEnabled = true;

    /// How the instance uses MDTBBS cloud saves.
    public enum CloudMode {
        /// Upload one save file into a slot (the historical behaviour).
        SINGLE_SAVE,
        /// Upload and restore the whole instance data directory.
        INSTANCE_ARCHIVE
    }

    /// Cloud mode of this instance; older manifests default to single-save.
    private CloudMode cloudMode = CloudMode.SINGLE_SAVE;
    /// Whether the instance automatically pulls before launch and pushes after exit.
    private boolean cloudAutoSync;
    /// Cloud slot used by the archive mode, or empty.
    private String cloudArchiveSlotId = "";
    /// Single-level user group, or empty.
    private String group = "";
    /// Whether a finished session creates an automatic save backup.
    private boolean autoBackupEnabled = true;
    /// Minimum session length before an automatic backup runs.
    private int autoBackupMinMinutes = 20;
    /// Number of automatic backups kept per instance.
    private int autoBackupKeep = 20;
    /// Development project directory, or empty.
    private String devProjectDir = "";
    /// Shell command used to build the development project.
    private String devBuildCommand = "";
    /// Glob selecting the build artifact, relative to the project directory.
    private String devArtifactGlob = "";
    /// Whether source changes trigger a rebuild.
    private boolean devAutoRebuild;
    /// Whether a successful build restarts the game.
    private boolean devAutoRestart;

    /** Runtime-only versions root that owns this instance; Gson never persists it. */
    private transient @Nullable Path repositoryRoot;

    /** Resolve the actual jar file given this version's root directory. */
    public Path resolveJar(Path versionRoot) {
        Objects.requireNonNull(versionRoot, "versionRoot");
        if (jarPath == null || jarPath.isEmpty()) {
            return versionRoot.resolve(id + ".jar");
        }
        Path p = Path.of(jarPath);
        return p.isAbsolute() ? p : versionRoot.resolve(jarPath);
    }

    /** Resolve the process working directory for this version. */
    public Path resolveWorkingDirectory(Path versionRoot) {
        Objects.requireNonNull(versionRoot, "versionRoot");
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return versionRoot.toAbsolutePath().normalize();
        }
        Path p = Path.of(workingDirectory);
        return (p.isAbsolute() ? p : versionRoot.resolve(p)).toAbsolutePath().normalize();
    }

    /** Resolve the optional Java home override for this version. */
    public @Nullable Path resolveJavaHome(Path versionRoot) {
        Objects.requireNonNull(versionRoot, "versionRoot");
        if (javaHome == null || javaHome.isBlank()) {
            return null;
        }
        Path p = Path.of(javaHome);
        return (p.isAbsolute() ? p : resolveWorkingDirectory(versionRoot).resolve(p)).toAbsolutePath().normalize();
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
                return winRoot.resolve(DATA_DIR_NAME).toAbsolutePath().normalize();
            case MACOS:
                return Path.of(System.getProperty("user.home", "."),
                        "Library", "Application Support", DATA_DIR_NAME).toAbsolutePath().normalize();
            default:
                return Path.of(System.getProperty("user.home", "."), ".local", "share", DATA_DIR_NAME)
                        .toAbsolutePath().normalize();
        }
    }

    // ---------- getters / setters ----------

    public @Nullable String getId() { return id; }

    public void setId(@Nullable String id) { this.id = id; }

    public String getName() { return name == null || name.isEmpty() ? (id == null ? "" : id) : name; }

    public void setName(@Nullable String name) { this.name = name; }

    public VersionVariant getVariant() { return variant == null ? VersionVariant.CUSTOM : variant; }

    public void setVariant(@Nullable VersionVariant variant) { this.variant = variant; }

    public int getBuild() { return build; }

    public void setBuild(int build) { this.build = build; }

    public String getBuildType() { return buildType == null || buildType.isBlank() ? "custom" : buildType; }

    public void setBuildType(@Nullable String buildType) { this.buildType = buildType; }

    public @Nullable String getJarPath() { return jarPath; }

    public void setJarPath(@Nullable String jarPath) { this.jarPath = jarPath; }

    public int getJavaReq() { return javaReq; }

    public void setJavaReq(int javaReq) { this.javaReq = javaReq; }

    public @Nullable String getJavaHome() { return javaHome; }

    public void setJavaHome(@Nullable String javaHome) { this.javaHome = javaHome; }

    public @Nullable String getWorkingDirectory() { return workingDirectory; }

    public void setWorkingDirectory(@Nullable String workingDirectory) { this.workingDirectory = workingDirectory; }

    public DataDirectoryPolicy getDataDirPolicy() { return dataDirPolicy == null ? DataDirectoryPolicy.ISOLATED : dataDirPolicy; }

    public void setDataDirPolicy(@Nullable DataDirectoryPolicy dataDirPolicy) { this.dataDirPolicy = dataDirPolicy; }

    public @Nullable String getCustomDataDir() { return customDataDir; }

    public void setCustomDataDir(@Nullable String customDataDir) { this.customDataDir = customDataDir; }

    public String getJvmArgs() { return jvmArgs == null ? "" : jvmArgs; }

    public void setJvmArgs(@Nullable String jvmArgs) { this.jvmArgs = jvmArgs; }

    public String getGameArgs() { return gameArgs == null ? "" : gameArgs; }

    public void setGameArgs(@Nullable String gameArgs) { this.gameArgs = gameArgs; }

    public @Nullable String getLaunchSaveFile() { return launchSaveFile; }

    public void setLaunchSaveFile(@Nullable String launchSaveFile) { this.launchSaveFile = launchSaveFile; }

    /// Whether the launcher may run the read-only cloud-save check for this instance.
    ///
    /// @return `true` when the startup check may compare local saves with the cloud
    public boolean isCloudSaveCheckEnabled() { return cloudSaveCheckEnabled; }

    /// Enables or disables the read-only cloud-save check for this instance.
    ///
    /// @param cloudSaveCheckEnabled desired state
    public void setCloudSaveCheckEnabled(boolean cloudSaveCheckEnabled) {
        this.cloudSaveCheckEnabled = cloudSaveCheckEnabled;
    }

    /// Cloud mode of this instance.
    ///
    /// @return the configured mode, never `null`
    public CloudMode getCloudMode() {
        return cloudMode == null ? CloudMode.SINGLE_SAVE : cloudMode;
    }

    /// Sets the cloud mode.
    ///
    /// @param cloudMode desired mode, or `null` to restore single-save
    public void setCloudMode(@Nullable CloudMode cloudMode) {
        this.cloudMode = cloudMode == null ? CloudMode.SINGLE_SAVE : cloudMode;
    }

    /// Whether automatic cloud sync is enabled for this instance.
    ///
    /// @return whether the launcher pulls before launch and pushes after exit
    public boolean isCloudAutoSync() { return cloudAutoSync; }

    /// Enables or disables automatic cloud sync.
    ///
    /// @param cloudAutoSync desired state
    public void setCloudAutoSync(boolean cloudAutoSync) { this.cloudAutoSync = cloudAutoSync; }

    /// Cloud slot used by archive mode, or empty.
    ///
    /// @return the slot id, or an empty string
    public String getCloudArchiveSlotId() {
        return cloudArchiveSlotId == null ? "" : cloudArchiveSlotId;
    }

    /// Sets the archive-mode cloud slot.
    ///
    /// @param cloudArchiveSlotId slot id, or `null` to clear
    public void setCloudArchiveSlotId(@Nullable String cloudArchiveSlotId) {
        this.cloudArchiveSlotId = cloudArchiveSlotId == null ? "" : cloudArchiveSlotId;
    }

    /// Single-level user group, or empty.
    ///
    /// @return the group name, or an empty string
    public String getGroup() { return group == null ? "" : group; }

    /// Sets the user group.
    ///
    /// @param group group name, or `null` to clear
    public void setGroup(@Nullable String group) { this.group = group == null ? "" : group; }

    /// Whether a finished session creates an automatic backup.
    ///
    /// @return whether automatic backups are enabled
    public boolean isAutoBackupEnabled() { return autoBackupEnabled; }

    /// Enables or disables automatic backups.
    ///
    /// @param autoBackupEnabled desired state
    public void setAutoBackupEnabled(boolean autoBackupEnabled) {
        this.autoBackupEnabled = autoBackupEnabled;
    }

    /// Minimum session length before an automatic backup runs.
    ///
    /// @return minutes, at least 1
    public int getAutoBackupMinMinutes() { return Math.max(1, autoBackupMinMinutes); }

    /// Sets the automatic-backup threshold.
    ///
    /// @param autoBackupMinMinutes minutes, clamped to at least 1
    public void setAutoBackupMinMinutes(int autoBackupMinMinutes) {
        this.autoBackupMinMinutes = Math.max(1, autoBackupMinMinutes);
    }

    /// Number of automatic backups kept.
    ///
    /// @return the retention count, at least 1
    public int getAutoBackupKeep() { return Math.max(1, autoBackupKeep); }

    /// Sets the automatic-backup retention count.
    ///
    /// @param autoBackupKeep count, clamped to at least 1
    public void setAutoBackupKeep(int autoBackupKeep) {
        this.autoBackupKeep = Math.max(1, autoBackupKeep);
    }

    /// Development project directory, or empty.
    ///
    /// @return the configured directory, or an empty string
    public String getDevProjectDir() { return devProjectDir == null ? "" : devProjectDir; }

    /// Sets the development project directory.
    ///
    /// @param devProjectDir directory, or `null` to clear
    public void setDevProjectDir(@Nullable String devProjectDir) {
        this.devProjectDir = devProjectDir == null ? "" : devProjectDir;
    }

    /// Shell command used to build the development project.
    ///
    /// @return the command, or an empty string
    public String getDevBuildCommand() { return devBuildCommand == null ? "" : devBuildCommand; }

    /// Sets the development build command.
    ///
    /// @param devBuildCommand command, or `null` to clear
    public void setDevBuildCommand(@Nullable String devBuildCommand) {
        this.devBuildCommand = devBuildCommand == null ? "" : devBuildCommand;
    }

    /// Glob selecting the build artifact.
    ///
    /// @return the glob, relative to the project directory
    public String getDevArtifactGlob() { return devArtifactGlob == null ? "" : devArtifactGlob; }

    /// Sets the artifact glob.
    ///
    /// @param devArtifactGlob glob, or `null` to clear
    public void setDevArtifactGlob(@Nullable String devArtifactGlob) {
        this.devArtifactGlob = devArtifactGlob == null ? "" : devArtifactGlob;
    }

    /// Whether source changes trigger a rebuild.
    ///
    /// @return whether automatic rebuilds are enabled
    public boolean isDevAutoRebuild() { return devAutoRebuild; }

    /// Enables or disables automatic rebuilds.
    ///
    /// @param devAutoRebuild desired state
    public void setDevAutoRebuild(boolean devAutoRebuild) {
        this.devAutoRebuild = devAutoRebuild;
    }

    /// Whether a successful build restarts the game.
    ///
    /// @return whether automatic restarts are enabled
    public boolean isDevAutoRestart() { return devAutoRestart; }

    /// Enables or disables automatic restarts.
    ///
    /// @param devAutoRestart desired state
    public void setDevAutoRestart(boolean devAutoRestart) {
        this.devAutoRestart = devAutoRestart;
    }

    /**
     * Returns the runtime-only versions root that loaded or saved this instance.
     *
     * @return the owning repository root, or {@code null} for a new detached instance
     */
    public @Nullable Path getRepositoryRoot() { return repositoryRoot; }

    /**
     * Associates this instance with the repository root that owns it.
     *
     * @param repositoryRoot the owning root, or {@code null} to detach it
     */
    public void setRepositoryRoot(@Nullable Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot == null
                ? null
                : repositoryRoot.toAbsolutePath().normalize();
    }
}
