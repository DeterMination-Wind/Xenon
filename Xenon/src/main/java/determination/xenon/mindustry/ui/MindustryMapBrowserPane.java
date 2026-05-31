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
import com.jfoenix.controls.JFXTextField;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.MindustryVersionDisplay;
import determination.xenon.mindustry.XenonGameRepository;
import determination.xenon.mindustry.download.ProgressCallback;
import determination.xenon.mindustry.map.MindustryRemoteMap;
import determination.xenon.mindustry.map.MindustryTopMapRepository;
import determination.xenon.mindustry.save.SaveFileReader;
import determination.xenon.setting.Profiles;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.task.TaskExecutor;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.ui.construct.PageAware;
import determination.xenon.util.TaskCancellationAction;
import determination.xenon.util.i18n.I18n;
import determination.xenon.util.io.FileUtils;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/**
 * mindustry.top-backed map browser for the download page.
 *
 * <p>Search text is sent to the backend directly; the lightweight chips
 * (`latest/all`, `mode`, `CP`, `version`) filter client-side so the UI can
 * react instantly without inventing a launcher-side search grammar.</p>
 */
public final class MindustryMapBrowserPane extends BorderPane implements PageAware {
    private static final List<String> MODES = List.of("Survive", "Pvp", "Attack", "Sandbox", "Editor");
    private static final double PREVIEW_HEIGHT = 168.0;
    private static final double LOAD_MORE_THRESHOLD = 0.88;
    private static final int AUTO_LOAD_MAX_PAGES = 8;
    private static final int AUTO_LOAD_MIN_VISIBLE = 8;
    private static final double CARD_MIN_WIDTH = 240.0;
    private static final double CARD_GAP = 12.0;

    private final MindustryTopMapRepository repo =
            new MindustryTopMapRepository(MindustryImportFlow.cachesDirectory());

    private final JFXTextField search = new JFXTextField();
    private final ComboBox<MindustryVersion> targetVersion = new ComboBox<>();
    private final Label status = new Label();
    private final GridPane grid = new GridPane();
    private final ScrollPane scroll = new ScrollPane(grid);
    private final Label notice = new Label();
    private final HBox viewChips = new HBox(8);
    private final FlowPane modeChips = new FlowPane(8, 8);
    private final FlowPane versionChips = new FlowPane(8, 8);
    private final PauseTransition searchPause = new PauseTransition(Duration.millis(350));
    private final List<MapCard> renderedCards = new ArrayList<>();

    private final List<MindustryRemoteMap> loaded = new ArrayList<>();

    private boolean latestOnly = true;
    private boolean cpOnly;
    private @Nullable String selectedMode;
    private @Nullable String selectedVersionTag;

    private boolean loadingPage;
    private boolean endReached;
    private int nextOffset;
    private int autoLoadBudget;
    private int lastVisibleCount;
    private long generation;
    private @Nullable String lastErrorMessage;

