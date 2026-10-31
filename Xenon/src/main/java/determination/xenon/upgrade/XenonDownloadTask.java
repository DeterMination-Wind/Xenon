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

import determination.xenon.task.FileDownloadTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/// Downloads one launcher artifact of a release.
final class XenonDownloadTask extends FileDownloadTask {

    /// Artifact kind expected at the target path.
    private final RemoteVersion.Type type;

    /// Creates a download task for the given release artifact.
    ///
    /// @param version release to download from
    /// @param type    artifact kind to download
    /// @param target  file the artifact is written to
    /// @throws IllegalArgumentException when the release has no such artifact
    public XenonDownloadTask(RemoteVersion version, RemoteVersion.Type type, Path target) {
        super(requireUrl(version, type), target, version.integrityCheck());
        this.type = type;
    }

    /// Resolves the download URL of one artifact.
    private static String requireUrl(RemoteVersion version, RemoteVersion.Type type) {
        String url = version.assetUrl(type);
        if (url == null) {
            throw new IllegalArgumentException("Release " + version.version() + " has no " + type + " asset");
        }
        return url;
    }

    @Override
    public void execute() throws Exception {
        super.execute();

        try {
            String name = getPath().getFileName().toString().toLowerCase(Locale.ROOT);
            String extension = switch (type) {
                case JAR -> ".jar";
                case EXE -> ".exe";
            };
            if (!name.endsWith(extension)) {
                throw new IOException("Downloaded " + type + " asset has an unexpected name: " + name);
            }
        } catch (Throwable e) {
            try {
                Files.deleteIfExists(getPath());
            } catch (Throwable e2) {
                e.addSuppressed(e2);
            }
            throw e;
        }
    }
}
