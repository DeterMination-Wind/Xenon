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
package determination.xenon.mindustry.cloud;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/// Decides whether an instance should pull, push or ask about its cloud archive.
///
/// The decision uses the local baseline plus a fingerprint of the instance,
/// not timestamps alone: "only I changed" pushes, "only the cloud changed"
/// pulls, and "both changed" is always a conflict the user must resolve. A
/// missing baseline never silently picks a side.
@NotNullByDefault
public final class MindustryCloudSyncService {

    /// One cloud head.
    ///
    /// @param snapshotId snapshot id
    /// @param size       archive size, or 0 when unknown
    /// @param sha256     archive hash, or empty
    /// @param createdAt  snapshot creation time
    public record CloudState(String snapshotId, long size, String sha256, Instant createdAt) {
    }

    /// What the launcher should do.
    public enum Kind {
        /// Local and cloud match the baseline.
        UP_TO_DATE,
        /// Only the cloud changed; pull before launch.
        PULL,
        /// Only the local instance changed; push after exit.
        PUSH,
        /// Both sides changed; the user decides.
        CONFLICT,
        /// There is no usable baseline yet.
        NO_BASELINE
    }

    /// One decision.
    ///
    /// @param kind             action to take
    /// @param cloud            current cloud head
    /// @param baseline         last known baseline, or `null`
    /// @param localFingerprint current instance fingerprint
    public record Decision(Kind kind, CloudState cloud,
                           @Nullable MindustryCloudSyncBaseline baseline,
                           String localFingerprint) {
    }

    /// Computes the decision for one instance.
    ///
    /// @param dataDir  instance data directory
    /// @param cloud    current cloud head
    /// @param baseline last baseline, or `null`
    /// @return the decision
    /// @throws IOException when the instance cannot be hashed
    public static Decision decide(Path dataDir, CloudState cloud,
                                  @Nullable MindustryCloudSyncBaseline baseline)
            throws IOException {
        String local = fingerprint(dataDir);
        if (baseline == null) {
            return new Decision(Kind.NO_BASELINE, cloud, null, local);
        }
        boolean localChanged = !local.equals(baseline.localFingerprint());
        boolean cloudChanged = !cloud.snapshotId().isBlank()
                && !cloud.snapshotId().equals(baseline.snapshotId());
        Kind kind;
        if (localChanged && cloudChanged) {
            kind = Kind.CONFLICT;
        } else if (cloudChanged) {
            kind = Kind.PULL;
        } else if (localChanged) {
            kind = Kind.PUSH;
        } else {
            kind = Kind.UP_TO_DATE;
        }
        return new Decision(kind, cloud, baseline, local);
    }

    /// Hashes the restorable instance content.
    ///
    /// Backups, staging directories and the baseline itself are excluded so a
    /// local backup does not look like a user edit.
    ///
    /// @param dataDir instance data directory
    /// @return lowercase hex SHA-256
    /// @throws IOException when a file cannot be read
    public static String fingerprint(Path dataDir) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (String directory : List.of("saves", "maps", "schematics", "mods")) {
            Path root = dataDir.resolve(directory);
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> !isExcluded(dataDir, path))
                        .forEach(paths::add);
            }
        }
        Path settings = dataDir.resolve("settings.bin");
        if (Files.isRegularFile(settings)) {
            paths.add(settings);
        }
        paths.sort(Comparator.comparing(path -> dataDir.relativize(path).toString()));
        MessageDigest digest = digest();
        for (Path path : paths) {
            String relative = dataDir.relativize(path).toString().replace('\\', '/');
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(fileDigest(path));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Hashes the Mod names and versions, ignoring their bytes.
    ///
    /// @param dataDir instance data directory
    /// @return lowercase hex SHA-256
    public static String modsHash(Path dataDir) {
        Path mods = dataDir.resolve("mods");
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(mods)) {
            try (Stream<Path> stream = Files.list(mods)) {
                stream.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .forEach(names::add);
            } catch (IOException ignored) {
                // Treat an unreadable Mods directory as empty.
            }
        }
        MessageDigest digest = digest();
        for (String name : names) {
            digest.update(name.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Whether a path must be excluded from the fingerprint.
    private static boolean isExcluded(Path dataDir, Path path) {
        String relative = dataDir.relativize(path).toString().replace('\\', '/');
        return relative.startsWith("saves/backups/")
                || relative.startsWith(".xenon-cloud-staging-")
                || relative.equals(MindustryCloudSyncBaseline.FILE_NAME);
    }

    /// Hashes one file.
    private static byte[] fileDigest(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    /// Creates a SHA-256 digest.
    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private MindustryCloudSyncService() {
    }
}
