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
package determination.xenon.mindustry.uuid;

import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.InflaterInputStream;

/**
 * Read-modify-write helper for Mindustry's {@code settings.bin}.
 *
 * <p>Mindustry persists per-player state (nickname, UID, every config
 * toggle) inside this single binary file under {@code <dataDir>/}. The
 * companion {@code uidManager} mod that
 * {@link MindustryPlayerLaunchHook}'s JVM-property approach relies on
 * doesn't ship in vanilla, so the two {@code -D} args are simply ignored
 * — which is exactly the symptom users hit (主菜单"名称："是空的). The
 * only mechanism that works on every variant is to write the values
 * directly into {@code settings.bin} before the game starts.</p>
 *
 * <h2>Binary format (Arc {@code Settings.saveValues})</h2>
 * <pre>
 *   int                              # entry count
 *   for each entry:
 *     writeUTF(key)                  # 2-byte length + UTF-8 bytes
 *     byte type
 *     value:
 *       0 boolean → 1 byte
 *       1 int     → 4 bytes
 *       2 long    → 8 bytes
 *       3 float   → 4 bytes
 *       4 String  → writeUTF
 *       5 byte[]  → int length + bytes
 * </pre>
 */
public final class MindustrySettingsBin {

    /** Filename Mindustry reads on startup. */
    public static final String FILE_NAME = "settings.bin";
    /** Filename Mindustry falls back to if the primary is corrupt. */
    public static final String BACKUP_NAME = "settings_backup.bin";
    /** Folder holding Mindustry's periodic timestamped settings snapshots. */
    public static final String BACKUP_FOLDER_NAME = "settings_backups";

    /** Whether {@code settings.bin} exists on disk. */
    public enum ReadStatus {
        /** No {@code settings.bin} in the data directory yet. */
        MISSING,
        /** The file exists and every entry was parsed. */
        OK
    }

    /**
     * Outcome of one {@link #read(Path)} call.
     *
     * @param status whether the file was missing or parsed successfully
     * @param values every entry in file order; empty when {@code status} is {@link ReadStatus#MISSING}
     */
    public record ReadResult(ReadStatus status, LinkedHashMap<String, Object> values) {
    }

    private static final byte TYPE_BOOL = 0;
    private static final byte TYPE_INT = 1;
    private static final byte TYPE_LONG = 2;
    private static final byte TYPE_FLOAT = 3;
    private static final byte TYPE_STRING = 4;
    private static final byte TYPE_BYTES = 5;

    private MindustrySettingsBin() {}

    /**
     * Set {@code name} and {@code uuid} in {@code <dataDir>/settings.bin},
     * preserving every other entry. Creates a brand-new two-entry file
     * if {@code settings.bin} doesn't exist yet (first launch).
     *
     * <p>Best-effort: any IO / parse failure is logged and swallowed — a
     * stale name in-game is preferable to refusing to launch.</p>
     */
    public static void setPlayerProfile(Path dataDir, String uid, String nickname) {
        if (dataDir == null) return;
        if ((uid == null || uid.isBlank()) && (nickname == null || nickname.isBlank())) return;
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        if (uid != null && !uid.isBlank()) values.put("uuid", uid);
        if (nickname != null && !nickname.isBlank()) values.put("name", nickname);
        // Forced player nickname flag — set by Mindustry when the user
        // edits their name in-game, but harmless to preserve / true.
        values.putIfAbsent("name-forced", Boolean.TRUE);
        putValues(dataDir, values);
    }

    /**
     * Merge launcher-provided values into {@code <dataDir>/settings.bin}.
     *
     * <p>Existing entries are preserved unless their keys are present in
     * {@code values}. The backup file is updated after the primary write so
     * Mindustry's own recovery path observes the same values.</p>
     */
    public static void putValues(Path dataDir, Map<String, Object> values) {
        if (dataDir == null || values == null || values.isEmpty()) return;
        try {
            putValuesOrThrow(dataDir, values);
        } catch (IOException ex) {
            Logger.LOG.warning("Failed to write Mindustry settings.bin in " + dataDir
                    + ": " + ex.getMessage());
        }
    }

    /**
     * Merge launcher-provided values into {@code <dataDir>/settings.bin}.
     *
     * <p>Unlike {@link #putValues(Path, Map)}, this method reports IO
     * failures to the caller. Use it for launch-time state that must be
     * persisted before the game starts.</p>
     *
     * <p>A corrupt primary is recovered from Mindustry's backup chain first,
     * mirroring what the game does on its next startup. When no backup parses
     * either, the write aborts so the corrupt file is never replaced by a
     * file containing only the launcher values.</p>
     */
    public static void putValuesOrThrow(Path dataDir, Map<String, Object> values) throws IOException {
        if (dataDir == null || values == null || values.isEmpty()) return;
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(FILE_NAME);

        // Strict read with backup recovery: a corrupt file must abort the
        // write instead of being silently replaced by a file containing only
        // the launcher values.
        LinkedHashMap<String, Object> entries = readForUpdate(dataDir);
        entries.putAll(values);

        writeAtomically(file, entries);

        // Mindustry checks the backup if the primary is corrupt; keep
        // it in sync so a crash mid-write doesn't hand the user a
        // stale value.
        syncBackup(dataDir, file);
        Logger.LOG.info("Mindustry settings.bin updated with " + values.size() + " launcher value(s)");
    }

