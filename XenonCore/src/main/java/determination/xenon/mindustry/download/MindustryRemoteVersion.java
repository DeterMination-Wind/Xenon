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

import java.time.Instant;
import java.util.Objects;

/**
 * One installable Mindustry build resolved from an upstream release feed.
 *
 * <p>Immutable value type; {@link MindustryVersionList} produces these
 * and the installer/download UI consumes them.</p>
 */
public final class MindustryRemoteVersion {
    private final int build;
    private final String buildType;
    private final VersionVariant variant;
    private final String downloadUrl;
    private final Instant publishedAt;
    private final long size;
    private final String tagName;
    private final String fileName;

    /**
     * Full constructor — preferred for new code.
     *
     * @param build       Mindustry build number (e.g. 146), or 0 if unknown
     * @param buildType   {@code stable} or {@code be} (or variant-specific tag)
     * @param variant     which upstream this version belongs to
     * @param downloadUrl direct asset URL (call {@link MirrorSelector#wrap} when fetching)
     * @param publishedAt release publish time; {@code null} if upstream omitted it
     * @param size        asset size in bytes, or 0 if unknown
     * @param tagName     upstream release tag verbatim (e.g. {@code v157.4} or {@code v2026.05.X34}); never {@code null}
     * @param fileName    asset file name (e.g. {@code Mindustry.jar}); never {@code null}
     */
    public MindustryRemoteVersion(int build,
                                  String buildType,
                                  VersionVariant variant,
                                  String downloadUrl,
                                  Instant publishedAt,
                                  long size,
                                  String tagName,
                                  String fileName) {
        this.build = build;
        this.buildType = buildType == null ? "" : buildType;
        this.variant = Objects.requireNonNull(variant, "variant");
        this.downloadUrl = Objects.requireNonNull(downloadUrl, "downloadUrl");
        this.publishedAt = publishedAt;
        this.size = Math.max(0, size);
        this.tagName = tagName == null ? "" : tagName;
        this.fileName = fileName == null ? "" : fileName;
    }

    /** Backward-compatible constructor — older callers without tag / file name. */
    public MindustryRemoteVersion(int build,
                                  String buildType,
                                  VersionVariant variant,
                                  String downloadUrl,
                                  Instant publishedAt,
                                  long size) {
        this(build, buildType, variant, downloadUrl, publishedAt, size, "", "");
    }

    public int getBuild() { return build; }

    public String getBuildType() { return buildType; }

    public VersionVariant getVariant() { return variant; }

    public String getDownloadUrl() { return downloadUrl; }

    public Instant getPublishedAt() { return publishedAt; }

    public long getSize() { return size; }

    public String getTagName() { return tagName; }

    public String getFileName() { return fileName; }

    /** Best-effort human-readable identifier: tag if present, else "build N". */
    public String getDisplayVersion() {
        if (tagName != null && !tagName.isEmpty()) return tagName;
        if (build > 0) return "build " + build;
        return fileName != null && !fileName.isEmpty() ? fileName : "(unknown)";
    }

    @Override
    public String toString() {
        return "MindustryRemoteVersion{" + variant + " " + getDisplayVersion()
                + " (" + buildType + "), " + size + " bytes}";
    }
}
