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

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Snapshot of one Mindustry mod entry as advertised by a remote mod
 * repository (typically the community {@code Anuken/mindustry-mods}
 * index). This is a UI-friendly DTO; it deliberately mirrors the upstream
 * schema rather than the local {@link MindustryLocalMod} schema.
 *
 * <p>All fields are best-effort — the upstream JSON shape changes
 * without notice and missing fields fall back to defaults
 * ({@code null} / {@code 0} / {@link Collections#emptyList()}). The only
 * field a caller can rely on being non-null is {@link #getRepo()}.</p>
 */
public final class MindustryRemoteMod {
    private final String name;
    private final String displayName;
    private final String author;
    private final String description;
    private final String repo;
    private final String latestTag;
    private final String downloadUrl;
    private final int stars;
    private final int issues;
    private final String iconUrl;
    private final Instant lastUpdated;
    private final List<String> dependencies;

    public MindustryRemoteMod(String name,
                              String displayName,
                              String author,
                              String description,
                              String repo,
                              String latestTag,
                              String downloadUrl,
                              int stars,
                              int issues,
                              String iconUrl,
                              Instant lastUpdated,
                              List<String> dependencies) {
        this.name = name;
        this.displayName = displayName;
        this.author = author;
        this.description = description;
        this.repo = repo;
        this.latestTag = latestTag;
        this.downloadUrl = downloadUrl;
        this.stars = stars;
        this.issues = issues;
        this.iconUrl = iconUrl;
        this.lastUpdated = lastUpdated;
        this.dependencies = dependencies == null
                ? Collections.emptyList()
                : List.copyOf(dependencies);
    }

    public String getName() { return name; }

    /** Author-supplied {@code displayName}; may be {@code null} or blank. */
    public String getDisplayName() { return displayName; }

    public String getAuthor() { return author; }

    public String getDescription() { return description; }

    /** {@code owner/repo} on GitHub; never {@code null} when produced by a parser. */
    public String getRepo() { return repo; }

    /** Latest release tag if the index advertises one; may be {@code null}. */
    public String getLatestTag() { return latestTag; }

    /**
     * Direct download URL when the index pre-resolves one. Most index
     * entries leave this {@code null} and rely on the
     * {@link GitHubDirectInstaller} to resolve the latest release.
     */
    public String getDownloadUrl() { return downloadUrl; }

    public int getStars() { return stars; }

    public int getIssues() { return issues; }

    public String getIconUrl() { return iconUrl; }

    public Instant getLastUpdated() { return lastUpdated; }

    public List<String> getDependencies() { return dependencies; }

    /**
     * Best-effort human-readable label: {@code displayName} when present,
     * else {@code name}, else {@code repo}.
     */
    public String displayName() {
        if (displayName != null && !displayName.isBlank()) return displayName;
        if (name != null && !name.isBlank()) return name;
        return repo;
    }

    @Override
    public String toString() {
        return "MindustryRemoteMod{" + displayName()
                + ", repo=" + repo
                + ", stars=" + stars
                + (latestTag == null ? "" : ", tag=" + latestTag)
                + '}';
    }
}