    /**
     * Reads the entries a merge write should start from, with the same
     * recovery chain Mindustry uses.
     *
     * <p>Reading order: the primary file, then {@code settings_backup.bin},
     * then the newest parseable snapshot in {@code settings_backups/}. A
     * missing primary whose backup parses is recovered (the game behaves the
     * same way, because a failed primary load falls through to the backups).
     * Only a corrupt primary with no usable backup throws.</p>
     *
     * @param dataDir Mindustry data directory that owns {@code settings.bin}
     * @return the recovered base entry set, never {@code null}
     * @throws IOException when the primary exists but neither it nor any
     *     backup can be parsed
     */
    public static LinkedHashMap<String, Object> readForUpdate(Path dataDir) throws IOException {
        if (dataDir == null) return new LinkedHashMap<>();
        Path file = dataDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            @Nullable LinkedHashMap<String, Object> recovered = tryReadBackups(dataDir);
            if (recovered != null) {
                Logger.LOG.warning("settings.bin is missing; recovered " + recovered.size()
                        + " value(s) from a backup in " + dataDir);
                return recovered;
            }
            return new LinkedHashMap<>();
        }
        try {
            return readFileOrThrow(file);
        } catch (IOException primaryFailure) {
            @Nullable LinkedHashMap<String, Object> recovered = tryReadBackups(dataDir);
            if (recovered == null) {
                throw primaryFailure;
            }
            Logger.LOG.warning("settings.bin is corrupt (" + primaryFailure.getMessage()
                    + "); recovered " + recovered.size() + " value(s) from a backup in " + dataDir);
            return recovered;
        }
    }

    /**
     * Replaces every entry in {@code settings.bin}, then mirrors the result
     * into {@code settings_backup.bin}.
     *
     * <p>Unlike {@link #putValuesOrThrow(Path, Map)}, entries absent from
     * {@code values} are removed. Use this for restores that must reproduce a
     * previously captured file exactly.</p>
     *
     * @param dataDir Mindustry data directory that owns {@code settings.bin}
     * @param values complete entry set to persist
     * @throws IOException when the directory is not writable or a value uses an unsupported type
     */
    public static void replaceWith(Path dataDir, Map<String, Object> values) throws IOException {
        if (dataDir == null || values == null) return;
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve(FILE_NAME);
        writeAtomically(file, values);
        syncBackup(dataDir, file);
    }

    /**
     * Read every entry from {@code <dataDir>/settings.bin}. Plain Arc
     * settings and Arc's optional zlib-compressed settings are both
     * supported.
     *
     * <p>Read-only callers (mod scanning, health checks) keep working on a
     * corrupt file: the backup chain is consulted first, and only when
     * nothing parses does the result fall back to an empty map.</p>
     */
    public static LinkedHashMap<String, Object> readValues(Path dataDir) {
        if (dataDir == null) return new LinkedHashMap<>();
        try {
            return readForUpdate(dataDir);
        } catch (IOException ex) {
            // Read-only callers keep working on an unrecoverable file;
            // writers must use putValuesOrThrow so they can refuse to clobber it.
            Logger.LOG.warning("Failed to parse settings.bin (" + ex.getMessage()
                    + ") — treating it as empty for read-only callers");
            return new LinkedHashMap<>();
        }
    }

    /**
     * Reads {@code settings.bin} while distinguishing a missing file from a
     * parse failure.
     *
     * <p>A file that exists but cannot be parsed throws instead of returning
     * an empty map, so callers never overwrite user settings with the result
     * of an incomplete parse.</p>
     *
     * @param dataDir Mindustry data directory that owns {@code settings.bin}
     * @return the read status plus every entry in file order
     * @throws IOException when the file exists but its contents cannot be parsed
     */
    public static ReadResult read(Path dataDir) throws IOException {
        if (dataDir == null) {
            return new ReadResult(ReadStatus.MISSING, new LinkedHashMap<>());
        }
        Path file = dataDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return new ReadResult(ReadStatus.MISSING, new LinkedHashMap<>());
        }
        return new ReadResult(ReadStatus.OK, readFileOrThrow(file));
    }

    /**
     * Read a boolean entry from {@code <dataDir>/settings.bin}.
     * Returns {@code defaultValue} when the key is missing, has another
     * type, or the file cannot be parsed.
     */
    public static boolean getBool(Path dataDir, String key, boolean defaultValue) {
        return getBool(readValues(dataDir), key, defaultValue);
    }

    /**
     * Read a boolean entry from a previously loaded settings map.
     * Returns {@code defaultValue} when the key is missing or has another
     * type.
     */
    public static boolean getBool(Map<String, Object> values, String key, boolean defaultValue) {
        if (values == null || key == null) return defaultValue;
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    /**
     * Strictly parses one settings file.
     *
     * <p>Package-private so the backup helpers can validate a snapshot before
     * it is restored over the live file. A missing file yields an empty map;
     * any malformed content throws.</p>
     */
    static LinkedHashMap<String, Object> readFileOrThrow(Path file) throws IOException {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        try (DataInputStream in = new DataInputStream(openSettingsInput(file))) {
            int count = in.readInt();
            if (count <= 0) {
                throw new IOException("0 values are not allowed");
            }
            for (int i = 0; i < count; i++) {
                String key = in.readUTF();
                byte type = in.readByte();
                Object value;
                switch (type) {
                    case TYPE_BOOL:
                        value = in.readBoolean();
                        break;
                    case TYPE_INT:
                        value = in.readInt();
                        break;
                    case TYPE_LONG:
                        value = in.readLong();
                        break;
                    case TYPE_FLOAT:
                        value = in.readFloat();
                        break;
                    case TYPE_STRING:
                        value = in.readUTF();
                        break;
                    case TYPE_BYTES: {
                        int len = in.readInt();
                        byte[] b = new byte[len];
                        in.readFully(b);
                        value = b;
                        break;
                    }
                    default:
                        // Unknown type — abort so the caller never rewrites
                        // the file from a partial parse.
                        throw new IOException("Unknown setting type " + type
                                + " at key '" + key + "'");
                }
                out.put(key, value);
            }
            int trailing = in.read();
            if (trailing != -1) {
                throw new IOException("Trailing settings data; expected EOF, got " + trailing);
            }
        }
        return out;
    }

    /** Mirrors the freshly written primary file into Mindustry's recovery backup. */
    private static void syncBackup(Path dataDir, Path file) {
        Path backup = dataDir.resolve(BACKUP_NAME);
        try {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Best-effort; primary file is the source of truth.
        }
    }

    /**
     * Tries {@code settings_backup.bin} and the {@code settings_backups/}
     * snapshots, newest first, exactly like Arc's {@code Settings.loadValues}.
     *
     * @param dataDir Mindustry data directory that owns the backups
     * @return the first parseable backup entry set, or {@code null} when none parses
     */
    private static @Nullable LinkedHashMap<String, Object> tryReadBackups(Path dataDir) {
        List<Path> candidates = new ArrayList<>();
        Path backup = dataDir.resolve(BACKUP_NAME);
        if (Files.isRegularFile(backup)) {
            candidates.add(backup);
        }
        Path folder = dataDir.resolve(BACKUP_FOLDER_NAME);
        if (Files.isDirectory(folder)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
                for (Path candidate : stream) {
                    if (Files.isRegularFile(candidate)) {
                        candidates.add(candidate);
                    }
                }
            } catch (IOException ignored) {
                // Unreadable backup folders are skipped; the plain backup file still counts.
            }
        }
        // Arc sorts by latest-modified first before attempting recovery.
        candidates.sort(Comparator.comparingLong(MindustrySettingsBin::lastModifiedMillis).reversed());
        for (Path candidate : candidates) {
            try {
                return readFileOrThrow(candidate);
            } catch (IOException ignored) {
                // Try the next, older snapshot.
            }
        }
        return null;
    }

    /// Returns a file's last-modified time, or `0` when it cannot be read.
    private static long lastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static InputStream openSettingsInput(Path file) throws IOException {
        BufferedInputStream raw = new BufferedInputStream(Files.newInputStream(file));
        raw.mark(2);
        int first = raw.read();
        int second = raw.read();
        raw.reset();
        if (isZlibHeader(first, second)) {
            return new InflaterInputStream(raw);
        }
        return raw;
    }

    private static boolean isZlibHeader(int first, int second) {
        return first == 0x78
                && (second == 0x01 || second == 0x5e || second == 0x9c || second == 0xda);
    }

    private static void writeAtomically(Path file, Map<String, Object> entries) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".xenon-tmp");
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(tmp,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)))) {
            out.writeInt(entries.size());
            for (Map.Entry<String, Object> e : entries.entrySet()) {
                out.writeUTF(e.getKey());
                Object v = e.getValue();
                if (v instanceof Boolean) {
                    out.writeByte(TYPE_BOOL); out.writeBoolean((Boolean) v);
                } else if (v instanceof Integer) {
                    out.writeByte(TYPE_INT); out.writeInt((Integer) v);
                } else if (v instanceof Long) {
                    out.writeByte(TYPE_LONG); out.writeLong((Long) v);
                } else if (v instanceof Float) {
                    out.writeByte(TYPE_FLOAT); out.writeFloat((Float) v);
                } else if (v instanceof String) {
                    out.writeByte(TYPE_STRING); out.writeUTF((String) v);
                } else if (v instanceof byte[]) {
                    byte[] b = (byte[]) v;
                    out.writeByte(TYPE_BYTES);
                    out.writeInt(b.length);
                    out.write(b);
                } else {
                    throw new IOException("Unsupported settings value type "
                            + v.getClass().getName() + " for key " + e.getKey());
                }
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            // Some filesystems reject ATOMIC_MOVE — fall back to a plain
            // replace. Both writes will hit disk; worst case is a torn
            // primary that Mindustry recovers from settings_backup.bin.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
