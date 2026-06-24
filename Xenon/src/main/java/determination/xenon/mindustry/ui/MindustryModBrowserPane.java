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
import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXTextField;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.XenonGameRepository;
import determination.xenon.mindustry.download.GitHubAsset;
import determination.xenon.mindustry.download.HighStarModCache;
import determination.xenon.mindustry.download.GitHubRelease;
import determination.xenon.mindustry.download.GitHubReleaseClient;
import determination.xenon.mindustry.download.MirrorDownloader;
import determination.xenon.mindustry.mod.GitHubDirectInstaller;
import determination.xenon.mindustry.mod.MindustryModManager;
import determination.xenon.mindustry.mod.MindustryModsIndexRepository;
import determination.xenon.mindustry.mod.MindustryRemoteMod;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.task.TaskExecutor;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.ui.construct.PageAware;
import determination.xenon.ui.construct.RipplerContainer;
import determination.xenon.ui.construct.TwoLineListItem;
import determination.xenon.util.TaskCancellationAction;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static determination.xenon.ui.FXUtils.newToggleButton4;
import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/**
 * HMCL-style browser for the {@code Anuken/MindustryMods} community
 * index.
 *
 * <p>Cache-first: on construction, we synchronously load the on-disk
 * cache (if any) so the user sees a populated list immediately, then
 * fire an async refresh in the background. A successful refresh swaps
 * the list contents in-place; a failed refresh leaves the cached list
 * visible and surfaces the error in the status label.</p>
 *
 * <p>Rows render as {@link TwoLineListItem} cards inside a
 * {@link JFXListView} for the same look-and-feel HMCL ships.</p>
 */
public final class MindustryModBrowserPane extends BorderPane implements PageAware {

    private static final DateTimeFormatter DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd")
            .withZone(ZoneId.systemDefault());

    private final MindustryModsIndexRepository repo =
            new MindustryModsIndexRepository(MindustryImportFlow.cachesDirectory());
    private final GitHubReleaseClient client =
            new GitHubReleaseClient(MindustryImportFlow.cachesDirectory());

    private final JFXTextField search = new JFXTextField();
    private final ComboBox<MindustryVersion> targetVersion = new ComboBox<>();
    private final Label status = new Label();
    private final JFXListView<MindustryRemoteMod> listView = new JFXListView<>();

    private final ObservableList<MindustryRemoteMod> all = FXCollections.observableArrayList();

    public MindustryModBrowserPane() {
        setPadding(new Insets(12));

        // ---- header / toolbar ----
        Label title = new Label(i18n("xenon.mindustry.mod.browser.title"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.mindustry.mod.browser.hint"));
        hint.setWrapText(true);

        search.setPromptText(i18n("xenon.mindustry.mod.browser.search"));
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().addListener((obs, o, n) -> rebuildList());

        // Pick up HMCL's JFoenix combo-box look without using JFXComboBox —
        // JFXComboBox crashes on JavaFX 25 (arrowButtonHandle == null in
        // its skin). The HMCL CSS keys off this style class.
        targetVersion.getStyleClass().add("jfx-combo-box");
        targetVersion.setPromptText(i18n("xenon.mindustry.mod.browser.target"));
        targetVersion.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(MindustryVersion v) {
                return v == null ? "" : (v.getName() == null ? v.getId() : v.getName());
            }

            @Override
            public MindustryVersion fromString(String s) {
                return null;
            }
        });

        JFXButton refresh = FXUtils.newRaisedButton(i18n("xenon.mindustry.mod.browser.refresh"));
        refresh.setOnAction(e -> reloadAsync());

        HBox toolbar = new HBox(8, search, targetVersion, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, title, hint, toolbar, status);
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);

