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

import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One parsed Mindustry crash report.
 *
 * <p>Built by {@link MindustryCrashAnalyzer#scan(Path)} from a crash
 * dump file under {@code <dataDir>/crashes/} or from
 * {@code <dataDir>/last_log.txt}. The original text is preserved in
 * {@link #getFullText()} so the UI can render it verbatim in a
 * monospace pane.</p>
 */
public final class CrashReport {

    /** Coarse cause classification, mirrored from the dominant frame mix. */
    public enum Category {
        JVM, MOD, NATIVE, UNKNOWN
    }

    private final Path file;
    private final Instant when;
    private final String summary;
    private final String rootClass;
    private final List<StackFrame> frames;
    private final Category category;
    private final String fullText;

    public CrashReport(Path file,
                       Instant when,
                       String summary,
                       String rootClass,
                       List<StackFrame> frames,
                       Category category,
                       String fullText) {
        this.file = file;
        this.when = when;
        this.summary = summary == null ? "" : summary;
        this.rootClass = rootClass;
        this.frames = frames == null
                ? List.of()
                : Collections.unmodifiableList(List.copyOf(frames));
        this.category = category == null ? Category.UNKNOWN : category;
        this.fullText = fullText == null ? "" : fullText;
    }

    public Path getFile() {
        return file;
    }

    public Instant getWhen() {
        return when;
    }

    public String getSummary() {
        return summary;
    }

    /**
     * Class name from the deepest {@code Caused by:} entry, or the
     * outermost throwable if no chain was present. Stored as a string
     * because Xenon does not actually instantiate the throwable.
     */
    public String getRootClass() {
        return rootClass;
    }

    public List<StackFrame> getFrames() {
        return frames;
    }

    public Category getCategory() {
        return category;
    }

    /** Full original text of the crash file (UI shows this verbatim). */
    public String getFullText() {
        return fullText;
    }

    @Override
    public String toString() {
        return "CrashReport{" + file + " @ " + when + " " + category
                + " root=" + rootClass + " frames=" + frames.size() + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CrashReport that)) return false;
        return Objects.equals(file, that.file)
                && Objects.equals(when, that.when);
    }

    @Override
    public int hashCode() {
        return Objects.hash(file, when);
    }
}
