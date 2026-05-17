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
package determination.xenon.mindustry;

/**
 * The five Mindustry-desktop client variants Xenon can manage.
 * <p>
 * Each variant points at a different upstream GitHub repository; the
 * release-asset matching rules and the per-build Java requirements differ
 * between them, so most of the launcher's decision logic is keyed on this
 * value instead of a free-form string.
 */
public enum VersionVariant {
    /** Anuken/Mindustry — the official stable release. */
    VANILLA("Vanilla", "Anuken/Mindustry"),
    /** Anuken/MindustryBuilds — official bleeding-edge builds. */
    BE("Bleeding Edge", "Anuken/MindustryBuilds"),
    /** TinyLake/MindustryX — TinyLake's MindustryX patched build. */
    MINDUSTRY_X("MindustryX", "TinyLake/MindustryX"),
    /** BlueWolf3434/Mindustry-CN-ARC — the CN-ARC variant. */
    CN_ARC("CN-ARC", "BlueWolf3434/Mindustry-CN-ARC"),
    /** mindustry-antigrief/mindustry-client — the Foo Client. */
    FOO("Foo Client", "mindustry-antigrief/mindustry-client"),
    /** Custom/imported jars without a specific upstream. */
    CUSTOM("Custom", null);

    private final String displayName;
    private final String upstreamRepo;

    VersionVariant(String displayName, String upstreamRepo) {
        this.displayName = displayName;
        this.upstreamRepo = upstreamRepo;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** {@code owner/repo}, or null for {@link #CUSTOM}. */
    public String getUpstreamRepo() {
        return upstreamRepo;
    }

    /** Convenience: full GitHub URL of the upstream repo, or null. */
    public String getUpstreamUrl() {
        return upstreamRepo == null ? null : "https://github.com/" + upstreamRepo;
    }
}