        // ---- list ----
        listView.getStyleClass().add("no-padding");
        listView.setItems(FXCollections.observableArrayList());
        listView.setCellFactory(lv -> new ModCell());
        // Disable horizontal scrolling: cells must fit the viewport
        // width, with title/subtitle Labels shrinking + ellipsising
        // instead of pushing a horizontal scrollbar.
        listView.lookupAll(".scroll-bar").forEach(n -> {
            if (n instanceof javafx.scene.control.ScrollBar
                    && ((javafx.scene.control.ScrollBar) n).getOrientation()
                            == javafx.geometry.Orientation.HORIZONTAL) {
                n.setVisible(false);
                n.setManaged(false);
            }
        });
        // The lookup above misses the bar before the skin is built; do it
        // again on the first layout pass via skinProperty.
        listView.skinProperty().addListener((obs, o, n) -> {
            if (n != null) {
                javafx.application.Platform.runLater(() -> {
                    for (javafx.scene.Node sb : listView.lookupAll(".scroll-bar")) {
                        if (sb instanceof javafx.scene.control.ScrollBar
                                && ((javafx.scene.control.ScrollBar) sb).getOrientation()
                                        == javafx.geometry.Orientation.HORIZONTAL) {
                            sb.setVisible(false);
                            sb.setManaged(false);
                        }
                    }
                });
            }
        });
        setCenter(listView);

        refreshTargetVersions();

