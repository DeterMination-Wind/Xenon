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

import determination.xenon.mindustry.VersionVariant;
import determination.xenon.mindustry.download.MindustryDownloadSource.AttemptOutcome;
import determination.xenon.mindustry.download.MindustryDownloadSource.Route;
import determination.xenon.mindustry.download.MindustryDownloadSource.RoutePlan;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the download-source decision table.
///
/// The enum is a pure function of the configured tier, so the whole matrix —
/// four tiers by five variants by three attempt outcomes — is checked without
/// touching the network.
@NotNullByDefault
public final class MindustryDownloadSourceTest {

    /// The variants the installer offers, in the order the wizard lists them.
    private static final List<VersionVariant> INSTALLABLE_VARIANTS = List.of(
            VersionVariant.VANILLA,
            VersionVariant.BE,
            VersionVariant.MINDUSTRY_X,
            VersionVariant.CN_ARC,
            VersionVariant.FOO);

    /// The vanilla feed must stay reachable in every tier: one of the two
    /// families always publishes it.
    @Test
    public void everyTierCanListVanilla() {
        for (MindustryDownloadSource source : MindustryDownloadSource.values()) {
            assertTrue(source.allowsVariant(VersionVariant.VANILLA),
                    source + " must still be able to list vanilla builds");
            assertTrue(source.allowsDomesticFeed() || source.allowsOfficialFeed(),
                    source + " must allow at least one vanilla feed");
        }
    }

    /// Domestic-only forbids every GitHub feed, so variants become unlistable.
    @Test
    public void domesticOnlyDisablesGithubOnlyVariants() {
        MindustryDownloadSource source = MindustryDownloadSource.DOMESTIC_ONLY;

        assertTrue(source.allowsDomesticFeed());
        assertFalse(source.allowsOfficialFeed());
        assertFalse(source.allowsVariantFeeds());

        assertTrue(source.allowsVariant(VersionVariant.VANILLA));
        for (VersionVariant variant : List.of(VersionVariant.BE, VersionVariant.MINDUSTRY_X,
                VersionVariant.CN_ARC, VersionVariant.FOO)) {
            assertFalse(source.allowsVariant(variant),
                    "domestic-only cannot list " + variant + " because only GitHub publishes it");
        }
    }

    /// Official-only drops the MDTBBS feed but keeps every variant listable.
    @Test
    public void officialOnlyKeepsEveryVariantListable() {
        MindustryDownloadSource source = MindustryDownloadSource.OFFICIAL_ONLY;

        assertFalse(source.allowsDomesticFeed());
        assertTrue(source.allowsOfficialFeed());

        for (VersionVariant variant : INSTALLABLE_VARIANTS) {
            assertTrue(source.allowsVariant(variant), "official-only must list " + variant);
        }
    }

    /// The two prefer tiers keep both feeds and both variant families.
    @Test
    public void preferTiersKeepBothFeeds() {
        for (MindustryDownloadSource source : List.of(MindustryDownloadSource.PREFER_DOMESTIC,
                MindustryDownloadSource.PREFER_OFFICIAL)) {
            assertTrue(source.allowsDomesticFeed(), source + " keeps the MDTBBS feed");
            assertTrue(source.allowsOfficialFeed(), source + " keeps the GitHub feed");
            assertTrue(source.allowsVariantFeeds(), source + " keeps variant feeds");
            assertTrue(source.allowsFeedFailover(), source + " may switch feeds");
            for (VersionVariant variant : INSTALLABLE_VARIANTS) {
                assertTrue(source.allowsVariant(variant));
            }
        }
    }

    /// Only the prefer tiers prefer the domestic feed, and only in the right direction.
    @Test
    public void feedPreferenceFollowsTheTier() {
        assertTrue(MindustryDownloadSource.PREFER_DOMESTIC.prefersDomesticFeed());
        assertTrue(MindustryDownloadSource.DOMESTIC_ONLY.prefersDomesticFeed());
        assertFalse(MindustryDownloadSource.PREFER_OFFICIAL.prefersDomesticFeed());
        assertFalse(MindustryDownloadSource.OFFICIAL_ONLY.prefersDomesticFeed());
    }

