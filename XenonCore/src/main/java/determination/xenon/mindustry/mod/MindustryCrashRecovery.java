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
package determination.xenon.mindustry.mod;

import determination.xenon.mindustry.uuid.MindustrySettingsBin;
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/// Takes over Mindustry's failed-launch protocol while the game is cold.
///
/// <p>Mindustry writes a marker file {@code launchid.dat} at startup and
/// deletes it once loading completes. When a run dies before that, the next
/// start sets every {@code mod-<name>-enabled} flag to {@code false} on its
/// own — silently discarding any launcher-side cold toggles. Xenon replaces
/// that flow: on a pending marker it snapshots the launcher-visible state,
/// deliberately disables every mod through the same settings keys the game
/// reads, and deletes the marker, so the user can re-enable mods from the
/// launcher or restore the snapshot with one click.</p>
@NotNullByDefault
public final class MindustryCrashRecovery {

    /// Marker Mindustry writes at startup and deletes after a successful load.
    public static final String LAUNCH_MARKER_NAME = "launchid.dat";

    /// Snapshot folder under the data directory.
    private static final String SNAPSHOT_DIR_NAME = ".xenon/mod-state-snapshots";

    /// Settings key prefix and suffix for a mod's enabled flag.
    private static final String KEY_PREFIX = "mod-";
    private static final String KEY_SUFFIX = "-enabled";

    /// Outcome of one snapshot restore.
    ///
    /// @param enabled mods re-enabled by the restore
    /// @param disabled mods re-disabled by the restore
    /// @param failures per-file failure reasons; when non-empty the snapshot is kept
    public record RestoreResult(int enabled, int disabled, Map<String, String> failures) {
    }

    /// Prevents instantiation of this utility class.
    private MindustryCrashRecovery() {}

    /// Whether the previous launch died before Mindustry removed its marker.
    ///
    /// @param effectiveDataDir data directory the next launch will use
    /// @return whether a failed-launch marker is present
    public static boolean pending(Path effectiveDataDir) {
        return Files.isRegularFile(effectiveDataDir.resolve(LAUNCH_MARKER_NAME));
    }

    /// Runs the launcher-side safe mode for a pending failed launch.
    ///
    /// <p>Order matters: the snapshot and the all-disabled flags are written
    /// before the marker is deleted, so a crash in between leaves the game's
    /// own (idempotent) recovery as the fallback instead of an unsafe state.</p>
    ///
    /// @param effectiveDataDir data directory the next launch will use
    /// @return path of the snapshot that can restore the previous configuration
    /// @throws IOException when the snapshot or the settings write fails; the
    ///     marker is then left in place
    public static Path takeOver(Path effectiveDataDir) throws IOException {
        Path modsDir = effectiveDataDir.resolve("mods");
        LinkedHashMap<String, Boolean> states = captureDesiredStates(effectiveDataDir, modsDir);
        Path snapshot = writeSnapshot(effectiveDataDir, states);

        LinkedHashMap<String, Object> disableAll = new LinkedHashMap<>();
        for (String key : states.keySet()) {
            disableAll.put(key, Boolean.FALSE);
        }
        if (!disableAll.isEmpty()) {
            MindustrySettingsBin.putValuesOrThrow(effectiveDataDir, disableAll);
        }
        Files.deleteIfExists(effectiveDataDir.resolve(LAUNCH_MARKER_NAME));
        Logger.LOG.info("Crash-recovery safe mode: disabled " + disableAll.size()
                + " mod(s); snapshot at " + snapshot);
        return snapshot;
    }

