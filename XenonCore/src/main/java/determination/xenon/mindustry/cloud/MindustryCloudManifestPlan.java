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
package determination.xenon.mindustry.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import determination.xenon.mindustry.cloud.MindustryCloudArchivePacker.Plan;
import determination.xenon.mindustry.cloud.MindustryCloudArchivePacker.PlannedFile;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Quota;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/// Chooses between the manifest (delta) upload format and the zip archive.
///
/// This planner decides manifest-shape eligibility only: it compares the plan
/// against the file-count and byte limits advertised by [Quota]. It does not
/// enforce the account's storage quota; the caller does that through
/// `MindustryCloudSaveSync.enforceQuota`.
///
/// The planner decides locally and never probes the server: it only trusts the
/// formats and limits advertised by [Quota]. A manifest upload is chosen only
/// when the slot supports it, the feature is enabled, the server advertises a
/// usable non-`zip` format, and every advertised limit is satisfied. Any
/// failure degrades to [Mode#ZIP] with a stable snake_case reason.
@NotNullByDefault
public final class MindustryCloudManifestPlan {

    /// System property that enables the manifest upload path.
    public static final String DELTA_PROPERTY = "xenon.cloud.delta";

    /// Manifest formats this build can commit and restore.
    public static final Set<String> SUPPORTED_MANIFEST_FORMATS =
            Set.of("manifest", "xenon-manifest", "xenon-manifest-v1");

    /// Format token that always means the zip fallback.
    private static final String ZIP_FORMAT = "zip";

    /// Reason reported when the manifest format is usable.
    private static final String MANIFEST_REASON = "manifest";

    /// Upload container selected by a [Decision].
    public enum Mode {

        /// A serialized manifest payload plus separately uploaded file contents.
        MANIFEST,

        /// A regular whole-instance zip archive.
        ZIP
    }

    /// One upload-format decision.
    ///
    /// @param mode    selected container
    /// @param format  chosen format token, or `zip` for a zip fallback
    /// @param reason  stable snake_case degradation reason, or `manifest` when usable
    /// @param payload serialized manifest bytes for [Mode#MANIFEST], otherwise empty
    public record Decision(Mode mode, String format, String reason,
                           byte @Unmodifiable [] payload) {

        /// Copies the payload defensively.
        public Decision {
            payload = payload.clone();
        }

        /// Returns a defensive copy of the payload.
        @Override
        public byte @Unmodifiable [] payload() {
            return payload.clone();
        }
    }

    /// Decides how the plan should be uploaded.
    ///
    /// The checks run in order and the first hit wins: slot support, feature
    /// flag, advertised format, file count, per-file size, non-empty plan and
    /// finally manifest size.
    ///
    /// The format is the first entry of `quota.formats()` that is not blank,
    /// not `zip` (case-insensitive) and in the supported manifest allowlist.
    /// Picking an advertised literal replaces the unavailable live probe: if the
    /// server did not advertise a usable format, a manifest upload cannot be
    /// assumed to work.
    ///
    /// @param plan          planned archive content
    /// @param quota         advertised manifest-shape limits and formats
    /// @param archiveSlot   whether the slot uses the archive container
    /// @param deltaEnabled  whether the manifest upload path is enabled
    /// @return the decision; never `null`
    public static Decision decide(Plan plan, Quota quota, boolean archiveSlot,
                                  boolean deltaEnabled) {
        if (!archiveSlot) {
            return zip("not_archive_slot");
        }
        if (!deltaEnabled) {
            return zip("delta_disabled");
        }
        @Nullable String format = manifestFormat(quota.formats());
        if (format == null) {
            return zip("format_unsupported");
        }
        long maxManifestFiles = quota.maxManifestFiles();
        if (maxManifestFiles > 0 && plan.files().size() > maxManifestFiles) {
            return zip("too_many_files");
        }
        long maxFileBytes = quota.maxFileBytes();
        if (maxFileBytes > 0) {
            for (PlannedFile file : plan.files()) {
                if (file.size() > maxFileBytes) {
                    return zip("file_too_large");
                }
            }
        }
        if (plan.files().isEmpty()) {
            return zip("no_files");
        }
        byte[] payload = serialize(format, plan.files());
        long maxManifestBytes = quota.maxManifestBytes();
        if (maxManifestBytes > 0 && payload.length > maxManifestBytes) {
            return zip("manifest_too_large");
        }
        return new Decision(Mode.MANIFEST, format, MANIFEST_REASON, payload);
    }