    public MindustryMapBrowserPane() {
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.mindustry.map.browser.title"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.mindustry.map.browser.hint"));
        hint.setWrapText(true);

        search.setPromptText(i18n("xenon.mindustry.map.browser.search"));
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().addListener((obs, oldValue, newValue) -> searchPause.playFromStart());
        searchPause.setOnFinished(e -> restartQuery());

        targetVersion.getStyleClass().add("jfx-combo-box");
        targetVersion.setPromptText(i18n("xenon.mindustry.map.browser.target"));
        targetVersion.setConverter(new StringConverter<>() {
            @Override
            public String toString(MindustryVersion version) {
                if (version == null) return "";
                String name = version.getName() == null ? version.getId() : version.getName();
                String build = MindustryVersionDisplay.buildLabel(
                        version.getVariant(), version.getBuild(), version.getBuildType());
                if (!build.isBlank()) {
                    return name + "  ·  " + version.getVariant().getDisplayName() + "  ·  " + build;
                }
                return name + "  ·  " + version.getVariant().getDisplayName();
            }

            @Override
            public MindustryVersion fromString(String string) {
                return null;
            }
        });

        JFXButton refresh = FXUtils.newRaisedButton(i18n("xenon.mindustry.map.browser.refresh"));
        refresh.setOnAction(e -> restartQuery());

        HBox toolbar = new HBox(8, search, targetVersion, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        viewChips.setAlignment(Pos.CENTER_LEFT);
        modeChips.setAlignment(Pos.CENTER_LEFT);
        versionChips.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(
                6,
                title,
                hint,
                toolbar,
                filterRow(i18n("xenon.mindustry.map.browser.view"), viewChips),
                filterRow(i18n("xenon.mindustry.map.browser.filter"), modeChips),
                filterRow(i18n("xenon.mindustry.map.browser.version"), versionChips),
                status
        );
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);

        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(50);
        c1.setHgrow(Priority.ALWAYS);
        c1.setFillWidth(true);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(50);
        c2.setHgrow(Priority.ALWAYS);
        c2.setFillWidth(true);
        grid.getColumnConstraints().setAll(c1, c2);
        grid.setHgap(CARD_GAP);
        grid.setVgap(CARD_GAP);
        grid.setMaxWidth(Double.MAX_VALUE);

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        FXUtils.smoothScrolling(scroll);
        scroll.viewportBoundsProperty().addListener((obs, oldBounds, newBounds) ->
                updateGridMetrics(newBounds.getWidth()));
        scroll.vvalueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue.doubleValue() >= LOAD_MORE_THRESHOLD) {
                loadNextPage();
            }
        });

        notice.getStyleClass().add("subtitle-label");
        notice.setWrapText(true);
        notice.setTextOverrun(OverrunStyle.CLIP);
        notice.setMaxWidth(Double.MAX_VALUE);
        notice.setAlignment(Pos.CENTER);
        notice.setPadding(new Insets(24));
        FXUtils.onClicked(notice, this::handleNoticeClick);

        StackPane center = new StackPane(scroll, notice);
        StackPane.setAlignment(notice, Pos.TOP_CENTER);
        setCenter(center);

        rebuildViewChips();
        rebuildModeChips();
        rebuildVersionChips();
        refreshTargetVersions();
        restartQuery();
        Platform.runLater(() -> updateGridMetrics(scroll.getViewportBounds().getWidth()));
    }

    @Override
    public void onPageShown() {
        refreshTargetVersions();
    }

    private static HBox filterRow(String labelText, FlowPane chips) {
        Label label = new Label(labelText);
        label.getStyleClass().add("subtitle-label");
        HBox row = new HBox(10, label, chips);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static HBox filterRow(String labelText, HBox chips) {
        Label label = new Label(labelText);
        label.getStyleClass().add("subtitle-label");
        HBox row = new HBox(10, label, chips);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void refreshTargetVersions() {
        XenonGameRepository xrepo = MindustryImportFlow.repository();
        xrepo.refresh();
        List<MindustryVersion> versions = new ArrayList<>(xrepo.all());
        versions.sort(Comparator.comparing(MindustryVersion::getName, String.CASE_INSENSITIVE_ORDER));

        MindustryVersion previous = targetVersion.getSelectionModel().getSelectedItem();
        targetVersion.getItems().setAll(versions);
        if (versions.isEmpty()) {
            targetVersion.getSelectionModel().clearSelection();
            return;
        }

        if (previous != null) {
            for (MindustryVersion version : versions) {
                if (version.getId().equals(previous.getId())) {
                    targetVersion.getSelectionModel().select(version);
                    return;
                }
            }
        }

        String selectedVersionId = Profiles.getSelectedVersion();
        if (selectedVersionId != null) {
            for (MindustryVersion version : versions) {
                if (version.getId().equals(selectedVersionId)) {
                    targetVersion.getSelectionModel().select(version);
                    return;
                }
            }
        }

        targetVersion.getSelectionModel().selectFirst();
    }

    private void restartQuery() {
        generation++;
        loaded.clear();
        nextOffset = 0;
        lastVisibleCount = 0;
        endReached = false;
        lastErrorMessage = null;
        loadingPage = false;
        autoLoadBudget = AUTO_LOAD_MAX_PAGES;
        rebuildVersionChips();
        rebuildVisibleMaps();
        loadNextPage();
    }

    private void loadNextPage() {
        if (loadingPage || endReached) return;
        final long token = generation;
        final int offset = nextOffset;
        final String query = normalizedSearch();
        loadingPage = true;
        updateStatus();
        Schedulers.io().execute(() -> {
            try {
                List<MindustryRemoteMap> page = repo.fetchPage(offset, query);
                Platform.runLater(() -> publishPage(token, offset, page, null));
            } catch (Throwable ex) {
                Platform.runLater(() -> publishPage(token, offset, List.of(), ex));
            }
        });
    }

    private void publishPage(long token, int offset, List<MindustryRemoteMap> page, @Nullable Throwable ex) {
        if (token != generation) return;

        loadingPage = false;
        if (ex != null) {
            String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            lastErrorMessage = message;
            LOG.warning("Failed to load mindustry.top maps", ex);
            rebuildVisibleMaps();
            return;
        }

        lastErrorMessage = null;
        if (page.isEmpty()) {
            endReached = true;
        } else {
            loaded.addAll(page);
            nextOffset = offset + page.size();
        }
        rebuildVisibleMaps();
        maybeAutoLoadMore();
    }

    private void maybeAutoLoadMore() {
        if (loadingPage || endReached || autoLoadBudget <= 0) return;
        if (lastVisibleCount == 0 || lastVisibleCount < AUTO_LOAD_MIN_VISIBLE) {
            autoLoadBudget--;
            loadNextPage();
        }
    }

    private void rebuildVisibleMaps() {
        List<MindustryRemoteMap> visible = filteredMaps();
        lastVisibleCount = visible.size();

        rebuildVersionChips();
        renderGrid(visible);
        updateGridMetrics(scroll.getViewportBounds().getWidth());
        updateStatus();
        updateNotice(visible);
    }

    private List<MindustryRemoteMap> filteredMaps() {
        List<MindustryRemoteMap> out = new ArrayList<>();
        Set<String> seenLatest = latestOnly ? new LinkedHashSet<>() : null;
        for (MindustryRemoteMap map : loaded) {
            if (seenLatest != null && !seenLatest.add(map.latestKey())) continue;
            if (selectedMode != null && !selectedMode.equalsIgnoreCase(map.displayMode())) continue;
            if (cpOnly && !map.hasCpTag()) continue;
            if (selectedVersionTag != null) {
                String version = map.versionTag();
                if (version == null || !selectedVersionTag.equalsIgnoreCase(version)) continue;
            }
            out.add(map);
        }
        return out;
    }

    private void renderGrid(List<MindustryRemoteMap> visible) {
        renderedCards.clear();
        grid.getChildren().clear();
        for (int i = 0; i < visible.size(); i++) {
            MindustryRemoteMap map = visible.get(i);
            MapCard card = createCard(map);
            GridPane.setHgrow(card, Priority.ALWAYS);
            grid.add(card, i % 2, i / 2);
            renderedCards.add(card);
        }
    }

    private MapCard createCard(MindustryRemoteMap map) {
        MapCard card = new MapCard(map);
        card.getStyleClass().add("card");
        card.setFillWidth(true);
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);

        StackPane preview = new StackPane();
        preview.setMinHeight(PREVIEW_HEIGHT);
        preview.setPrefHeight(PREVIEW_HEIGHT);
        preview.setMaxHeight(PREVIEW_HEIGHT);
        preview.setMinWidth(0);
        preview.setMaxWidth(Double.MAX_VALUE);
        preview.setStyle("-fx-background-color: -monet-surface-container; -fx-background-radius: 4;");
        FXUtils.setOverflowHidden(preview, 4);

        ImageView imageView = new ImageView();
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        imageView.setFitHeight(PREVIEW_HEIGHT);
        imageView.fitWidthProperty().bind(preview.widthProperty());
        if (!map.previewUrl().isBlank()) {
            imageView.imageProperty().bind(FXUtils.newRemoteImage(
                    map.previewUrl(), 720, 405, true, true));
        }

        Label placeholder = new Label(i18n("xenon.mindustry.map.browser.preview"));
        placeholder.setGraphic(SVG.LANDSCAPE.createIcon(28));
        placeholder.setContentDisplay(ContentDisplay.TOP);
        placeholder.getStyleClass().add("subtitle-label");
        placeholder.setMinWidth(0);
        placeholder.setMaxWidth(Double.MAX_VALUE);
        placeholder.visibleProperty().bind(Bindings.isNull(imageView.imageProperty()));
        placeholder.managedProperty().bind(placeholder.visibleProperty());

        preview.getChildren().setAll(imageView, placeholder);

        Label title = new Label(map.displayName());
        title.getStyleClass().add("title-label");
        title.setWrapText(true);
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);

        Label meta = new Label("#" + map.id() + "  ·  " + map.width() + "x" + map.height());
        meta.getStyleClass().add("subtitle-label");
        meta.setWrapText(true);
        meta.setMinWidth(0);
        meta.setMaxWidth(Double.MAX_VALUE);

        FlowPane tags = new FlowPane(8, 6);
        tags.setAlignment(Pos.CENTER_LEFT);
        tags.setMinWidth(0);
        tags.setMaxWidth(Double.MAX_VALUE);
        tags.getStyleClass().add("mindustry-map-tags");
        for (String tag : map.displayTags()) {
            Label chip = new Label(localizeTag(tag));
            chip.getStyleClass().add("mindustry-map-tag");
            tags.getChildren().add(chip);
        }

        Label summary = new Label(truncate(map.displaySummary(), 140));
        summary.getStyleClass().add("subtitle-label");
        summary.setWrapText(true);
        summary.setMinHeight(Label.USE_PREF_SIZE);
        summary.setMinWidth(0);
        summary.setMaxWidth(Double.MAX_VALUE);

        JFXButton importButton = FXUtils.newRaisedButton(i18n("xenon.mindustry.map.browser.import"));
        importButton.setOnAction(e -> install(map));

        HBox footer = new HBox(importButton);
        footer.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().setAll(preview, title, meta, tags, summary, footer);
        card.preview = preview;
        card.title = title;
        card.meta = meta;
        card.tags = tags;
        card.summary = summary;
        card.setContentSpacing(10);
        return card;
    }

    private void updateNotice(List<MindustryRemoteMap> visible) {
        boolean showNotice = visible.isEmpty();
        notice.setVisible(showNotice);
        notice.setManaged(showNotice);
        scroll.setVisible(!showNotice);
        scroll.setManaged(!showNotice);
        if (!showNotice) return;

        if (loadingPage && loaded.isEmpty()) {
            notice.setText(i18n("xenon.mindustry.map.browser.loading"));
            return;
        }
        if (lastErrorMessage != null && loaded.isEmpty()) {
            notice.setText(i18n("xenon.mindustry.map.browser.failed.click", lastErrorMessage));
            return;
        }
        if (!endReached) {
            notice.setText(i18n("xenon.mindustry.map.browser.empty.more"));
            return;
        }
        notice.setText(i18n("xenon.mindustry.map.browser.empty"));
    }

    private void updateStatus() {
        if (loadingPage && loaded.isEmpty()) {
            status.setText(i18n("xenon.mindustry.map.browser.loading"));
            return;
        }
        if (lastErrorMessage != null && loaded.isEmpty()) {
            status.setText(i18n("xenon.mindustry.map.browser.failed.prefix") + " " + lastErrorMessage);
            return;
        }
        if (loadingPage) {
            status.setText(i18n("xenon.mindustry.map.browser.count.loading", lastVisibleCount));
            return;
        }
        if (lastErrorMessage != null) {
            status.setText(i18n("xenon.mindustry.map.browser.count.failed", lastVisibleCount, lastErrorMessage));
            return;
        }
        status.setText(i18n("xenon.mindustry.map.browser.count", lastVisibleCount));
    }

    private void handleNoticeClick() {
        if (loadingPage) return;
        if (lastErrorMessage != null && loaded.isEmpty()) {
            restartQuery();
            return;
        }
        if (!endReached) {
            loadNextPage();
        }
    }

    private void rebuildViewChips() {
        viewChips.getChildren().setAll(
                newChip(i18n("xenon.mindustry.map.browser.latest"), latestOnly, () -> {
                    if (!latestOnly) {
                        latestOnly = true;
                        onFilterChanged();
                    }
                }),
                newChip(i18n("xenon.mindustry.map.browser.all"), !latestOnly, () -> {
                    if (latestOnly) {
                        latestOnly = false;
                        onFilterChanged();
                    }
                })
        );
    }

    private void rebuildModeChips() {
        List<ToggleButton> chips = new ArrayList<>();
        chips.add(newChip(i18n("xenon.mindustry.map.browser.cp"), cpOnly, () -> {
            cpOnly = !cpOnly;
            onFilterChanged();
        }));
        for (String mode : MODES) {
            chips.add(newChip(displayModeLabel(mode), mode.equalsIgnoreCase(selectedMode), () -> {
                selectedMode = mode.equalsIgnoreCase(selectedMode) ? null : mode;
                onFilterChanged();
            }));
        }
        modeChips.getChildren().setAll(chips);
    }

    private void rebuildVersionChips() {
        Set<String> versions = new LinkedHashSet<>();
        if (selectedVersionTag != null) versions.add(selectedVersionTag);
        for (MindustryRemoteMap map : loaded) {
            String version = map.versionTag();
            if (version != null) versions.add(version);
        }

        List<String> ordered = new ArrayList<>(versions);
        ordered.sort((a, b) -> Integer.compare(versionNumber(b), versionNumber(a)));

        List<ToggleButton> chips = new ArrayList<>(ordered.size());
        for (String version : ordered) {
            chips.add(newChip(version, version.equalsIgnoreCase(selectedVersionTag), () -> {
                selectedVersionTag = version.equalsIgnoreCase(selectedVersionTag) ? null : version;
                onFilterChanged();
            }));
        }
        versionChips.getChildren().setAll(chips);
    }

    private void onFilterChanged() {
        autoLoadBudget = AUTO_LOAD_MAX_PAGES;
        rebuildViewChips();
        rebuildModeChips();
        rebuildVisibleMaps();
        maybeAutoLoadMore();
    }

    private ToggleButton newChip(String text, boolean selected, Runnable action) {
        ToggleButton chip = new ToggleButton(text);
        chip.getStyleClass().add("mindustry-map-chip");
        chip.setSelected(selected);
        chip.setOnAction(e -> action.run());
        return chip;
    }

    private void install(MindustryRemoteMap map) {
        MindustryVersion target = targetVersion.getSelectionModel().getSelectedItem();
        if (target == null) {
            Path chosen = chooseSavePath(null, map.suggestedFileName());
            if (chosen != null) {
                runInstallPipeline(map, chosen, null, true);
            }
            return;
        }

        XenonGameRepository xrepo = MindustryImportFlow.repository();
        Path versionRoot = xrepo.getVersionRoot(target.getId());
        Path mapsDir = target.resolveDataDir(versionRoot).resolve("maps");
        Path defaultTarget = mapsDir.resolve(map.suggestedFileName());

        if (Files.exists(defaultTarget)) {
            MessageDialogPane.Builder builder = new MessageDialogPane.Builder(
                    i18n("xenon.mindustry.map.browser.conflict.message", defaultTarget.getFileName().toString()),
                    i18n("xenon.mindustry.map.browser.conflict.title"),
                    MessageDialogPane.MessageType.WARNING
            );
            builder.addAction(i18n("xenon.mindustry.map.browser.overwrite"), () ->
                    runInstallPipeline(map, defaultTarget, target, true));
            builder.addAction(i18n("button.save_as"), () -> {
                Path chosen = chooseSavePath(existingDirectory(mapsDir), map.suggestedFileName());
                if (chosen != null) {
                    runInstallPipeline(map, chosen, target, true);
                }
            });
            builder.addCancel(null);
            Controllers.dialog(builder.build());
            return;
        }

        runInstallPipeline(map, defaultTarget, target, false);
    }

    private void runInstallPipeline(MindustryRemoteMap map, Path targetPath,
                                    @Nullable MindustryVersion targetVersion,
                                    boolean replaceExisting) {
        Path staging = Path.of(System.getProperty("java.io.tmpdir", "."),
                "xenon-map-" + map.id() + "-" + System.nanoTime() + ".msav");

        Task<Path> download = new MapDownloadTask(map, staging);
        Task<Void> install = download.thenComposeAsync(Schedulers.io(), file ->
                Task.runAsync(Schedulers.io(), () -> {
                    try {
                        SaveFileReader.readHeaderLenient(file);
                        Path parent = targetPath.getParent();
                        if (parent != null) Files.createDirectories(parent);
                        if (replaceExisting) {
                            Files.move(file, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            Files.move(file, targetPath);
                        }
                    } finally {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException ignored) {
                        }
                    }
                }).setName(i18n("xenon.mindustry.map.browser.task.install", map.displayName())));

        Task<Void> pipeline = install.whenComplete(Schedulers.javafx(), ex -> {
            if (ex == null) {
                if (targetVersion != null) {
                    Controllers.showToast(i18n("xenon.mindustry.map.browser.installed.into",
                            map.displayName(), targetVersion.getName()));
                } else {
                    Controllers.showToast(i18n("xenon.mindustry.map.browser.saved.as",
                            map.displayName(), targetPath.getFileName().toString()));
                }
            } else {
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                LOG.warning("Failed to import map " + map.id() + " into " + targetPath, ex);
                Controllers.dialog(
                        i18n("xenon.mindustry.map.browser.install.failed", map.displayName(), msg),
                        i18n("message.error"),
                        MessageDialogPane.MessageType.ERROR);
            }
        }).setName(i18n("xenon.mindustry.map.browser.installing", map.displayName()));

        TaskExecutor executor = pipeline.executor();
        TaskCancellationAction cancel = new TaskCancellationAction(it ->
                it.fireEvent(new determination.xenon.ui.construct.DialogCloseEvent()));
        Controllers.taskDialog(executor,
                i18n("xenon.mindustry.map.browser.installing", map.displayName()),
                cancel);
        executor.start();
    }

    private Path chooseSavePath(@Nullable Path preferredDirectory, String suggestedFileName) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("button.save_as"));
        chooser.setInitialFileName(suggestedFileName);
        chooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("Mindustry map (*.msav)", "*.msav"));
        Path existingDir = existingDirectory(preferredDirectory);
        if (existingDir != null) {
            chooser.setInitialDirectory(existingDir.toFile());
        }
        File file = FXUtils.showSaveDialog(chooser, Controllers.getStage());
        return FileUtils.toPath(file);
    }

    private static @Nullable Path existingDirectory(@Nullable Path preferredDirectory) {
        if (preferredDirectory == null) return null;
        Path dir = preferredDirectory.toAbsolutePath().normalize();
        while (dir != null && !Files.isDirectory(dir)) {
            dir = dir.getParent();
        }
        return dir;
    }

    private String normalizedSearch() {
        String text = search.getText();
        return text == null ? "" : text.trim();
    }

    private void updateGridMetrics(double viewportWidth) {
        double width = Math.max(0, viewportWidth);
        if (width <= 0) return;
        double tileWidth = Math.max(CARD_MIN_WIDTH, (width - grid.getHgap()) / 2.0);
        for (MapCard card : renderedCards) {
            card.updateWidth(tileWidth);
        }
    }

    private static int versionNumber(String versionTag) {
        if (versionTag == null) return 0;
        String digits = versionTag.replaceAll("[^0-9]", "");
        try {
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String truncate(String text, int max) {
        if (text == null || text.isBlank()) return "";
        return text.length() <= max ? text : text.substring(0, max).trim() + "…";
    }

    private static String localizeTag(String tag) {
        for (String mode : MODES) {
            if (mode.equalsIgnoreCase(tag)) {
                return displayModeLabel(mode);
            }
        }
        return tag;
    }

    private static String displayModeLabel(String mode) {
        String key = "xenon.mindustry.map.browser.mode." + mode.toLowerCase(Locale.ROOT);
        return I18n.hasKey(key) ? i18n(key) : mode;
    }

    private final class MapCard extends VBox {
        private final MindustryRemoteMap map;
        private StackPane preview;
        private Label title;
        private Label meta;
        private FlowPane tags;
        private Label summary;

        MapCard(MindustryRemoteMap map) {
            this.map = map;
        }

        void setContentSpacing(double spacing) {
            setSpacing(spacing);
        }

        void updateWidth(double width) {
            double cardWidth = Math.max(CARD_MIN_WIDTH, width);
            setMinWidth(cardWidth);
            setPrefWidth(cardWidth);
            setMaxWidth(cardWidth);
            double innerWidth = Math.max(0, cardWidth - 32);
            if (preview != null) {
                preview.setPrefWidth(innerWidth);
                preview.setMaxWidth(innerWidth);
            }
            if (title != null) {
                title.setPrefWidth(innerWidth);
            }
            if (meta != null) {
                meta.setPrefWidth(innerWidth);
            }
            if (tags != null) {
                tags.setPrefWidth(innerWidth);
                tags.setPrefWrapLength(innerWidth);
            }
            if (summary != null) {
                summary.setPrefWidth(innerWidth);
            }
        }
    }

    private final class MapDownloadTask extends Task<Path> {
        private final MindustryRemoteMap map;
        private final Path target;

        MapDownloadTask(MindustryRemoteMap map, Path target) {
            this.map = Objects.requireNonNull(map, "map");
            this.target = Objects.requireNonNull(target, "target");
            setName(i18n("xenon.mindustry.map.browser.task.download", map.displayName()));
        }

        @Override
        public void execute() throws Exception {
            repo.downloadMap(map.id(), target, updateProgress());
            setResult(target);
        }

        private ProgressCallback updateProgress() {
            return (read, total) -> {
                if (total > 0 && read >= 0) {
                    updateProgress(Math.min(read, total), total);
                }
            };
        }
    }
}
