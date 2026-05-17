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
package determination.xenon.mindustry.uuid;

import java.time.Instant;
import java.util.Objects;

/**
 * One UUID + nickname pair Xenon can launch Mindustry with.
 *
 * <p>Mindustry has no online auth and no profile system: the client
 * decides locally what its 22-char base64 player UUID and visible
 * nickname are, then sends them straight to the server on join. To give
 * the launcher UI an HMCL-style "account" experience, Xenon stores a
 * list of these pairs and lets the user pick one before launch — the
 * chosen profile is then injected as JVM system properties that a
 * companion {@code uuidManager}-style mod reads on startup (see
 * {@link MindustryPlayerLaunchHook}).</p>
 *
 * <p>The fields are deliberately public-mutable so the JSON
 * (de)serializer in {@link UuidProfileManager} can hydrate them with
 * Gson's default field-based strategy. Nothing outside the manager
 * should mutate these directly; go through {@code rename} / {@code touch}
 * / {@code delete} so the on-disk file stays consistent.</p>
 */
public final class UuidProfile {

    /**
     * Mindustry-formatted player UUID, 22 characters of URL-safe base64
     * (no padding). See {@link UuidGenerator} for the exact encoding.
     */
    public String uuid;

    /** User-visible nickname; whatever they want to appear as in-game. */
    public String nickname;

    /**
     * Timestamp of the last successful launch with this profile.
     * {@link UuidProfileManager#getActive()} uses this to pick the
     * "current" profile; never {@code null} once persisted.
     */
    public Instant lastUsedAt;

    /** Time the profile was first created; never {@code null} once persisted. */
    public Instant createdAt;

    /** Optional free-form note shown next to the profile in the UI. */
    public String note;

    /** No-arg constructor required by Gson. */
    public UuidProfile() {
    }

    public UuidProfile(String uuid, String nickname, Instant createdAt) {
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.nickname = Objects.requireNonNull(nickname, "nickname");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.lastUsedAt = createdAt;
        this.note = "";
    }

    public String getUuid() {
        return uuid;
    }

    public String getNickname() {
        return nickname;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getNote() {
        return note;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UuidProfile that && Objects.equals(uuid, that.uuid);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(uuid);
    }

    @Override
    public String toString() {
        return "UuidProfile{uuid='" + uuid + "', nickname='" + nickname + "'}";
    }
}
