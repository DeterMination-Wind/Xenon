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

import determination.xenon.util.logging.Logger;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * Header / meta-region parser for Mindustry {@code .msav} save files.
 *
 * <p>This is a minimal port of the upstream {@code mindustry.io.SaveIO} +
 * {@code SaveVersion#getMeta} pipeline, intentionally trimmed down so it does
 * <em>not</em> depend on the {@code arc} runtime, the Mindustry assembly or
 * any third-party JSON library: only {@link DataInputStream} and
 * {@link InflaterInputStream} from the JDK are used.</p>
 *
 * <p>On-disk layout (Mindustry SAVE v7, mirroring
 * {@code SaveIO.write} / {@code SaveVersion.writeMeta}):</p>
 * <pre>
 *   [zlib (raw DEFLATE wrapped) stream]
 *     int      magic   = 'M' 'S' 'A' 'V'   (== 0x4D534156)
 *     int      version
 *     int      metaRegionLength            (for chunk framing; ignored here)
 *     short    tagCount
 *     repeated tagCount times:
 *       UTF    key    (DataInput.writeUTF: u16 length + modified UTF-8 bytes)
 *       UTF    value
 * </pre>
 *
 * <p>Note: the task brief lists the magic as {@code 0x53415645} ("SAVE"); the
 * actual byte order in real Mindustry .msav files (and in
 * {@code mindustry.io.SaveIO#header}) is {@code 'M','S','A','V'}, which reads
 * as the big-endian int {@code 0x4D534156}. We use the correct on-disk
 * value here.</p>
 *
 * <p>The meta-region layout has been stable across Save1..Save11 in upstream
 * Mindustry. Callers that need a strict allow-list can use
 * {@link #readHeader(Path)}; callers that only need a light-weight
 * "is this still a parseable .msav container?" check can use
 * {@link #readHeaderLenient(Path)} to avoid rejecting newer upstream save
 * versions solely because the version integer increased.</p>
 */
public final class SaveFileReader {

    /** Big-endian int form of the 4-byte file magic {@code "MSAV"}. */
    public static final int MAGIC = 0x4D534156;

    /**
     * SAVE format versions this reader is willing to parse.
     *
     * <p>Mindustry currently ships save readers/writers for {@code Save1}
     * through {@code Save11}, and the header/meta-region layout this parser
     * consumes remains compatible across that range. Versions outside this
     * set still raise {@link MsavException.UnsupportedVersion}.</p>
     */
    public static final Set<Integer> SUPPORTED_VERSIONS =
            Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);

    private static final int BUFFER_SIZE = 16 * 1024;

    private SaveFileReader() {
        // utility class
    }

    /**
     * Parse the magic, version and meta string-map of {@code msav} and return
     * a {@link SaveSummary}. The rest of the file (content header, world tiles,
     * entities, custom chunks) is intentionally <em>not</em> consumed.
     *
     * @param msav path to a Mindustry save file
     * @return non-null summary of the parsed header
     * @throws MsavException.InvalidMagic       if the leading 4 bytes are not {@code MSAV}
     * @throws MsavException.UnsupportedVersion if the version int is not in {@link #SUPPORTED_VERSIONS}
     * @throws MsavException.Truncated          if the file ends mid-record
     * @throws MsavException                    for inflate / framing errors
     * @throws IOException                      for plain filesystem errors (file missing, unreadable etc.)
     */
    public static SaveSummary readHeader(Path msav) throws IOException {
        return readHeader(msav, true);
    }

    /**
     * Parse the save header without enforcing {@link #SUPPORTED_VERSIONS}.
     *
     * <p>This is intended for validation / import flows where a future
     * upstream save version should still be accepted as long as the header and
     * meta-region remain parseable. If the on-disk framing changes, parsing
     * still fails naturally via the existing magic / inflate / truncation
     * checks.</p>
     */
    public static SaveSummary readHeaderLenient(Path msav) throws IOException {
        return readHeader(msav, false);
    }

    private static SaveSummary readHeader(Path msav, boolean enforceSupportedVersions) throws IOException {
        Objects.requireNonNull(msav, "msav");

        long fileSize;
        Instant lastModified;
        try {
            fileSize = Files.size(msav);
            lastModified = Files.getLastModifiedTime(msav).toInstant();
        } catch (IOException e) {
            throw new IOException("Cannot stat .msav file: " + msav, e);
        }

        try (InputStream raw = Files.newInputStream(msav);
             InputStream buffered = new BufferedInputStream(raw, BUFFER_SIZE);
             InflaterInputStream inflated = new InflaterInputStream(buffered);
             DataInputStream in = new DataInputStream(inflated)) {

            return doRead(in, msav, fileSize, lastModified, enforceSupportedVersions);

        } catch (ZipException e) {
            throw new MsavException(
                    "Failed to inflate .msav stream (not a valid zlib payload?): " + msav, e);
        }
    }

    private static SaveSummary doRead(DataInputStream in, Path file, long fileSize, Instant lastModified,
                                      boolean enforceSupportedVersions)
            throws IOException {

        int magic = readIntOrTruncated(in, "magic header", file);
        if (magic != MAGIC) {
            throw new MsavException.InvalidMagic(String.format(
                    "Invalid magic for %s: expected 0x%08X (\"MSAV\") but got 0x%08X",
                    file, MAGIC, magic));
        }

        int version = readIntOrTruncated(in, "version int", file);
        if (enforceSupportedVersions && !SUPPORTED_VERSIONS.contains(version)) {
            throw new MsavException.UnsupportedVersion(
                    version,
                    "Unsupported SAVE version " + version + " for " + file
                            + " (supported: " + SUPPORTED_VERSIONS + ")");
        }

        // Meta region begins with a chunk-length int (used by SaveFileReader.readChunk
        // for framing). We don't validate against it - we just skip past it.
        readIntOrTruncated(in, "meta region length", file);

        Map<String, String> tags = readStringMap(in, file);

        String mapName = tags.getOrDefault("mapname", "");
        long playtime = parseLong(tags.get("playtime"), 0L);
        int build = parseInt(tags.get("build"), 0);
        int wave = parseInt(tags.get("wave"), 0);
        long savedAt = parseLong(tags.get("saved"), 0L);
        String rules = tags.get("rules");
        String mode = extractRuleField(rules, "mode");
        String difficulty = extractRuleField(rules, "difficulty");

        Logger.LOG.debug("Parsed .msav " + file.getFileName()
                + " version=" + version
                + " map=" + mapName
                + " build=" + build
                + " wave=" + wave
                + " tags=" + tags.size());

        return new SaveSummary(
                file,
                fileSize,
                lastModified,
                version,
                mapName,
                /* playerName */ null,
                playtime,
                build,
                wave,
                difficulty,
                mode,
                savedAt,
                tags);
    }

    private static int readIntOrTruncated(DataInputStream in, String fieldName, Path file) throws IOException {
        try {
            return in.readInt();
        } catch (EOFException e) {
            throw new MsavException.Truncated("EOF while reading " + fieldName + " of " + file, e);
        }
    }

    private static Map<String, String> readStringMap(DataInputStream in, Path file) throws IOException {
        short count;
        try {
            count = in.readShort();
        } catch (EOFException e) {
            throw new MsavException.Truncated("EOF while reading meta string-map size of " + file, e);
        }
        if (count < 0) {
            throw new MsavException.Truncated(
                    "Invalid meta string-map size " + count + " in " + file
                            + " (file appears corrupt)");
        }

        Map<String, String> map = new LinkedHashMap<>(Math.max(8, count * 2));
        for (int i = 0; i < count; i++) {
            String key;
            String value;
            try {
                key = in.readUTF();
                value = in.readUTF();
            } catch (EOFException e) {
                throw new MsavException.Truncated(
                        "EOF inside meta entry " + i + " of " + count + " in " + file, e);
            }
            map.put(key, value);
        }
        return map;
    }

    /**
     * Best-effort extraction of a top-level scalar field out of the JSON / hjson
     * blob Mindustry stores under the {@code rules} tag. Used for {@code mode}
     * and {@code difficulty} which are not direct meta keys.
     *
     * <p>Handles both the strict-JSON form ({@code "field":"value"} or
     * {@code "field":value}) and the hjson form Mindustry frequently emits
     * ({@code field:value}). Returns {@code null} if {@code source} is
     * {@code null} or no match is found, rather than throwing — these fields
     * are advisory metadata, not required for correctness.</p>
     */
    static String extractRuleField(String source, String field) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        // (?:^|[\s{,]) anchors the field name to a value boundary so we don't
        // accidentally match it inside another key (e.g. "mode" inside "modeName").
        Pattern p = Pattern.compile(
                "(?:^|[\\s{,])\"?" + Pattern.quote(field) + "\"?\\s*[:=]\\s*\"?([^\",}\\s]+)\"?");
        Matcher m = p.matcher(source);
        return m.find() ? m.group(1) : null;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String s, long fallback) {
        if (s == null || s.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
