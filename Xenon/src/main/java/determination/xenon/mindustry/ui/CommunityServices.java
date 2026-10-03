/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package determination.xenon.mindustry.ui;

import determination.xenon.Metadata;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient;
import determination.xenon.mindustry.community.MdtbbsCommunity;
import determination.xenon.mindustry.community.MdtbbsForumClient;
import determination.xenon.mindustry.community.MdtbbsGameContentClient;
import determination.xenon.mindustry.community.MdtbbsPresenceService;
import determination.xenon.mindustry.community.MdtbbsSocialClient;
import determination.xenon.mindustry.netplay.MdtbbsNetplayManager;
import org.jetbrains.annotations.NotNullByDefault;

import static determination.xenon.util.i18n.I18n.i18n;

/// Shared MDTBBS account and forum services for the launcher UI.
///
/// The session lives under the global Xenon directory so it survives profile
/// switches. Services are created lazily on first use and never log tokens.
@NotNullByDefault
public final class CommunityServices {
    /// Account coordinator backed by the persisted OAuth session.
    private static final MdtbbsCommunity COMMUNITY = new MdtbbsCommunity(
            Metadata.XENON_GLOBAL_DIRECTORY.resolve("community").resolve("mdtbbs-session.json"));
    /// Forum client sharing the coordinator's token provider.
    private static final MdtbbsForumClient FORUM = new MdtbbsForumClient(COMMUNITY.api());
    /// Game-content client sharing the coordinator's token provider.
    private static final MdtbbsGameContentClient GAME_CONTENT =
            new MdtbbsGameContentClient(COMMUNITY.api());
    /// Cloud-save client sharing the coordinator's token provider.
    private static final MdtbbsCloudSavesClient CLOUD_SAVES =
            new MdtbbsCloudSavesClient(COMMUNITY.api());
    /// Social client sharing the coordinator's token provider.
    private static final MdtbbsSocialClient SOCIAL = new MdtbbsSocialClient(COMMUNITY.api());
    /// Presence heartbeat service bound to the account coordinator.
    private static final MdtbbsPresenceService PRESENCE = new MdtbbsPresenceService(COMMUNITY);
    /// Shared MDTBBS multiplayer session manager.
    private static final MdtbbsNetplayManager NETPLAY = new MdtbbsNetplayManager(COMMUNITY);
    /// Whether the netplay activity sink has been installed.
    private static volatile boolean netplayWired;

    /// Shared account coordinator.
    public static MdtbbsCommunity community() { return COMMUNITY; }

    /// Shared forum client.
    public static MdtbbsForumClient forum() { return FORUM; }

    /// Shared blueprint and map client.
    public static MdtbbsGameContentClient gameContent() { return GAME_CONTENT; }

    /// Shared cloud-save client.
    public static MdtbbsCloudSavesClient cloudSaves() { return CLOUD_SAVES; }

    /// Shared social and multiplayer-signalling client.
    public static MdtbbsSocialClient social() { return SOCIAL; }

    /// Shared presence heartbeat service.
    public static MdtbbsPresenceService presence() { return PRESENCE; }

    /// Shared MDTBBS multiplayer session manager.
    ///
    /// The activity sink is installed on first access because it needs the
    /// localised activity name, which is only available after I18n starts.
    public static MdtbbsNetplayManager netplay() {
        if (!netplayWired) {
            synchronized (CommunityServices.class) {
                if (!netplayWired) {
                    NETPLAY.setActivitySink((sessionId, hosting) -> {
                        if (sessionId == null || sessionId.isBlank()) {
                            PRESENCE.clearPlaying();
                        } else {
                            PRESENCE.publishPlaying(i18n("xenon.netplay.mdtbbs.activity"), null, sessionId);
                        }
                    });
                    netplayWired = true;
                }
            }
        }
        return NETPLAY;
    }

    private CommunityServices() {
    }
}
