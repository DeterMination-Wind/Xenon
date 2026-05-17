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
package determination.xenon.mindustry.server;

import com.google.gson.annotations.SerializedName;

/**
 * POJO mirror of the Mindustry dedicated-server {@code config.json} that
 * lives at {@code <dataDir>/config/config.json}.
 *
 * <p>Mindustry only writes keys the user has actually changed, so every
 * field here is reference-typed (or {@link Integer}) and may be
 * {@code null}. Gson will simply leave missing fields {@code null} when
 * deserialising; callers should treat {@code null} as "use Mindustry's
 * built-in default".</p>
 *
 * <p>The {@code public} key collides with a Java keyword, so it is
 * mapped to {@link #isPublic} via {@link SerializedName}.</p>
 */
public final class ServerConfig {
    private String name;
    private String motd;
    private Integer port;
    @SerializedName("public")
    private Boolean isPublic;
    private Boolean autoUpdate;
    private Integer roundLimit;
    private Boolean whitelist;
    private String description;

    public ServerConfig() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMotd() { return motd; }
    public void setMotd(String motd) { this.motd = motd; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public Boolean getIsPublic() { return isPublic; }
    public void setIsPublic(Boolean isPublic) { this.isPublic = isPublic; }

    public Boolean getAutoUpdate() { return autoUpdate; }
    public void setAutoUpdate(Boolean autoUpdate) { this.autoUpdate = autoUpdate; }

    public Integer getRoundLimit() { return roundLimit; }
    public void setRoundLimit(Integer roundLimit) { this.roundLimit = roundLimit; }

    public Boolean getWhitelist() { return whitelist; }
    public void setWhitelist(Boolean whitelist) { this.whitelist = whitelist; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
