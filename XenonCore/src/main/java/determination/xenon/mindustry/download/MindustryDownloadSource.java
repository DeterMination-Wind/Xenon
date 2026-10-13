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
import determination.xenon.util.i18n.LocaleUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Where Xenon is allowed to fetch Mindustry version lists and jars from.
///
/// The launcher has two independent source families:
///
/// * **domestic** — the 像素工厂文件站 (MDTBBS) manifest and file host, plus the
///   domestic `github.com` mirror pool. Fast and reachable from mainland
///   China, but only the 像素工厂文件站 publishes a Mindustry *version list*;
///   the mirrors only serve artifacts.
/// * **official** — `api.github.com` and `github.com`. The only feed that can
///   list the BE / MindustryX / CN-ARC / Foo variants, and the only origin
///   that is guaranteed to have every asset.
///
/// A tier therefore answers two separate questions: which *feed* may be used
/// to list versions, and which *routes* may serve the artifact bytes. This
/// type is a pure decision table — it performs no I/O — so the download UI can
/// explain a disabled entry without attempting a request.
@NotNullByDefault
public enum MindustryDownloadSource {

    /// Try the domestic source first and fall back to the official origin.
    ///
    /// The default for mainland users, and the behaviour Xenon had before the
    /// source became configurable.
    PREFER_DOMESTIC,

    /// Try the official origin first and fall back to the domestic source.
    ///
    /// The default outside mainland China.
    PREFER_OFFICIAL,

    /// Never touch `github.com` or `api.github.com`.
    ///
    /// Variants that only exist on GitHub cannot be listed at all and are
    /// offered as disabled entries with an explanatory hint.
    DOMESTIC_ONLY,

    /// Never touch the 像素工厂文件站 or the domestic mirror pool.
    OFFICIAL_ONLY;

    /// The two artifact routes a download attempt can take.
    public enum Route {
        /// The 像素工厂文件站 file host and the domestic mirror pool.
        DOMESTIC,
        /// The official `github.com` release origin, reached without a mirror.
        OFFICIAL
    }

    /// Ordered artifact routes for one download.
    ///
    /// @param preferred route to try first
    /// @param failover  route to try when `preferred` fails, or `null` when the
    ///                  tier forbids switching sides
    public record RoutePlan(Route preferred, @Nullable Route failover) {
    }

    /// How a download attempt ended, for the failover decision.
    public enum AttemptOutcome {
        /// The transfer completed but the artifact did not match the manifest.
        INTEGRITY_FAILED,
        /// The transfer failed for a network, protocol or filesystem reason.
        TRANSFER_FAILED,
        /// The user paused or cancelled the transfer.
        ABORTED
    }

    /// Returns the default tier for the current locale.
    ///
    /// @return [MindustryDownloadSource#PREFER_DOMESTIC] in mainland China,
    ///         [MindustryDownloadSource#PREFER_OFFICIAL] everywhere else
    public static MindustryDownloadSource defaultForLocale() {
        return LocaleUtils.IS_CHINA_MAINLAND ? PREFER_DOMESTIC : PREFER_OFFICIAL;
    }

    /// Tier currently in effect for the whole download chain.
    ///
    /// The launcher module owns the persisted setting and pushes it here, so
    /// this package can branch on the tier without depending on the launcher
    /// or on JavaFX. Reading it on every decision makes a settings change take
    /// effect on the next download instead of the next restart.
    private static volatile MindustryDownloadSource current = defaultForLocale();

    /// Returns the tier currently in effect.
    ///
    /// @return the active tier, never `null`
    public static MindustryDownloadSource current() {
        return current;
    }

    /// Updates the tier currently in effect.
    ///
    /// @param source tier to apply, or `null` to restore the locale default
    public static void setCurrent(@Nullable MindustryDownloadSource source) {
        current = source == null ? defaultForLocale() : source;
    }

    /// Parses a serialized tier name, falling back to the locale default.
    ///
    /// @param id serialized enum name, possibly `null` or unknown
    /// @return the matching tier, or [MindustryDownloadSource#defaultForLocale]
    public static MindustryDownloadSource fromIdOrDefault(@Nullable String id) {
        if (id == null || id.isBlank()) return defaultForLocale();
        for (MindustryDownloadSource source : values()) {
            if (source.name().equals(id)) return source;
        }
        return defaultForLocale();
    }

    /// Whether the MDTBBS manifest may be used as a version feed.
    ///
    /// @return `true` unless this tier is official-only
    public boolean allowsDomesticFeed() {
        return this != OFFICIAL_ONLY;
    }

    /// Whether the GitHub release API may be used as a version feed.
    ///
    /// @return `true` unless this tier is domestic-only
    public boolean allowsOfficialFeed() {
        return this != DOMESTIC_ONLY;
    }

