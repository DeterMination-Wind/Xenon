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

import determination.xenon.Metadata;
import determination.xenon.mindustry.VersionVariant;
import determination.xenon.mindustry.download.BeVersionList;
import determination.xenon.mindustry.download.CnArcVersionList;
import determination.xenon.mindustry.download.FooVersionList;
import determination.xenon.mindustry.download.GitHubReleaseClient;
import determination.xenon.mindustry.download.MindustryDownloadSource;
import determination.xenon.mindustry.download.MindustryVersionList;
import determination.xenon.mindustry.download.MindustryXVersionList;
import determination.xenon.mindustry.download.MdtbbsVersionList;
import determination.xenon.mindustry.download.VanillaVersionList;
import org.jetbrains.annotations.NotNullByDefault;

/// Maps {@link VersionVariant} → its {@link MindustryVersionList}
/// implementation. Centralised so that the wizard pages don't grow
/// variant-specific switches.
///
/// The configured [MindustryDownloadSource] decides which feed a variant is
/// read from. Only vanilla has a domestic feed: the 像素工厂文件站 manifest.
/// BE, MindustryX, CN-ARC and Foo are published on GitHub alone, so the
/// domestic-only tier cannot list them and callers must check
/// [MindustryDownloadSource#allowsVariant] before asking for a list.
@NotNullByDefault
public final class VersionListFactory {
    private VersionListFactory() {
    }

    /// Builds the version list for the tier currently in effect.
    ///
    /// @param variant variant to list
    /// @param client  official release feed client
    /// @return the feed implementation for `variant`
    /// @throws IllegalArgumentException when the tier cannot list `variant`
    public static MindustryVersionList listFor(VersionVariant variant, GitHubReleaseClient client) {
        return listFor(variant, client, MindustryDownloadSource.current());
    }

    /// Builds the version list for an explicit tier.
    ///
    /// @param variant variant to list
    /// @param client  official release feed client
    /// @param source  tier to resolve the feed for
    /// @return the feed implementation for `variant`
    /// @throws IllegalArgumentException when `source` cannot list `variant`
    public static MindustryVersionList listFor(VersionVariant variant, GitHubReleaseClient client,
                                               MindustryDownloadSource source) {
        if (!source.allowsVariant(variant)) {
            throw new IllegalArgumentException("Variant " + variant
                    + " has no version list under " + source);
        }
        switch (variant) {
            case VANILLA:
                // The manifest carries asset hashes and a domestic file host;
                // the GitHub feed is the other half of the pair. Whichever the
                // tier prefers becomes the primary and the other the fallback.
                return source.prefersDomesticFeed()
                        ? new MdtbbsVersionList(client, Metadata.VERSION, source)
                        : new VanillaVersionList(client, source);
            case BE:
                return new BeVersionList(client, source);
            case MINDUSTRY_X:
                return new MindustryXVersionList(client, source);
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
