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
package determination.xenon.mindustry.migrate;

import determination.xenon.mindustry.download.ProgressCallback;
import determination.xenon.mindustry.playtime.MindustryPlaytimeStore;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/// Copies user content between two Mindustry instances.
///
/// Categories map onto the files Mindustry and the launcher keep for one
/// instance: saves (including launcher backups), mods (enabled and disabled
/// archives), schematics, local maps, `settings.bin`, and the launcher's
/// playtime log. Files with the same name are kept unless overwrite is
/// enabled, and nothing is deleted from the source.
///
/// The class only takes paths so it stays usable from XenonCore; the UI
/// resolves instances through the repository before calling it.
@NotNullByDefault
public final class InstanceDataMigration {

    /// Content groups that can be copied between instances.
    public enum Category {
        /// `saves/`, including the launcher's `saves/backups/` snapshots.
        SAVES,
        /// `mods/`, including archives disabled with the `.disabled` suffix.
        MODS,
        /// `schematics/`.
        SCHEMATICS,
        /// `maps/` (custom map files).
        MAPS,
        /// `settings.bin` — player name, UUID and client preferences.
        SETTINGS,
        /// The launcher's `xenon-playtime.jsonl` in the version root.
        PLAYTIME
    }

    /// One migration request.
    ///
    /// @param sourceVersionRoot instance root of the source instance
    /// @param sourceDataDir     effective data directory of the source instance
    /// @param targetVersionRoot instance root of the target instance
    /// @param targetDataDir     effective data directory of the target instance
    /// @param categories        categories to copy; an empty set copies nothing
    /// @param overwrite         whether same-named files in the target are replaced
    public record Request(Path sourceVersionRoot, Path sourceDataDir,
                          Path targetVersionRoot, Path targetDataDir,
                          Set<Category> categories, boolean overwrite) {
    }

    /// Per-category copy statistics of one run.
    ///
    /// @param copied  files written, keyed by category
    /// @param skipped files left untouched because the target already had them
    public record Result(Map<Category, Integer> copied, Map<Category, Integer> skipped) {

        /// Total number of files written.
        public int totalCopied() {
            return copied.values().stream().mapToInt(Integer::intValue).sum();
        }

        /// Total number of files skipped because they already existed.
        public int totalSkipped() {
            return skipped.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private InstanceDataMigration() {
    }

    /// Runs one migration.
    ///
    /// @param request  what to copy and where
    /// @param progress optional callback receiving `(filesDone, filesTotal)`
    /// @return per-category statistics
    /// @throws IOException when source and target are the same location, or a copy fails
    public static Result run(Request request, @Nullable ProgressCallback progress) throws IOException {
        if (samePath(request.sourceDataDir(), request.targetDataDir())) {
            throw new IOException("Source and target data directories are identical");
        }
        Set<Category> categories = EnumSet.copyOf(request.categories());
        Map<Category, Integer> copied = new EnumMap<>(Category.class);
        Map<Category, Integer> skipped = new EnumMap<>(Category.class);
        for (Category category : categories) {
            copied.put(category, 0);
            skipped.put(category, 0);
        }

        // Plan every file with its category up front so progress is monotonic
        // across category boundaries.
        List<PlannedCopy> planned = plan(request, categories);
        int done = 0;
        for (PlannedCopy item : planned) {
            copyFile(item.source(), item.target(), request.overwrite(), copied, skipped, item.category());
            report(progress, ++done, planned.size());
        }
        report(progress, planned.size(), planned.size());
        return new Result(Map.copyOf(copied), Map.copyOf(skipped));
    }

    /// One file scheduled for copying.
    ///
    /// @param source   the file to read
    /// @param target   where the copy goes
    /// @param category statistics bucket for the file
    private record PlannedCopy(Path source, Path target, Category category) {
    }

    /// Builds the copy list for one request.
    private static List<PlannedCopy> plan(Request request, Set<Category> categories) throws IOException {
        List<PlannedCopy> out = new ArrayList<>();
        for (Category category : categories) {
            switch (category) {
                case SAVES -> planTree(request.sourceDataDir().resolve("saves"),
                        request.targetDataDir().resolve("saves"), category, out);
                case MODS -> planTree(request.sourceDataDir().resolve("mods"),
                        request.targetDataDir().resolve("mods"), category, out);
                case SCHEMATICS -> planTree(request.sourceDataDir().resolve("schematics"),
                        request.targetDataDir().resolve("schematics"), category, out);
                case MAPS -> planTree(request.sourceDataDir().resolve("maps"),
                        request.targetDataDir().resolve("maps"), category, out);
                case SETTINGS -> {
                    Path source = request.sourceDataDir().resolve("settings.bin");
                    if (Files.isRegularFile(source)) {
                        out.add(new PlannedCopy(source,
                                request.targetDataDir().resolve("settings.bin"), Category.SETTINGS));
                    }
                }
                case PLAYTIME -> {
                    Path source = request.sourceVersionRoot().resolve(MindustryPlaytimeStore.FILE_NAME);
                    if (Files.isRegularFile(source)) {
                        out.add(new PlannedCopy(source,
                                request.targetVersionRoot().resolve(MindustryPlaytimeStore.FILE_NAME),
                                Category.PLAYTIME));
                    }
                }
                default -> throw new IllegalStateException("Unhandled category " + category);
            }
        }
        return out;
    }

    /// Adds every regular file under `sourceDir` to the copy list.
    private static void planTree(Path sourceDir, Path targetDir, Category category,
                                 List<PlannedCopy> out) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            return;
        }
        for (Path source : listFiles(sourceDir)) {
            out.add(new PlannedCopy(source, targetDir.resolve(sourceDir.relativize(source)), category));
        }
    }

    /// Lists every regular file under `directory`.
    private static List<Path> listFiles(Path directory) throws IOException {
        try (Stream<Path> stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile).toList();
        }
    }

    /// Copies one file, honouring the overwrite policy.
    ///
    /// @return whether the file was written
    private static boolean copyFile(Path source, Path target, boolean overwrite,
                                    Map<Category, Integer> copied, Map<Category, Integer> skipped,
                                    Category category) throws IOException {
        if (Files.exists(target) && !overwrite) {
            skipped.merge(category, 1, Integer::sum);
            return false;
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
        copied.merge(category, 1, Integer::sum);
        return true;
    }

    /// Reports progress when a callback is present and the total is known.
    private static void report(@Nullable ProgressCallback progress, int done, int total) {
        if (progress != null && total > 0) {
            progress.onProgress(Math.min(done, total), total);
        }
    }

    /// Whether two paths point at the same location once normalised.
    private static boolean samePath(Path a, Path b) {
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }
}
