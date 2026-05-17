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
import determination.xenon.util.Immutable;
import determination.xenon.util.gson.JsonSerializable;
import determination.xenon.util.gson.Validation;

import java.util.List;

/**
 *
 * @author huangyuhui
 */
@Immutable
@JsonSerializable
public record GameRemoteVersions(
        @SerializedName("versions") List<GameRemoteVersionInfo> versions,
        @SerializedName("latest") GameRemoteLatestVersions latest) implements Validation {

    @Override
    public void validate() throws JsonParseException {
        if (versions == null)
            throw new JsonParseException("GameRemoteVersions.versions cannot be null");
    }
}
