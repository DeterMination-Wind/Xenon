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

import com.jfoenix.controls.JFXButton;
import determination.xenon.game.HMCLGameRepository;
import determination.xenon.mindustry.LaunchOptions;
import determination.xenon.mindustry.MindustryClientRuntimeRegistry;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryLaunchService;
import determination.xenon.mindustry.MindustryLogFinder;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.XenonGameRepository;
import determination.xenon.mindustry.XenonLauncher;
import determination.xenon.mindustry.mod.MindustryCrashRecovery;
import determination.xenon.mindustry.modpack.XenonModpackPacker;
import determination.xenon.mindustry.playtime.MindustryPlaytimeStore;
import determination.xenon.mindustry.save.MindustryLaunchSaveService;
import determination.xenon.mindustry.ui.MindustryCloudArchiveService.Outcome;
import determination.xenon.setting.ConfigHolder;
import determination.xenon.setting.Profile;
import determination.xenon.setting.Profiles;
import determination.xenon.task.FetchTask;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.ui.construct.PromptDialogPane;
import determination.xenon.ui.construct.Validator;
import determination.xenon.util.TaskCancellationAction;
import determination.xenon.util.io.FileUtils;
import javafx.application.Platform;
import javafx.stage.FileChooser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/**
 * Routing helper that hands HMCL-style {@code (profile, id)} calls to the
 * Mindustry-specific UI / launch path when {@code id} matches an entry in
 * {@link XenonGameRepository}.
 *
 * <p>Patched into {@code Versions.*} and {@code GameItem} so the existing
 * HMCL UI shell keeps working for any non-Mindustry instances while
 * Mindustry instances detour to the Xenon launcher and
 * {@link MindustryVersionPage}.</p>
 */
@NotNullByDefault
public final class MindustryRoutes {
    private static final int RECENT_LINE_DISPLAY_LIMIT = 12;
    private static final Map<String, CopyOnWriteArrayList<Runnable>> MOD_PANE_REFRESH_LISTENERS =
            new ConcurrentHashMap<>();

    /// Live playtime sessions of this launcher process, keyed by instance id
    /// and valued by the pid that started the session. Reload relaunches keep
    /// the original pid so the recorded session spans the whole play run.
    private static final Map<String, Long> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    /// Instance ids whose current process is being restarted by the dev workflow.
    private static final java.util.Set<String> DEV_RESTARTS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private MindustryRoutes() {}

    /**
     * True iff {@code id} matches a Mindustry version visible from the
     * currently selected Profile. The Profile-scoped lookup refreshes the
     * selected versions root, so a newly saved manifest is recognized before
     * the HMCL-facing list event arrives.
     *
     * <p>The disk fallback accepts both manifest layouts the launcher
     * has used historically: {@code <id>/version.json} (current) and
     * {@code <id>/<id>.json} (older Xenon installs).</p>
     */
    public static boolean isMindustry(@Nullable String id) {
        return isMindustry(Profiles.getSelectedProfile(), id);
    }

    /** Tests whether an id belongs to the supplied Profile's Mindustry repository. */
    public static boolean isMindustry(@Nullable Profile profile, @Nullable String id) {
        return get(profile, id).isPresent();
    }

    /** Resolves an id from the currently selected Profile. */
    public static Optional<MindustryVersion> get(@Nullable String id) {
        return get(Profiles.getSelectedProfile(), id);
    }

    /** Resolves an id without confusing duplicate ids in another Profile. */
    public static Optional<MindustryVersion> get(@Nullable Profile profile, @Nullable String id) {
        return MindustryImportFlow.findVersion(profile, id);
    }

    /** Export one Mindustry instance as a .xenon zip containing the game jar and data files. */
    public static void exportModpack(MindustryVersion version) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("modpack.export"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(i18n("modpack"), "*.xenon"));
        chooser.setInitialFileName(version.getId() + ".xenon");
        Path selected = FileUtils.toPath(FXUtils.showSaveDialog(chooser, Controllers.getStage()));
        if (selected == null) {
            return;
        }
        Path output = "xenon".equalsIgnoreCase(FileUtils.getExtension(selected))
                ? selected
                : selected.resolveSibling(selected.getFileName().toString() + ".xenon");

