/*
 * Xenon Launcher
 * Copyright (C) 2020-2026  Xenon contributors
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
package determination.xenon.auth.offline;

import javafx.beans.binding.ObjectBinding;
import determination.xenon.auth.Account;
import determination.xenon.auth.AuthInfo;
import determination.xenon.auth.AuthenticationException;
import determination.xenon.auth.authlibinjector.AuthlibInjectorArtifactInfo;
import determination.xenon.auth.authlibinjector.AuthlibInjectorArtifactProvider;
import determination.xenon.auth.authlibinjector.AuthlibInjectorDownloadException;
import determination.xenon.auth.yggdrasil.Texture;
import determination.xenon.auth.yggdrasil.TextureType;
import determination.xenon.game.Arguments;
import determination.xenon.game.LaunchOptions;
import determination.xenon.util.StringUtils;
import determination.xenon.util.ToStringBuilder;
import determination.xenon.util.gson.UUIDTypeAdapter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static java.util.Objects.requireNonNull;
import static determination.xenon.util.Lang.mapOf;
import static determination.xenon.util.Pair.pair;

/**
 *
 * @author huang
 */
public class OfflineAccount extends Account {

    private final AuthlibInjectorArtifactProvider downloader;
    private final String username;
    private final UUID uuid;
    private Skin skin;

    protected OfflineAccount(AuthlibInjectorArtifactProvider downloader, String username, UUID uuid, Skin skin) {
        this.downloader = requireNonNull(downloader);
        this.username = requireNonNull(username);
        this.uuid = requireNonNull(uuid);
        this.skin = skin;

        if (StringUtils.isBlank(username)) {
            throw new IllegalArgumentException("Username cannot be blank");
        }
    }

    public AuthlibInjectorArtifactProvider getDownloader() {
        return downloader;
    }

    @Override
    public UUID getUUID() {
        return uuid;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getCharacter() {
        return username;
    }

    @Override
    public String getIdentifier() {
        return username + ":" + username;
    }

    public Skin getSkin() {
        return skin;
    }

    public void setSkin(Skin skin) {
        this.skin = skin;
        invalidate();
    }

    protected boolean loadAuthlibInjector(Skin skin) {
        return skin != null && skin.getType() != Skin.Type.DEFAULT;
    }

    public AuthInfo logInWithoutSkin() throws AuthenticationException {
        // Using "legacy" user type here because "mojang" user type may cause "invalid session token" or "disconnected" when connecting to a game server.
        return new AuthInfo(username, uuid, UUIDTypeAdapter.fromUUID(UUID.randomUUID()), AuthInfo.USER_TYPE_MSA, "{}");
    }

    @Override
    public AuthInfo logIn() throws AuthenticationException {
        AuthInfo authInfo = logInWithoutSkin();

        if (loadAuthlibInjector(skin)) {
            CompletableFuture<AuthlibInjectorArtifactInfo> artifactTask = CompletableFuture.supplyAsync(() -> {
                try {
                    return downloader.getArtifactInfo();
                } catch (IOException e) {
                    throw new CompletionException(new AuthlibInjectorDownloadException(e));
                }
            });

            AuthlibInjectorArtifactInfo artifact;
            try {
                artifact = artifactTask.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuthenticationException(e);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof AuthenticationException) {
                    throw (AuthenticationException) e.getCause();
                } else {
                    throw new AuthenticationException(e.getCause());
                }
            }

            try {
                return new OfflineAuthInfo(authInfo, artifact);
            } catch (Exception e) {
                throw new AuthenticationException(e);
            }
        } else {
            return authInfo;
        }
    }

    private class OfflineAuthInfo extends AuthInfo {
        private final AuthlibInjectorArtifactInfo artifact;
        private YggdrasilServer server;

        public OfflineAuthInfo(AuthInfo authInfo, AuthlibInjectorArtifactInfo artifact) {
            super(authInfo.getUsername(), authInfo.getUUID(), authInfo.getAccessToken(), USER_TYPE_MSA, authInfo.getUserProperties());

            this.artifact = artifact;
        }

        @Override
        public Arguments getLaunchArguments(LaunchOptions options) throws IOException {
            if (!options.isDaemon()) return null;

            server = new YggdrasilServer(0);
            server.start();

            try {
                server.addCharacter(new YggdrasilServer.Character(uuid, username,
                        skin != null ? skin.load(username).run() : null));
            } catch (IOException e) {
                // ignore
            } catch (Exception e) {
                throw new IOException(e);
            }

            return new Arguments().addJVMArguments(
                    "-javaagent:" + artifact.getLocation().toString() + "=" + "http://localhost:" + server.getListeningPort(),
                    "-Dauthlibinjector.side=client"
            );
        }

        @Override
        public void close() throws Exception {
            super.close();

            if (server != null)
                server.stop();
        }
    }

    @Override
    public AuthInfo playOffline() throws AuthenticationException {
        return logIn();
    }

    @Override
    public Map<Object, Object> toStorage() {
        return mapOf(
                pair("uuid", UUIDTypeAdapter.fromUUID(uuid)),
                pair("username", username),
                pair("skin", skin == null ? null : skin.toStorage())
        );
    }

    @Override
    public ObjectBinding<Optional<Map<TextureType, Texture>>> getTextures() {
        return super.getTextures();
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("username", username)
                .append("uuid", uuid)
                .toString();
    }

    @Override
    public int hashCode() {
        return username.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof OfflineAccount))
            return false;
        OfflineAccount another = (OfflineAccount) obj;
        return isPortable() == another.isPortable() && username.equals(another.username);
    }
}