    /// The strict tiers never switch feeds; the prefer tiers always do.
    @Test
    public void feedFailoverOnlyAppliesToPreferTiers() {
        assertTrue(MindustryDownloadSource.PREFER_DOMESTIC.allowsFeedFailover());
        assertTrue(MindustryDownloadSource.PREFER_OFFICIAL.allowsFeedFailover());
        assertFalse(MindustryDownloadSource.DOMESTIC_ONLY.allowsFeedFailover());
        assertFalse(MindustryDownloadSource.OFFICIAL_ONLY.allowsFeedFailover());
    }

    /// Every tier produces a usable first route plus the expected failover.
    @Test
    public void artifactRoutePlansMatchTheTier() {
        RoutePlan domesticFirst = MindustryDownloadSource.PREFER_DOMESTIC.artifactRoutes();
        assertEquals(Route.DOMESTIC, domesticFirst.preferred());
        assertEquals(Route.OFFICIAL, domesticFirst.failover());

        RoutePlan officialFirst = MindustryDownloadSource.PREFER_OFFICIAL.artifactRoutes();
        assertEquals(Route.OFFICIAL, officialFirst.preferred());
        assertEquals(Route.DOMESTIC, officialFirst.failover());

        RoutePlan domesticOnly = MindustryDownloadSource.DOMESTIC_ONLY.artifactRoutes();
        assertEquals(Route.DOMESTIC, domesticOnly.preferred());
        assertNull(domesticOnly.failover(), "domestic-only must not fall back to GitHub");

        RoutePlan officialOnly = MindustryDownloadSource.OFFICIAL_ONLY.artifactRoutes();
        assertEquals(Route.OFFICIAL, officialOnly.preferred());
        assertNull(officialOnly.failover(), "official-only must not fall back to a mirror");
    }

    /// The artifact route flags agree with the route plan for every tier.
    @Test
    public void artifactRouteFlagsAgreeWithThePlan() {
        for (MindustryDownloadSource source : MindustryDownloadSource.values()) {
            RoutePlan plan = source.artifactRoutes();
            Set<Route> allowed = EnumSet.noneOf(Route.class);
            if (source.allowsDomesticArtifacts()) allowed.add(Route.DOMESTIC);
            if (source.allowsOfficialArtifacts()) allowed.add(Route.OFFICIAL);

            assertTrue(allowed.contains(plan.preferred()),
                    source + " must allow its own preferred route");
            if (plan.failover() == null) {
                assertEquals(1, allowed.size(),
                        source + " forbids failover, so exactly one route may be allowed");
            } else {
                assertEquals(2, allowed.size(),
                        source + " allows failover, so both routes must be allowed");
                assertTrue(allowed.contains(plan.failover()),
                        source + " must allow the route it fails over to");
            }
        }
    }

    /// A domestic-only tier keeps the file station and the mirror pool.
    @Test
    public void domesticOnlyKeepsBothDomesticArtifactRoutes() {
        MindustryDownloadSource source = MindustryDownloadSource.DOMESTIC_ONLY;

        assertTrue(source.allowsFileStation());
        assertTrue(source.allowsMirrorPool());
        assertFalse(source.allowsDirectOrigin());
    }

    /// An official-only tier drops both domestic artifact routes.
    @Test
    public void officialOnlyDropsBothDomesticArtifactRoutes() {
        MindustryDownloadSource source = MindustryDownloadSource.OFFICIAL_ONLY;

        assertFalse(source.allowsFileStation());
        assertFalse(source.allowsMirrorPool());
        assertTrue(source.allowsDirectOrigin());
    }

