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
package determination.xenon.upgrade;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import determination.xenon.task.FileDownloadTask.IntegrityCheck;
import determination.xenon.util.gson.JsonUtils;
import determination.xenon.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/// One release offered by the update endpoint.
///
/// A GitHub release ships two interchangeable launchers: the portable `.jar`
/// (needs an installed Java 17+) and the Windows single-file `.exe`. The
/// record keeps both URLs so the updater can pick the artifact the current
/// build is actually able to start. Custom update endpoints may only provide
/// the jar.
public record RemoteVersion(UpdateChannel channel, String version,
                            @Nullable String jarUrl, @Nullable String executableUrl,
                            @Nullable String body, @Nullable IntegrityCheck integrityCheck,
                            boolean preview, boolean force) {

    /// Downloads and parses the update endpoint response.
    ///
    /// @param channel update channel that requested the check
    /// @param preview whether preview builds were requested
    /// @param url     update endpoint URL
    /// @return the parsed release
    /// @throws IOException when the endpoint is unreachable or its payload is invalid
    public static RemoteVersion fetch(UpdateChannel channel, boolean preview, String url) throws IOException {
        try {
            JsonObject response = JsonUtils.fromNonNullJson(NetworkUtils.doGet(url), JsonObject.class);
            return parse(channel, preview, response);
        } catch (JsonParseException e) {
            throw new IOException("Malformed response", e);
        }
    }

    /// Parses one update endpoint payload.
    ///
    /// Supports the Xenon native schema
    /// (`{"version":"...","jar":"...","jarsha1":"...","force":bool}`) and the
    /// GitHub release schema (`tag_name` + `assets[]`).
    ///
    /// @param channel update channel that requested the check
    /// @param preview whether preview builds were requested
    /// @param response parsed JSON payload
    /// @return the parsed release
    /// @throws IOException when the payload does not describe a usable release
    static RemoteVersion parse(UpdateChannel channel, boolean preview, JsonObject response) throws IOException {
        // Xenon native schema (custom server): {"version":"...","jar":"...","jarsha1":"...","force":bool}
        JsonElement versionEl = response.get("version");
        JsonElement jarEl = response.get("jar");
        if (versionEl != null && jarEl != null) {
            String version = versionEl.getAsString();
            String jarUrl = jarEl.getAsString();
            String jarHash = Optional.ofNullable(response.get("jarsha1")).map(JsonElement::getAsString).orElse(null);
            boolean force = Optional.ofNullable(response.get("force")).map(JsonElement::getAsBoolean).orElse(false);
            if (jarHash == null) {
                throw new IOException("Missing jarsha1 in custom update response");
            }
            return new RemoteVersion(channel, version, jarUrl, null, null,
                    new IntegrityCheck("SHA-1", jarHash), preview, force);
        }

        // GitHub Release JSON: tag_name + assets[*].browser_download_url
        JsonElement tagEl = response.get("tag_name");
        JsonElement assetsEl = response.get("assets");
        if (tagEl != null && assetsEl instanceof JsonArray assets) {
            String tag = tagEl.getAsString();
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            boolean isPrerelease = Optional.ofNullable(response.get("prerelease"))
                    .map(JsonElement::getAsBoolean).orElse(false);
            String body = Optional.ofNullable(response.get("body")).map(JsonElement::getAsString).orElse(null);

            String jarUrl = null;
            String executableUrl = null;
            for (JsonElement el : assets) {
                JsonObject asset = el.getAsJsonObject();
                String name = Optional.ofNullable(asset.get("name")).map(JsonElement::getAsString).orElse("");
                String downloadUrl = Optional.ofNullable(asset.get("browser_download_url"))
                        .map(JsonElement::getAsString).orElse(null);
                if (downloadUrl == null) {
                    continue;
                }
                String lowerName = name.toLowerCase(Locale.ROOT);
                if (jarUrl == null && lowerName.endsWith(".jar")) {
                    jarUrl = downloadUrl;
                } else if (executableUrl == null && lowerName.endsWith(".exe")) {
                    executableUrl = downloadUrl;
                }
            }

            if (jarUrl != null || executableUrl != null) {
                // No SHA-1 from GitHub directly; integrity check is skipped (null).
                return new RemoteVersion(channel, version, jarUrl, executableUrl, body, null, isPrerelease, false);
            }
            throw new IOException("No .jar or .exe asset in GitHub release " + tag);
        }

        throw new IOException("Unrecognised update endpoint payload");
    }

    /// Returns the URL of the requested artifact, or `null` when the release
    /// does not ship that artifact.
    ///
    /// @param type artifact kind
    /// @return download URL or `null`
    public @Nullable String assetUrl(Type type) {
        return switch (type) {
            case JAR -> jarUrl;
            case EXE -> executableUrl;
        };
    }

    @Override
    public @NotNull String toString() {
        return "[" + version + " from " + (jarUrl != null ? jarUrl : executableUrl) + "]";
    }

    /// Launcher artifact kinds published by a release.
    public enum Type {
        /// Portable launcher jar, needs an installed Java 17+.
        JAR,
        /// Windows single-file executable with a bundled runtime.
        EXE
    }
}
