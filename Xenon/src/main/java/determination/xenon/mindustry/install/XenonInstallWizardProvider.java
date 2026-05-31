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
package determination.xenon.mindustry.install;

import determination.xenon.mindustry.DataDirectoryPolicy;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.VersionVariant;
import determination.xenon.mindustry.XenonGameRepository;
import determination.xenon.mindustry.download.GitHubReleaseClient;
import determination.xenon.mindustry.download.MindustryDownloadTask;
import determination.xenon.mindustry.download.MindustryRemoteVersion;
import determination.xenon.mindustry.mod.GitHubDirectInstaller;
import determination.xenon.mindustry.mod.MindustryModManager;
import determination.xenon.mindustry.mod.MindustryModsIndexRepository;
import determination.xenon.mindustry.mod.MindustryRemoteMod;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.ui.wizard.WizardController;
import determination.xenon.ui.wizard.WizardProvider;
import determination.xenon.util.SettingsMap;
import determination.xenon.util.io.FileUtils;
import javafx.scene.Node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/**
 * Drives the Xenon "install a new Mindustry version" flow:
 * <ol>
 *     <li>{@link VariantSelectionPage}</li>
 *     <li>{@link XenonVersionsPage}</li>
 *     <li>{@link IsolationPage}</li>
 *     <li>{@link PreloadModsPage} (terminal — calls {@link #finish})</li>
 * </ol>
 *
 * <p>The download itself runs as a {@link Task} so HMCL's existing
 * {@link determination.xenon.ui.wizard.TaskExecutorDialogWizardDisplayer}
 * can give us a progress bar + cancel button for free.</p>
 */
public final class XenonInstallWizardProvider implements WizardProvider {

    /** Pluggable community mod source for {@link PreloadModsPage}. */
    public interface ModIndexLoader {
        List<MindustryRemoteMod> load() throws IOException;
    }

    private final GitHubReleaseClient client;
    private final MindustryModsIndexRepository modIndex;
    private VersionVariant preseededVariant;

    public XenonInstallWizardProvider() {
        this.client = new GitHubReleaseClient(MindustryImportFlow.cachesDirectory());
        this.modIndex = new MindustryModsIndexRepository(MindustryImportFlow.cachesDirectory());
    }

    /**
     * Tell the wizard to skip the variant-selection page and use
     * {@code v} directly. Has to be called <em>after</em>
     * {@link determination.xenon.ui.decorator.DecoratorController#startWizard}
     * to take effect (the controller calls {@link #start} synchronously,
     * after which the first {@link #createPage} runs).
     */
    public void preseedVariant(VersionVariant v) {
        this.preseededVariant = v;
    }

    @Override
    public void start(SettingsMap settings) {
        if (preseededVariant != null) {
            settings.put(WizardKeys.VARIANT, preseededVariant);
        }
    }

    @Override
    public Node createPage(WizardController controller, int step, SettingsMap settings) {
        // When variant is pre-seeded, skip the picker page entirely:
        // step 0 → versions, step 1 → isolation, step 2 → preload mods.
        if (preseededVariant != null) {
            switch (step) {
                case 0: return new XenonVersionsPage(controller, client);
                case 1: return new IsolationPage(controller);
                case 2: return new PreloadModsPage(controller, modIndex::refresh);
                default: throw new IllegalStateException("Unexpected step " + step);
            }
        }
        switch (step) {
            case 0:
                return new VariantSelectionPage(controller);
            case 1:
                return new XenonVersionsPage(controller, client);
            case 2:
                return new IsolationPage(controller);
            case 3:
                return new PreloadModsPage(controller, modIndex::refresh);
            default:
                throw new IllegalStateException("Unexpected step " + step);
        }
    }

