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
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
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
    private static final int AUTO_LOAD_MAX_PAGES = 8;
    private static final int AUTO_LOAD_MIN_VISIBLE = 8;
    private static final double CARD_MIN_WIDTH = 240.0;
    private static final double CARD_GAP = 12.0;

    private final MindustryTopMapRepository repo =
            new MindustryTopMapRepository(MindustryImportFlow.cachesDirectory());

    private final JFXTextField search = new JFXTextField();
    private final ComboBox<MindustryVersion> targetVersion = new ComboBox<>();
    private final Label status = new Label();
    /// Virtualized card list; each row holds one or two map cards.
    private final JFXListView<MapRow> listView = new JFXListView<>();
    private final Label notice = new Label();
    private final HBox viewChips = new HBox(8);
    private final FlowPane modeChips = new FlowPane(8, 8);
    private final FlowPane versionChips = new FlowPane(8, 8);
    private final PauseTransition searchPause = new PauseTransition(Duration.millis(350));

    private final List<MindustryRemoteMap> loaded = new ArrayList<>();
    /// Latest keys already represented by the rendered rows (latest-only view).
    private final Set<String> renderedLatestKeys = new LinkedHashSet<>();

    private boolean latestOnly = true;
    private boolean cpOnly;
    private @Nullable String selectedMode;
    private @Nullable String selectedVersionTag;

    private boolean loadingPage;
    private boolean endReached;
    private int nextOffset;
    private int autoLoadBudget;
    private int lastVisibleCount;
    /// Number of `loaded` entries already reflected by the rendered rows.
    private int renderedLoadedCount;
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

        listView.getStyleClass().add("no-padding");
        listView.setCellFactory(lv -> new MapRowCell());
        // Cells must fit the viewport width: cards shrink instead of pushing
        // a horizontal scrollbar.
        listView.lookupAll(".scroll-bar").forEach(n -> {
            if (n instanceof javafx.scene.control.ScrollBar bar
                    && bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL) {
                bar.setVisible(false);
                bar.setManaged(false);
            }
        });
        listView.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                Platform.runLater(() -> {
                    for (Node sb : listView.lookupAll(".scroll-bar")) {
                        if (sb instanceof javafx.scene.control.ScrollBar bar
                                && bar.getOrientation() == javafx.geometry.Orientation.HORIZONTAL) {
                            bar.setVisible(false);
                            bar.setManaged(false);
                        }
                    }
                });
            }
        });

        notice.getStyleClass().add("subtitle-label");
        notice.setWrapText(true);
        notice.setTextOverrun(OverrunStyle.CLIP);
        notice.setMaxWidth(Double.MAX_VALUE);
        notice.setAlignment(Pos.CENTER);
        notice.setPadding(new Insets(24));
        FXUtils.onClicked(notice, this::handleNoticeClick);

        StackPane center = new StackPane(listView, notice);
        StackPane.setAlignment(notice, Pos.TOP_CENTER);
        setCenter(center);

        rebuildViewChips();
        rebuildModeChips();
        rebuildVersionChips();
        refreshTargetVersions();
        restartQuery();
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
        List<MindustryVersion> versions = new ArrayList<>(
                MindustryImportFlow.visibleVersions(Profiles.getSelectedProfile()));
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
        renderedLatestKeys.clear();
        renderedLoadedCount = 0;
        listView.getItems().clear();
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
        appendVisibleMaps();
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
        renderedLoadedCount = loaded.size();
        renderedLatestKeys.clear();
        if (latestOnly) {
            // Record every loaded key, not just the visible ones: the append
            // path must deduplicate exactly like `filteredMaps`, which claims
            // the key before applying the chip filters.
            for (MindustryRemoteMap map : loaded) {
                renderedLatestKeys.add(map.latestKey());
            }
        }

        rebuildVersionChips();
        listView.getItems().setAll(buildRows(visible));
        updateStatus();
        updateNotice();
    }

    /// Appends rows for entries loaded after the last render.
    ///
    /// Existing rows stay untouched; only the newly arrived page is paired
    /// into rows, so paging never rebuilds cards that are already on screen.
    private void appendVisibleMaps() {
        if (renderedLoadedCount >= loaded.size()) {
            updateStatus();
            updateNotice();
            return;
        }

        List<MindustryRemoteMap> pending = new ArrayList<>(2);
        ObservableList<MapRow> items = listView.getItems();
        // Reuse a trailing single-item row so pairing continues across pages.
        if (!items.isEmpty()) {
            MapRow last = items.get(items.size() - 1);
            if (last.items().size() == 1) {
                pending.add(last.items().get(0));
                items.remove(items.size() - 1);
            }
        }

        for (int i = renderedLoadedCount; i < loaded.size(); i++) {
            MindustryRemoteMap map = loaded.get(i);
            if (latestOnly && !renderedLatestKeys.add(map.latestKey())) continue;
            if (!matchesFilters(map)) continue;
            pending.add(map);
            lastVisibleCount++;
        }
        renderedLoadedCount = loaded.size();

        for (int i = 0; i + 1 < pending.size(); i += 2) {
            items.add(new MapRow(List.of(pending.get(i), pending.get(i + 1))));
        }
        if (pending.size() % 2 == 1) {
            items.add(new MapRow(List.of(pending.get(pending.size() - 1))));
        }

        // The version chips list every version present in the loaded pages,
        // so they must follow the paging path as well.
        rebuildVersionChips();
        updateStatus();
        updateNotice();
    }

    /// Pairs a filtered list into rows of one or two cards.
    private static List<MapRow> buildRows(List<MindustryRemoteMap> visible) {
        List<MapRow> result = new ArrayList<>((visible.size() + 1) / 2);
        for (int i = 0; i < visible.size(); i += 2) {
            if (i + 1 < visible.size()) {
                result.add(new MapRow(List.of(visible.get(i), visible.get(i + 1))));
            } else {
                result.add(new MapRow(List.of(visible.get(i))));
            }
        }
        return result;
    }

    private List<MindustryRemoteMap> filteredMaps() {
        List<MindustryRemoteMap> out = new ArrayList<>();
        Set<String> seenLatest = latestOnly ? new LinkedHashSet<>() : null;
        for (MindustryRemoteMap map : loaded) {
            if (seenLatest != null && !seenLatest.add(map.latestKey())) continue;
            if (!matchesFilters(map)) continue;
            out.add(map);
        }
        return out;
    }

    /// Applies the chip filters that do not depend on list position.
    private boolean matchesFilters(MindustryRemoteMap map) {
        if (selectedMode != null && !selectedMode.equalsIgnoreCase(map.displayMode())) return false;
        if (cpOnly && !map.hasCpTag()) return false;
        if (selectedVersionTag != null) {
            String version = map.versionTag();
            if (version == null || !selectedVersionTag.equalsIgnoreCase(version)) return false;
        }
        return true;
    }

    /// One virtualized card-list row holding one or two map cards.
    private record MapRow(List<MindustryRemoteMap> items) {
    }

    /// Virtualized cell rendering one row of one or two map cards.
    private final class MapRowCell extends ListCell<MapRow> {
        /// Container for the cards of the current row.
        private final HBox cards = new HBox(CARD_GAP);

        MapRowCell() {
            cards.setAlignment(Pos.TOP_LEFT);
            cards.setMaxWidth(Double.MAX_VALUE);
            // The row gap lives here because the `no-padding` style class
            // forces the cell's own padding to zero.
            cards.setPadding(new Insets(0, 0, CARD_GAP, 0));
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setMinHeight(Region.USE_PREF_SIZE);
            widthProperty().addListener((obs, oldWidth, newWidth) ->
                    updateCardWidths(newWidth.doubleValue()));
        }

        @Override
        protected double computePrefWidth(double height) {
            // Cells take the viewport width; the cards shrink instead of
            // forcing a horizontal scrollbar.
            return 0;
        }

        @Override
        protected void updateItem(@Nullable MapRow row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null) {
                setGraphic(null);
                return;
            }

            List<Node> nodes = new ArrayList<>(2);
            for (MindustryRemoteMap map : row.items()) {
                nodes.add(createCard(map));
            }
            for (Node node : nodes) {
                HBox.setHgrow(node, Priority.ALWAYS);
            }
            if (nodes.size() < 2) {
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                spacer.setMaxWidth(Double.MAX_VALUE);
                nodes.add(spacer);
            }
            cards.getChildren().setAll(nodes);
            setGraphic(cards);
            updateCardWidths(getWidth());

            // Prefetch the next page slightly before the user reaches the end.
            if (getIndex() >= listView.getItems().size() - 2) {
                loadNextPage();
            }
        }

        /// Sizes both card slots from the cell width, keeping the same 32px
        /// inner padding the grid layout used.
        private void updateCardWidths(double width) {
            if (width <= 0) return;
            double tileWidth = Math.max(CARD_MIN_WIDTH, (width - CARD_GAP) / 2.0);
            for (Node node : cards.getChildren()) {
                if (node instanceof MapCard card) {
                    card.updateWidth(tileWidth);
                }
            }
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

    private void updateNotice() {
        boolean showNotice = lastVisibleCount == 0;
        notice.setVisible(showNotice);
        notice.setManaged(showNotice);
        listView.setVisible(!showNotice);
        listView.setManaged(!showNotice);
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

        XenonGameRepository xrepo = MindustryImportFlow.repositoryForVersion(target);
        Path versionRoot = xrepo.getVersionRoot(target);
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
