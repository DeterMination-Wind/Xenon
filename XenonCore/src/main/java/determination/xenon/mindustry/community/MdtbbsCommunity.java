/*
 * Xenon Launcher
 * Copyright (C) 2026  Xenon contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package determination.xenon.mindustry.community;

import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

/// Launcher-side MDTBBS account coordinator.
///
/// Owns the persisted OAuth session, refreshes the access token on demand, and
/// exposes the authenticated API client. The public client id is read from the
/// `xenon.mdtbbs.clientId` system property, then `XENON_MDTBBS_CLIENT_ID`, and
/// finally the built-in default. Keep the default empty until the launcher's
/// public client has been registered in the MindAuth developer center; the UI
/// hides account actions while it is blank.
@NotNullByDefault
public final class MdtbbsCommunity implements MdtbbsApiClient.TokenProvider {
    /// System property that overrides the built-in public client id.
    public static final String CLIENT_ID_PROPERTY = "xenon.mdtbbs.clientId";
    /// Environment variable that overrides the built-in public client id.
    public static final String CLIENT_ID_ENV = "XENON_MDTBBS_CLIENT_ID";
    /// Built-in public client id registered in the MindAuth developer center
    /// with the loopback redirect URI `http://127.0.0.1:0/oauth/callback`.
    public static final String DEFAULT_CLIENT_ID = "0bec1a5ab14d022055452bcbe6a5ec9a";
    /// Scopes requested by the launcher across all MDTBBS features.
    public static final String REQUESTED_SCOPES = String.join(" ",
            "openid", "profile",
            "forum.read", "forum.write", "notification.read",
            "resource.read", "resource.download", "resource.upload",
            "friends.read", "presence.read", "presence.write",
            "multiplayer.read", "multiplayer.write",
            "game_content.saves.read", "game_content.saves.write",
            "game_content.saves.delete");
    /// Refresh the access token this long before it actually expires.
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(2);

    /// Public client id used by this instance.
    private final String clientId;
    /// Persistence for the OAuth session.
    private final MdtbbsSessionStore store;
    /// Interactive login and refresh flow.
    private final MdtbbsOauthClient oauth;
    /// REST client for `/api/v1`.
    private final MdtbbsApiClient api;
    /// Current session, or `null` when logged out.
    private @Nullable MdtbbsOauthSession session;

    /// Creates a coordinator for the given session file.
    ///
    /// @param sessionFile JSON file used to persist the OAuth session
    public MdtbbsCommunity(Path sessionFile) {
        this(resolveClientId(), new MdtbbsSessionStore(sessionFile), new MdtbbsOauthClient());
    }

    /// Creates a coordinator with injected components for tests.
    ///
    /// @param clientId public client id
    /// @param store    session persistence
    /// @param oauth    OAuth flow
    MdtbbsCommunity(String clientId, MdtbbsSessionStore store, MdtbbsOauthClient oauth) {
        this.clientId = clientId;
        this.store = store;
        this.oauth = oauth;
        this.session = store.load().orElse(null);
        this.api = new MdtbbsApiClient(this);
    }

    /// Public client id used for OAuth requests.
    public String clientId() { return clientId; }

    /// Whether a public client id has been configured.
    public boolean isConfigured() { return !clientId.isBlank(); }

    /// Whether a session is currently loaded.
    public synchronized boolean isLoggedIn() { return session != null; }

    /// Current session, or `null` when logged out.
    public synchronized @Nullable MdtbbsOauthSession session() { return session; }

    /// Runs the interactive browser login and persists the granted session.
    ///
    /// @param openBrowser callback that opens the authorization URL
    /// @throws IOException when no client id is configured or the flow fails
    public void login(Consumer<String> openBrowser) throws IOException {
        if (!isConfigured()) {
            throw new IOException("No MDTBBS client id is configured");
        }
        MdtbbsOauthSession granted = oauth.authorize(clientId, REQUESTED_SCOPES, openBrowser);
        synchronized (this) {
            session = granted;
            store.save(granted);
        }
    }

    /// Revokes and forgets the current session.
    public void logout() {
        MdtbbsOauthSession current;
        synchronized (this) {
            current = session;
            session = null;
            store.clear();
        }
        if (current != null) oauth.revoke(clientId, current);
    }

    @Override
    public synchronized @Nullable String accessToken() {
        MdtbbsOauthSession current = session;
        if (current == null) return null;
        if (!current.isExpiringWithin(REFRESH_MARGIN)) return current.accessToken();
        try {
            MdtbbsOauthSession refreshed = oauth.refresh(clientId, current);
            session = refreshed;
            store.save(refreshed);
            return refreshed.accessToken();
        } catch (IOException e) {
            Logger.LOG.warning("MDTbbs token refresh failed: " + e.getMessage());
            return null;
        }
    }

    /// REST client bound to this account's token.
    public MdtbbsApiClient api() { return api; }

    /// Resolves the public client id from system property, environment, then default.
    ///
    /// @return configured client id, possibly blank
    public static String resolveClientId() {
        String property = System.getProperty(CLIENT_ID_PROPERTY);
        if (property != null && !property.isBlank()) return property.trim();
        String environment = System.getenv(CLIENT_ID_ENV);
        if (environment != null && !environment.isBlank()) return environment.trim();
        return DEFAULT_CLIENT_ID;
    }
}
