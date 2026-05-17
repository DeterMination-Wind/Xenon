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
package determination.xenon.mindustry.download;

import determination.xenon.mindustry.VersionVariant;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Provider that pulls a list of installable Mindustry builds from one
 * upstream {@link VersionVariant}.
 *
 * <p>Concrete subclasses know how to translate a specific repo's release
 * naming convention (vanilla {@code Mindustry.jar}, BE {@code Mindustry-BE-*.jar},
 * MindustryX's archive layout, ...) into uniform
 * {@link MindustryRemoteVersion} rows.</p>
 *
 * <p>Subclasses should be safe to call from background threads; results
 * are not cached at this layer (let callers cache or rely on
 * {@link GitHubReleaseClient}'s ETag cache).</p>
 */
public abstract class MindustryVersionList {

    protected final VersionVariant variant;
    protected final GitHubReleaseClient client;

    protected MindustryVersionList(VersionVariant variant, GitHubReleaseClient client) {
        this.variant = Objects.requireNonNull(variant, "variant");
        this.client = Objects.requireNonNull(client, "client");
    }

    /** Which upstream this list pulls from. */
    public final VersionVariant getVariant() {
        return variant;
    }

    /**
     * Re-fetch the upstream release feed and convert it into a flat list
     * of installable builds, newest first.
     *
     * @throws IOException on network failure with no usable cached fallback
     */
    public abstract List<MindustryRemoteVersion> refresh() throws IOException;
}
