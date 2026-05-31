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

import java.io.IOException;
import java.util.Optional;

public record RemoteVersion(UpdateChannel channel, String version, String url, Type type, IntegrityCheck integrityCheck,
                            boolean preview, boolean force) {

    public static RemoteVersion fetch(UpdateChannel channel, boolean preview, String url) throws IOException {
        try {
            JsonObject response = JsonUtils.fromNonNullJson(NetworkUtils.doGet(url), JsonObject.class);

            // Xenon native schema (custom server): {"version":"...","jar":"...","jarsha1":"...","force":bool}
            JsonElement versionEl = response.get("version");
            JsonElement jarEl = response.get("jar");
            if (versionEl != null && jarEl != null) {
                String version = versionEl.getAsString();
                String jarUrl = jarEl.getAsString();
                String jarHash = Optional.ofNullable(response.get("jarsha1")).map(JsonElement::getAsString).orElse(null);
                boolean force = Optional.ofNullable(response.get("force")).map(JsonElement::getAsBoolean).orElse(false);
                if (jarHash != null) {
                    return new RemoteVersion(channel, version, jarUrl, Type.JAR, new IntegrityCheck("SHA-1", jarHash), preview, force);
                }
                throw new IOException("Missing jarsha1 in custom update response");
            }

            // GitHub Release JSON: tag_name + assets[*].browser_download_url
            JsonElement tagEl = response.get("tag_name");
            JsonElement assetsEl = response.get("assets");
            if (tagEl != null && assetsEl instanceof JsonArray assets) {
                String tag = tagEl.getAsString();
                String version = tag.startsWith("v") ? tag.substring(1) : tag;
                String jarUrl = null;
                for (JsonElement el : assets) {
                    JsonObject a = el.getAsJsonObject();
                    String name = Optional.ofNullable(a.get("name")).map(JsonElement::getAsString).orElse("");
                    if (name.toLowerCase().endsWith(".jar")) {
                        jarUrl = Optional.ofNullable(a.get("browser_download_url")).map(JsonElement::getAsString).orElse(null);
                        if (jarUrl != null) break;
                    }
                }
                if (jarUrl != null) {
                    // No SHA-1 from GitHub directly; integrity check is skipped (null).
                    return new RemoteVersion(channel, version, jarUrl, Type.JAR, null, preview, false);
                }
                throw new IOException("No .jar asset in GitHub release " + tag);
            }

            throw new IOException("Unrecognised update endpoint payload");
        } catch (JsonParseException e) {
            throw new IOException("Malformed response", e);
        }
    }

    @Override
    public @NotNull String toString() {
        return "[" + version + " from " + url + "]";
    }

    public enum Type {
        JAR
    }
}
