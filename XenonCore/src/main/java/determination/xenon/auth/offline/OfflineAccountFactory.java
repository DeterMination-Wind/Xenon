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

import determination.xenon.auth.AccountFactory;
import determination.xenon.auth.CharacterSelector;
import determination.xenon.auth.authlibinjector.AuthlibInjectorArtifactProvider;
import determination.xenon.util.gson.UUIDTypeAdapter;

import java.util.Map;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;
import static determination.xenon.util.Lang.tryCast;

/**
 *
 * @author huangyuhui
 */
public final class OfflineAccountFactory extends AccountFactory<OfflineAccount> {
    private final AuthlibInjectorArtifactProvider downloader;

    public OfflineAccountFactory(AuthlibInjectorArtifactProvider downloader) {
        this.downloader = downloader;
    }

    @Override
    public AccountLoginType getLoginType() {
        return AccountLoginType.USERNAME;
    }

    public OfflineAccount create(String username, UUID uuid) {
        return new OfflineAccount(downloader, username, uuid, null);
    }

    @Override
    public OfflineAccount create(CharacterSelector selector, String username, String password, ProgressCallback progressCallback, Object additionalData) {
        AdditionalData data;
        UUID uuid;
        Skin skin;
        if (additionalData != null) {
            data = (AdditionalData) additionalData;
            uuid = data.uuid == null ? getUUIDFromUserName(username) : data.uuid;
            skin = data.skin;
        } else {
            uuid = getUUIDFromUserName(username);
            skin = null;
        }
        return new OfflineAccount(downloader, username, uuid, skin);
    }

    @Override
    public OfflineAccount fromStorage(Map<Object, Object> storage) {
        String username = tryCast(storage.get("username"), String.class)
                .orElseThrow(() -> new IllegalStateException("Offline account configuration malformed."));
        UUID uuid = tryCast(storage.get("uuid"), String.class)
                .map(UUIDTypeAdapter::fromString)
                .orElse(getUUIDFromUserName(username));
        Skin skin = Skin.fromStorage(tryCast(storage.get("skin"), Map.class).orElse(null));

        return new OfflineAccount(downloader, username, uuid, skin);
    }

    public static UUID getUUIDFromUserName(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(UTF_8));
    }

    public static class AdditionalData {
        private final UUID uuid;
        private final Skin skin;

        public AdditionalData(UUID uuid, Skin skin) {
            this.uuid = uuid;
            this.skin = skin;
        }
    }

}
