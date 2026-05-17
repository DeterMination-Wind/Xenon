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
package determination.xenon.mindustry.scriptagent;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Read-only DTO describing one ScriptAgent {@code .kts} module sitting under
 * {@code <data>/config/scripts/}. Produced by
 * {@link ScriptAgentModuleManager#list()} and consumed by the UI to drive
 * enable/disable toggles and {@code sa hotReload} actions.
 *
 * <p>{@link #moduleName} is the file name with the {@code .kts} suffix
 * stripped — that is the identifier ScriptAgent itself uses for
 * {@code sa load}/{@code sa hotReload} commands.</p>
 *
 * <p>{@link #enabled} is a heuristic: it is {@code true} iff the file
 * contains an un-commented {@code @Module} annotation when scanned. It
 * is not a substitute for ScriptAgent's runtime view of which modules
 * are actually loaded.</p>
 */
public final class ScriptAgentModule {

    private final Path file;
    private final String moduleName;
    private final boolean enabled;
    private final String description;

    public ScriptAgentModule(Path file, String moduleName, boolean enabled, String description) {
        this.file = Objects.requireNonNull(file, "file");
        this.moduleName = Objects.requireNonNull(moduleName, "moduleName");
        this.enabled = enabled;
        this.description = description == null ? "" : description;
    }

    /** Absolute path to the {@code .kts} file backing this module. */
    public Path getFile() {
        return file;
    }

    /** File name without the {@code .kts} suffix (the ScriptAgent module id). */
    public String getModuleName() {
        return moduleName;
    }

    /** {@code true} when the file contains an un-commented {@code @Module}. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Best-effort description sourced from leading {@code //} comment lines. */
    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return "ScriptAgentModule{" + moduleName + (enabled ? "" : " (disabled)") + '}';
    }
}
