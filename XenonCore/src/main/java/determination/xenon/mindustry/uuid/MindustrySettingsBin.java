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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

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
        try {
            Files.createDirectories(dataDir);
            Path file = dataDir.resolve(FILE_NAME);

            LinkedHashMap<String, Object> entries = readIfPresent(file);
            if (uid != null && !uid.isBlank()) entries.put("uuid", uid);
            if (nickname != null && !nickname.isBlank()) entries.put("name", nickname);
            // Forced player nickname flag — set by Mindustry when the user
            // edits their name in-game, but harmless to preserve / true.
            entries.putIfAbsent("name-forced", Boolean.TRUE);

            writeAtomically(file, entries);

            // Mindustry checks the backup if the primary is corrupt; keep
            // it in sync so a crash mid-write doesn't hand the user a
            // stale name.
            Path backup = dataDir.resolve(BACKUP_NAME);
            try {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Best-effort; primary file is the source of truth.
            }
            Logger.LOG.info("Mindustry settings.bin updated: name=\"" + nickname + "\" uid=" + uid);
        } catch (IOException ex) {
            Logger.LOG.warning("Failed to write Mindustry settings.bin in " + dataDir
                    + ": " + ex.getMessage());
        }
    }

    private static LinkedHashMap<String, Object> readIfPresent(Path file) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return out;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            int count = in.readInt();
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
                        // Unknown type — bail out and start from a clean
                        // slate rather than corrupt the file.
                        Logger.LOG.warning("Unknown setting type " + type
                                + " at key '" + key + "' — starting settings.bin fresh");
                        return new LinkedHashMap<>();
                }
                out.put(key, value);
            }
        } catch (IOException ex) {
            Logger.LOG.warning("Failed to parse settings.bin (" + ex.getMessage()
                    + ") — starting fresh");
            return new LinkedHashMap<>();
        }
        return out;
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
