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
package determination.xenon.mindustry.mod;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Metadata snapshot for a single mod / plugin archive that lives inside
 * a Mindustry data directory's {@code mods/} folder.
 *
 * <p>Named with a {@code Mindustry} prefix to avoid collisions with HMCL's
 * {@code org.jackhuang.hmcl.mod.LocalMod}, which targets Minecraft mods
 * and follows a different metadata schema.</p>
 *
 * <p>{@link #file} may end with {@code .disabled}; in that case the mod
 * is considered {@linkplain #isEnabled() disabled} and Mindustry will
 * skip it on startup.</p>
 */
public final class MindustryLocalMod {
    private final Path file;
    private final String name;
    private final String displayName;
    private final String author;
    private final String version;
    private final String description;
    private final String main;
    private final int minGameVersion;
    private final boolean java;
    private final List<String> dependencies;
    private final boolean enabled;
    private final String internalName;
    private final String ignoredByFileName;

    public MindustryLocalMod(Path file,
                             String name,
                             String displayName,
                             String author,
                             String version,
                             String description,
                             String main,
                             int minGameVersion,
                             boolean java,
                             List<String> dependencies) {
        this(file, name, displayName, author, version, description, main, minGameVersion,
                java, dependencies, null);
    }

    private MindustryLocalMod(Path file,
                              String name,
                              String displayName,
                              String author,
                              String version,
                              String description,
                              String main,
                              int minGameVersion,
                              boolean java,
                              List<String> dependencies,
                              String ignoredByFileName) {
        this.file = Objects.requireNonNull(file, "file");
        this.name = name;
        this.displayName = displayName;
        this.author = author;
        this.version = version;
        this.description = description;
        this.main = main;
        this.minGameVersion = minGameVersion;
        this.java = java;
        this.dependencies = dependencies == null
                ? Collections.emptyList()
                : List.copyOf(dependencies);
        this.enabled = !file.getFileName().toString()
                .toLowerCase(Locale.ROOT).endsWith(".disabled");
        this.internalName = normalizeInternalName(name);
        this.ignoredByFileName = ignoredByFileName;
    }

    public Path getFile() { return file; }

    public String getName() { return name; }

    /** Raw {@code displayName} from the descriptor; may be {@code null}. */
    public String getDisplayName() { return displayName; }

    public String getAuthor() { return author; }

    public String getVersion() { return version; }

    public String getDescription() { return description; }

    public String getMain() { return main; }

    public int getMinGameVersion() { return minGameVersion; }

    public boolean isJava() { return java; }

    public List<String> getDependencies() { return dependencies; }

    public boolean isEnabled() { return enabled; }

    public String getInternalName() { return internalName; }

    public boolean isIgnoredByDuplicate() { return ignoredByFileName != null; }

    public String getIgnoredByFileName() { return ignoredByFileName; }

    public MindustryLocalMod ignoredBy(MindustryLocalMod winner) {
        return new MindustryLocalMod(file, name, displayName, author, version, description,
                main, minGameVersion, java, dependencies, winner.getFile().getFileName().toString());
    }

    /**
     * Best-effort human-readable label: {@code displayName} when present,
     * else {@code name}, else the archive's file name.
     */
    public String displayName() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (name != null && !name.isBlank()) return name;
        return file.getFileName().toString();
    }

    private static String normalizeInternalName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "";
        }
        return rawName.toLowerCase(Locale.ROOT).replace(" ", "-");
    }

    @Override
    public String toString() {
        return "MindustryLocalMod{" + displayName()
                + ", file=" + file.getFileName()
                + ", enabled=" + enabled
                + ", ignoredBy=" + ignoredByFileName + '}';
    }
}
