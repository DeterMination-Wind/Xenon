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
package determination.xenon.mindustry.scriptagent;

import determination.xenon.mindustry.download.GitHubAsset;
import determination.xenon.mindustry.download.GitHubRelease;
import determination.xenon.mindustry.download.GitHubReleaseClient;
import determination.xenon.mindustry.server.ServerInstance;
import determination.xenon.mindustry.server.ServerInstanceManager;
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Pull the latest ScriptAgent4Mindustry release off GitHub and lay it down
 * on top of an existing {@link ServerInstance}.
 *
 * <p>Two assets are expected on the upstream release:
 * <ul>
 *   <li>a {@code scriptagent*.jar} mod, dropped at
 *       {@code <dataDir>/mods/scriptagent.jar};</li>
 *   <li>a {@code scriptagent*.zip} bundle, expanded under
 *       {@code <dataDir>/config/scripts/}.</li>
 * </ul>
 * After a successful install the instance's
 * {@link ServerInstance#setScriptAgent(boolean)} flag is flipped on and
 * persisted via {@link ServerInstanceManager#save(ServerInstance)}.</p>
 *
 * <p>Asset naming on the upstream side is not perfectly stable, so the
 * matcher is a case-insensitive prefix-and-extension test
 * ({@code (?i)scriptagent.*\.jar}/{@code .zip}). If the conventions ever
 * shift further, this matcher is the place to extend.</p>
 */
public final class ScriptAgentInstaller {

    /** Upstream repo as of 2026/05. Keep the constant centralised. */
    public static final String REPO = "way-zer/ScriptAgent4Mindustry";

    private static final Pattern JAR_PATTERN = Pattern.compile("(?i)scriptagent.*\\.jar");
    private static final Pattern ZIP_PATTERN = Pattern.compile("(?i)scriptagent.*\\.zip");

    private final GitHubReleaseClient client;
    private final ServerInstanceManager manager;

    public ScriptAgentInstaller(GitHubReleaseClient client, ServerInstanceManager mgr) {
        this.client = Objects.requireNonNull(client, "client");
        this.manager = Objects.requireNonNull(mgr, "mgr");
    }

    /**
     * Install (or overwrite) ScriptAgent for the given server.
     *
     * @param serverId id of a previously-saved {@link ServerInstance}
     * @throws IOException if the release lookup, download, or extraction fails,
     *                     or the server id is unknown
     */
    public void installLatest(String serverId) throws IOException {
        Objects.requireNonNull(serverId, "serverId");
        ServerInstance inst = manager.get(serverId).orElseThrow(
                () -> new IOException("server instance not found: " + serverId));

        GitHubRelease release = client.getLatestRelease(REPO);
        if (release == null) {
            throw new IOException("No ScriptAgent release found at " + REPO);
        }
        List<GitHubAsset> assets = release.getAssets();
        GitHubAsset jarAsset = pickFirst(assets, JAR_PATTERN);
        GitHubAsset zipAsset = pickFirst(assets, ZIP_PATTERN);
        if (jarAsset == null) {
            throw new IOException("ScriptAgent release " + release.getTagName()
                    + " has no jar asset matching " + JAR_PATTERN.pattern());
        }
        if (zipAsset == null) {
            throw new IOException("ScriptAgent release " + release.getTagName()
                    + " has no zip asset matching " + ZIP_PATTERN.pattern());
        }

        Path serverRoot = manager.getServerRoot(serverId);
        Path dataDir = inst.resolveDataDir(serverRoot);
        Path modsDir = dataDir.resolve("mods");
        Path scriptsDir = dataDir.resolve("config").resolve("scripts");
        Files.createDirectories(modsDir);
        Files.createDirectories(scriptsDir);

        Path jarTarget = modsDir.resolve("scriptagent.jar");
        Logger.LOG.info("Downloading ScriptAgent jar " + jarAsset.getName() + " -> " + jarTarget);
        client.downloadAsset(jarAsset, jarTarget, null);

        Path zipTmp = Files.createTempFile("xenon-scriptagent-", ".zip");
        try {
            Logger.LOG.info("Downloading ScriptAgent scripts " + zipAsset.getName() + " -> " + zipTmp);
            client.downloadAsset(zipAsset, zipTmp, null);
            extractZip(zipTmp, scriptsDir);
        } finally {
            try {
                Files.deleteIfExists(zipTmp);
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }

        inst.setScriptAgent(true);
        manager.save(inst);
        Logger.LOG.info("ScriptAgent " + release.getTagName() + " installed for server " + serverId);
    }

    /** First asset whose {@link GitHubAsset#getName()} matches {@code pattern}. */
    private static GitHubAsset pickFirst(List<GitHubAsset> assets, Pattern pattern) {
        if (assets == null) return null;
        for (GitHubAsset a : assets) {
            if (a == null || a.getName() == null) continue;
            if (pattern.matcher(a.getName()).matches()) {
                return a;
            }
        }
        return null;
    }

    /** Extract {@code zip} into {@code targetDir}, guarding against zip-slip. */
    private static void extractZip(Path zip, Path targetDir) throws IOException {
        Path normRoot = targetDir.toAbsolutePath().normalize();
        try (InputStream raw = Files.newInputStream(zip);
             ZipInputStream zis = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }
                Path resolved = normRoot.resolve(name).normalize();
                if (!resolved.startsWith(normRoot)) {
                    throw new IOException("Refusing to extract entry escaping target dir: " + name);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Path parent = resolved.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Files.copy(zis, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }
}
