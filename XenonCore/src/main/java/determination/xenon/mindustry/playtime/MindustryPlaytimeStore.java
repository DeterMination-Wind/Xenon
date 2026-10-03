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
package determination.xenon.mindustry.playtime;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Append-only session log backing the per-instance playtime statistics.
///
/// Events are stored as one JSON object per line in
/// `<versionRoot>/xenon-playtime.jsonl`, for example
/// `{"type":"start","pid":1234,"at":1690000000000}`. Each append holds an
/// exclusive [FileLock] on the file, so several launcher processes may
/// safely write the same log. Reading is tolerant: unreadable or malformed
/// lines are skipped silently and never propagate an exception.
@NotNullByDefault
public final class MindustryPlaytimeStore {

    /// File name of the per-instance playtime log.
    public static final String FILE_NAME = "xenon-playtime.jsonl";

    /// JSONL event type written when a session starts.
    private static final String TYPE_START = "start";

    /// JSONL event type written when a session ends.
    private static final String TYPE_END = "end";

    /// JSON codec shared by all appends.
    private static final Gson GSON = new Gson();

    /// Process-wide serialization of appends so threads of this JVM never try
    /// to hold overlapping locks on one file region; cross-process exclusion is
    /// provided by the file lock itself.
    private static final Object APPEND_MONITOR = new Object();

    /// Path of the JSONL file backing this store.
    private final Path file;

    /// Creates a store rooted at one Mindustry version directory.
    public MindustryPlaytimeStore(Path versionRoot) {
        this.file = versionRoot.resolve(FILE_NAME);
    }

    /// Appends a session-start event for `pid`.
    ///
    /// The write blocks until the file lock is acquired and throws
    /// [IOException] when the log cannot be written.
    public void recordStart(long pid, long startedAtEpochMillis) throws IOException {
        append(TYPE_START, pid, startedAtEpochMillis);
    }

    /// Appends a session-end event for `pid`.
    ///
    /// The write blocks until the file lock is acquired and throws
    /// [IOException] when the log cannot be written.
    public void recordEnd(long pid, long endedAtEpochMillis) throws IOException {
        append(TYPE_END, pid, endedAtEpochMillis);
    }

    /// Builds the playtime summary of this instance.
    ///
    /// Missing files, unreadable lines and malformed events are skipped
    /// silently, so this method never throws and may return [PlaytimeSummary#EMPTY]
    /// or a partial summary.
    public PlaytimeSummary readSummary() {
        List<Event> events = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Event event = parse(line);
                if (event != null) {
                    events.add(event);
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A missing or partially corrupted log must not break the UI.
        }
        return summarize(events);
    }

    /// Opens the file in append mode and writes one locked JSONL event.
    private void append(String type, long pid, long at) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String line = GSON.toJson(new Event(type, pid, at)) + System.lineSeparator();
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        synchronized (APPEND_MONITOR) {
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
                FileLock lock = channel.lock();
                try {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                } finally {
                    lock.release();
                }
            }
        }
    }

    /// Parses one JSONL line; returns null for blank, unknown or malformed events.
    private static @Nullable Event parse(String line) {
        if (line.isBlank()) {
            return null;
        }
        try {
            Event event = GSON.fromJson(line, Event.class);
            if (event == null || event.pid() <= 0 || event.at() <= 0) {
                return null;
            }
            if (!TYPE_START.equals(event.type()) && !TYPE_END.equals(event.type())) {
                return null;
            }
            return event;
        } catch (JsonParseException | IllegalStateException ignored) {
            return null;
        }
    }

    /// Pairs every end with the most recent still-open start of the same pid.
    ///
    /// The stack discipline (LIFO) means a dangling start left behind by a
    /// killed launcher can never swallow the end of a later session that
    /// happens to reuse the same pid. Unpaired starts and dangling ends do
    /// not contribute a session, and durations are only accumulated when they
    /// are non-negative.
    private static PlaytimeSummary summarize(List<Event> events) {
        List<Event> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(Event::at));
        Map<Long, Deque<Event>> openStarts = new HashMap<>();

        long totalActiveMillis = 0L;
        long lastLaunchEpochMillis = 0L;
        int sessions = 0;
        for (Event event : sorted) {
            if (TYPE_START.equals(event.type())) {
                openStarts.computeIfAbsent(event.pid(), ignored -> new ArrayDeque<>()).push(event);
                lastLaunchEpochMillis = Math.max(lastLaunchEpochMillis, event.at());
                continue;
            }
            Deque<Event> starts = openStarts.get(event.pid());
            if (starts == null || starts.isEmpty()) {
                continue;
            }
            Event start = starts.pop();
            long duration = event.at() - start.at();
            if (duration >= 0L) {
                totalActiveMillis += duration;
                sessions++;
            }
        }
        return new PlaytimeSummary(totalActiveMillis, lastLaunchEpochMillis, sessions);
    }

    /// One JSONL event. Unknown fields from future formats are ignored by Gson.
    private record Event(String type, long pid, long at) {
    }
}
