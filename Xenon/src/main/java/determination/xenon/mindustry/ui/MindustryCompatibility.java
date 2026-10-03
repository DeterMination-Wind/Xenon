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
package determination.xenon.mindustry.ui;

import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.mod.MindustryLocalMod;
import determination.xenon.mindustry.mod.MindustryModManager;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.construct.MessageDialogPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Static compatibility evaluator for one Mindustry instance.
///
/// The evaluator inspects the instance metadata together with its `mods/`
/// directory and reports structured issues the launcher can surface around
/// launches and installs. Archive-level problems never propagate as
/// exceptions: unreadable or unparsable archives become issues instead.
///
/// [#forInstance] touches the disk and must run on a background thread;
/// [#showIssues] builds dialog content and must run on the JavaFX thread.
@NotNullByDefault
public final class MindustryCompatibility {

    /// Dependency names provided by Mindustry itself and therefore never
    /// satisfied by an installed mod archive.
    private static final Set<String> BUILTIN_DEPENDENCIES = Set.of("mindustry", "core", "arc");

    /// Prevents instantiation of this utility class.
    private MindustryCompatibility() {
    }

    /// Urgency of a compatibility issue, ordered from most to least urgent.
    public enum Severity {
        /// The instance cannot start correctly until this is fixed.
        ERROR,
        /// The instance starts, but a mod may misbehave.
        WARNING,
        /// Purely informational finding.
        INFO
    }

    /// One structured compatibility finding.
    ///
    /// @param severity urgency of the finding
    /// @param message already-localized human-readable description
    public record Issue(Severity severity, String message) {
    }

    /// Evaluates the compatibility of `version` as installed under `versionRoot`.
    ///
    /// Uses `dataDir` to locate `mods/` and the per-instance mod enable
    /// settings. The returned list is sorted by severity (ERROR, then
    /// WARNING, then INFO) and is stable within one severity level.
    ///
    /// Performs file IO; call from a background thread.
    ///
    /// @param version the Mindustry instance descriptor
    /// @param versionRoot instance root directory resolved for `version`
    /// @param dataDir Mindustry data directory the instance launches with
    /// @return an immutable, severity-sorted issue list (possibly empty)
    public static @Unmodifiable List<Issue> forInstance(MindustryVersion version, Path versionRoot, Path dataDir) {
        List<Issue> issues = new ArrayList<>();
        int targetBuild = version.getBuild();
        if (targetBuild <= 0) {
            issues.add(new Issue(Severity.INFO, i18n("xenon.mindustry.health.issue.build_unknown")));
        }

        Path jar = version.resolveJar(versionRoot);
        if (!Files.isRegularFile(jar)) {
            issues.add(new Issue(Severity.ERROR, i18n("xenon.mindustry.health.issue.jar_missing", jar)));
        }

        Path modsDir = dataDir.resolve("mods");
        List<MindustryLocalMod> mods = new MindustryModManager(modsDir, dataDir).scan();

        if (targetBuild > 0) {
            for (MindustryLocalMod mod : mods) {
                if (mod.getMinGameVersion() > targetBuild) {
                    issues.add(new Issue(Severity.WARNING, i18n(
                            "xenon.mindustry.health.issue.min_game_version",
                            mod.displayName(), mod.getMinGameVersion(), targetBuild)));
                }
            }
        }

        collectDependencyIssues(mods, issues);

        for (MindustryLocalMod mod : mods) {
            if (mod.isIgnoredByDuplicate()) {
                issues.add(new Issue(Severity.WARNING, i18n(
                        "xenon.mindustry.health.issue.duplicate",
                        mod.displayName(), mod.getIgnoredByFileName())));
            }
        }

        collectUnparsableIssues(modsDir, mods, issues);

        // List.sort is stable, so findings of equal severity keep their scan order.
        issues.sort(Comparator.comparingInt(issue -> issue.severity().ordinal()));
        return List.copyOf(issues);
    }

    /// Returns the localized label of `severity`.
    public static String severityLabel(Severity severity) {
        return switch (severity) {
            case ERROR -> i18n("xenon.mindustry.health.severity.error");
            case WARNING -> i18n("xenon.mindustry.health.severity.warning");
            case INFO -> i18n("xenon.mindustry.health.severity.info");
        };
    }

    /// Shows a warning dialog listing `issues` under `heading`.
    ///
    /// Does nothing when `issues` is empty. Must be called from the JavaFX
    /// application thread.
    public static void showIssues(String heading, List<Issue> issues) {
        if (issues.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder();
        for (Issue issue : issues) {
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(severityLabel(issue.severity())).append(": ").append(issue.message());
        }
        Controllers.dialog(body.toString(), heading, MessageDialogPane.MessageType.WARNING);
    }

    /// Appends one issue per unresolved dependency of every parsed mod.
    ///
    /// Names are compared case-insensitively against mod internal names;
    /// built-in loader names and a mod's own name are skipped. A dependency
    /// counts as satisfied as soon as one matching mod is enabled.
    private static void collectDependencyIssues(List<MindustryLocalMod> mods, List<Issue> issues) {
        for (MindustryLocalMod mod : mods) {
            for (String rawDependency : mod.getDependencies()) {
                String dependency = rawDependency == null ? "" : rawDependency.trim();
                if (dependency.isEmpty()) {
                    continue;
                }
                if (BUILTIN_DEPENDENCIES.contains(dependency.toLowerCase(Locale.ROOT))
                        || dependency.equalsIgnoreCase(mod.getInternalName())) {
                    continue;
                }
                boolean installed = false;
                boolean enabled = false;
                for (MindustryLocalMod candidate : mods) {
                    if (!dependency.equalsIgnoreCase(candidate.getInternalName())) {
                        continue;
                    }
                    installed = true;
                    if (candidate.isEnabled()) {
                        enabled = true;
                        break;
                    }
                }
                if (enabled) {
                    continue;
                }
                issues.add(new Issue(Severity.WARNING, i18n(
                        installed
                                ? "xenon.mindustry.health.issue.disabled_dependency"
                                : "xenon.mindustry.health.issue.missing_dependency",
                        mod.displayName(), dependency)));
            }
        }
    }

    /// Appends one issue per raw archive in `modsDir` that `scan` did not parse.
    ///
    /// The comparison uses absolute normalized paths so the `.disabled`
    /// suffix convention and directory aliases cannot produce false positives.
    private static void collectUnparsableIssues(Path modsDir, List<MindustryLocalMod> mods, List<Issue> issues) {
        if (!Files.isDirectory(modsDir)) {
            return;
        }
        Set<Path> parsed = new HashSet<>();
        for (MindustryLocalMod mod : mods) {
            parsed.add(mod.getFile().toAbsolutePath().normalize());
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir)) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file) || !isModArchiveName(file)) {
                    continue;
                }
                if (!parsed.contains(file.toAbsolutePath().normalize())) {
                    issues.add(new Issue(Severity.WARNING, i18n(
                            "xenon.mindustry.health.issue.unparsable",
                            file.getFileName().toString())));
                }
            }
        } catch (IOException ex) {
            LOG.warning("Failed to list Mindustry mods dir " + modsDir, ex);
        }
    }

    /// Returns whether the file name selects a Mindustry mod archive.
    ///
    /// Mirrors `MindustryModManager`: a trailing `.disabled` is ignored and
    /// both `.jar` and `.zip` archives count.
    private static boolean isModArchiveName(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".disabled")) {
            name = name.substring(0, name.length() - ".disabled".length());
        }
        return name.endsWith(".jar") || name.endsWith(".zip");
    }
}
