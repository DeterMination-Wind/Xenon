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

/**
 * Receives byte-level progress while a {@link GitHubAsset} is being
 * streamed to disk by {@link GitHubReleaseClient#downloadAsset}.
 *
 * <p>Implementations should be cheap and non-blocking: the callback is
 * invoked on the thread driving the download, once per buffer chunk.
 * {@code total} is {@code -1} when the {@code Content-Length} of the
 * response is unknown.</p>
 */
@FunctionalInterface
public interface ProgressCallback {
    /**
     * @param bytesRead number of bytes written to the target file so far
     * @param total     total expected bytes, or {@code -1} if unknown
     */
    void onProgress(long bytesRead, long total);
}
