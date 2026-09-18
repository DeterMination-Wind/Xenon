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
import determination.xenon.mindustry.LaunchOptions;
import determination.xenon.mindustry.MindustryClientRuntimeRegistry;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryLaunchService;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.XenonGameRepository;
import determination.xenon.mindustry.XenonLauncher;
import determination.xenon.mindustry.modpack.XenonModpackPacker;
import determination.xenon.setting.Profile;
import determination.xenon.setting.Profiles;
import determination.xenon.task.FetchTask;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.MessageDialogPane;
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
                LaunchOptions opts = MindustryLaunchService.buildLaunchOptions(repo, version,
                        determination.xenon.mindustry.CurrentPlayerProfile.current());
                MindustryClientRuntimeRegistry.shared().launch(id, opts, MindustryRoutes::onClientEvent,
                        line -> LOG.info("[mindustry] " + line),
                        line -> LOG.warning("[mindustry] " + line));
            } catch (Throwable ex) {
                LOG.warning("Mindustry launch failed", ex);
                Platform.runLater(() -> Controllers.dialog(
                        i18n("xenon.mindustry.launch.failed") + "\n\n" + ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    private static void onClientEvent(MindustryClientRuntimeRegistry.ClientEvent event) {
        if (event instanceof MindustryClientRuntimeRegistry.Started started) {
            LOG.info("Mindustry process started: id=" + started.id() + ", pid=" + started.pid());
        } else if (event instanceof MindustryClientRuntimeRegistry.AlreadyRunning alreadyRunning) {
            LOG.info("Ignored duplicate Mindustry launch request for " + alreadyRunning.id()
                    + ", pid=" + alreadyRunning.pid());
            Platform.runLater(() -> Controllers.showToast(i18n("xenon.mindustry.launch.already_running")));
        } else if (event instanceof MindustryClientRuntimeRegistry.Exited exited) {
            LOG.info("Mindustry process exited: id=" + exited.id() + ", pid=" + exited.pid()
                    + ", code=" + exited.exitCode());
            refreshOpenModPanes(exited.id());
            if (exited.exitCode() != 0) {
                Path lastLog = exited.dataDir().resolve("last_log.txt");
                Platform.runLater(() -> Controllers.dialog(new MessageDialogPane.Builder(
                                i18n("xenon.mindustry.launch.exited_abnormally", exited.exitCode())
                                        + formatRecentLines(exited.recentLines()),
                                i18n("message.error"), MessageDialogPane.MessageType.ERROR)
                        .addAction(i18n("xenon.mindustry.launch.open_log"), () -> openLastLog(lastLog))
                        .ok(null)
                        .build()));
            }
        } else if (event instanceof MindustryClientRuntimeRegistry.WindowlessProcessTerminated terminated) {
            LOG.warning("Terminated windowless Mindustry process: id=" + terminated.id()
                    + ", pid=" + terminated.pid());
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
        }
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

    /// Opens the log written by the Mindustry process that just exited.
    private static void openLastLog(Path lastLog) {
        if (!Files.isRegularFile(lastLog)) {
            LOG.warning("Mindustry last log does not exist: " + lastLog);
            Controllers.showToast(i18n("xenon.mindustry.logs.last_log.missing"));
            return;
        }
        FXUtils.openFile(lastLog);
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
