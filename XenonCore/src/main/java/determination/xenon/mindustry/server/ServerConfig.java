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
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

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
 * <p>Only keys backed by Mindustry's {@code Administration.Config} are
 * exposed here. Legacy Xenon-written keys are accepted through
 * {@link SerializedName#alternate()} but are normalized when saved.</p>
 */
@NotNullByDefault
public final class ServerConfig {
    @SerializedName(value = "servername", alternate = {"serverName", "name"})
    private @Nullable String serverName;
    private @Nullable String motd;
    private @Nullable Integer port;
    private @Nullable Boolean autoUpdate;
    private @Nullable Boolean whitelist;
    @SerializedName(value = "desc", alternate = "description")
    private @Nullable String description;

    public ServerConfig() {
    }

    public @Nullable String getServerName() { return serverName; }

    public void setServerName(@Nullable String serverName) { this.serverName = serverName; }

    public @Nullable String getMotd() { return motd; }

    public void setMotd(@Nullable String motd) { this.motd = motd; }

    public @Nullable Integer getPort() { return port; }

    public void setPort(@Nullable Integer port) { this.port = port; }

    public @Nullable Boolean getAutoUpdate() { return autoUpdate; }

    public void setAutoUpdate(@Nullable Boolean autoUpdate) { this.autoUpdate = autoUpdate; }

    public @Nullable Boolean getWhitelist() { return whitelist; }

    public void setWhitelist(@Nullable Boolean whitelist) { this.whitelist = whitelist; }

    public @Nullable String getDescription() { return description; }

    public void setDescription(@Nullable String description) { this.description = description; }
}
