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

import determination.xenon.util.io.FileUtils;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Filesystem-side operations on Mindustry {@code .msav} files for the launcher
 * UI: list / backup / restore / rename / delete / export-zip / cross-version copy.
 *
 * <p>The service is bound to one {@code saves/} directory at construction time
 * (i.e. {@code <dataDir>/saves}). Backups land in a sibling
 * {@code saves/backups/} folder so the game itself never sees them as live
 * saves.</p>
 *
 * <p>All mutating methods operate atomically per-file using
 * {@link Files#copy(Path, Path, java.nio.file.CopyOption...)} /
 * {@link Files#move(Path, Path, java.nio.file.CopyOption...)} with
 * {@link StandardCopyOption#REPLACE_EXISTING} where it is explicitly safe
 * to clobber. {@link #rename(Path, String)} refuses to overwrite and raises
 * {@link BackupConflictException}.</p>
 *
 * <p>This service does <em>not</em> cache results — call {@link #list()} again
 * after any mutation if the UI needs a refreshed view.</p>
 */
public final class SaveBackupService {

    /** Filename suffix Mindustry uses for save files. */
    public static final String MSAV_EXT = ".msav";

    /** Sub-folder of {@code saves/} where {@link #backup(SaveSummary)} drops copies. */
    public static final String BACKUPS_SUBDIR = "backups";

    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

    /// Same-second collision counter appended by [#backupWithResult(SaveSummary)].
    private static final java.util.regex.Pattern COLLISION_SUFFIX =
            java.util.regex.Pattern.compile("-\\d{8}-\\d{6}-(\\d+)\\.msav$");

    private final Path savesDir;

    /**
     * @param savesDir absolute or relative path to the {@code saves/} folder of
     *                 a Mindustry data directory (i.e. {@code <.data>/saves}).
     *                 The folder is <em>not</em> required to exist yet — it
     *                 will be created lazily by mutating operations.
     */
    public SaveBackupService(Path savesDir) {
        this.savesDir = Objects.requireNonNull(savesDir, "savesDir").toAbsolutePath().normalize();
    }

    /** @return the {@code saves/} folder this service is bound to. */
    public Path getSavesDir() {
        return savesDir;
    }

    /** @return the {@code saves/backups/} folder backups are written into. */
    public Path getBackupsDir() {
        return savesDir.resolve(BACKUPS_SUBDIR);
    }

    // ---------------------------------------------------------------------
    // Listing
    // ---------------------------------------------------------------------

    /**
     * Scan {@link #getSavesDir()} (non-recursively, excluding the
     * {@link #BACKUPS_SUBDIR backups/} sub-folder) and return a parsed
     * {@link SaveSummary} for every {@code *.msav} file present.
     *
     * <p>Files that fail to parse are <em>logged</em> via
     * {@link Logger#warning(String, Throwable)} and skipped rather than
     * aborting the whole listing — one corrupt save should not break the UI.</p>
     *
     * <p>Results are sorted by {@link SaveSummary#lastModified()} descending
     * (newest first).</p>
     *
     * @return mutable list (caller may sort / filter further); empty if the
     *         saves directory does not exist
     * @throws IOException if the directory exists but cannot be enumerated
     */
    public List<SaveSummary> list() throws IOException {
        if (!Files.isDirectory(savesDir)) {
            return new ArrayList<>();
        }

        List<SaveSummary> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDir)) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(MSAV_EXT)) {
                    continue;
                }
                try {
                    out.add(SaveFileReader.readHeader(entry));
                } catch (IOException e) {
                    Logger.LOG.warning("Failed to parse .msav " + entry + ", skipping", e);
                }
            }
        }
        out.sort(Comparator.comparing(SaveSummary::lastModified).reversed());
        return out;
    }

    // ---------------------------------------------------------------------
    // Backup / Restore
    // ---------------------------------------------------------------------

    /// Result of one [backupWithResult(SaveSummary)] call.
    ///
    /// @param file    the backup that represents the save now
    /// @param skipped `true` when an identical newer backup already existed
    @NotNullByDefault
    public record BackupResult(Path file, boolean skipped) {
        /// Validates the required member.
        public BackupResult {
            Objects.requireNonNull(file, "file");
        }
    }

    /**
     * Copy {@code s.file()} into {@link #getBackupsDir()} with a timestamp
     * suffix, preserving the original {@code .msav} extension.
     *
     * <p>Naming: {@code <basename>-<yyyyMMdd-HHmmss>.msav}. If the timestamp
     * collides (same second), a numeric suffix {@code -1}, {@code -2}, ... is
     * appended until a free name is found.</p>
     *
     * <p>When the newest existing backup of the same save already has byte
     * identical content, no copy is written and the existing file is returned:
     * automatic backups run after every long session, and a save the player did
     * not touch would otherwise consume the whole retention window with
     * duplicate files.</p>
     *
     * @param s summary returned from {@link #list()} (or any
     *          {@link SaveSummary} whose {@link SaveSummary#file()} is a
     *          readable {@code .msav})
     * @return path of the written (or reused) backup file
     * @throws IOException if the source cannot be read or the backup folder
     *                     cannot be created / written
     */
    public Path backup(SaveSummary s) throws IOException {
        return backupWithResult(s).file();
    }

    /// Copies {@code s.file()} into the backups folder unless an identical copy
    /// is already the newest backup of that save.
    ///
    /// @param s summary of the live save
    /// @return the backup that represents this save plus whether it was reused
    /// @throws IOException if the source cannot be read or written
    public BackupResult backupWithResult(SaveSummary s) throws IOException {
        Objects.requireNonNull(s, "s");
        Path src = s.file();
        if (!Files.isRegularFile(src)) {
            throw new IOException("Source save does not exist: " + src);
        }

        Path backupsDir = getBackupsDir();
        Files.createDirectories(backupsDir);

        String base = stripExtension(src.getFileName().toString());
        long srcSize = Files.size(src);
        @Nullable Path newest = newestBackup(backupsDir, base);
        if (newest != null && Files.size(newest) == srcSize
                && sha256(newest).equals(sha256(src))) {
            Logger.LOG.info("Skipped unchanged backup for " + src.getFileName()
                    + " -> " + newest.getFileName());
            return new BackupResult(newest, true);
        }

        String stamp = BACKUP_STAMP.format(LocalDateTime.now().atZone(ZoneId.systemDefault()));
        Path target = backupsDir.resolve(base + "-" + stamp + MSAV_EXT);

        for (int attempt = 1; Files.exists(target); attempt++) {
            target = backupsDir.resolve(base + "-" + stamp + "-" + attempt + MSAV_EXT);
        }

        Path tmp = target.resolveSibling(target.getFileName() + ".xenon-tmp");
        Files.copy(src, tmp, StandardCopyOption.COPY_ATTRIBUTES,
                StandardCopyOption.REPLACE_EXISTING);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        Logger.LOG.info("Backed up save " + src.getFileName() + " -> " + target.getFileName());
        return new BackupResult(target, false);
    }

    /// Lists every backup of this saves folder, newest first.
    ///
    /// Notes come from the folder's [SaveBackupNotes] document. Files whose
    /// names carry no stamp fall back to their modification time so a
    /// hand-renamed backup keeps a position in the list.
    ///
    /// @return mutable list sorted by backup time descending
    /// @throws IOException when the backups folder exists but cannot be listed
    public List<BackupSummary> listBackups() throws IOException {
        Path dir = getBackupsDir();
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        Map<String, String> notes = new SaveBackupNotes(dir).load();
        List<BackupSummary> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(MSAV_EXT)) {
                    continue;
                }
                @Nullable String slot = MindustryBackupNaming.slotName(entry);
                String slotName = slot == null ? name : slot;
                @Nullable Instant takenAt = MindustryBackupNaming.timestamp(entry);
                long size;
                try {
                    size = Files.size(entry);
                    if (takenAt == null) {
                        takenAt = Files.getLastModifiedTime(entry).toInstant();
                    }
                } catch (IOException e) {
                    size = -1L;
                }
                out.add(new BackupSummary(entry, slotName, takenAt, size, notes.get(name)));
            }
        }
        out.sort(Comparator.comparing((BackupSummary b) -> b.takenAt(),
                Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(b -> collisionCounter(b.file()), Comparator.reverseOrder())
                .thenComparing(BackupSummary::fileName, Comparator.reverseOrder()));
        return out;
    }

    /// Reads the same-second collision counter out of a backup file name.
    ///
    /// `slot-20261124-101500.msav` yields `0`, `slot-20261124-101500-3.msav`
    /// yields `3`, so two copies written in one second still sort newest-first.
    ///
    /// @param file backup file
    /// @return the counter, or `0` when the name has none
    private static int collisionCounter(Path file) {
        @Nullable Path name = file.getFileName();
        if (name == null) {
            return 0;
        }
        java.util.regex.Matcher matcher = COLLISION_SUFFIX.matcher(name.toString());
        if (!matcher.find()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /// Finds the newest backup belonging to one save slot.
    ///
    /// @param backupsDir backups folder
    /// @param base       live save name without its extension
    /// @return the newest matching backup, or `null` when there is none
    private static @Nullable Path newestBackup(Path backupsDir, String base) throws IOException {
        Path best = null;
        Instant bestStamp = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(backupsDir)) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) {
                    continue;
                }
                String name = entry.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(MSAV_EXT)) {
                    continue;
                }
                if (!base.equals(MindustryBackupNaming.slotName(entry))) {
                    continue;
                }
                @Nullable Instant stamp = MindustryBackupNaming.timestamp(entry);
                if (stamp == null) {
                    try {
                        stamp = Files.getLastModifiedTime(entry).toInstant();
                    } catch (IOException ignored) {
                        continue;
                    }
                }
                if (best == null || bestStamp == null || stamp.isAfter(bestStamp)
                        || (stamp.equals(bestStamp)
                                && collisionCounter(entry) > collisionCounter(best))) {
                    best = entry;
                    bestStamp = stamp;
                }
            }
        }
        return best;
    }

    /// Streaming SHA-256 of one file.
    ///
    /// @param file file to hash
    /// @return lowercase hex digest
    /// @throws IOException when the file cannot be read
    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
                byte[] buffer = new byte[64 * 1024];
                while (true) {
                    int read = in.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    /**
     * Restore a previously-written backup over a live save slot. The target's
     * parent directory is created if needed; an existing target file at
     * {@code target} is replaced atomically where the platform supports it.
     *
     * @param backup path produced by {@link #backup(SaveSummary)} (or any
     *               readable {@code .msav})
     * @param target destination, typically {@code <savesDir>/<slot>.msav}
     */
    public void restore(Path backup, Path target) throws IOException {
        Objects.requireNonNull(backup, "backup");
        Objects.requireNonNull(target, "target");
        if (!Files.isRegularFile(backup)) {
            throw new IOException("Backup file does not exist: " + backup);
        }

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Logger.LOG.info("Restored save " + backup.getFileName() + " -> " + target);
    }

    // ---------------------------------------------------------------------
    // Rename / Delete
    // ---------------------------------------------------------------------

    /**
     * Rename a save in place. {@code newBaseName} must <em>not</em> include
     * the {@code .msav} extension — it is appended automatically.
     *
     * <p>Refuses to overwrite an existing file: if the target name already
     * exists, a {@link BackupConflictException} is raised and the source
     * file is left untouched.</p>
     *
     * @param msav        the save file to rename (must exist)
     * @param newBaseName new file name without {@code .msav}; must not be
     *                    {@code null}, blank, contain path separators or the
     *                    {@code .msav} extension
     * @throws BackupConflictException if a file with the resolved target name
     *                                 already exists in the same directory
     * @throws IOException             on filesystem errors or invalid
     *                                 {@code newBaseName}
     */
    public void rename(Path msav, String newBaseName) throws IOException {
        Objects.requireNonNull(msav, "msav");
        Objects.requireNonNull(newBaseName, "newBaseName");
        validateBaseName(newBaseName);

        if (!Files.isRegularFile(msav)) {
            throw new IOException("Save file does not exist: " + msav);
        }

        Path parent = msav.getParent();
        if (parent == null) {
            throw new IOException("Refusing to rename file with no parent directory: " + msav);
        }

        Path target = parent.resolve(newBaseName + MSAV_EXT);
        if (Files.exists(target)) {
            throw new BackupConflictException(target,
                    "Cannot rename " + msav.getFileName() + " -> " + target.getFileName()
                            + ": target already exists");
        }

        Files.move(msav, target);
        Logger.LOG.info("Renamed save " + msav.getFileName() + " -> " + target.getFileName());
    }

    /**
     * Delete a save file, preferring the platform trash. Missing files are
     * tolerated silently — the operation is idempotent from the caller's
     * perspective.
     */
    public void delete(Path msav) throws IOException {
        Objects.requireNonNull(msav, "msav");
        boolean removed = FileUtils.deleteSafely(msav);
        if (removed) {
            Logger.LOG.info("Deleted save " + msav);
        } else {
            Logger.LOG.debug("delete(): nothing to remove at " + msav);
        }
    }

    // ---------------------------------------------------------------------
    // Export
    // ---------------------------------------------------------------------

    /**
     * Wrap a single {@code .msav} file in a zip archive at {@code zipTarget}.
     * The archive contains exactly one entry, named after the source file's
     * basename ({@code <name>.msav}).
     *
     * @param msav      source save file
     * @param zipTarget destination {@code .zip} (overwritten if it exists)
     * @return {@code zipTarget}
     */
    public Path exportZip(Path msav, Path zipTarget) throws IOException {
        Objects.requireNonNull(msav, "msav");
        Objects.requireNonNull(zipTarget, "zipTarget");
        if (!Files.isRegularFile(msav)) {
            throw new IOException("Save file does not exist: " + msav);
        }

        Path parent = zipTarget.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String entryName = msav.getFileName().toString();
        try (OutputStream rawOut = Files.newOutputStream(zipTarget);
             BufferedOutputStream bufOut = new BufferedOutputStream(rawOut);
             ZipOutputStream zip = new ZipOutputStream(bufOut)) {

            ZipEntry entry = new ZipEntry(entryName);
            try {
                entry.setLastModifiedTime(Files.getLastModifiedTime(msav));
            } catch (IOException ignored) {
                // not fatal: zip entries don't strictly need a mtime
            }
            zip.putNextEntry(entry);

            try (InputStream in = Files.newInputStream(msav);
                 BufferedInputStream bin = new BufferedInputStream(in)) {
                bin.transferTo(zip);
            }
            zip.closeEntry();
        }

        Logger.LOG.info("Exported save " + msav.getFileName() + " -> " + zipTarget);
        return zipTarget;
    }

    // ---------------------------------------------------------------------
    // Cross-version copy
    // ---------------------------------------------------------------------

    /**
     * Copy {@code msav} into another version's {@code saves/} directory,
     * tagging the filename with {@code -from-<srcDir-name>} so the user can
     * see where the save came from and so we never overwrite a slot that
     * already exists in the destination.
     *
     * <p>{@code <srcDir-name>} is the simple name of {@code msav}'s parent
     * folder (typically the slot dir of the source data directory). If the
     * tagged name still collides on the destination side, a numeric suffix
     * {@code -1}, {@code -2}, ... is appended until a free name is found —
     * existing destination files are <em>never</em> overwritten.</p>
     *
     * @param msav                 source save file
     * @param otherVersionSavesDir target {@code saves/} folder, typically a
     *                             sibling of {@link #getSavesDir()} belonging
     *                             to another Mindustry version
     * @return absolute path of the written copy
     */
    public Path crossVersionCopy(Path msav, Path otherVersionSavesDir) throws IOException {
        Objects.requireNonNull(msav, "msav");
        Objects.requireNonNull(otherVersionSavesDir, "otherVersionSavesDir");
        if (!Files.isRegularFile(msav)) {
            throw new IOException("Save file does not exist: " + msav);
        }

        Files.createDirectories(otherVersionSavesDir);

        String base = stripExtension(msav.getFileName().toString());
        Path srcParent = msav.getParent();
        String tag = (srcParent == null || srcParent.getFileName() == null)
                ? "unknown"
                : sanitizeForFilename(srcParent.getFileName().toString());

        Path target = otherVersionSavesDir.resolve(base + "-from-" + tag + MSAV_EXT);
        for (int attempt = 1; Files.exists(target); attempt++) {
            target = otherVersionSavesDir.resolve(base + "-from-" + tag + "-" + attempt + MSAV_EXT);
        }

        Files.copy(msav, target, StandardCopyOption.COPY_ATTRIBUTES);
        Logger.LOG.info("Cross-version copied " + msav + " -> " + target);
        return target;
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static String stripExtension(String fileName) {
        if (fileName.toLowerCase(Locale.ROOT).endsWith(MSAV_EXT)) {
            return fileName.substring(0, fileName.length() - MSAV_EXT.length());
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static void validateBaseName(String name) throws IOException {
        if (name.isBlank()) {
            throw new IOException("New save name must not be blank");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IOException("New save name must not contain path separators: " + name);
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(MSAV_EXT)) {
            throw new IOException("New save name must not include the .msav extension: " + name);
        }
    }

    private static String sanitizeForFilename(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Allow letters, digits, dot, underscore, dash; replace anything
            // else with '_' so we never produce a path-traversal or
            // FS-illegal token in the output filename.
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }
}
