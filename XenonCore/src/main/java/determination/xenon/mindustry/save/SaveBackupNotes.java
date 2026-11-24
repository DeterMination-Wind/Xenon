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
package determination.xenon.mindustry.save;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// User notes attached to `saves/backups/*.msav` files.
///
/// Notes live in one JSON file next to the backups so a backup folder stays
/// self-describing when it is copied between machines. Entries are keyed by
/// file name only: renaming a backup by hand loses its note, which is the same
/// behaviour as a sidecar file and keeps the store a single small document.
///
/// Reads never throw — a corrupt or unreadable document degrades to "no notes"
/// so the backup list still works. Writes are atomic (write to
/// `<file>.xenon-tmp`, then move) so a crash cannot truncate the notes of every
/// backup at once.
@NotNullByDefault
public final class SaveBackupNotes {

    /// File name of the notes document inside the backups folder.
    public static final String FILE_NAME = "backup-notes.json";

    /// Maximum accepted note length, in characters.
    public static final int MAX_NOTE_LENGTH = 500;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() { }.getType();

    private final Path file;

    /// Binds the store to one backups folder.
    ///
    /// @param backupsDir the instance's `saves/backups/` folder
    public SaveBackupNotes(Path backupsDir) {
        Objects.requireNonNull(backupsDir, "backupsDir");
        this.file = backupsDir.toAbsolutePath().normalize().resolve(FILE_NAME);
    }

    /// Reads every note in the document.
    ///
    /// @return a mutable copy of the stored notes; empty when the document is
    ///     missing or unreadable
    public Map<String, String> load() {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            @Nullable LinkedHashMap<String, String> parsed = GSON.fromJson(reader, MAP_TYPE);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (IOException | JsonSyntaxException e) {
            Logger.LOG.warning("Cannot read " + file + ": " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /// Returns the note attached to one backup file.
    ///
    /// @param backupFileName bare file name such as `slot-20261124-101500.msav`
    /// @return the stored note, or `null` when none is set
    public @Nullable String get(@Nullable String backupFileName) {
        if (backupFileName == null || backupFileName.isBlank()) {
            return null;
        }
        @Nullable String note = load().get(backupFileName);
        return note == null || note.isBlank() ? null : note;
    }

    /// Writes or clears the note of one backup file.
    ///
    /// @param backupFileName bare file name of an existing backup
    /// @param note           note text; blank removes the entry
    /// @throws IOException when the document cannot be written
    public void put(String backupFileName, @Nullable String note) throws IOException {
        Objects.requireNonNull(backupFileName, "backupFileName");
        Map<String, String> notes = load();
        @Nullable String trimmed = note == null ? null : note.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            notes.remove(backupFileName);
        } else {
            notes.put(backupFileName,
                    trimmed.length() > MAX_NOTE_LENGTH
                            ? trimmed.substring(0, MAX_NOTE_LENGTH)
                            : trimmed);
        }
        save(notes);
    }

    /// Drops the note of one backup file, ignoring a missing entry.
    ///
    /// @param backupFileName bare file name of the removed backup
    /// @throws IOException when the document cannot be written
    public void remove(@Nullable String backupFileName) throws IOException {
        if (backupFileName == null || backupFileName.isBlank()) {
            return;
        }
        Map<String, String> notes = load();
        if (notes.remove(backupFileName) != null) {
            save(notes);
        }
    }

    /// Persists the document atomically.
    ///
    /// @param notes full note map to write
    /// @throws IOException when the parent folder or the file cannot be written
    private void save(Map<String, String> notes) throws IOException {
        @Nullable Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".xenon-tmp");
        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(notes, MAP_TYPE, writer);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