        Task<Path> exportTask = new Task<Path>() {
            @Override
            public void execute() throws Exception {
                XenonModpackPacker.pack(MindustryImportFlow.repositoryForVersion(version), version, output,
                        new XenonModpackPacker.ExportMonitor() {
                            private long lastWritten;

                            @Override
                            public void update(long writtenBytes, long totalBytes) {
                                updateProgress(writtenBytes, totalBytes);
                                FetchTask.recordDownloadedBytes(writtenBytes - lastWritten);
                                lastWritten = writtenBytes;
                            }

                            @Override
                            public boolean isCancelled() {
                                return thisTaskIsCancelled();
                            }
                        });
                setResult(output);
            }

            private boolean thisTaskIsCancelled() {
                return isCancelled();
            }
        }.setName(i18n("modpack.export"));
        Task<?> export = exportTask.whenComplete(Schedulers.javafx(), (written, exception) -> {
            if (exception == null) {
                Controllers.showToast(i18n("message.success") + ": " + written);
            } else if (exception instanceof CancellationException) {
                Controllers.showToast(i18n("message.cancelled"));
            } else {
                LOG.warning("Failed to export Xenon modpack " + output, exception);
                Controllers.dialog(exception.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR);
            }
        }).setName(i18n("modpack.export"));
        Controllers.taskDialog(export, i18n("modpack.export"), TaskCancellationAction.NORMAL);
    }

    /** Open the per-version Mindustry management page. */
    public static void openVersionPage(MindustryVersion version) {
        Controllers.navigate(new MindustryVersionPage(version));
    }

    /**
     * Renames one Mindustry instance after asking for a new id.
     *
     * <p>Mindustry instances share {@code <gameDir>/versions} with HMCL's
     * Minecraft repository, but HMCL's rename only understands
     * {@code <id>.json}/{@code <id>.jar} and would leave {@code version.json}
     * pointing at the previous id and jar. The move is therefore performed on
     * the owning {@link XenonGameRepository}, which rewrites the manifest.</p>
     *
     * @param profile the Profile that owns the instance
     * @param id the current instance id
     * @return a future completed with the new instance id
     */
    public static CompletableFuture<String> renameVersion(Profile profile, String id) {
        Optional<MindustryVersion> found = get(profile, id);
        if (profile == null || found.isEmpty()) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException("Mindustry instance not found: " + id));
            return failed;
        }

        MindustryVersion version = found.get();
        String currentId = version.getId() == null || version.getId().isBlank() ? id : version.getId();
        XenonGameRepository repo = MindustryImportFlow.repositoryForVersion(version);
        repo.refresh();

        return Controllers.prompt(i18n("version.manage.rename.message"), (input, handler) -> {
            String targetId = input == null ? "" : input.trim();
            if (!XenonGameRepository.isValidId(targetId)) {
                handler.reject(i18n("install.new_game.malformed"));
                return;
            }
            if (targetId.equals(currentId)) {
                handler.resolve();
                return;
            }
            if (!targetId.equalsIgnoreCase(currentId) && repo.has(targetId)) {
                handler.reject(i18n("install.new_game.already_exists"));
                return;
            }
            if (profile.getRepository().versionIdConflicts(targetId)) {
                handler.reject(i18n("install.new_game.already_exists"));
                return;
            }
            handler.resolve();

            Task<?> rename = Task.supplyAsync(Schedulers.io(), () -> {
                if (!repo.renameVersion(currentId, targetId)) {
                    throw new IOException("Unable to rename Mindustry instance " + currentId);
                }
                return targetId;
            }).setName(i18n("version.manage.rename"));

            rename.whenComplete(Schedulers.javafx(), (result, exception) -> {
                if (exception != null) {
                    LOG.warning("Failed to rename Mindustry instance " + currentId + " to " + targetId, exception);
                    Controllers.dialog(i18n("version.manage.rename.fail"),
                            i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                    return;
                }
                moveModPaneRefreshListeners(currentId, targetId);
                profile.setSelectedVersion(targetId);
                profile.getRepository().refreshVersionsAsync().start();
                Controllers.showToast(i18n("message.success"));
            }).start();
        }, currentId,
                new Validator(i18n("install.new_game.malformed"), XenonGameRepository::isValidId),
                new Validator(i18n("install.new_game.already_exists"), name -> name != null
                        && (name.equals(currentId) || !repo.has(name))));
    }

    /// Duplicates one Mindustry instance from the HMCL version-management flow.
    ///
    /// @param profile the Profile that owns the instance
    /// @param id the source instance id
    public static void duplicateVersion(Profile profile, String id) {
        Optional<MindustryVersion> found = get(profile, id);
        if (found.isEmpty()) {
            Controllers.dialog(i18n("version.not_exist"), i18n("message.error"),
                    MessageDialogPane.MessageType.ERROR);
            return;
        }
        MindustryVersion version = found.get();
        MindustryImportFlow.repositoryForVersion(version).refresh();
        duplicateVersion(version);
    }

    /// Asks for a new instance id, then copies the instance on a background task.
    ///
    /// The instance directory is always copied. The game data checkbox is only
    /// offered when the effective data directory lives inside the instance
    /// directory; `GLOBAL`/`CUSTOM` instances share their data directory and
    /// show a hint instead. `settings.bin` follows the global
    /// duplicate-instance-settings option so duplicates can receive a fresh
    /// player identity.
    ///
    /// @param version the instance to duplicate
    public static void duplicateVersion(MindustryVersion version) {
        @Nullable String currentId = version.getId();
        if (currentId == null || currentId.isBlank()) {
            Controllers.dialog(i18n("version.not_exist"), i18n("message.error"),
                    MessageDialogPane.MessageType.ERROR);
            return;
        }

        XenonGameRepository repo = MindustryImportFlow.repositoryForVersion(version);
        repo.refresh();

        @Nullable Profile profile = Profiles.getSelectedProfile();
        @Nullable HMCLGameRepository hmclRepository = profile == null ? null : profile.getRepository();

        String baseId = currentId + "-copy";
        String defaultId = MindustryImportFlow.uniqueId(repo, baseId);
        for (int i = 2; hmclRepository != null && hmclRepository.versionIdConflicts(defaultId); i++) {
            defaultId = MindustryImportFlow.uniqueId(repo, baseId + "-" + i);
        }

        Path versionRoot = repo.getVersionRoot(version);
        boolean dataInRoot = version.resolveDataDir(versionRoot).toAbsolutePath().normalize()
                .startsWith(versionRoot.toAbsolutePath().normalize());

        PromptDialogPane.Builder builder = new PromptDialogPane.Builder(
                i18n("xenon.mindustry.instance.duplicate.title"), (res, handler) -> {
            String targetId = ((PromptDialogPane.Builder.StringQuestion) res.get(1)).getValue();
            boolean copyData = dataInRoot
                    && res.get(2) instanceof PromptDialogPane.Builder.BooleanQuestion dataQuestion
                    && Boolean.TRUE.equals(dataQuestion.getValue());
            handler.resolve();

            Runnable startCopy = () -> startDuplicateTask(
                    profile, repo, currentId, targetId, copyData);
            if (copyData && ACTIVE_SESSIONS.containsKey(currentId)) {
                // Mindustry rewrites saves and settings while it runs, so copying
                // a live instance can produce a half-written duplicate.
                Controllers.confirm(i18n("xenon.mindustry.instance.duplicate.running"),
                        i18n("message.warning"), MessageDialogPane.MessageType.WARNING,
                        startCopy, () -> {
                        });
                return;
            }
            startCopy.run();
        })
                .addQuestion(new PromptDialogPane.Builder.HintQuestion(
                        i18n("xenon.mindustry.instance.duplicate.prompt", version.getName())))
                .addQuestion(new PromptDialogPane.Builder.StringQuestion(null, defaultId,
                        new Validator(i18n("install.new_game.malformed"), XenonGameRepository::isValidId),
                        new Validator(i18n("install.new_game.already_exists"), name -> name != null
                                && !repo.has(name)
                                && (hmclRepository == null || !hmclRepository.versionIdConflicts(name)))));
        if (dataInRoot) {
            builder.addQuestion(new PromptDialogPane.Builder.BooleanQuestion(
                    i18n("xenon.mindustry.instance.duplicate.data"), false));
        } else {
            builder.addQuestion(new PromptDialogPane.Builder.HintQuestion(
                    i18n("xenon.mindustry.instance.duplicate.shared_data")));
        }
        Controllers.prompt(builder);
    }

    /// Runs {@link XenonGameRepository#copyVersion} off the FX thread and updates the UI.
    private static void startDuplicateTask(@Nullable Profile profile, XenonGameRepository repo,
                                           String currentId, String targetId, boolean copyData) {
        boolean copySettings = ConfigHolder.globalConfig().isDuplicateInstanceCopiesSettings();
        Task<MindustryVersion> copy = Task.supplyAsync(Schedulers.io(),
                        () -> repo.copyVersion(currentId, targetId, copyData, copySettings))
                .setName(i18n("xenon.mindustry.instance.duplicate.title"));
        copy.whenComplete(Schedulers.javafx(), (result, exception) -> {
            if (exception != null) {
                LOG.warning("Failed to duplicate Mindustry instance " + currentId + " to " + targetId,
                        exception);
                String message = exception.getMessage() != null
                        ? exception.getMessage()
                        : exception.toString();
                Controllers.dialog(i18n("xenon.mindustry.instance.duplicate.failed", message),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                return;
            }
            if (profile != null) {
                profile.setSelectedVersion(targetId);
                profile.getRepository().refreshVersionsAsync().start();
            }
            Controllers.showToast(i18n("message.success"));
        }).start();
    }

    /** Moves the mod pane refresh callbacks when an instance id changes. */
    private static void moveModPaneRefreshListeners(String from, String to) {
        if (from.equals(to)) {
            return;
        }
        CopyOnWriteArrayList<Runnable> listeners = MOD_PANE_REFRESH_LISTENERS.remove(from);
        if (listeners != null && !listeners.isEmpty()) {
            MOD_PANE_REFRESH_LISTENERS.merge(to, listeners, (existing, moved) -> {
                existing.addAllAbsent(moved);
                return existing;
            });
        }
    }

    /** Register a visible mod pane refresh callback for one Mindustry instance id. */
    public static void addModPaneRefreshListener(String id, Runnable listener) {
        MOD_PANE_REFRESH_LISTENERS
                .computeIfAbsent(id, ignored -> new CopyOnWriteArrayList<>())
                .addIfAbsent(listener);
    }

    /** Remove a previously registered mod pane refresh callback. */
    public static void removeModPaneRefreshListener(String id, Runnable listener) {
        CopyOnWriteArrayList<Runnable> listeners = MOD_PANE_REFRESH_LISTENERS.get(id);
        if (listeners == null) {
            return;
        }
        listeners.remove(listener);
        if (listeners.isEmpty()) {
            MOD_PANE_REFRESH_LISTENERS.remove(id, listeners);
        }
    }

    /** Spawn the Mindustry process for {@code id} via {@link XenonLauncher}. */
    public static void launch(MindustryVersion version) {
        String id = version.getId();
        Schedulers.io().execute(() -> {
            try {
                XenonGameRepository repo = MindustryImportFlow.repositoryForVersion(version);
                Path versionRoot = repo.getVersionRoot(version);
                Path effectiveDataDir = MindustryLaunchSaveService.resolveLaunchDataDir(versionRoot,
                        version.resolveDataDir(versionRoot), version.getLaunchSaveFile());
                syncCloudArchiveBeforeLaunch(version, versionRoot);
                if (MindustryCrashRecovery.pending(effectiveDataDir)) {
                    // The previous run died before mod init completed; let the
                    // user decide between launcher-side safe mode and the raw
                    // configuration instead of letting the game silently
                    // disable every mod on startup.
                    Platform.runLater(() -> confirmCrashRecoveryLaunch(repo, version, versionRoot,
                            effectiveDataDir));
                    return;
                }
                spawnClient(repo, version, versionRoot);
            } catch (Throwable ex) {
                LOG.warning("Mindustry launch failed", ex);
                Platform.runLater(() -> {
                    if (MindustryJavaMissingPrompt.showIfMissing(ex)) {
                        return;
                    }
                    Controllers.dialog(
                            i18n("xenon.mindustry.launch.failed") + "\n\n" + ex.getMessage(),
                            i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                });
            }
        });
    }

    /// Restarts an instance for the development workflow.
    ///
    /// A live process is destroyed and relaunched on exit; a stopped instance
    /// is spawned directly. The relaunch skips cloud sync, automatic backups and
    /// abnormal-exit dialogs, and the playtime session keeps spanning the run.
    ///
    /// @param version instance to restart
    public static void restartForDevelopment(MindustryVersion version) {
        String id = version.getId();
        if (id == null || id.isBlank()) {
            return;
        }
        MindustryClientRuntimeRegistry.RuntimeHandle handle =
                MindustryClientRuntimeRegistry.shared().snapshot().get(id);
        if (handle != null && handle.isAlive()) {
            DEV_RESTARTS.add(id);
            handle.destroy();
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                XenonGameRepository repo = MindustryImportFlow.repositoryForVersion(version);
                Path versionRoot = repo.getVersionRoot(version);
                spawnClient(repo, version, versionRoot);
            } catch (Throwable ex) {
                LOG.warning("Development restart failed", ex);
            }
        });
    }

    /// Whether an instance is currently being restarted by the dev workflow.
    ///
    /// @param id instance id
    /// @return whether the next exit belongs to a development restart
    public static boolean isDevRestarting(String id) {
        return id != null && DEV_RESTARTS.contains(id);
    }

    /// Pulls a newer cloud archive before the game starts.
    ///
    /// Runs on the launch IO thread, so a failure never blocks the game: the
    /// error is reported as a toast and the local instance starts unchanged.
    private static void syncCloudArchiveBeforeLaunch(MindustryVersion version, Path versionRoot) {
        if (!version.isCloudAutoSync() || isDevRestarting(version.getId())) {
            return;
        }
        try {
            MindustryCloudArchiveService service = new MindustryCloudArchiveService(
                    CommunityServices.cloudSaves(), version, versionRoot);
            var result = service.preLaunch();
            if (result.outcome() == Outcome.RESTORED) {
                Platform.runLater(() -> Controllers.showToast(
                        i18n("xenon.cloud.archive.restored")));
            } else if (result.outcome() == Outcome.CONFLICT) {
                Platform.runLater(() -> Controllers.showToast(
                        i18n("xenon.cloud.archive.conflict")));
            }
        } catch (IOException e) {
            LOG.warning("Cloud archive pre-launch sync failed", e);
            Platform.runLater(() -> Controllers.showToast(
                    i18n("xenon.cloud.archive.failed", MdtbbsMessages.describe(e))));
        }
    }

    /// Uploads the instance archive after a normal exit.
    private static void syncCloudArchiveAfterExit(String id, Path versionRoot) {
        MindustryVersion version = get(id).orElse(null);
        if (version == null || !version.isCloudAutoSync()) {
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                MindustryCloudArchiveService service = new MindustryCloudArchiveService(
                        CommunityServices.cloudSaves(), version, versionRoot);
                var result = service.postExit();
                if (result.outcome() == Outcome.UPLOADED) {
                    Platform.runLater(() -> Controllers.showToast(
                            i18n("xenon.cloud.archive.uploaded")));
                } else if (result.outcome() == Outcome.CONFLICT) {
                    Platform.runLater(() -> Controllers.showToast(
                            i18n("xenon.cloud.archive.conflict")));
                }
            } catch (IOException e) {
                LOG.warning("Cloud archive post-exit sync failed", e);
                Platform.runLater(() -> Controllers.showToast(
                        i18n("xenon.cloud.archive.failed", MdtbbsMessages.describe(e))));
            }
        });
    }

    /// Builds launch options, materializing the save runtime as a side effect.
    ///
    /// <p>{@link MindustryLaunchService#buildLaunchOptions} validates the
    /// archive selection and runs {@link MindustryLaunchSaveService#prepare},
    /// so callers that write marker files or crash-recovery snapshots must
    /// build first: a rebuild after those writes would wipe them out.</p>
    ///
    /// @param repo repository that owns the instance
    /// @param version instance descriptor to launch
    /// @return immutable options for the client process
    /// @throws Exception when the jar, Java runtime, or save runtime cannot be prepared
    private static LaunchOptions buildClientOptions(XenonGameRepository repo,
                                                    MindustryVersion version) throws Exception {
        return MindustryLaunchService.buildLaunchOptions(repo, version,
                determination.xenon.mindustry.CurrentPlayerProfile.current());
    }

    /// Starts the client process for already-built launch options.
    ///
    /// @param version instance descriptor that owns the process
    /// @param versionRoot instance root directory used for event routing
    /// @param opts options returned by [#buildClientOptions]
    /// @throws Exception when the runtime registry cannot start the process
    private static void startClientProcess(MindustryVersion version, Path versionRoot,
                                           LaunchOptions opts) throws Exception {
        String id = version.getId();
        MindustryClientRuntimeRegistry.shared().launch(id, opts,
                event -> onClientEvent(versionRoot, event),
                line -> LOG.info("[mindustry] " + line),
                line -> LOG.warning("[mindustry] " + line));
    }

    /// Builds launch options and spawns the client process.
    private static void spawnClient(XenonGameRepository repo, MindustryVersion version,
                                    Path versionRoot) throws Exception {
        LaunchOptions opts = buildClientOptions(repo, version);
        startClientProcess(version, versionRoot, opts);
    }

    /// Offers safe mode or a raw launch after an interrupted previous run.
    private static void confirmCrashRecoveryLaunch(XenonGameRepository repo, MindustryVersion version,
                                                   Path versionRoot, Path effectiveDataDir) {
        Controllers.dialog(new MessageDialogPane.Builder(
                i18n("xenon.mindustry.modlist.crash.message"),
                i18n("xenon.mindustry.modlist.crash.title"),
                MessageDialogPane.MessageType.WARNING)
                .addAction(i18n("xenon.mindustry.modlist.crash.safe"),
                        () -> startSafeModeLaunch(repo, version, versionRoot, effectiveDataDir))
                .addAction(i18n("xenon.mindustry.modlist.crash.asis"),
                        () -> startRawLaunch(version, versionRoot, effectiveDataDir))
                .addCancel(null)
                .build());
    }

    /// Snapshots and disables every mod, then launches, so the game's own
    /// recovery never runs and the launcher keeps owning the mod state.
    private static void startSafeModeLaunch(XenonGameRepository repo, MindustryVersion version,
                                            Path versionRoot, Path effectiveDataDir) {
        Schedulers.io().execute(() -> {
            try {
                // Materialize the runtime before writing state: prepare() may
                // rebuild the data directory, which would discard a snapshot
                // (and re-extract a stale launchid.dat) written any earlier.
                LaunchOptions opts = buildClientOptions(repo, version);
                MindustryCrashRecovery.takeOver(effectiveDataDir);
                startClientProcess(version, versionRoot, opts);
                Platform.runLater(() -> Controllers.showToast(
                        i18n("xenon.mindustry.modlist.crash.safe.done")));
            } catch (Throwable ex) {
                LOG.warning("Mindustry crash-recovery safe mode failed", ex);
                Platform.runLater(() -> {
                    if (MindustryJavaMissingPrompt.showIfMissing(ex)) {
                        return;
                    }
                    Controllers.dialog(
                            i18n("xenon.mindustry.modlist.crash.safe.failed") + "\n\n" + ex.getMessage(),
                            i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                });
            }
        });
    }

    /// Launches with the current configuration after clearing the failed-launch
    /// marker, so the game does not mass-disable mods behind the user's back.
    private static void startRawLaunch(MindustryVersion version, Path versionRoot,
                                       Path effectiveDataDir) {
        Schedulers.io().execute(() -> {
            try {
                // Build first so a runtime rebuild cannot resurrect the
                // archive's launchid.dat after the marker was cleared.
                LaunchOptions opts = buildClientOptions(
                        MindustryImportFlow.repositoryForVersion(version), version);
                Files.deleteIfExists(effectiveDataDir.resolve(MindustryCrashRecovery.LAUNCH_MARKER_NAME));
                startClientProcess(version, versionRoot, opts);
            } catch (Throwable ex) {
                LOG.warning("Mindustry launch failed", ex);
                Platform.runLater(() -> {
                    if (MindustryJavaMissingPrompt.showIfMissing(ex)) {
                        return;
                    }
                    Controllers.dialog(
                            i18n("xenon.mindustry.launch.failed") + "\n\n" + ex.getMessage(),
                            i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                });
            }
        });
    }

    private static void onClientEvent(Path versionRoot, MindustryClientRuntimeRegistry.ClientEvent event) {
        if (event instanceof MindustryClientRuntimeRegistry.Started started) {
            LOG.info("Mindustry process started: id=" + started.id() + ", pid=" + started.pid());
            CommunityServices.presence().publishPlaying(started.id(), null, null);
            if (ACTIVE_SESSIONS.putIfAbsent(started.id(), started.pid()) == null) {
                openPlaytimeSession(versionRoot, started.id(), started.pid());
            }
        } else if (event instanceof MindustryClientRuntimeRegistry.AlreadyRunning alreadyRunning) {
            LOG.info("Ignored duplicate Mindustry launch request for " + alreadyRunning.id()
                    + ", pid=" + alreadyRunning.pid());
            Platform.runLater(() -> Controllers.showToast(i18n("xenon.mindustry.launch.already_running")));
        } else if (event instanceof MindustryClientRuntimeRegistry.Exited exited) {
            LOG.info("Mindustry process exited: id=" + exited.id() + ", pid=" + exited.pid()
                    + ", code=" + exited.exitCode());
            if (DEV_RESTARTS.remove(exited.id())) {
                // A development rebuild asked for this exit: keep the playtime
                // session and presence alive, skip cloud/backup processing, and
                // spawn the replacement without user-facing error dialogs.
                MindustryVersion version = get(exited.id()).orElse(null);
                if (version != null) {
                    Schedulers.io().execute(() -> {
                        try {
                            XenonGameRepository repo =
                                    MindustryImportFlow.repositoryForVersion(version);
                            spawnClient(repo, version, repo.getVersionRoot(version));
                        } catch (Throwable ex) {
                            LOG.warning("Development restart failed", ex);
                        }
                    });
                }
                return;
            }
            if (!exited.reloadExit()) {
                // Mindustry restarts itself after mod changes; the session stays
                // open until the replacement process exits for good.
                closePlaytimeSession(versionRoot, exited.id());
                syncCloudArchiveAfterExit(exited.id(), versionRoot);
            }
            CommunityServices.presence().clearPlaying();
            refreshOpenModPanes(exited.id());
            if (exited.exitCode() != 0) {
                Platform.runLater(() -> Controllers.dialog(new MessageDialogPane.Builder(
                                i18n("xenon.mindustry.launch.exited_abnormally", exited.exitCode())
                                        + formatRecentLines(exited.recentLines()),
                                i18n("message.error"), MessageDialogPane.MessageType.ERROR)
                        .addAction(i18n("xenon.mindustry.launch.open_log"),
                                () -> openLastLog(exited.dataDir(), exited.launchLogFile()))
                        .ok(null)
                        .build()));
            }
        } else if (event instanceof MindustryClientRuntimeRegistry.WindowlessProcessTerminated terminated) {
            LOG.warning("Terminated windowless Mindustry process: id=" + terminated.id()
                    + ", pid=" + terminated.pid());
            if (!terminated.relaunching()) {
                closePlaytimeSession(versionRoot, terminated.id());
            }
            Platform.runLater(() -> {
                if (terminated.relaunching()) {
                    Controllers.showToast(i18n("xenon.mindustry.launch.windowless.relaunching"));
                } else {
                    Controllers.dialog(
                            i18n("xenon.mindustry.launch.windowless") + formatRecentLines(terminated.recentLines()),
                            i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                }
            });
        } else if (event instanceof MindustryClientRuntimeRegistry.LaunchFailed failed) {
            LOG.warning("Mindustry runtime launch failed: id=" + failed.id(), failed.error());
            // A relaunch may fail before the replacement starts; close the
            // still-open session so it cannot absorb a later play run.
            closePlaytimeSession(versionRoot, failed.id());
        }
    }

    /// Records the start of one play session on the IO scheduler.
    ///
    /// @param versionRoot instance root that owns the playtime log
    /// @param id Mindustry instance id the session belongs to
    /// @param pid process id reported by the [MindustryClientRuntimeRegistry.Started] event
    private static void openPlaytimeSession(Path versionRoot, String id, long pid) {
        Schedulers.io().execute(() -> {
            try {
                new MindustryPlaytimeStore(versionRoot).recordStart(pid, System.currentTimeMillis());
            } catch (IOException ex) {
                LOG.warning("Failed to record Mindustry playtime start for " + id, ex);
            }
        });
    }

    /// Closes the open play session of `id` on the IO scheduler.
    ///
    /// Does nothing when no session is open and never throws.
    ///
    /// @param versionRoot instance root that owns the playtime log
    /// @param id Mindustry instance id whose session is closed
    private static void closePlaytimeSession(Path versionRoot, String id) {
        Long sessionPid = ACTIVE_SESSIONS.remove(id);
        if (sessionPid == null) {
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                new MindustryPlaytimeStore(versionRoot)
                        .recordEnd(sessionPid, System.currentTimeMillis());
            } catch (IOException ex) {
                LOG.warning("Failed to record Mindustry playtime end for " + id, ex);
            }
        });
    }

    private static void refreshOpenModPanes(String id) {
        CopyOnWriteArrayList<Runnable> listeners = MOD_PANE_REFRESH_LISTENERS.get(id);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        Platform.runLater(() -> {
            for (Runnable listener : listeners) {
                try {
                    listener.run();
                } catch (RuntimeException ex) {
                    LOG.warning("Mindustry mod pane refresh listener failed for " + id, ex);
                }
            }
        });
    }

    private static String formatRecentLines(List<String> recentLines) {
        if (recentLines.isEmpty()) {
            return "";
        }
        int start = Math.max(0, recentLines.size() - RECENT_LINE_DISPLAY_LIMIT);
        StringBuilder builder = new StringBuilder("\n\n")
                .append(i18n("xenon.mindustry.launch.recent_logs"));
        for (int i = start; i < recentLines.size(); i++) {
            builder.append('\n').append(recentLines.get(i));
        }
        return builder.toString();
    }

    /// Opens the best available log of the Mindustry process that just exited.
    ///
    /// The game's own `last_log.txt` is preferred; when the JVM died before
    /// Mindustry installed its file logger (or the file is missing), the
    /// launcher-captured output of this very launch is shown instead.
    private static void openLastLog(Path dataDir, @Nullable Path launchLogFile) {
        @Nullable Path log = MindustryLogFinder.resolveLog(dataDir, launchLogFile, null);
        if (log == null) {
            LOG.warning("Mindustry produced no log for data directory " + dataDir
                    + " (launcher log " + launchLogFile + ")");
            FXUtils.openFolder(dataDir);
            Controllers.showToast(i18n("xenon.mindustry.logs.last_log.missing"));
            return;
        }
        FXUtils.openFile(log);
    }

    /** Confirm + delete a Mindustry version (clears its data dir as well). */
    public static void deleteVersion(MindustryVersion version) {
        JFXButton confirm = new JFXButton(i18n("button.delete"));
        confirm.getStyleClass().add("dialog-error");
        confirm.setOnAction(e -> Schedulers.io().execute(() -> {
            try {
                MindustryImportFlow.repositoryForVersion(version).delete(version.getId());
                Platform.runLater(() -> Controllers.showToast(i18n("message.success")));
            } catch (Throwable ex) {
                LOG.warning("Failed to delete Mindustry version " + version.getId(), ex);
                Platform.runLater(() -> Controllers.dialog(ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        }));
        Controllers.confirmAction(
                i18n("xenon.mindustry.versions.delete.confirm", version.getName()),
                i18n("message.warning"), MessageDialogPane.MessageType.WARNING, confirm);
    }
}
