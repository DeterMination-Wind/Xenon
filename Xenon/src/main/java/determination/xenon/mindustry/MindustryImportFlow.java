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

import determination.xenon.Metadata;
import determination.xenon.mindustry.MindustryInstallationDiscovery.DiscoveredInstallation;
import determination.xenon.mindustry.modpack.XenonModpackInstaller;
import determination.xenon.mindustry.ui.MindustryRoutes;
import determination.xenon.setting.Profile;
import determination.xenon.setting.Profiles;
import determination.xenon.setting.Settings;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.util.TaskCancellationAction;
import determination.xenon.util.io.FileUtils;
import javafx.stage.FileChooser;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/**
 * UI-side helper: choose a Mindustry jar, prompt for an id, register it
 * in {@link XenonGameRepository} and launch immediately.
 *
 * <p>This stays out of the HMCL launching pipeline on purpose. Mindustry
 * has no MC-style libraries / asset index / auth, so wiring it through
 * {@code LauncherHelper} would buy nothing and risk side effects from
 * the MC code path. Instead we hand straight off to
 * {@link MindustryLaunchService} + {@link XenonLauncher}.</p>
 */
public final class MindustryImportFlow {

    private MindustryImportFlow() {
    }

    /** Cached repositories keyed by their normalized versions root. */
    private static final Map<Path, XenonGameRepository> REPOSITORIES = new LinkedHashMap<>();

    /** Returns the repository rooted at Xenon's global versions directory. */
    public static synchronized XenonGameRepository repository() {
        return repositoryAt(Metadata.getVersionsDirectory());
    }

    /** Returns the repository belonging to {@code profile}'s game directory. */
    public static synchronized XenonGameRepository repository(@Nullable Profile profile) {
        return repositoryAt(versionsRoot(profile));
    }

    /** Returns the repository that owns an already loaded Mindustry instance. */
    public static synchronized XenonGameRepository repositoryForVersion(MindustryVersion version) {
        Path ownerRoot = version == null ? null : version.getRepositoryRoot();
        return ownerRoot == null ? repository() : repositoryAt(ownerRoot);
    }

    /** Returns the repository for the currently selected Profile, or global Home storage during bootstrap. */
    public static XenonGameRepository currentRepository() {
        return repository(Profiles.getSelectedProfile());
    }

    /** Returns the on-disk versions root selected by a Profile. */
    public static Path versionsRoot(@Nullable Profile profile) {
        if (profile == null || isGlobalProfile(profile)) {
            return Metadata.getVersionsDirectory();
        }
        return profile.getGameDir().toAbsolutePath().normalize().resolve("versions");
    }

    /** True when a Profile points at Xenon's global Home directory. */
    public static boolean isGlobalProfile(@Nullable Profile profile) {
        return profile != null && samePath(profile.getGameDir(), Metadata.XENON_GLOBAL_DIRECTORY);
    }

    private static XenonGameRepository repositoryAt(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        XenonGameRepository repo = REPOSITORIES.get(normalizedRoot);
        if (repo == null) {
            repo = new XenonGameRepository(normalizedRoot);
            try {
                Files.createDirectories(normalizedRoot);
            } catch (Exception ignored) {
                // The repository will report the failure again when it scans or saves.
            }
            repo.refresh();
            REPOSITORIES.put(normalizedRoot, repo);
        }
        return repo;
    }

    /** Resolve the cache root used by Mindustry release/mod downloads. */
    public static Path cachesDirectory() {
        try {
            String commonDirectory = Settings.instance().getCommonDirectory();
            if (commonDirectory != null && !commonDirectory.isBlank()) {
                return Path.of(commonDirectory, "cache");
            }
        } catch (Throwable ignored) {
            // Metadata is always available, while Settings can be early during bootstrap.
        }
        return Metadata.getCachesDirectory();
    }

    /**
     * If a profile's game directory points at an external Mindustry install
     * such as Steam, register it as one normal Xenon Mindustry instance.
     */
    public static Optional<MindustryVersion> syncProfileGameDirectory(Profile profile) {
        if (profile == null) {
            return Optional.empty();
        }
        return syncExternalInstallation(profile, profile.getGameDir());
    }

    /**
     * Mindustry instances that should be visible for {@code profile}'s current
     * game directory.
     *
     * <p>Normal Profiles read their own {@code <gameDir>/versions} root. The
     * Home Profile reads Xenon's global root, and an existing global external
     * registration is included for compatibility without moving its files.</p>
     */
    public static List<MindustryVersion> visibleVersions(Profile profile) {
        if (profile == null) {
            return List.of();
        }
        XenonGameRepository repo = repository(profile);
        repo.refresh();
        if (isGlobalProfile(profile)) {
            return new ArrayList<>(repo.all());
        }

        List<MindustryVersion> visible = new ArrayList<>();
        for (MindustryVersion version : repo.all()) {
            visible.add(version);
        }

        // Existing Steam/external registrations may still live in the global
        // repository. Keep exposing those records without moving their files.
        syncProfileGameDirectory(profile).ifPresent(external -> {
            String id = external.getId();
            if (id != null && visible.stream().noneMatch(version -> id.equals(version.getId()))) {
                visible.add(external);
            }
        });
        return visible;
    }

