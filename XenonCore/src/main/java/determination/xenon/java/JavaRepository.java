/*
 * Xenon Launcher
 * Copyright (C) 2024-2026  Xenon contributors
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
package determination.xenon.java;

import determination.xenon.download.DownloadProvider;
import determination.xenon.game.GameJavaVersion;
import determination.xenon.task.Task;
import determination.xenon.util.platform.Platform;

import java.nio.file.Path;
import java.util.Collection;

/**
 * @author Glavo
 */
public interface JavaRepository {

    Path getJavaDir(Platform platform, String name);

    Path getManifestFile(Platform platform, String name);

    Collection<Path> getAllJava(Platform platform);

    Task<JavaRuntime> getDownloadJavaTask(DownloadProvider downloadProvider, Platform platform, GameJavaVersion gameJavaVersion);

    Task<Void> getUninstallJavaTask(Platform platform, String name);

    Task<Void> getUninstallJavaTask(JavaRuntime java);
}
