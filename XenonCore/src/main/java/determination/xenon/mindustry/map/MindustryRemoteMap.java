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
package determination.xenon.mindustry.map;

import determination.xenon.util.io.FileUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable list-row model returned by {@code api.mindustry.top/maps/list}.
 *
 * <p>The backend text fields frequently contain Mindustry's in-band colour
 * markup such as {@code [red]} or {@code [#EEC591]}. This type exposes both
 * the raw values (for debugging / future detail pages) and cleaned helper
 * accessors intended for launcher UI + file naming.</p>
 */
@NotNullByDefault
public record MindustryRemoteMap(
        int id,
        String latest,
        String name,
        String description,
        String previewUrl,
        List<String> tags,
        int width,
        int height,
        String mode
) {
    private static final Pattern MINDUSTRY_MARKUP =
            Pattern.compile("\\[(?:[#A-Za-z0-9_@;:=+.,\\-/]+)?]");
    private static final Pattern TAG_SUFFIX = Pattern.compile("§.*$");
    private static final Pattern ILLEGAL_FILE_CHARS = Pattern.compile("[<>:\"/\\\\|?*]");

    public MindustryRemoteMap {
        latest = latest == null ? "" : latest;
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        previewUrl = previewUrl == null ? "" : previewUrl;
        tags = tags == null ? List.of() : List.copyOf(tags);
        mode = mode == null ? "" : mode;
    }

    /** Feed-level dedup key: newest thread id if provided, else this row id. */
    public String latestKey() {
        return latest.isBlank() ? String.valueOf(id) : latest;
    }

    /** Human-facing title with Mindustry markup stripped. */
    public String displayName() {
        String cleaned = normalizeUiText(name);
        return cleaned.isBlank() ? "Map #" + id : cleaned;
    }

    /** One-line summary suitable for compact cards. */
    public String displaySummary() {
        return normalizeUiText(description);
    }

    /** Mode chip / filter token, falling back to the cleaned tag payload. */
    public String displayMode() {
        if (!mode.isBlank() && !"Unknown".equalsIgnoreCase(mode)) {
            return mode;
        }
        for (String tag : cleanedTags()) {
            if (tag.equalsIgnoreCase("Pvp")
                    || tag.equalsIgnoreCase("Survive")
                    || tag.equalsIgnoreCase("Attack")
                    || tag.equalsIgnoreCase("Sandbox")
                    || tag.equalsIgnoreCase("Editor")) {
                return tag;
            }
        }
        return mode.isBlank() ? "Unknown" : mode;
    }

    /** True when the cleaned tag list contains {@code CP}. */
    public boolean hasCpTag() {
        for (String tag : cleanedTags()) {
            if ("CP".equalsIgnoreCase(tag)) return true;
        }
        return false;
    }

    /** First cleaned {@code v###} tag, or {@code null}. */
    public @Nullable String versionTag() {
        for (String tag : cleanedTags()) {
            if (tag.matches("(?i)v\\d+")) return tag;
        }
        return null;
    }

    /** Display tags with the feed's numeric thread id tag removed. */
    public List<String> displayTags() {
        List<String> out = new ArrayList<>();
        for (String tag : cleanedTags()) {
            if (tag.matches("\\d+")) continue;
            out.add(tag);
        }
        return List.copyOf(out);
    }

    /** Suggested filename for a downloaded map archive. */
    public String suggestedFileName() {
        String stem = sanitizeFileStem(displayName());
        if (stem.isBlank()) {
            stem = "map-" + id;
        }
        String candidate = stem + ".msav";
        if (!FileUtils.isNameValid(candidate)) {
            candidate = "map-" + id + ".msav";
        }
        return candidate;
    }

    private List<String> cleanedTags() {
        List<String> out = new ArrayList<>(tags.size());
        for (String raw : tags) {
            if (raw == null || raw.isBlank()) continue;
            String cleaned = normalizeTag(raw);
            if (!cleaned.isBlank()) out.add(cleaned);
        }
        return out;
    }

    static String normalizeTag(String raw) {
        Objects.requireNonNull(raw, "raw");
        String withoutSuffix = TAG_SUFFIX.matcher(raw).replaceFirst("");
        return normalizeUiText(withoutSuffix);
    }

    static String normalizeUiText(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String cleaned = MINDUSTRY_MARKUP.matcher(raw).replaceAll("");
        cleaned = cleaned.replace('\r', ' ').replace('\n', ' ');
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned;
    }

    static String sanitizeFileStem(String raw) {
        String cleaned = normalizeUiText(raw);
        if (cleaned.isBlank()) return "";
        cleaned = ILLEGAL_FILE_CHARS.matcher(cleaned).replaceAll("_");
        cleaned = cleaned.replace('\0', '_');
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        if (cleaned.length() > 80) {
            cleaned = cleaned.substring(0, 80).trim();
        }
        return cleaned;
    }
}
