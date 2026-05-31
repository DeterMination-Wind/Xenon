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
package determination.xenon.mindustry.ui.server;

import determination.xenon.Metadata;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.download.GitHubReleaseClient;
import determination.xenon.mindustry.server.BeServerVersionList;
import determination.xenon.mindustry.server.MindustryXServerVersionList;
import determination.xenon.mindustry.server.ServerInstanceManager;
import determination.xenon.mindustry.server.ServerJarInstaller;
import determination.xenon.mindustry.server.VanillaServerVersionList;
import determination.xenon.mindustry.scriptagent.ScriptAgentInstaller;

/// Shared singleton-style service bundle for the server-management UI.
///
/// This keeps the list/detail panes on one `ServerInstanceManager` and one
/// `ServerRuntimeRegistry`, which is required for stable cross-page console
/// sessions and status badges.
public final class MindustryServerServices {

    private static volatile MindustryServerServices instance;

    private final ServerInstanceManager manager;
    private final GitHubReleaseClient githubClient;
    private final VanillaServerVersionList vanillaVersions;
    private final BeServerVersionList beVersions;
    private final MindustryXServerVersionList mindustryXVersions;
    private final ServerJarInstaller jarInstaller;
    private final ScriptAgentInstaller scriptAgentInstaller;

    private MindustryServerServices() {
        this.manager = new ServerInstanceManager(Metadata.getServersDirectory());
        this.githubClient = new GitHubReleaseClient(MindustryImportFlow.cachesDirectory());
        this.vanillaVersions = new VanillaServerVersionList(githubClient);
        this.beVersions = new BeServerVersionList(githubClient);
        this.mindustryXVersions = new MindustryXServerVersionList(githubClient);
        this.jarInstaller = new ServerJarInstaller(githubClient, manager);
        this.scriptAgentInstaller = new ScriptAgentInstaller(githubClient, manager);
    }

    public static MindustryServerServices getInstance() {
        MindustryServerServices local = instance;
        if (local == null) {
            synchronized (MindustryServerServices.class) {
                local = instance;
                if (local == null) {
                    local = new MindustryServerServices();
                    instance = local;
                }
            }
        }
        return local;
    }

    public ServerInstanceManager manager() {
        return manager;
    }

    public GitHubReleaseClient githubClient() {
        return githubClient;
    }

    public VanillaServerVersionList vanillaVersions() {
        return vanillaVersions;
    }

    public BeServerVersionList beVersions() {
        return beVersions;
    }

    public MindustryXServerVersionList mindustryXVersions() {
        return mindustryXVersions;
    }

    public ServerJarInstaller jarInstaller() {
        return jarInstaller;
    }

    public ScriptAgentInstaller scriptAgentInstaller() {
        return scriptAgentInstaller;
    }
}
