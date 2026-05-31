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

/**
 * One downloadable file attached to a {@link GitHubRelease}.
 * <p>Field shape mirrors GitHub's REST {@code release_asset} object;
 * Gson populates it directly from the JSON returned by
 * {@code /repos/{owner}/{repo}/releases}.</p>
 */
public final class GitHubAsset {
    @SerializedName("name")
    private String name;

    @SerializedName("size")
    private long size;

    /** GitHub's binary download URL ({@code browser_download_url}). */
    @SerializedName("browser_download_url")
    private String downloadUrl;

    @SerializedName("content_type")
    private String contentType;

    public GitHubAsset() {
    }

    public GitHubAsset(String name, long size, String downloadUrl, String contentType) {
        this.name = name;
        this.size = size;
        this.downloadUrl = downloadUrl;
        this.contentType = contentType;
    }

    public String getName() { return name; }
    public long getSize() { return size; }
    public String getDownloadUrl() { return downloadUrl; }
    public String getContentType() { return contentType; }

    @Override
    public String toString() {
        return "GitHubAsset{" + name + ", " + size + " bytes, " + downloadUrl + '}';
    }
}
