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
package determination.xenon.auth.authlibinjector;

import determination.xenon.auth.AuthenticationException;
import determination.xenon.auth.yggdrasil.YggdrasilProvider;
import determination.xenon.util.gson.UUIDTypeAdapter;

import java.net.URI;
import java.util.UUID;

public class AuthlibInjectorProvider implements YggdrasilProvider {

    private final String apiRoot;

    public AuthlibInjectorProvider(String apiRoot) {
        this.apiRoot = apiRoot;
    }

    @Override
    public URI getAuthenticationURL() throws AuthenticationException {
        return URI.create(apiRoot + "authserver/authenticate");
    }

    @Override
    public URI getRefreshmentURL() throws AuthenticationException {
        return URI.create(apiRoot + "authserver/refresh");
    }

    @Override
    public URI getValidationURL() throws AuthenticationException {
        return URI.create(apiRoot + "authserver/validate");
    }

    @Override
    public URI getInvalidationURL() throws AuthenticationException {
        return URI.create(apiRoot + "authserver/invalidate");
    }

    @Override
    public URI getSkinUploadURL(UUID uuid) throws UnsupportedOperationException {
        return URI.create(apiRoot + "api/user/profile/" + UUIDTypeAdapter.fromUUID(uuid) + "/skin");
    }

    @Override
    public URI getProfilePropertiesURL(UUID uuid) throws AuthenticationException {
        return URI.create(apiRoot + "sessionserver/session/minecraft/profile/" + UUIDTypeAdapter.fromUUID(uuid));
    }

    @Override
    public String toString() {
        return apiRoot;
    }
}
