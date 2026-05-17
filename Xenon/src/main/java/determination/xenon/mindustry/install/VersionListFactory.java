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
package determination.xenon.mindustry.install;

import determination.xenon.mindustry.VersionVariant;
import determination.xenon.mindustry.download.BeVersionList;
import determination.xenon.mindustry.download.CnArcVersionList;
import determination.xenon.mindustry.download.FooVersionList;
import determination.xenon.mindustry.download.GitHubReleaseClient;
import determination.xenon.mindustry.download.MindustryVersionList;
import determination.xenon.mindustry.download.MindustryXVersionList;
import determination.xenon.mindustry.download.VanillaVersionList;

/**
 * Maps {@link VersionVariant} → its {@link MindustryVersionList}
 * implementation. Centralised so that the wizard pages don't grow
 * variant-specific switches.
 */
public final class VersionListFactory {
    private VersionListFactory() {
    }

    public static MindustryVersionList listFor(VersionVariant variant, GitHubReleaseClient client) {
        switch (variant) {
            case VANILLA:
                return new VanillaVersionList(client);
            case BE:
                return new BeVersionList(client);
            case MINDUSTRY_X:
                return new MindustryXVersionList(client);
            case CN_ARC:
                return new CnArcVersionList(client);
            case FOO:
                return new FooVersionList(client);
            case CUSTOM:
            default:
                throw new IllegalArgumentException("No remote list for variant " + variant);
        }
    }
}