    /** Finds an instance visible from a Profile without resolving another Profile's duplicate id. */
    public static Optional<MindustryVersion> findVersion(@Nullable Profile profile, @Nullable String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        if (profile == null) {
            XenonGameRepository global = repository();
            global.refresh();
            return global.get(id);
        }
        return visibleVersions(profile).stream()
                .filter(version -> id.equals(version.getId()))
                .findFirst();
    }

    /** Resolves the actual {@code versions/<id>} directory for an instance. */
    public static Path versionRoot(MindustryVersion version) {
        XenonGameRepository repo = repositoryForVersion(version);
        return repo.getVersionRoot(version);
    }

    /** Selects an imported instance and refreshes the HMCL-facing version list. */
    public static void selectVersion(Profile profile, MindustryVersion version) {
        if (profile == null || version == null || version.getId() == null) {
            return;
        }
        profile.setSelectedVersion(version.getId());
        profile.getRepository().refreshVersionsAsync().start();
    }

    /**
     * Register an external Mindustry installation without copying its game jar.
     * Repeated calls are idempotent for the same jar/data/working directory.
     */
    public static Optional<MindustryVersion> syncExternalInstallation(Path directory) {
        return syncExternalInstallation(null, directory);
    }

    /** Register an external installation using a Profile-scoped repository when possible. */
    public static Optional<MindustryVersion> syncExternalInstallation(
            @Nullable Profile profile, Path directory) {
        Optional<DiscoveredInstallation> discovered = MindustryInstallationDiscovery.discover(directory);
        if (discovered.isEmpty()) {
            return Optional.empty();
        }

        try {
            XenonGameRepository repo = repository(profile);
            repo.refresh();
            MindustryVersion existing = findExistingExternalInstall(repo, discovered.get());
            if (existing != null) {
                return Optional.of(existing);
            }

            // Do not migrate an existing global registration when a Profile
            // starts using its own versions directory.
            if (profile != null && !isGlobalProfile(profile)) {
                XenonGameRepository global = repository();
                global.refresh();
                existing = findExistingExternalInstall(global, discovered.get());
                if (existing != null) {
                    return Optional.of(existing);
                }
            }

            MindustryVersion version = toVersion(repo, discovered.get());
            repo.save(version);
            LOG.info("Registered external Mindustry install " + discovered.get().getRoot()
                    + " as " + version.getId());
            return Optional.of(version);
        } catch (Throwable ex) {
            LOG.warning("Failed to register external Mindustry install " + directory, ex);
            return Optional.empty();
        }
    }

    /** True iff the dropped file is a Xenon Mindustry modpack. */
    public static boolean isXenonModpackFile(Path file) {
        return file != null && "xenon".equalsIgnoreCase(FileUtils.getExtension(file));
    }

    /** Prompt for a target id, then install a .xenon modpack into the Mindustry repository. */
    public static void showInstallModpackDialog(Path file) {
        if (!isXenonModpackFile(file)) {
            return;
        }

        String suggested = sanitizeId(file.getFileName().toString().replaceFirst("(?i)\\.xenon$", ""));
        Controllers.prompt(i18n("xenon.mindustry.import.id.prompt"), (id, handler) -> {
            String trimmed = id == null ? "" : id.trim();
            if (trimmed.isEmpty() || !trimmed.matches("[A-Za-z0-9._-]+")) {
                handler.reject(i18n("xenon.mindustry.import.id.invalid"));
                return;
            }

            Profile profile = Profiles.getSelectedProfile();
            XenonGameRepository repo = repository(profile);
            repo.refresh();
            if (repo.has(trimmed)) {
                handler.reject(i18n("xenon.mindustry.import.id.duplicate"));
                return;
            }

            handler.resolve();
            Task<?> install = Task.supplyAsync(Schedulers.io(), () ->
                            XenonModpackInstaller.install(repo, file, trimmed))
                    .whenComplete(Schedulers.javafx(), (version, exception) -> {
                        if (exception == null && version != null) {
                            if (profile != null) {
                                selectVersion(profile, version);
                            }
                            Controllers.showToast(i18n("message.success"));
                        } else if (exception != null) {
                            LOG.warning("Failed to install Xenon modpack " + file, exception);
                            Controllers.dialog(
                                    i18n("modpack.task.install.error") + "\n\n" + exception.getMessage(),
                                    i18n("message.error"),
                                    MessageDialogPane.MessageType.ERROR);
                        }
                    }).setName(i18n("modpack.task.install"));
            Controllers.taskDialog(install, i18n("modpack.installing"), TaskCancellationAction.NORMAL);
        }, suggested);
    }