    /// Serialises one manifest payload.
    ///
    /// The result is compact UTF-8 JSON with a stable key order:
    /// `{"format":..,"files":[{"path":..,"size":..,"sha256":..}]}`.
    ///
    /// @param format chosen format token
    /// @param files  files to describe, in archive order
    /// @return the UTF-8 payload
    public static byte[] serialize(String format, List<PlannedFile> files) {
        JsonObject root = new JsonObject();
        root.addProperty("format", format);
        JsonArray array = new JsonArray();
        for (PlannedFile file : files) {
            JsonObject entry = new JsonObject();
            entry.addProperty("path", file.archivePath().replace('\\', '/'));
            entry.addProperty("size", file.size());
            entry.addProperty("sha256", file.sha256());
            array.add(entry);
        }
        root.add("files", array);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /// Returns whether a manifest path may be materialized in a data directory.
    ///
    /// A restorable path uses `/` separators, contains no whole `..` segment,
    /// backslash or colon, is not absolute, does not end with `/`, and either is
    /// exactly `settings.bin` or lives below `saves`, `maps`, `schematics` or
    /// `mods`.
    ///
    /// @param path candidate manifest path
    /// @return `true` when the path is safe and restorable
    public static boolean isRestorablePath(String path) {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\")
                || hasParentSegment(path) || path.contains(":") || path.endsWith("/")
                || path.indexOf('\0') >= 0) {
            return false;
        }
        if ("settings.bin".equals(path)) {
            return true;
        }
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            return false;
        }
        String top = path.substring(0, slash);
        return "saves".equals(top) || "maps".equals(top)
                || "schematics".equals(top) || "mods".equals(top);
    }

    /// Whether a path contains a whole `..` segment.
    ///
    /// Names such as `saves/a..b.msav` are legitimate and must stay restorable.
    ///
    /// @param path slash-separated path
    /// @return `true` when any segment is exactly `..`
    private static boolean hasParentSegment(String path) {
        int start = 0;
        while (start <= path.length()) {
            int slash = path.indexOf('/', start);
            int end = slash < 0 ? path.length() : slash;
            if (end - start == 2 && path.charAt(start) == '.'
                    && path.charAt(start + 1) == '.') {
                return true;
            }
            if (slash < 0) return false;
            start = slash + 1;
        }
        return false;
    }

    /// Reads whether the manifest upload path is enabled.
    ///
    /// @return `false` only when `xenon.cloud.delta` is `false`, ignoring case
    ///         and surrounding whitespace; `true` otherwise
    public static boolean deltaEnabled() {
        String value = System.getProperty(DELTA_PROPERTY);
        return value == null || !value.trim().equalsIgnoreCase("false");
    }

    /// Returns whether a format token is a supported manifest format.
    ///
    /// @param format format token to inspect
    /// @return `true` for a case-insensitive allow-listed manifest format
    public static boolean isSupportedManifestFormat(String format) {
        return SUPPORTED_MANIFEST_FORMATS.contains(format.trim().toLowerCase(Locale.ROOT));
    }

    /// Picks the first advertised supported non-`zip` format.
    @Nullable
    private static String manifestFormat(Iterable<String> formats) {
        for (String candidate : formats) {
            String trimmed = candidate.trim();
            if (trimmed.isEmpty() || trimmed.equalsIgnoreCase(ZIP_FORMAT)
                    || !isSupportedManifestFormat(trimmed)) {
                continue;
            }
            return trimmed;
        }
        return null;
    }

    /// Builds a zip fallback decision.
    private static Decision zip(String reason) {
        return new Decision(Mode.ZIP, ZIP_FORMAT, reason, new byte[0]);
    }

    private MindustryCloudManifestPlan() {
    }
}
