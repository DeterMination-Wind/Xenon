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

import java.nio.file.Files;
import java.nio.file.Path;

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

    /** Cached repo so multiple flow invocations share the in-memory list. */
    private static volatile XenonGameRepository sharedRepo;

    /** Lazily created shared repository rooted at {@code <config>/versions}. */
    public static synchronized XenonGameRepository repository() {
        XenonGameRepository repo = sharedRepo;
        if (repo == null) {
            repo = new XenonGameRepository(Metadata.getVersionsDirectory());
            try {
                Files.createDirectories(Metadata.getVersionsDirectory());
            } catch (Exception ignored) {
                // Will surface again at scan/save; nothing to do here.
            }
            repo.refresh();
            sharedRepo = repo;
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

            XenonGameRepository repo = repository();
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
                            Profile profile = Profiles.getSelectedProfile();
                            if (profile != null) {
                                profile.setSelectedVersion(version.getId());
                                profile.getRepository().refreshVersionsAsync().start();
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
            XenonGameRepository repo = repository();
            if (repo.has(trimmed)) {
                handler.reject(i18n("xenon.mindustry.import.id.duplicate"));
                return;
            }
            handler.resolve();
            doImportAndLaunch(repo, file, trimmed);
        }, suggested);
    }

    private static void doImportAndLaunch(XenonGameRepository repo, Path jar, String id) {
        Schedulers.io().execute(() -> {
            try {
                MindustryVersion version = MindustryLaunchService.importLocalJar(repo, jar, id, null);
                MindustryRoutes.launch(version);
            } catch (Throwable ex) {
                LOG.warning("Mindustry import/launch failed", ex);
                Schedulers.javafx().execute(() -> Controllers.dialog(
                        i18n("xenon.mindustry.launch.failed") + "\n\n" + ex.getMessage(),
                        i18n("message.error"),
                        MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    private static String sanitizeId(String raw) {
        if (raw == null) return "mindustry";
        String s = raw.replaceAll("[^A-Za-z0-9._-]", "-");
        if (s.isEmpty()) return "mindustry";
        return s;
    }
}
