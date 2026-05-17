/*
 * Xenon Launcher
 * Copyright (C) 2026-2026  Xenon contributors
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
package determination.xenon.resourcepack;

import determination.xenon.mod.LocalModFile;
import determination.xenon.mod.modinfo.PackMcMeta;
import determination.xenon.util.gson.JsonUtils;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static determination.xenon.util.logging.Logger.LOG;

public final class ResourcepackFolder implements ResourcepackFile {
    private final Path path;
    private final LocalModFile.Description description;
    private final byte @Nullable [] icon;

    public ResourcepackFolder(Path path) {
        this.path = path;

        LocalModFile.Description description = null;
        try {
            description = JsonUtils.fromJsonFile(path.resolve("pack.mcmeta"), PackMcMeta.class).pack().description();
        } catch (Exception e) {
            LOG.warning("Failed to parse resourcepack meta", e);
        }

        byte[] icon;
        try {
            icon = Files.readAllBytes(path.resolve("pack.png"));
        } catch (IOException e) {
            icon = null;
            LOG.warning("Failed to read resourcepack icon", e);
        }
        this.icon = icon;

        this.description = description;
    }

    @Override
    public String getName() {
        return path.getFileName().toString();
    }

    @Override
    public Path getPath() {
        return path;
    }

    @Override
    public LocalModFile.Description getDescription() {
        return description;
    }

    @Override
    public byte @Nullable [] getIcon() {
        return icon;
    }
}
