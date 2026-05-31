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
import determination.xenon.mindustry.download.GitHubAsset;
import determination.xenon.mindustry.download.GitHubReleaseClient;
import determination.xenon.mindustry.download.MindustryRemoteVersion;
import determination.xenon.mindustry.download.ProgressCallback;
import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Downloads a server jar resolved by a {@link ServerVersionList} into the
 * Xenon-managed {@code <config>/servers/<id>/} layout, then registers a
 * fresh {@link ServerInstance} with {@link ServerInstanceManager}.
 *
 * <p>The jar always lands at {@code server-release.jar} inside the server
 * root regardless of upstream filename; that keeps the on-disk layout
 * uniform across vanilla / BE / MindustryX and matches the convention
 * used by Mindustry's own dedicated-server scripts.</p>
 */
public final class ServerJarInstaller {

    /** On-disk filename Xenon uses for every variant's server jar. */
    public static final String SERVER_JAR_NAME = "server-release.jar";

    /** Build number at which Mindustry started requiring Java 17. */
    private static final int JAVA_17_MIN_BUILD = 140;

    private final GitHubReleaseClient client;
    private final ServerInstanceManager serverMgr;

    public ServerJarInstaller(GitHubReleaseClient client, ServerInstanceManager serverMgr) {
        this.client = Objects.requireNonNull(client, "client");
        this.serverMgr = Objects.requireNonNull(serverMgr, "serverMgr");
    }

    /**
     * Download the server jar for {@code version} and persist a new
     * {@link ServerInstance} under {@code id}. Reports progress via
     * {@code cb}; pass {@code null} to suppress.
     *
     * @return the freshly registered {@link ServerInstance}
     * @throws IOException on download or filesystem failure
     */
    public ServerInstance installServer(String id,
                                        MindustryRemoteVersion version,
                                        ProgressCallback cb) throws IOException {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("server id must be set");
        }
        Objects.requireNonNull(version, "version");
        if (version.getDownloadUrl() == null || version.getDownloadUrl().isEmpty()) {
            throw new IOException("remote version has no download URL: " + version);
        }

        Path serverRoot = serverMgr.getServerRoot(id);
        Files.createDirectories(serverRoot);
        Path target = serverRoot.resolve(SERVER_JAR_NAME);

        // GitHubReleaseClient#downloadAsset only needs name/size/url to
        // stream a binary; a synthesized asset is enough here.
        GitHubAsset asset = new GitHubAsset(
                SERVER_JAR_NAME,
                version.getSize(),
                version.getDownloadUrl(),
                "application/java-archive");

        Logger.LOG.info("Xenon downloading server " + id + " (" + version.getVariant()
                + " build " + version.getBuild() + ") -> " + target);
        client.downloadAsset(asset, target, cb);

        ServerInstance inst = new ServerInstance();
        inst.setId(id);
        inst.setName(id);
        inst.setVariant(version.getVariant());
        inst.setBuild(version.getBuild());
        inst.setBuildType(version.getBuildType());
        inst.setUpstreamTag(version.getTagName());
        inst.setJarPath(SERVER_JAR_NAME);
        inst.setPort(6567);
        inst.setJavaReq(version.getBuild() >= JAVA_17_MIN_BUILD ? 17 : 8);
        inst.setDataDirPolicy(DataDirectoryPolicy.ISOLATED);

        serverMgr.save(inst);
        Logger.LOG.info("Xenon registered server instance " + id);
        return inst;
    }
}