    /** Show a FileChooser for a local .xenon package. */
    public static void showInstallModpackFileChooser() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("modpack.choose"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(i18n("modpack"), "*.xenon"));
        Path file = FileUtils.toPath(FXUtils.showOpenDialog(chooser, Controllers.getStage()));
        if (file != null) {
            showInstallModpackDialog(file);
        }
    }

    /** Show a FileChooser, then run the import + launch chain. */
    public static void showImportAndLaunchDialog() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("xenon.mindustry.import.choose"));
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Mindustry jar", "*.jar"));
        Path file = FileUtils.toPath(FXUtils.showOpenDialog(chooser, Controllers.getStage()));
        if (file == null) return;

        // Default id = filename without extension, sanitised.
        String suggested = sanitizeId(file.getFileName().toString().replaceFirst("(?i)\\.jar$", ""));

        Controllers.prompt(i18n("xenon.mindustry.import.id.prompt"), (id, handler) -> {
            String trimmed = id == null ? "" : id.trim();
            if (trimmed.isEmpty() || !trimmed.matches("[A-Za-z0-9._-]+")) {
                handler.reject(i18n("xenon.mindustry.import.id.invalid"));
                return;
            }
            Profile profile = Profiles.getSelectedProfile();
            XenonGameRepository repo = repository(profile);
            repo.refresh();
            if (repo.has(trimmed)) {
                handler.reject(i18n("xenon.mindustry.import.id.duplicate"));
                return;
            }
            handler.resolve();
            doImportAndLaunch(profile, repo, file, trimmed);
        }, suggested);
    }

    private static void doImportAndLaunch(@Nullable Profile profile,
                                          XenonGameRepository repo,
                                          Path jar,
                                          String id) {
        Schedulers.io().execute(() -> {
            try {
                MindustryVersion version = MindustryLaunchService.importLocalJar(repo, jar, id, null);
                Schedulers.javafx().execute(() -> {
                    if (profile != null) {
                        selectVersion(profile, version);
                    }
                    MindustryRoutes.launch(version);
                });
            } catch (Throwable ex) {
                LOG.warning("Mindustry import/launch failed", ex);
                Schedulers.javafx().execute(() -> Controllers.dialog(
                        i18n("xenon.mindustry.launch.failed") + "\n\n" + ex.getMessage(),
                        i18n("message.error"),
                        MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    private static MindustryVersion toVersion(XenonGameRepository repo, DiscoveredInstallation installation) {
        String id = uniqueId(repo, installation.getSuggestedId());
        MindustryVersion version = new MindustryVersion();
        version.setId(id);
        version.setName(installation.getDisplayName());
        version.setVariant(installation.getVariant());
        version.setBuild(installation.getBuild());
        version.setBuildType(installation.getBuildType());
        version.setJarPath(installation.getJar().toString());
        version.setJavaReq(installation.getBuild() > 0 && installation.getBuild() < 140 ? 8 : 17);
        if (installation.getJavaHome() != null) {
            version.setJavaHome(installation.getJavaHome().toString());
        }
        version.setWorkingDirectory(installation.getWorkingDirectory().toString());
        version.setDataDirPolicy(DataDirectoryPolicy.CUSTOM);
        version.setCustomDataDir(installation.getDataDir().toString());
        if (!installation.getJvmArgs().isEmpty()) {
            version.setJvmArgs(String.join(" ", installation.getJvmArgs()));
        }
        return version;
    }

    private static String uniqueId(XenonGameRepository repo, String suggestedId) {
        String base = sanitizeId(suggestedId);
        if (!repo.has(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            String candidate = base + "-" + i;
            if (!repo.has(candidate)) {
                return candidate;
            }
        }
    }

    private static @Nullable MindustryVersion findExistingExternalInstall(
            XenonGameRepository repo,
            DiscoveredInstallation installation) {
        for (MindustryVersion version : repo.all()) {
            String id = version.getId();
            if (id == null || id.isBlank()) {
                continue;
            }
            Path versionRoot = repo.getVersionRoot(version);
            if (samePath(version.resolveJar(versionRoot), installation.getJar())
                    && samePath(version.resolveDataDir(versionRoot), installation.getDataDir())
                    && samePath(version.resolveWorkingDirectory(versionRoot), installation.getWorkingDirectory())) {
                return version;
            }
        }
        return null;
    }

    private static boolean samePath(Path left, Path right) {
        return Objects.equals(normalize(left), normalize(right));
    }

    private static Path normalize(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String sanitizeId(String raw) {
        if (raw == null) return "mindustry";
        String s = raw.replaceAll("[^A-Za-z0-9._-]", "-");
        if (s.isEmpty()) return "mindustry";
        return s;
    }
}