    @Override
    public Object finish(SettingsMap settings) {
        VersionVariant variant = settings.get(WizardKeys.VARIANT);
        MindustryRemoteVersion remote = settings.get(WizardKeys.REMOTE_VERSION);
        String id = settings.get(WizardKeys.VERSION_ID);
        DataDirectoryPolicy policy = settings.get(WizardKeys.DATA_DIR_POLICY);
        String customDir = settings.get(WizardKeys.CUSTOM_DATA_DIR);
        List<MindustryRemoteMod> preload = settings.get(PreloadModsPage.SELECTED_MODS);

        if (variant == null || remote == null || id == null || policy == null) {
            throw new IllegalStateException("Wizard finished with missing settings: "
                    + "variant=" + variant + ", remote=" + remote + ", id=" + id + ", policy=" + policy);
        }

        XenonGameRepository repo = MindustryImportFlow.repository();
        Path versionRoot = repo.getVersionRoot(id);
        Path jar = versionRoot.resolve(id + ".jar");

        // Stage 1: prepare the version directory and look for a matching
        // already-installed client jar before touching the network.
        Task<Path> prepareDir = Task.supplyAsync(Schedulers.io(), () -> {
            Files.createDirectories(versionRoot);
            return findReusableJar(repo, id, variant, remote);
        }).setName(i18n("xenon.install.task.prepare"));

        // Stage 2: download the jar via HMCL's Task pipeline so TaskListPane
        // shows a real progress bar + the per-second speed indicator.
        MindustryDownloadTask download = new MindustryDownloadTask(
                remote.getDownloadUrl(), jar, remote.getSize(),
                MindustryImportFlow.cachesDirectory());
        download.setName(i18n("xenon.install.task.download", id));

        Task<Void> installJar = prepareDir.thenComposeAsync(Schedulers.io(), reusableJar -> {
            if (reusableJar == null) {
                return download;
            }
            return Task.runAsync(Schedulers.io(), () -> {
                FileUtils.copyFile(reusableJar, jar);
                LOG.info("Reused installed Mindustry client " + reusableJar
                        + " for new instance " + id);
            }).setName(i18n("xenon.install.task.prepare"));
        });

        // Stage 3: persist version.json — runs in parallel with installJar
        // (only needs the directory from prepareDir, not the downloaded jar).
        Task<Void> save = prepareDir.thenRunAsync(Schedulers.io(), () -> {
            MindustryVersion v = new MindustryVersion();
            v.setId(id);
            v.setName(id);
            v.setVariant(variant);
            v.setBuild(remote.getBuild());
            v.setBuildType(remote.getBuildType());
            v.setJarPath(id + ".jar");
            v.setJavaReq(remote.getBuild() > 0 && remote.getBuild() < 140 ? 8 : 17);
            v.setDataDirPolicy(policy);
            if (policy == DataDirectoryPolicy.CUSTOM && customDir != null) {
                v.setCustomDataDir(customDir);
            }
            repo.save(v);
            LOG.info("Xenon installed " + variant + " build=" + remote.getBuild() + " as id=" + id);
        }).setName(i18n("xenon.install.task.save"));

        // Stage 4 (optional): pre-install community mods — runs after save
        // (needs repo.get(id) to resolve the data directory), in parallel with
        // installJar. Each mod is a separate child task so TaskListPane shows
        // individual download progress.
        Task<Void> preloadStage = save.thenRunAsync(Schedulers.io(), () -> {
            if (preload == null || preload.isEmpty()) return;
            MindustryVersion v = repo.get(id).orElseThrow();
            Path dataDir = v.resolveDataDir(versionRoot);
            Path modsDir = dataDir.resolve("mods");
            Files.createDirectories(modsDir);
            MindustryModManager target = new MindustryModManager(modsDir);
            GitHubDirectInstaller installer = new GitHubDirectInstaller(client, target);
            for (MindustryRemoteMod mod : preload) {
                if (mod.getRepo() == null || mod.getRepo().isBlank()) continue;
                try {
                    installer.installLatest(mod.getRepo(), null);
                    LOG.info("Pre-installed mod " + mod.getRepo() + " into " + modsDir);
                } catch (Throwable ex) {
                    LOG.warning("Failed to pre-install " + mod.getRepo() + ": " + ex.getMessage());
                }
            }
        }).setName(i18n("xenon.install.task.preload"));

        // installJar and preloadStage run in parallel off prepareDir;
        // whenComplete fires after both branches finish.
        return Task.allOf(installJar, preloadStage)
                .whenComplete(any -> {
                    // Kick the HMCL versions listener so MainPage / sidebar
                    // re-runs its merge of HMCL + XenonGameRepository and the
                    // newly installed Mindustry instance shows up immediately
                    // (without it the launch button stays in "no game" state
                    // until the user navigates somewhere that refreshes).
                    determination.xenon.setting.Profile p =
                            determination.xenon.setting.Profiles.getSelectedProfile();
                    if (p != null) {
                        p.getRepository().refreshVersionsAsync().start();
                    }
                })
                .setName(i18n("xenon.install.task.title"));
    }

    @Override
    public boolean cancel() {
        return true;
    }

    /// Returns an existing local jar with the same variant/build/channel as the selected remote row.
    private static Path findReusableJar(XenonGameRepository repo, String newId,
                                        VersionVariant variant,
                                        MindustryRemoteVersion remote) {
        repo.refresh();
        for (MindustryVersion candidate : repo.all()) {
            if (Objects.equals(candidate.getId(), newId)) continue;
            if (candidate.getVariant() != variant) continue;
            if (candidate.getBuild() != remote.getBuild()) continue;
            if (!Objects.equals(candidate.getBuildType(), remote.getBuildType())) continue;

            Path root = repo.getVersionRoot(candidate.getId());
            Path candidateJar = candidate.resolveJar(root);
            if (Files.isRegularFile(candidateJar) && FileUtils.size(candidateJar) > 0) {
                return candidateJar;
            }
        }
        return null;
    }
}