        // Cache-first: synchronous load so the list isn't empty during
        // the async refresh; falls back to "loading..." if cache miss.
        List<MindustryRemoteMod> cached = repo.loadCache();
        if (!cached.isEmpty()) {
            all.setAll(cached);
            status.setText(i18n("xenon.mindustry.mod.browser.cache.loaded", cached.size()));
            rebuildList();
        } else {
            status.setText(i18n("xenon.mindustry.mod.browser.loading"));
        }
        // Fire-and-forget refresh — never blocks the FX thread.
        reloadAsync();
    }

    // ------------------------------------------------------------------
    // Data refresh
    // ------------------------------------------------------------------

    private void refreshTargetVersions() {
        XenonGameRepository xrepo = MindustryImportFlow.repository();
        xrepo.refresh();
        List<MindustryVersion> versions = new ArrayList<>(xrepo.all());
        // Preserve the user's selection across reloads (selectFirst() only
        // when nothing was selected before — otherwise re-pick by id).
        MindustryVersion previous = targetVersion.getSelectionModel().getSelectedItem();
        targetVersion.setItems(FXCollections.observableArrayList(versions));
        if (versions.isEmpty()) return;
        if (previous != null) {
            for (MindustryVersion v : versions) {
                if (v.getId().equals(previous.getId())) {
                    targetVersion.getSelectionModel().select(v);
                    return;
                }
            }
        }
        targetVersion.getSelectionModel().selectFirst();
    }

    @Override
    public void onPageShown() {
        // The browser pane is cached by TabHeader, so its constructor only
        // ran once. Re-pull the Mindustry instance list every time the tab
        // becomes visible — otherwise users who install a Mindustry version
        // *after* opening the download page see an empty target dropdown.
        refreshTargetVersions();
    }

    private void reloadAsync() {
        // Don't blow away the cached rows — leave them visible while we
        // refresh, swap atomically when the new payload arrives.
        boolean haveExisting = !all.isEmpty();
        if (!haveExisting) {
            status.setText(i18n("xenon.mindustry.mod.browser.loading"));
        } else {
            status.setText(i18n("xenon.mindustry.mod.browser.refreshing", all.size()));
        }
        Schedulers.io().execute(() -> {
            try {
                List<MindustryRemoteMod> mods = repo.refresh();
                Platform.runLater(() -> {
                    all.setAll(mods);
                    status.setText(i18n("xenon.mindustry.mod.browser.count", mods.size()));
                    rebuildList();
                });
            } catch (Throwable ex) {
                LOG.warning("Failed to refresh mindustry-mods index", ex);
                Platform.runLater(() -> {
                    if (haveExisting) {
                        // Keep the cached list, just note the refresh failed.
                        status.setText(i18n("xenon.mindustry.mod.browser.refresh.failed",
                                all.size(), ex.getMessage()));
                    } else {
                        status.setText(i18n("xenon.mindustry.mod.browser.failed")
                                + " " + ex.getMessage());
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // List rendering / filtering
    // ------------------------------------------------------------------

    /**
     * Repos that should always sort first when the search box is empty.
     * The user keeps these four mods on every Mindustry instance, so we
     * front-load them to save a search every time. Order here defines the
     * pinned order in the list. Match is case-insensitive on full
     * {@code owner/repo}; entries that aren't in the upstream index are
     * silently skipped (so adding a not-yet-indexed mod here won't break
     * the list — it just stays unpinned until the index picks it up).
     */
    private static final List<String> PINNED_REPOS = List.of(
            "determination-wind/neon",
            "blackdeluxecat/mi2-utilities-java",
            "dustdustry/dpsheatmap",
            "dustdustry/patcheditor"
    );

    private void rebuildList() {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        List<MindustryRemoteMod> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> Integer.compare(b.getStars(), a.getStars()));

        // Pinning only applies when the user isn't actively searching —
        // a query already implies "show me what matches", overriding the
        // editorial pin order.
        boolean searching = !q.isEmpty();
        List<MindustryRemoteMod> pinnedHits = new ArrayList<>();
        if (!searching) {
            for (String pin : PINNED_REPOS) {
                MindustryRemoteMod hit = findByRepo(sorted, pin);
                if (hit != null) {
                    pinnedHits.add(hit);
                    sorted.remove(hit);
                }
            }
        }

        List<MindustryRemoteMod> visible = new ArrayList<>(pinnedHits);
        for (MindustryRemoteMod mod : sorted) {
            if (!matches(mod, q)) continue;
            visible.add(mod);
            if (visible.size() >= 200) break;
        }
        listView.getItems().setAll(visible);
    }

    /** Case-insensitive {@code owner/repo} lookup. */
    private static MindustryRemoteMod findByRepo(List<MindustryRemoteMod> mods, String ownerRepo) {
        for (MindustryRemoteMod m : mods) {
            if (m.getRepo() != null && m.getRepo().equalsIgnoreCase(ownerRepo)) {
                return m;
            }
        }
        return null;
    }

    private static boolean matches(MindustryRemoteMod mod, String q) {
        if (q.isEmpty()) return true;
        if (contains(mod.displayName(), q)) return true;
        if (contains(mod.getRepo(), q)) return true;
        if (contains(mod.getAuthor(), q)) return true;
        if (contains(mod.getDescription(), q)) return true;
        return false;
    }

    private static boolean contains(String s, String q) {
        return s != null && s.toLowerCase(Locale.ROOT).contains(q);
    }

    // ------------------------------------------------------------------
    // Install
    // ------------------------------------------------------------------

    private void install(MindustryRemoteMod mod) {
        installInternal(mod, null);
    }

    /**
     * "Install an older version" entry point — fetches the recent
     * releases for {@code mod}, lets the user pick one, then runs the
     * normal instance-picker → download pipeline against that specific
     * release.
     */
    private void installHistorical(MindustryRemoteMod mod) {
        if (mod.getRepo() == null || mod.getRepo().isBlank()) {
            Controllers.dialog("This mod has no GitHub repo to download from.",
                    i18n("message.error"), MessageDialogPane.MessageType.ERROR);
            return;
        }
        String label = mod.displayName();
        Controllers.showToast(i18n("xenon.mindustry.mod.browser.history.loading", label));
        Schedulers.io().execute(() -> {
            try {
                List<GitHubRelease> releases = client.listReleases(mod.getRepo(), 30);
                Platform.runLater(() -> {
                    if (releases == null || releases.isEmpty()) {
                        Controllers.dialog(
                                i18n("xenon.mindustry.mod.browser.history.empty", label),
                                i18n("message.warning"),
                                MessageDialogPane.MessageType.WARNING);
                        return;
                    }
                    showReleasePickerDialog(mod, releases);
                });
            } catch (Throwable ex) {
                LOG.warning("Failed to list releases for " + mod.getRepo(), ex);
                Platform.runLater(() -> Controllers.dialog(
                        i18n("xenon.mindustry.mod.browser.history.failed", label,
                                ex.getMessage() == null ? ex.toString() : ex.getMessage()),
                        i18n("message.error"),
                        MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    private void installInternal(MindustryRemoteMod mod, GitHubRelease release) {
        if (mod.getRepo() == null || mod.getRepo().isBlank()) {
            Controllers.dialog("This mod has no GitHub repo to download from.",
                    i18n("message.error"), MessageDialogPane.MessageType.ERROR);
            return;
        }
        XenonGameRepository xrepo = MindustryImportFlow.repository();
        xrepo.refresh();
        List<MindustryVersion> instances = new ArrayList<>(xrepo.all());
        if (instances.isEmpty()) {
            Controllers.dialog(i18n("xenon.mindustry.mod.browser.target.none"),
                    i18n("message.error"), MessageDialogPane.MessageType.WARNING);
            return;
        }
        showInstancePickerDialog(mod, instances, release);
    }

    /**
     * Modal dialog with a {@link JFXListView} of every release returned
     * by GitHub for this repo (newest first). Tag + publish date in the
     * row, "(prerelease)" suffix when applicable.
     */
    private void showReleasePickerDialog(MindustryRemoteMod mod, List<GitHubRelease> releases) {
        com.jfoenix.controls.JFXDialogLayout layout = new com.jfoenix.controls.JFXDialogLayout();
        layout.setHeading(new Label(i18n("xenon.mindustry.mod.browser.history.title", mod.displayName())));

        com.jfoenix.controls.JFXListView<GitHubRelease> list = new com.jfoenix.controls.JFXListView<>();
        list.setItems(FXCollections.observableArrayList(releases));
        list.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(GitHubRelease r, boolean empty) {
                super.updateItem(r, empty);
                if (empty || r == null) { setText(null); return; }
                StringBuilder sb = new StringBuilder();
                sb.append(r.getTagName() == null ? r.getName() : r.getTagName());
                if (r.getPublishedAt() != null) {
                    sb.append("  ·  ").append(DATE.format(r.getPublishedAt()));
                }
                if (r.isPrerelease()) sb.append("  (prerelease)");
                setText(sb.toString());
            }
        });
        list.getSelectionModel().selectFirst();
        list.setPrefHeight(360);

        VBox body = new VBox(8,
                new Label(i18n("xenon.mindustry.mod.browser.history.hint")),
                list);
        body.setPadding(new Insets(4, 0, 0, 0));
        layout.setBody(body);

        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.setOnAction(e -> layout.fireEvent(
                new determination.xenon.ui.construct.DialogCloseEvent()));
        JFXButton next = new JFXButton(i18n("xenon.mindustry.mod.browser.history.pick"));
        next.getStyleClass().add("dialog-accept");
        next.setDefaultButton(true);
        next.disableProperty().bind(list.getSelectionModel()
                .selectedItemProperty().isNull());
        next.setOnAction(e -> {
            GitHubRelease picked = list.getSelectionModel().getSelectedItem();
            layout.fireEvent(new determination.xenon.ui.construct.DialogCloseEvent());
            if (picked != null) installInternal(mod, picked);
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && list.getSelectionModel().getSelectedItem() != null) {
                next.fire();
            }
        });

        layout.setActions(cancel, next);
        Controllers.dialog(layout);
    }

    /**
     * Modal dialog asking which Mindustry instance the mod should be
     * installed into. Defaults to the toolbar selection so single-instance
     * users still get a one-click flow (Confirm + Enter to accept).
     */
    private void showInstancePickerDialog(MindustryRemoteMod mod, List<MindustryVersion> instances,
                                          GitHubRelease specificRelease) {
        com.jfoenix.controls.JFXDialogLayout layout = new com.jfoenix.controls.JFXDialogLayout();
        String headingKey = specificRelease == null
                ? "xenon.mindustry.mod.browser.pick.title"
                : "xenon.mindustry.mod.browser.pick.title.version";
        String tag = specificRelease == null ? "" :
                (specificRelease.getTagName() == null ? specificRelease.getName() : specificRelease.getTagName());
        layout.setHeading(new Label(specificRelease == null
                ? i18n(headingKey, mod.displayName())
                : i18n(headingKey, mod.displayName(), tag)));

        com.jfoenix.controls.JFXListView<MindustryVersion> instanceList =
                new com.jfoenix.controls.JFXListView<>();
        instanceList.setItems(FXCollections.observableArrayList(instances));
        instanceList.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(MindustryVersion v, boolean empty) {
                super.updateItem(v, empty);
                if (empty || v == null) { setText(null); return; }
                String name = v.getName() == null ? v.getId() : v.getName();
                String channel = v.getVariant() == null ? "" : v.getVariant().name();
                setText(name + (channel.isEmpty() ? "" : "  ·  " + channel));
            }
        });
        // Pre-select whatever the toolbar showed; users who didn't touch the
        // toolbar still get a sensible default.
        MindustryVersion preselect = targetVersion.getSelectionModel().getSelectedItem();
        if (preselect != null) instanceList.getSelectionModel().select(preselect);
        else instanceList.getSelectionModel().selectFirst();
        instanceList.setPrefHeight(Math.min(360, 44 * Math.max(2, instances.size()) + 12));

        VBox body = new VBox(8,
                new Label(i18n("xenon.mindustry.mod.browser.pick.hint")),
                instanceList);
        body.setPadding(new Insets(4, 0, 0, 0));
        layout.setBody(body);

        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.setOnAction(e -> layout.fireEvent(
                new determination.xenon.ui.construct.DialogCloseEvent()));
        JFXButton ok = new JFXButton(i18n("xenon.mindustry.mod.browser.install"));
        ok.getStyleClass().add("dialog-accept");
        ok.setDefaultButton(true);
        ok.disableProperty().bind(instanceList.getSelectionModel()
                .selectedItemProperty().isNull());
        ok.setOnAction(e -> {
            MindustryVersion picked = instanceList.getSelectionModel().getSelectedItem();
            layout.fireEvent(new determination.xenon.ui.construct.DialogCloseEvent());
            if (picked != null) {
                // Sync the toolbar dropdown so a follow-up install on the
                // same row defaults to the same target.
                targetVersion.getSelectionModel().select(picked);
                runInstallPipeline(mod, picked, specificRelease);
            }
        });
        // Double-click a row = pick + confirm.
        instanceList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && instanceList.getSelectionModel().getSelectedItem() != null) {
                ok.fire();
            }
        });

        layout.setActions(cancel, ok);
        Controllers.dialog(layout);
    }

    /** Build and start the three-stage install task pipeline. */
    private void runInstallPipeline(MindustryRemoteMod mod, MindustryVersion target) {
        runInstallPipeline(mod, target, null);
    }

    /**
     * Same as {@link #runInstallPipeline(MindustryRemoteMod, MindustryVersion)}
     * but installs a specific past release instead of the latest. {@code
     * specificRelease == null} means "use whatever /releases/latest
     * returns" (the default behaviour).
     */
    private void runInstallPipeline(MindustryRemoteMod mod, MindustryVersion target,
                                    GitHubRelease specificRelease) {
        String label = mod.displayName();
        String ownerRepo = mod.getRepo();

        // Resolve target dirs eagerly — they don't need a Task wrapper and
        // we want any IO failure here to surface synchronously.
        XenonGameRepository xrepo = MindustryImportFlow.repository();
        Path versionRoot = xrepo.getVersionRoot(target.getId());
        Path dataDir = target.resolveDataDir(versionRoot);
        Path modsDir = dataDir.resolve("mods");

        // Stage 1: hit GitHub /releases/latest, pick the right asset, and
        // hand off (asset, stagingPath) to the download stage. Wrapped in a
        // Task so the dialog can show "Resolving..." before the bar starts.
        Task<AssetPick> resolve = Task.supplyAsync(Schedulers.io(), () -> {
            Files.createDirectories(modsDir);
            GitHubRelease release = specificRelease != null
                    ? specificRelease
                    : client.getLatestRelease(ownerRepo);
            if (release == null) {
                throw new IOException("No published releases for " + ownerRepo);
            }
            GitHubAsset asset = GitHubDirectInstaller.pickAsset(release.getAssets());
            if (asset == null) {
                throw new IOException("No installable .zip/.jar asset in "
                        + ownerRepo + " release " + release.getTagName());
            }
            String directDownloadUrl = asset.getDownloadUrl();
            String primaryDownloadUrl = directDownloadUrl;
            if (HighStarModCache.shouldTryCacheFirst(mod.getStars(), directDownloadUrl)) {
                String cacheUrl = HighStarModCache.toCacheUrl(directDownloadUrl);
                if (cacheUrl != null) {
                    primaryDownloadUrl = cacheUrl;
                }
            }
            String assetName = asset.getName();
            String ext = assetName.toLowerCase(Locale.ROOT).endsWith(".jar") ? ".jar" : ".zip";
            String safeRepo = ownerRepo.replace('/', '_').replaceAll("[^A-Za-z0-9._-]", "_");
            Path staging = Path.of(System.getProperty("java.io.tmpdir", "."),
                    "xenon-mod-" + safeRepo + "-" + System.nanoTime() + ext);
            return new AssetPick(asset, staging, primaryDownloadUrl, directDownloadUrl);
        }).setName(i18n("xenon.mindustry.mod.browser.task.resolve", label));

        // Stage 2: high-star mods try the 121 cache first when the chosen
        // asset is a canonical GitHub release URL, then fall back to the
        // usual MirrorDownloader GitHub race. Everything else keeps the
        // existing mirror-racing path unchanged.
        Task<Path> download = resolve.thenComposeAsync(Schedulers.io(), pick ->
                new ModDownloadTask(pick, label));

        // Stage 3: copy the downloaded archive into <dataDir>/mods/, then
        // delete the staging file regardless of outcome.
        Task<Void> install = download.thenComposeAsync(Schedulers.io(), staging ->
                Task.runAsync(Schedulers.io(), () -> {
                    try {
                        new MindustryModManager(modsDir).install(staging);
                    } finally {
                        try { Files.deleteIfExists(staging); } catch (IOException ignored) {}
                    }
                }).setName(i18n("xenon.mindustry.mod.browser.task.install", label)));

        // Toast on success (with the destination so the user knows where it
        // landed) / dialog on failure.
        String targetName = target.getName() == null ? target.getId() : target.getName();
        Task<Void> pipeline = install.whenComplete(Schedulers.javafx(), ex -> {
            if (ex == null) {
                Controllers.showToast(i18n("xenon.mindustry.mod.browser.installed.into",
                        label, targetName));
            } else {
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                LOG.warning("Failed to install mod " + ownerRepo + " into " + target.getId(), ex);
                Controllers.dialog(
                        i18n("xenon.mindustry.mod.browser.install.failed", label, msg),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR);
            }
        }).setName(i18n("xenon.mindustry.mod.browser.installing", label));

        TaskExecutor executor = pipeline.executor();
        // TaskCancellationAction.NORMAL is a no-op runnable, which means
        // pressing Cancel calls executor.cancel() but never closes the
        // dialog. MirrorDownloader.downloadStream blocks inside
        // http.send(); HttpClient ignores interrupts, so the task stays
        // alive and onStop never fires. Close the dialog explicitly so
        // the user gets out immediately — the in-flight HTTP read can
        // finish on its own thread, the finally block deletes staging.
        TaskCancellationAction cancel = new TaskCancellationAction(it ->
                it.fireEvent(new determination.xenon.ui.construct.DialogCloseEvent()));
        Controllers.taskDialog(executor,
                i18n("xenon.mindustry.mod.browser.installing.into", label, targetName),
                cancel);
        executor.start();
    }

    /** Tuple returned by the resolve stage and consumed by the download stage. */
    private static final class AssetPick {
        final GitHubAsset asset;
        final Path staging;
        final String primaryDownloadUrl;
        final String fallbackDownloadUrl;

        AssetPick(GitHubAsset asset, Path staging, String primaryDownloadUrl, String fallbackDownloadUrl) {
            this.asset = asset;
            this.staging = staging;
            this.primaryDownloadUrl = primaryDownloadUrl;
            this.fallbackDownloadUrl = fallbackDownloadUrl;
        }
    }

    /**
     * Reuses {@link MirrorDownloader} so the mod download tab shares its
     * mirror race + per-second speed counter with the Mindustry-jar
     * installer, and reports progress through HMCL's Task system so the
     * task dialog draws a determinate bar.
     */
    private final class ModDownloadTask extends Task<Path> {
        private final GitHubAsset asset;
        private final Path target;
        private final String primaryDownloadUrl;
        private final String fallbackDownloadUrl;

        ModDownloadTask(AssetPick pick, String label) {
            this.asset = pick.asset;
            this.target = pick.staging;
            this.primaryDownloadUrl = pick.primaryDownloadUrl;
            this.fallbackDownloadUrl = pick.fallbackDownloadUrl;
            setName(i18n("xenon.mindustry.mod.browser.task.download",
                    label, asset.getName()));
        }

        @Override
        public void execute() throws Exception {
            MirrorDownloader downloader = new MirrorDownloader(MindustryImportFlow.cachesDirectory());
            try {
                downloadAndVerify(downloader, primaryDownloadUrl);
            } catch (IOException primaryFailure) {
                if (Objects.equals(primaryDownloadUrl, fallbackDownloadUrl)) {
                    throw primaryFailure;
                }
                LOG.warning("High-star mod cache download failed for "
                        + asset.getName() + " via " + primaryDownloadUrl
                        + ": " + primaryFailure.getMessage()
                        + " — falling back to mirror-race GitHub path",
                        primaryFailure);
                Files.deleteIfExists(target);
                downloadAndVerify(downloader, fallbackDownloadUrl);
            }
            setResult(target);
        }

        private void downloadAndVerify(MirrorDownloader downloader, String sourceUrl) throws IOException {
            downloader.download(sourceUrl, target, asset.getSize(), (read, total) -> {
                if (total > 0 && read >= 0) {
                    updateProgress(Math.min(read, total), total);
                }
            });
            if (asset.getSize() > 0) {
                long actualSize = Files.size(target);
                if (actualSize != asset.getSize()) {
                    throw new IOException("Size mismatch downloading " + asset.getName()
                            + " from " + sourceUrl + ": expected "
                            + asset.getSize() + " bytes but got " + actualSize);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Cell — HMCL-style two-line card with tags + install action
    // ------------------------------------------------------------------

    private final class ModCell extends ListCell<MindustryRemoteMod> {
        private final TwoLineListItem twoLine = new TwoLineListItem();
        private final StackPane pane = new StackPane();
        private final JFXButton installBtn = FXUtils.newRaisedButton(
                i18n("xenon.mindustry.mod.browser.install"));
        private final JFXButton historyBtn = newToggleButton4(SVG.FORMAT_LIST_BULLETED);
        private final JFXButton openRepo = newToggleButton4(SVG.GLOBE_BOOK);
        // Badge shown to the right of the title for repos in PINNED_REPOS.
        // The cell is recycled by JFXListView, so toggle managed/visible
        // in updateItem rather than adding/removing the node each time.
        private final Label recommendedBadge = new Label(
                i18n("xenon.mindustry.mod.browser.recommended"));

        ModCell() {
            // BorderPane keeps the action cluster glued to the right edge
            // and lets the center (title + subtitle) shrink to whatever
            // viewport width remains. The previous HBox layout summed
            // pref-widths and forced the JFXListView to grow a horizontal
            // scrollbar whenever the description was long.
            javafx.scene.layout.BorderPane root = new javafx.scene.layout.BorderPane();
            root.setMinWidth(0);
            root.setMaxWidth(Double.MAX_VALUE);

            // Title + subtitle: clamp min=0, max=∞, overrun=ELLIPSIS, and
            // give them Hgrow inside TwoLineListItem's first/second-line
            // HBoxes. Without Hgrow the HBox lays the label out at its
            // pref-width (= natural text width) regardless of how wide
            // its parent is, which is exactly why long descriptions were
            // bleeding off the right edge of the viewport.
            javafx.scene.control.Label titleLabel = twoLine.getTitleLabel();
            titleLabel.setMinWidth(0);
            titleLabel.setMaxWidth(Double.MAX_VALUE);
            titleLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            HBox.setHgrow(titleLabel, Priority.ALWAYS);

            // Force the subtitle Label into existence (TwoLineListItem
            // builds it lazily on first setSubtitle) so we can configure
            // it once at construction.
            javafx.scene.control.Label subLabel = twoLine.getSubtitleLabel();
            subLabel.setMinWidth(0);
            subLabel.setMaxWidth(Double.MAX_VALUE);
            subLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            HBox.setHgrow(subLabel, Priority.ALWAYS);

            twoLine.setMinWidth(0);
            twoLine.setMaxWidth(Double.MAX_VALUE);

            // "Recommended utility mod" pill — sits in the title row, just
            // after the title Label. Hgrow stays on the title so the badge
            // hugs the title's right edge instead of getting pushed out.
            recommendedBadge.getStyleClass().addAll("tag");
            recommendedBadge.setMinWidth(Label.USE_PREF_SIZE);
            HBox.setMargin(recommendedBadge, new Insets(0, 0, 0, 8));
            recommendedBadge.setManaged(false);
            recommendedBadge.setVisible(false);
            twoLine.getFirstLine().getChildren().add(recommendedBadge);

            FXUtils.installFastTooltip(historyBtn, i18n("xenon.mindustry.mod.browser.history.tooltip"));
            historyBtn.setOnAction(e -> {
                MindustryRemoteMod m = getItem();
                if (m != null) installHistorical(m);
            });
            FXUtils.installFastTooltip(openRepo, i18n("xenon.mindustry.mod.browser.repo"));
            openRepo.setOnAction(e -> {
                MindustryRemoteMod m = getItem();
                if (m != null && m.getRepo() != null && !m.getRepo().isBlank()) {
                    FXUtils.openLink("https://github.com/" + m.getRepo());
                }
            });
            installBtn.setOnAction(e -> {
                MindustryRemoteMod m = getItem();
                if (m != null) install(m);
            });

            HBox actions = new HBox(8, openRepo, historyBtn, installBtn);
            actions.setAlignment(Pos.CENTER_RIGHT);
            // Action cluster keeps its preferred size; only the center
            // (twoLine) flexes when the row gets narrow.
            actions.setMinWidth(HBox.USE_PREF_SIZE);

            root.setCenter(twoLine);
            root.setRight(actions);
            javafx.scene.layout.BorderPane.setAlignment(actions, Pos.CENTER_RIGHT);
            javafx.scene.layout.BorderPane.setMargin(actions, new Insets(0, 0, 0, 12));

            pane.getStyleClass().add("md-list-cell");
            pane.setMinWidth(0);
            pane.setMaxWidth(Double.MAX_VALUE);
            StackPane.setMargin(root, new Insets(10, 16, 10, 16));
            pane.getChildren().setAll(new RipplerContainer(root));
        }

        @Override
        protected double computePrefWidth(double height) {
            // Cell width = whatever the listView viewport gives us, never
            // wider. Returning 0 (instead of summing the children's
            // pref-widths) prevents the row from outgrowing the viewport
            // and pushing labels past the right edge.
            return 0;
        }

        @Override
        protected void updateItem(MindustryRemoteMod mod, boolean empty) {
            super.updateItem(mod, empty);
            if (empty || mod == null) {
                setGraphic(null);
                return;
            }
            setGraphic(pane);

            // Title — display name with the star count inline.
            String title = mod.displayName();
            if (mod.getStars() > 0) {
                title = title + "   ★ " + mod.getStars();
            }
            twoLine.setTitle(title);

            // Show the "recommended" badge only for the four pinned repos.
            // Match the same case-insensitive owner/repo rule that the
            // pinning logic uses — keeps both lists consistent.
            boolean recommended = false;
            String repo = mod.getRepo();
            if (repo != null) {
                for (String pin : PINNED_REPOS) {
                    if (pin.equalsIgnoreCase(repo)) {
                        recommended = true;
                        break;
                    }
                }
            }
            recommendedBadge.setManaged(recommended);
            recommendedBadge.setVisible(recommended);

            // Subtitle — single ellipsised line: repo · @author · date · description.
            // Pre-tags layout kept the description in a tag chip with a
            // ScrollPane wrapper, which is what was forcing the row to
            // grow past the viewport. Folding everything into the
            // subtitle Label lets the default truncation kick in cleanly.
            StringBuilder subtitle = new StringBuilder();
            if (mod.getRepo() != null && !mod.getRepo().isBlank()) {
                subtitle.append(mod.getRepo());
            }
            if (mod.getAuthor() != null && !mod.getAuthor().isBlank()) {
                if (subtitle.length() > 0) subtitle.append("  ·  ");
                subtitle.append("@").append(mod.getAuthor());
            }
            if (mod.getLastUpdated() != null) {
                if (subtitle.length() > 0) subtitle.append("  ·  ");
                subtitle.append(DATE.format(mod.getLastUpdated()));
            }
            String desc = mod.getDescription();
            if (desc != null && !desc.isBlank()) {
                if (subtitle.length() > 0) subtitle.append("  ·  ");
                subtitle.append(desc.replaceAll("\\s+", " ").trim());
            }
            twoLine.setSubtitle(subtitle.length() == 0 ? null : subtitle.toString());
            // The subtitle label is built on demand by setSubtitle; clamp
            // it the same way as the title so it ellipsises rather than
            // pushing the row width.
            javafx.scene.control.Label subLabel = twoLine.getSubtitleLabel();
            if (subLabel != null) {
                subLabel.setMinWidth(0);
                subLabel.setMaxWidth(Double.MAX_VALUE);
            }
        }
    }
}
