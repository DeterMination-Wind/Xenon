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
package determination.xenon.mindustry.crash;

import determination.xenon.mindustry.VersionVariant;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds a pre-filled GitHub {@code /issues/new} URL for a given
 * {@link CrashReport}, targeted at the upstream repository for the
 * user's {@link VersionVariant}.
 *
 * <p>Returns {@code null} for {@link VersionVariant#CUSTOM} (no known
 * upstream).</p>
 */
public final class IssueTemplateBuilder {

    private static final int MAX_TITLE_BODY = 80;
    private static final int MAX_FRAMES = 20;

    private IssueTemplateBuilder() {}

    /**
     * Build the issue-creation URL. The result already has its query
     * parameters URL-encoded.
     *
     * @return URL string, or {@code null} if {@code variant} has no
     *         configured upstream repo.
     */
    public static String buildIssueUrl(CrashReport report, VersionVariant variant) {
        if (report == null || variant == null) return null;
        String repo = repoFor(variant);
        if (repo == null) return null;

        String title = "[Crash] " + truncate(report.getSummary(), MAX_TITLE_BODY);
        String body = buildBody(report, variant);

        return "https://github.com/" + repo + "/issues/new"
                + "?title=" + enc(title)
                + "&body=" + enc(body);
    }

    private static String repoFor(VersionVariant variant) {
        return switch (variant) {
            case VANILLA, BE -> "Anuken/Mindustry";
            case MINDUSTRY_X -> "TinyLake/MindustryX";
            case CN_ARC -> "BlueWolf3434/Mindustry-CN-ARC";
            case FOO -> "mindustry-antigrief/mindustry-client";
            case CUSTOM -> null;
        };
    }

    private static String buildBody(CrashReport report, VersionVariant variant) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("### Summary\n");
        sb.append(report.getSummary()).append("\n\n");

        sb.append("### Environment\n");
        sb.append("- Variant: ").append(variant.getDisplayName()).append('\n');
        sb.append("- Xenon version: <!-- TODO: fill in Xenon version -->\n");
        sb.append("- Crash file: ");
        if (report.getFile() != null) {
            sb.append('`').append(report.getFile().getFileName()).append('`');
        } else {
            sb.append("(unknown)");
        }
        sb.append('\n');
        if (report.getWhen() != null) {
            sb.append("- When: ").append(report.getWhen()).append('\n');
        }
        if (report.getRootClass() != null) {
            sb.append("- Root cause class: `").append(report.getRootClass()).append("`\n");
        }
        sb.append("- Category: ").append(report.getCategory()).append("\n\n");

        sb.append("### Top stack frames\n");
        sb.append("```\n");
        List<StackFrame> frames = report.getFrames();
        int n = Math.min(MAX_FRAMES, frames.size());
        for (int i = 0; i < n; i++) {
            StackFrame f = frames.get(i);
            sb.append("at ").append(f.toString());
            if (f.highlight()) sb.append("    <-- mod");
            sb.append('\n');
        }
        if (frames.size() > n) {
            sb.append("... ").append(frames.size() - n).append(" more frames\n");
        }
        sb.append("```\n\n");

        sb.append("### Steps to reproduce\n");
        sb.append("<!-- describe what you were doing when the game crashed -->\n");
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        if (one.length() <= max) return one;
        return one.substring(0, max - 1).trim() + "…";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
