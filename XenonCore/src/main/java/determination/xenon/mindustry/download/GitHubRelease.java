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

import com.google.gson.annotations.SerializedName;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * One row of GitHub's {@code /repos/{owner}/{repo}/releases} response.
 * <p>This is a thin POJO consumed by {@link GitHubReleaseClient}; field
 * names are mapped from GitHub's snake_case via {@link SerializedName}
 * so callers see idiomatic Java property names.</p>
 */
public final class GitHubRelease {
    @SerializedName("tag_name")
    private String tagName;

    @SerializedName("name")
    private String name;

    @SerializedName("published_at")
    private Instant publishedAt;

    @SerializedName("prerelease")
    private boolean prerelease;

    @SerializedName("body")
    private String body;

    @SerializedName("assets")
    private List<GitHubAsset> assets;

    public GitHubRelease() {
    }

    public String getTagName() { return tagName; }

    public String getName() { return name == null || name.isEmpty() ? tagName : name; }

    public Instant getPublishedAt() { return publishedAt; }

    public boolean isPrerelease() { return prerelease; }

    public String getBody() { return body == null ? "" : body; }

    public List<GitHubAsset> getAssets() { return assets == null ? Collections.emptyList() : assets; }

    @Override
    public String toString() {
        return "GitHubRelease{" + tagName + (prerelease ? " (pre)" : "") + ", " + getAssets().size() + " assets}";
    }
}