    /// The prefer tiers advertise every artifact route.
    @Test
    public void preferTiersAdvertiseEveryArtifactRoute() {
        for (MindustryDownloadSource source : List.of(MindustryDownloadSource.PREFER_DOMESTIC,
                MindustryDownloadSource.PREFER_OFFICIAL)) {
            assertTrue(source.allowsFileStation(), source + " keeps the file station");
            assertTrue(source.allowsMirrorPool(), source + " keeps the mirror pool");
            assertTrue(source.allowsDirectOrigin(), source + " keeps the direct origin");
        }
    }

    /// A pause or cancel never switches sides, whatever the tier.
    @Test
    public void abortedAttemptsNeverFailOver() {
        for (MindustryDownloadSource source : MindustryDownloadSource.values()) {
            assertFalse(source.shouldFailover(AttemptOutcome.ABORTED),
                    source + " must not switch routes after a pause or cancel");
        }
    }

    /// Failures fail over only when the tier allows the other side.
    @Test
    public void failedAttemptsFailOverOnlyWhenAllowed() {
        for (MindustryDownloadSource source : MindustryDownloadSource.values()) {
            boolean allowed = source.allowsFeedFailover();
            assertEquals(allowed, source.shouldFailover(AttemptOutcome.TRANSFER_FAILED),
                    source + " transfer-failure failover");
            assertEquals(allowed, source.shouldFailover(AttemptOutcome.INTEGRITY_FAILED),
                    source + " integrity-failure failover");
        }
    }

    /// The full tier-by-outcome matrix, written out for review.
    @Test
    public void failoverMatrixIsStable() {
        assertTrue(MindustryDownloadSource.PREFER_DOMESTIC.shouldFailover(AttemptOutcome.TRANSFER_FAILED));
        assertTrue(MindustryDownloadSource.PREFER_OFFICIAL.shouldFailover(AttemptOutcome.INTEGRITY_FAILED));
        assertFalse(MindustryDownloadSource.DOMESTIC_ONLY.shouldFailover(AttemptOutcome.TRANSFER_FAILED));
        assertFalse(MindustryDownloadSource.OFFICIAL_ONLY.shouldFailover(AttemptOutcome.INTEGRITY_FAILED));

        assertFalse(MindustryDownloadSource.PREFER_DOMESTIC.shouldFailover(AttemptOutcome.ABORTED));
        assertFalse(MindustryDownloadSource.PREFER_OFFICIAL.shouldFailover(AttemptOutcome.ABORTED));
        assertFalse(MindustryDownloadSource.DOMESTIC_ONLY.shouldFailover(AttemptOutcome.ABORTED));
        assertFalse(MindustryDownloadSource.OFFICIAL_ONLY.shouldFailover(AttemptOutcome.ABORTED));
    }

    /// Parsing tolerates a blank or unknown stored value.
    @Test
    public void parsingFallsBackToTheLocaleDefault() {
        assertEquals(MindustryDownloadSource.defaultForLocale(),
                MindustryDownloadSource.fromIdOrDefault(null));
        assertEquals(MindustryDownloadSource.defaultForLocale(),
                MindustryDownloadSource.fromIdOrDefault(""));
        assertEquals(MindustryDownloadSource.defaultForLocale(),
                MindustryDownloadSource.fromIdOrDefault("   "));
        assertEquals(MindustryDownloadSource.defaultForLocale(),
                MindustryDownloadSource.fromIdOrDefault("NOT_A_TIER"));

        for (MindustryDownloadSource source : MindustryDownloadSource.values()) {
            assertSame(source, MindustryDownloadSource.fromIdOrDefault(source.name()));
        }
    }

    /// The locale default is one of the two prefer tiers.
    @Test
    public void localeDefaultIsAUsableTier() {
        MindustryDownloadSource fallback = MindustryDownloadSource.defaultForLocale();

        assertTrue(fallback.allowsDomesticFeed()
                        || fallback.allowsOfficialFeed(),
                "the default must allow at least one feed");
        assertTrue(INSTALLABLE_VARIANTS.stream().allMatch(fallback::allowsVariant),
                "the default must keep every variant installable");
    }

