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
package determination.xenon.mindustry.server;

import determination.xenon.mindustry.DataDirectoryPolicy;
import determination.xenon.mindustry.MindustryVersion;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Persistent metadata for one Mindustry dedicated-server instance Xenon
 * manages. Lives at {@code <config>/servers/<sid>/server.json} and is
 * serialised by Gson.
 *
 * <p>Mirrors {@link MindustryVersion} for clients but only carries the
 * fields a headless server actually needs (jar, java, port, data dir,
 * auto-restart hooks, ScriptAgent toggle).</p>
 */
public final class ServerInstance {
    /** Stable identifier picked by the user (also the directory name). */
    private String id;
    /** Display name (defaults to {@link #id} if absent). */
    private String name;
    /**
     * Server jar path, relative to the server root if it lives there,
     * otherwise an absolute path.
     */
    private String jarPath;
    /** Minimum Java major version required to run this jar. */
    private int javaReq = 17;
    /**
     * Optional explicit Java home override. {@code null} = pick automatically
     * based on {@link #javaReq}.
     */
    private String javaHome;
    /** Whether ScriptAgent should be wired in when launching. */
    private boolean scriptAgent = false;
    /** Whether to auto-restart the server when it exits unexpectedly. */
    private boolean autoRestart = false;
    /** Maximum auto-restart attempts before giving up. */
    private int autoRestartMaxRetries = 5;
    /** Delay in seconds between auto-restart attempts. */
    private int autoRestartDelaySec = 10;
    /** Free-form JVM args appended after Xenon's defaults. */
    private String jvmArgs = "";
    /** Game/server port. Mindustry's default is 6567. */
    private int port = 6567;
    /** How to resolve {@code -Dmindustry.data.dir}. */
    private DataDirectoryPolicy dataDirPolicy = DataDirectoryPolicy.ISOLATED;
    /** Used only when {@link #dataDirPolicy} is {@link DataDirectoryPolicy#CUSTOM}. */
    private String customDataDir;

    /** Resolve the actual server jar file given this instance's root directory. */
    public Path resolveJar(Path serverRoot) {
        Objects.requireNonNull(serverRoot, "serverRoot");
        if (jarPath == null || jarPath.isEmpty()) {
            return serverRoot.resolve(id + ".jar");
        }
        Path p = Path.of(jarPath);
        return p.isAbsolute() ? p : serverRoot.resolve(jarPath);
    }

    /** Resolve the effective data directory for this server. */
    public Path resolveDataDir(Path serverRoot) {
        Objects.requireNonNull(serverRoot, "serverRoot");
        DataDirectoryPolicy policy = getDataDirPolicy();
        switch (policy) {
            case ISOLATED:
                return serverRoot.resolve(".data");
            case GLOBAL:
                return MindustryVersion.defaultMindustryDataDir();
            case CUSTOM:
                if (customDataDir == null || customDataDir.isEmpty()) {
                    return serverRoot.resolve(".data");
                }
                return Path.of(customDataDir).toAbsolutePath().normalize();
            default:
                throw new IllegalStateException("Unknown policy " + policy);
        }
    }

    // ---------- getters / setters ----------

    public String getId() { return id; }

    public void setId(String id) { this.id = id; }

    public String getName() { return name == null || name.isEmpty() ? id : name; }

    public void setName(String name) { this.name = name; }

    public String getJarPath() { return jarPath; }

    public void setJarPath(String jarPath) { this.jarPath = jarPath; }

    public int getJavaReq() { return javaReq; }

    public void setJavaReq(int javaReq) { this.javaReq = javaReq; }

    public String getJavaHome() { return javaHome; }

    public void setJavaHome(String javaHome) { this.javaHome = javaHome; }

    public boolean isScriptAgent() { return scriptAgent; }

    public void setScriptAgent(boolean scriptAgent) { this.scriptAgent = scriptAgent; }

    public boolean isAutoRestart() { return autoRestart; }

    public void setAutoRestart(boolean autoRestart) { this.autoRestart = autoRestart; }

    public int getAutoRestartMaxRetries() { return autoRestartMaxRetries; }

    public void setAutoRestartMaxRetries(int autoRestartMaxRetries) { this.autoRestartMaxRetries = autoRestartMaxRetries; }

    public int getAutoRestartDelaySec() { return autoRestartDelaySec; }

    public void setAutoRestartDelaySec(int autoRestartDelaySec) { this.autoRestartDelaySec = autoRestartDelaySec; }

    public String getJvmArgs() { return jvmArgs == null ? "" : jvmArgs; }

    public void setJvmArgs(String jvmArgs) { this.jvmArgs = jvmArgs; }

    public int getPort() { return port; }

    public void setPort(int port) { this.port = port; }

    public DataDirectoryPolicy getDataDirPolicy() { return dataDirPolicy == null ? DataDirectoryPolicy.ISOLATED : dataDirPolicy; }

    public void setDataDirPolicy(DataDirectoryPolicy dataDirPolicy) { this.dataDirPolicy = dataDirPolicy; }

    public String getCustomDataDir() { return customDataDir; }

    public void setCustomDataDir(String customDataDir) { this.customDataDir = customDataDir; }
}
