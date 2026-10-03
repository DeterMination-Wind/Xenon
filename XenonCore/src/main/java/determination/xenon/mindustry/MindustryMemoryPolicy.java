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
package determination.xenon.mindustry;

import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/// Computes the JVM heap for an instance from the machine's memory and the
/// installed mod set.
///
/// Mindustry content mods regularly ship tens of megabytes of textures and
/// classes; a fixed 1 GiB heap that works for vanilla is not enough for a
/// modded instance. The policy starts from a quarter of the physical memory
/// and adds a mod-size allowance, capped by what the machine can actually
/// spare.
@NotNullByDefault
public final class MindustryMemoryPolicy {

    /// Smallest heap the policy recommends.
    public static final long MINIMUM_HEAP_MB = 1024L;

    /// Largest allowance added for enabled mods.
    public static final long MAX_MODS_ALLOWANCE_MB = 2048L;

    /// Heap reserved for the rest of the system when capping the suggestion.
    private static final long SYSTEM_RESERVE_MB = 1024L;

    private MindustryMemoryPolicy() {
    }

    /// Sums the size of the enabled mod archives in `dataDir/mods`.
    ///
    /// Files carrying the `.disabled` suffix are ignored because Mindustry
    /// never loads them. Missing directories and unreadable entries count
    /// as zero.
    ///
    /// @param dataDir the instance's effective Mindustry data directory
    /// @return total size in bytes, never negative
    public static long measureEnabledModsBytes(@Nullable Path dataDir) {
        if (dataDir == null) {
            return 0L;
        }
        Path modsDir = dataDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            return 0L;
        }
        long total = 0L;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path file : stream) {
                if (isEnabledModArchive(file.getFileName().toString())) {
                    try {
                        total += Math.max(0L, Files.size(file));
                    } catch (IOException e) {
                        Logger.LOG.warning("Cannot size the mod file " + file + ": " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            Logger.LOG.warning("Cannot scan the mods directory " + modsDir + ": " + e.getMessage());
        }
        return total;
    }

    /// Suggests a heap size in MiB.
    ///
    /// @param totalMemoryMb     total physical memory, or `0` when unknown
    /// @param availableMemoryMb currently available memory, or `0` when unknown
    /// @param enabledModsBytes  total size of the enabled mods, from
    ///                          {@link #measureEnabledModsBytes(Path)}
    /// @return heap size in MiB, never below {@link #MINIMUM_HEAP_MB}
    public static long suggestHeapMb(long totalMemoryMb, long availableMemoryMb,
                                     long enabledModsBytes) {
        long base;
        if (totalMemoryMb >= 32768L) {
            base = 4096L;
        } else {
            base = Math.max(roundUp128(totalMemoryMb / 4), MINIMUM_HEAP_MB);
        }
        long modsMb = Math.max(0L, enabledModsBytes) / (1024L * 1024L);
        long allowance = Math.min(MAX_MODS_ALLOWANCE_MB, roundUp128(modsMb * 2L));
        long suggested = base + allowance;

        long cap = totalMemoryMb > 0 ? totalMemoryMb - SYSTEM_RESERVE_MB : suggested;
        if (availableMemoryMb > 0) {
            cap = Math.min(cap, availableMemoryMb);
        }
        cap = Math.max(cap, MINIMUM_HEAP_MB);
        return Math.max(MINIMUM_HEAP_MB, Math.min(suggested, cap));
    }

    /// Whether `jvmArgs` already pins the heap size.
    ///
    /// An explicit `-Xmx`/`-XX:MaxHeapSize` must never be overridden by the
    /// automatic suggestion.
    ///
    /// @param jvmArgs tokenised JVM arguments, or `null`
    /// @return whether a heap limit is already present
    public static boolean hasExplicitHeapLimit(@Nullable List<String> jvmArgs) {
        if (jvmArgs == null) {
            return false;
        }
        for (String arg : jvmArgs) {
            if (arg == null) {
                continue;
            }
            String normalized = arg.trim();
            if (normalized.startsWith("-Xmx") || normalized.startsWith("-XX:MaxHeapSize")) {
                return true;
            }
        }
        return false;
    }

    /// Rounds `value` up to the next multiple of 128 (or 0 for no value).
    private static long roundUp128(long value) {
        if (value <= 0) {
            return 0L;
        }
        return (value + 127L) / 128L * 128L;
    }

    /// Whether `name` is an enabled mod archive (`.jar`/`.zip`, no `.disabled`).
    private static boolean isEnabledModArchive(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".disabled")) {
            return false;
        }
        return lower.endsWith(".jar") || lower.endsWith(".zip");
    }
}
