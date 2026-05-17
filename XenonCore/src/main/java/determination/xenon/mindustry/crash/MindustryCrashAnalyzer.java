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

import determination.xenon.util.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scan a Mindustry data directory for crash artefacts and build
 * {@link CrashReport} objects from them.
 *
 * <p>This class is intentionally tolerant: malformed files don't break
 * the scan, they just turn into a degraded report whose
 * {@link CrashReport#getFullText()} is still the original text. The
 * goal is to give users something to copy-paste into an issue, not to
 * be a strict parser.</p>
 */
public final class MindustryCrashAnalyzer {

    private MindustryCrashAnalyzer() {}

    private static final String CALLER = MindustryCrashAnalyzer.class.getName();

    /** Filename like {@code crash_2024-01-01_xx.txt}. Captures the date. */
    private static final Pattern CRASH_FILENAME = Pattern.compile(
            "^crash[_-](\\d{4})[-_](\\d{2})[-_](\\d{2})(?:[_-]([0-9]{2})[-_:]([0-9]{2})[-_:]([0-9]{2}))?.*\\.txt$",
            Pattern.CASE_INSENSITIVE);

    /** A standard JVM stack frame line ({@code at pkg.Class.method(File.java:42)}). */
    private static final Pattern FRAME = Pattern.compile(
            "^\\s*at\\s+([\\w$.<>]+)\\.([\\w$<>\\-]+)\\(([^)]*)\\)\\s*$");

    /** Header line of a throwable: {@code pkg.Class: message}. */
    private static final Pattern THROWABLE_HEAD = Pattern.compile(
            "^([\\w$.]+(?:Exception|Error|Throwable))(?::\\s*(.*))?$");

    /** {@code Caused by: pkg.Class: message}. */
    private static final Pattern CAUSED_BY = Pattern.compile(
            "^\\s*Caused by:\\s*([\\w$.]+)(?::\\s*(.*))?$");

    /**
     * Scan {@code <dataDir>/crashes/*.txt} and {@code <dataDir>/last_log.txt}.
     * Returns reports sorted newest-first.
     *
     * @param dataDir Mindustry data directory; if it doesn't exist the
     *                method returns an empty list.
     */
    public static List<CrashReport> scan(Path dataDir) throws IOException {
        if (dataDir == null || !Files.isDirectory(dataDir)) {
            return List.of();
        }
        List<CrashReport> out = new ArrayList<>();

        Path crashes = dataDir.resolve("crashes");
        if (Files.isDirectory(crashes)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(crashes, "*.txt")) {
                for (Path file : stream) {
                    if (!Files.isRegularFile(file)) continue;
                    CrashReport r = safeParse(file);
                    if (r != null) out.add(r);
                }
            }
        }

        Path lastLog = dataDir.resolve("last_log.txt");
        if (Files.isRegularFile(lastLog)) {
            CrashReport r = safeParse(lastLog);
            if (r != null) out.add(r);
        }

        out.sort(Comparator.comparing(CrashReport::getWhen,
                Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return out;
    }

    private static CrashReport safeParse(Path file) {
        try {
            return parse(file);
        } catch (IOException e) {
            Logger.LOG.log(System.Logger.Level.WARNING,
                    "Failed to read crash file " + file, e);
            return null;
        } catch (RuntimeException e) {
            Logger.LOG.log(System.Logger.Level.WARNING,
                    "Failed to parse crash file " + file, e);
            return null;
        }
    }

    /** Parse a single file into a {@link CrashReport}. */
    public static CrashReport parse(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Instant when = inferTimestamp(file);
        return parse(file, when, text);
    }

    static CrashReport parse(Path file, Instant when, String text) {
        String[] lines = text.split("\\R", -1);

        String headLine = null;       // first throwable header we saw
        String headMessage = null;
        String rootClass = null;      // class from deepest "Caused by" or head
        List<StackFrame> frames = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            Matcher mh = THROWABLE_HEAD.matcher(line.trim());
            if (headLine == null && mh.matches()) {
                headLine = mh.group(1);
                headMessage = mh.group(2);
                rootClass = headLine;
                continue;
            }

            Matcher mc = CAUSED_BY.matcher(line);
            if (mc.matches()) {
                // Always overwrite — we want the deepest cause class.
                rootClass = mc.group(1);
                continue;
            }

            Matcher mf = FRAME.matcher(line);
            if (mf.matches()) {
                String className = mf.group(1);
                String methodName = mf.group(2);
                String tail = mf.group(3);
                String fileName = null;
                int lineNumber = -1;
                if ("Native Method".equals(tail)) {
                    lineNumber = -2;
                } else if (!"Unknown Source".equals(tail) && !tail.isEmpty()) {
                    int colon = tail.lastIndexOf(':');
                    if (colon >= 0) {
                        fileName = tail.substring(0, colon);
                        try {
                            lineNumber = Integer.parseInt(tail.substring(colon + 1).trim());
                        } catch (NumberFormatException ignored) {
                            lineNumber = -1;
                        }
                    } else {
                        fileName = tail;
                    }
                }
                StackFrame.Source src = StackFrame.classify(className, tail);
                frames.add(new StackFrame(className, methodName, fileName, lineNumber, src));
            }
        }

        String summary;
        if (headLine != null) {
            String tail = headMessage == null || headMessage.isBlank()
                    ? "" : ": " + headMessage.trim();
            summary = headLine + tail;
        } else if (!frames.isEmpty()) {
            // No throwable header: synthesise from the top frame.
            StackFrame top = frames.get(0);
            summary = top.toString();
        } else {
            // Last resort: first non-blank line of the file.
            String first = "";
            for (String l : lines) {
                if (!l.isBlank()) { first = l.strip(); break; }
            }
            summary = first.isEmpty() ? "(empty crash log)" : first;
        }

        CrashReport.Category category = classifyOverall(frames);

        return new CrashReport(file, when, summary, rootClass, frames, category, text);
    }

    private static CrashReport.Category classifyOverall(List<StackFrame> frames) {
        if (frames.isEmpty()) return CrashReport.Category.UNKNOWN;
        // If any mod frame is present, it's almost always the suspect.
        Map<StackFrame.Source, Integer> counts = new EnumMap<>(StackFrame.Source.class);
        for (StackFrame f : frames) {
            counts.merge(f.getSource(), 1, Integer::sum);
        }
        if (counts.getOrDefault(StackFrame.Source.MOD, 0) > 0) {
            return CrashReport.Category.MOD;
        }
        if (counts.getOrDefault(StackFrame.Source.NATIVE, 0) > 0) {
            return CrashReport.Category.NATIVE;
        }
        if (counts.getOrDefault(StackFrame.Source.JVM, 0) > 0) {
            return CrashReport.Category.JVM;
        }
        return CrashReport.Category.UNKNOWN;
    }

    /** Pull a timestamp from {@code crash_YYYY-MM-DD[_HH-MM-SS]…} or fall back to mtime. */
    static Instant inferTimestamp(Path file) {
        String name = file.getFileName().toString();
        Matcher m = CRASH_FILENAME.matcher(name);
        if (m.matches()) {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            int day = Integer.parseInt(m.group(3));
            int hour = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
            int minute = m.group(5) == null ? 0 : Integer.parseInt(m.group(5));
            int second = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
            try {
                return LocalDateTime.of(year, month, day, hour, minute, second)
                        .atZone(ZoneId.systemDefault())
                        .toInstant();
            } catch (RuntimeException ignored) {
                // fall through to mtime
            }
        }
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            Logger.LOG.log(System.Logger.Level.DEBUG,
                    "Failed to read mtime for " + file, e);
            return Instant.EPOCH;
        }
    }

    // Visible-for-formatters helper — used by IssueTemplateBuilder.
    static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    static String callerName() {
        return CALLER;
    }
}
