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
package determination.xenon.download.game;

import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import determination.xenon.game.ReleaseType;
import determination.xenon.util.StringUtils;
import determination.xenon.util.gson.JsonSerializable;
import determination.xenon.util.gson.Validation;

import java.time.Instant;

/**
 *
 * @author huangyuhui
 */
@JsonSerializable
public record GameRemoteVersionInfo(
        @SerializedName("id") String gameVersion,
        @SerializedName("time") Instant time,
        @SerializedName("releaseTime") Instant releaseTime,
        @SerializedName("type") ReleaseType type,
        @SerializedName("url") String url) implements Validation {

    @Override
    public void validate() throws JsonParseException {
        if (StringUtils.isBlank(gameVersion))
            throw new JsonParseException("GameRemoteVersion id cannot be blank");
        if (StringUtils.isBlank(url))
            throw new JsonParseException("GameRemoteVersion url cannot be blank");
    }
}