    /// Whether variant feeds that exist only on GitHub are usable.
    ///
    /// Only the 像素工厂文件站 manifest lists vanilla builds, so BE /
    /// MindustryX / CN-ARC / Foo need `api.github.com` to be reachable.
    ///
    /// @return `true` unless this tier forbids the official feed
    public boolean allowsVariantFeeds() {
        return allowsOfficialFeed();
    }

    /// Whether one variant's version list can be obtained under this tier.
    ///
    /// Vanilla is always listable because both families publish it; the other
    /// variants exist only on GitHub and disappear when the official feed is
    /// forbidden.
    ///
    /// @param variant variant the user selected
    /// @return `true` when an allowed feed publishes that variant
    public boolean allowsVariant(VersionVariant variant) {
        if (variant == VersionVariant.VANILLA) {
            return allowsDomesticFeed() || allowsOfficialFeed();
        }
        return allowsVariantFeeds();
    }

    /// Whether a failed feed may be retried against the other family.
    ///
    /// @return `true` for the two "prefer" tiers, `false` for the strict ones
    public boolean allowsFeedFailover() {
        return this == PREFER_DOMESTIC || this == PREFER_OFFICIAL;
    }

    /// Whether the domestic feed should be consulted before the official one.
    ///
    /// @return `true` for [MindustryDownloadSource#PREFER_DOMESTIC] and
    ///         [MindustryDownloadSource#DOMESTIC_ONLY]
    public boolean prefersDomesticFeed() {
        return this == PREFER_DOMESTIC || this == DOMESTIC_ONLY;
    }

    /// Returns the ordered artifact routes for one download.
    ///
    /// @return the preferred route and, when failover is allowed, the other one
    public RoutePlan artifactRoutes() {
        return switch (this) {
            case PREFER_DOMESTIC -> new RoutePlan(Route.DOMESTIC, Route.OFFICIAL);
            case PREFER_OFFICIAL -> new RoutePlan(Route.OFFICIAL, Route.DOMESTIC);
            case DOMESTIC_ONLY -> new RoutePlan(Route.DOMESTIC, null);
            case OFFICIAL_ONLY -> new RoutePlan(Route.OFFICIAL, null);
        };
    }

    /// Whether the 像素工厂文件站 may serve an artifact.
    ///
    /// @return `true` unless this tier is official-only
    public boolean allowsFileStation() {
        return allowsDomesticArtifacts();
    }

    /// Whether the domestic `github.com` mirror pool may serve an artifact.
    ///
    /// @return `true` unless this tier is official-only
    public boolean allowsMirrorPool() {
        return allowsDomesticArtifacts();
    }

    /// Whether `github.com` may be contacted directly, without a mirror.
    ///
    /// @return `true` unless this tier is domestic-only
    public boolean allowsDirectOrigin() {
        return allowsOfficialArtifacts();
    }

    /// Whether any domestic route may serve an artifact.
    ///
    /// @return `true` unless this tier is official-only
    public boolean allowsDomesticArtifacts() {
        return this != OFFICIAL_ONLY;
    }

    /// Whether the official origin may serve an artifact.
    ///
    /// @return `true` unless this tier is domestic-only
    public boolean allowsOfficialArtifacts() {
        return this != DOMESTIC_ONLY;
    }

    /// Whether a failed attempt may be retried on the other route.
    ///
    /// A user-initiated pause or cancel never switches sides: the partial file
    /// is kept for a resume and the switch would silently discard the user's
    /// choice of route.
    ///
    /// @param outcome how the attempt ended
    /// @return whether the caller should switch routes and retry
    public boolean shouldFailover(AttemptOutcome outcome) {
        if (outcome == AttemptOutcome.ABORTED) return false;
        return artifactRoutes().failover() != null;
    }

    /// Host of the 像素工厂文件站, the only route-specific artifact origin.
    private static final String FILE_STATION_HOST = "file.mdtbbs.cn";

    /// Legacy 像素工厂文件站 host used by older manifest entries.
    private static final String LEGACY_FILE_STATION_HOST = "d.file.mdtbbs.cn";

    /// Whether an artifact URL may be fetched under this tier.
    ///
    /// This guards *cached* version rows, which were produced while a different
    /// tier was in effect. A `github.com` URL is always allowed because the
    /// domestic route serves it through a mirror; the 像素工厂文件站 host is the
    /// only origin that belongs to a single family.
    ///
    /// @param url artifact URL, possibly `null` or empty
    /// @return `true` when this tier may fetch `url`
    public boolean allowsArtifactUrl(@Nullable String url) {
        return !isFileStationUrl(url) || allowsDomesticArtifacts();
    }

    /// Whether a URL points at the 像素工厂文件站 file host.
    ///
    /// @param url artifact URL, possibly `null` or empty
    /// @return `true` when the host is the file station
    public static boolean isFileStationUrl(@Nullable String url) {
        if (url == null || url.isBlank()) return false;
        String host;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) return false;
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return FILE_STATION_HOST.equals(normalized)
                || LEGACY_FILE_STATION_HOST.equals(normalized);
    }
}
