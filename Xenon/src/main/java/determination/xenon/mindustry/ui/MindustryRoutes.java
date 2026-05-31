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
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryLaunchService;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.XenonGameRepository;
import determination.xenon.mindustry.XenonLauncher;
import determination.xenon.mindustry.modpack.XenonModpackPacker;
import determination.xenon.task.FetchTask;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.util.TaskCancellationAction;
import determination.xenon.util.io.FileUtils;
import javafx.application.Platform;
import javafx.stage.FileChooser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CancellationException;

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
public final class MindustryRoutes {
    private static final java.util.Set<String> LAUNCHING_IDS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private MindustryRoutes() {}

    /**
     * True iff {@code id} matches a Mindustry version registered in the
     * shared {@link XenonGameRepository}. Falls back to a disk check so
     * we still recognise the id during the small window between
     * {@code XenonGameRepository.save} writing the manifest and the
     * FX-thread refresh seeing it (which is exactly when the user
     * lands on the version-management page from the install wizard).
     *
     * <p>The disk fallback accepts both manifest layouts the launcher
     * has used historically: {@code <id>/version.json} (current) and
     * {@code <id>/<id>.json} (older Xenon installs).</p>
     */
    public static boolean isMindustry(String id) {
        if (id == null || id.isBlank()) return false;
        XenonGameRepository repo = MindustryImportFlow.repository();
        if (repo.has(id)) return true;
        Path versionRoot = repo.getVersionRoot(id);
        Path canonical = versionRoot.resolve("version.json");
        Path legacy = versionRoot.resolve(id + ".json");
        if (Files.isRegularFile(canonical) || Files.isRegularFile(legacy)) {
            repo.refresh();
            return repo.has(id);
        }
        return false;
    }

    public static Optional<MindustryVersion> get(String id) {
        if (id == null) return Optional.empty();
        return MindustryImportFlow.repository().get(id);
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
                XenonModpackPacker.pack(MindustryImportFlow.repository(), version, output,
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

    /** Spawn the Mindustry process for {@code id} via {@link XenonLauncher}. */
    public static void launch(MindustryVersion version) {
        String id = version.getId();
        if (!LAUNCHING_IDS.add(id)) {
            LOG.info("Ignored duplicate Mindustry launch request for " + id);
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                XenonGameRepository repo = MindustryImportFlow.repository();
                LaunchOptions opts = MindustryLaunchService.buildLaunchOptions(repo, version,
                        determination.xenon.mindustry.CurrentPlayerProfile.current());
                XenonLauncher.MindustryProcess proc = XenonLauncher.launch(opts,
                        line -> LOG.info("[mindustry] " + line),
                        line -> LOG.warning("[mindustry] " + line));
                LOG.info("Mindustry process started: pid=" + proc.getProcess().pid());
                proc.getProcess().onExit().thenRun(() -> LAUNCHING_IDS.remove(id));
            } catch (Throwable ex) {
                LAUNCHING_IDS.remove(id);
                LOG.warning("Mindustry launch failed", ex);
                Platform.runLater(() -> Controllers.dialog(
                        i18n("xenon.mindustry.launch.failed") + "\n\n" + ex.getMessage(),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    /** Confirm + delete a Mindustry version (clears its data dir as well). */
    public static void deleteVersion(MindustryVersion version) {
        JFXButton confirm = new JFXButton(i18n("button.delete"));
        confirm.getStyleClass().add("dialog-error");
        confirm.setOnAction(e -> Schedulers.io().execute(() -> {
            try {
                MindustryImportFlow.repository().delete(version.getId());
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