    /// A GitHub URL is usable in every tier because the domestic route proxies it.
    @Test
    public void githubUrlsAreAllowedInEveryTier() {
        for (MindustryDownloadSource source : MindustryDownloadSource.values()) {
            assertTrue(source.allowsArtifactUrl("https://github.com/Anuken/Mindustry/releases/download/v160.7/Mindustry.jar"),
                    source + " must accept a GitHub artifact URL");
            assertTrue(source.allowsArtifactUrl(
                            "https://objects.githubusercontent.com/github-production-release-asset/123"),
                    source + " must accept a GitHub CDN artifact URL");
        }
    }

    /// A file-station URL is route-specific: domestic tiers accept it, official-only does not.
    @Test
    public void fileStationUrlsFollowTheDomesticRoute() {
        String artifact = "https://file.mdtbbs.cn/d/Mindustry/Main/Stable/v160.7/desktop/Mindustry.jar";
        String legacy = "https://d.file.mdtbbs.cn/d/Mindustry/v8/build-160-stable/Mindustry.jar";

        assertTrue(MindustryDownloadSource.PREFER_DOMESTIC.allowsArtifactUrl(artifact));
        assertTrue(MindustryDownloadSource.PREFER_OFFICIAL.allowsArtifactUrl(artifact));
        assertTrue(MindustryDownloadSource.DOMESTIC_ONLY.allowsArtifactUrl(artifact));
        assertFalse(MindustryDownloadSource.OFFICIAL_ONLY.allowsArtifactUrl(artifact));

        assertTrue(MindustryDownloadSource.DOMESTIC_ONLY.allowsArtifactUrl(legacy));
        assertFalse(MindustryDownloadSource.OFFICIAL_ONLY.allowsArtifactUrl(legacy));
    }

    /// The file-station host detector tolerates blank and malformed input.
    @Test
    public void fileStationUrlDetectionIsLenient() {
        assertFalse(MindustryDownloadSource.isFileStationUrl(null));
        assertFalse(MindustryDownloadSource.isFileStationUrl(""));
        assertFalse(MindustryDownloadSource.isFileStationUrl("   "));
        assertFalse(MindustryDownloadSource.isFileStationUrl("not a url"));
        assertFalse(MindustryDownloadSource.isFileStationUrl("https://example.invalid/Mindustry.jar"));
        assertTrue(MindustryDownloadSource.isFileStationUrl(
                "https://file.mdtbbs.cn/d/Mindustry/Main/Stable/v160.7/desktop/Mindustry.jar"));
        assertTrue(MindustryDownloadSource.isFileStationUrl(
                "https://FILE.MDTBBS.CN/d/Mindustry/Main/Stable/v160.7/desktop/Mindustry.jar"));
        // A lookalike host must not be mistaken for the file station.
        assertFalse(MindustryDownloadSource.isFileStationUrl(
                "https://file.mdtbbs.cn.evil.invalid/Mindustry.jar"));
    }

    /// The tier in effect is a global that round-trips and never becomes null.
    @Test
    public void currentTierRoundTrips() {
        MindustryDownloadSource previous = MindustryDownloadSource.current();
        try {
            for (MindustryDownloadSource source : MindustryDownloadSource.values()) {
                MindustryDownloadSource.setCurrent(source);
                assertSame(source, MindustryDownloadSource.current());
            }

            MindustryDownloadSource.setCurrent(null);
            assertSame(MindustryDownloadSource.defaultForLocale(),
                    MindustryDownloadSource.current(),
                    "a null tier must restore the locale default instead of crashing callers");
        } finally {
            MindustryDownloadSource.setCurrent(previous);
        }
    }
}