    /// Returns the newest snapshot for one data directory, if any exists.
    ///
    /// @param dataDir Mindustry data directory
    /// @return the newest snapshot file, or `null` when none exists
    public static @Nullable Path latestSnapshot(Path dataDir) {
        Path dir = dataDir.resolve(SNAPSHOT_DIR_NAME);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        @Nullable Path newest = null;
        long newestMillis = Long.MIN_VALUE;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.properties")) {
            for (Path candidate : stream) {
                long modified = lastModifiedMillis(candidate);
                if (modified > newestMillis) {
                    newestMillis = modified;
                    newest = candidate;
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return newest;
    }

    /// Reads the capture time of one snapshot.
    ///
    /// @param snapshot snapshot returned by [#latestSnapshot(Path)]
    /// @return last-modified instant, or {@link Instant#EPOCH} when unreadable
    public static Instant snapshotTime(Path snapshot) {
        try {
            return Files.getLastModifiedTime(snapshot).toInstant();
        } catch (IOException ignored) {
            return Instant.EPOCH;
        }
    }

    /// Removes one snapshot, used to discard a restore point.
    ///
    /// @param snapshot snapshot file to delete
    /// @throws IOException when the file exists but cannot be deleted
    public static void discard(Path snapshot) throws IOException {
        Files.deleteIfExists(snapshot);
    }

    /// Restores a snapshot onto the mods folder and settings flags.
    ///
    /// <p>Enabled entries have their archive suffix restored and their flag
    /// set true; disabled entries the same in reverse. Keys without a matching
    /// archive (folder mods, deleted files) are restored as plain settings
    /// values. The snapshot file is removed on full success.</p>
    ///
    /// @param dataDir Mindustry data directory
    /// @param snapshot snapshot to restore
    /// @return applied counts and per-file failures
    /// @throws IOException when the snapshot cannot be read
    public static RestoreResult restore(Path dataDir, Path snapshot) throws IOException {
        LinkedHashMap<String, Boolean> states = readSnapshot(snapshot);
        Path modsDir = dataDir.resolve("mods");
        MindustryModManager manager = new MindustryModManager(modsDir, dataDir);

        Map<String, MindustryLocalMod> byName = new HashMap<>();
        for (MindustryLocalMod mod : manager.scanAll()) {
            if (!mod.getInternalName().isBlank()) {
                byName.putIfAbsent(mod.getInternalName(), mod);
            }
        }

        List<MindustryLocalMod> toEnable = new ArrayList<>();
        List<MindustryLocalMod> toDisable = new ArrayList<>();
        LinkedHashMap<String, Object> orphanKeys = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : states.entrySet()) {
            @Nullable String name = nameOfKey(entry.getKey());
            if (name == null) {
                continue;
            }
            @Nullable MindustryLocalMod mod = byName.remove(name);
            if (mod == null) {
                orphanKeys.put(entry.getKey(), entry.getValue());
            } else if (entry.getValue()) {
                toEnable.add(mod);
            } else {
                toDisable.add(mod);
            }
        }

        Map<String, String> failures = new LinkedHashMap<>();
        MindustryModManager.BatchResult enabled = manager.setEnabled(toEnable, true);
        MindustryModManager.BatchResult disabled = manager.setEnabled(toDisable, false);
        failures.putAll(enabled.failures());
        failures.putAll(disabled.failures());
        if (!orphanKeys.isEmpty()) {
            MindustrySettingsBin.putValuesOrThrow(dataDir, orphanKeys);
        }

        if (failures.isEmpty()) {
            Files.deleteIfExists(snapshot);
        }
        Logger.LOG.info("Crash-recovery restore: " + enabled.applied().size() + " enabled, "
                + disabled.applied().size() + " disabled, " + failures.size() + " failed");
        return new RestoreResult(enabled.applied().size(), disabled.applied().size(), failures);
    }

    /// Captures the launcher-visible state of every mod the game could load.
    ///
    /// <p>Scanned archives contribute their displayed state (suffix AND
    /// settings key); settings keys without a matching archive - folder mods,
    /// deleted files, stale entries - are carried over with their raw value.</p>
    ///
    /// @param dataDir Mindustry data directory
    /// @param modsDir mods folder inside it
    /// @return settings key to desired enabled state
    static LinkedHashMap<String, Boolean> captureDesiredStates(Path dataDir, Path modsDir) {
        LinkedHashMap<String, Boolean> states = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : MindustrySettingsBin.readValues(dataDir).entrySet()) {
            if (isModEnabledKey(entry.getKey())) {
                Object value = entry.getValue();
                states.put(entry.getKey(), value instanceof Boolean bool ? bool : Boolean.TRUE);
            }
        }
        MindustryModManager manager = new MindustryModManager(modsDir, dataDir);
        for (MindustryLocalMod mod : manager.scanAll()) {
            String name = mod.getInternalName();
            if (name.isBlank()) {
                continue;
            }
            // The displayed state overrides a raw key so a suffix-disabled
            // archive is recorded as disabled even while its key says true.
            states.put(KEY_PREFIX + name + KEY_SUFFIX, mod.isEnabled());
        }
        return states;
    }

    /// Serializes one capture under the data directory's snapshot folder.
    private static Path writeSnapshot(Path dataDir, LinkedHashMap<String, Boolean> states)
            throws IOException {
        Path dir = dataDir.resolve(SNAPSHOT_DIR_NAME);
        Files.createDirectories(dir);
        Path snapshot = dir.resolve(System.currentTimeMillis() + ".properties");
        Properties properties = new Properties();
        for (Map.Entry<String, Boolean> entry : states.entrySet()) {
            properties.setProperty(entry.getKey(), entry.getValue().toString());
        }
        try (Writer writer = Files.newBufferedWriter(snapshot, StandardCharsets.UTF_8)) {
            properties.store(writer, "Xenon crash-recovery mod state snapshot");
        }
        return snapshot;
    }

    /// Loads one snapshot file.
    private static LinkedHashMap<String, Boolean> readSnapshot(Path snapshot) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(snapshot, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        LinkedHashMap<String, Boolean> states = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (isModEnabledKey(key)) {
                states.put(key, Boolean.parseBoolean(properties.getProperty(key)));
            }
        }
        return states;
    }

    /// Whether a settings key is one of the game's per-mod enabled flags.
    private static boolean isModEnabledKey(String key) {
        return key.startsWith(KEY_PREFIX) && key.endsWith(KEY_SUFFIX)
                && key.length() > KEY_PREFIX.length() + KEY_SUFFIX.length();
    }

    /// Extracts the internal mod name from an enabled-flag key.
    private static @Nullable String nameOfKey(String key) {
        if (!isModEnabledKey(key)) {
            return null;
        }
        return key.substring(KEY_PREFIX.length(), key.length() - KEY_SUFFIX.length());
    }

    /// Returns a file's last-modified time, or `Long.MIN_VALUE` when unreadable.
    private static long lastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }
}
