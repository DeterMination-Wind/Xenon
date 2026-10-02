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
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXTextArea;
import com.jfoenix.controls.JFXTextField;
import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.XenonGameRepository;
import determination.xenon.mindustry.community.MdtbbsCommunity;
import determination.xenon.mindustry.community.MdtbbsForumClient;
import determination.xenon.mindustry.community.MdtbbsGameContentClient;
import determination.xenon.mindustry.community.MdtbbsGameContentClient.Blueprint;
import determination.xenon.mindustry.community.MdtbbsGameContentClient.ContentPage;
import determination.xenon.mindustry.community.MdtbbsGameContentClient.MapItem;
import determination.xenon.mindustry.schematic.SchematicManager;
import determination.xenon.mindustry.save.SaveFileReader;
import determination.xenon.setting.Profiles;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;
import determination.xenon.task.TaskExecutor;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.DialogCloseEvent;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.ui.construct.PageAware;
import determination.xenon.util.TaskCancellationAction;
import determination.xenon.util.io.FileUtils;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Native MDTBBS resource browser for the download page.
///
/// Lists blueprints and maps through the game-content API with cursor
/// pagination and server-side search, installs blueprints into the selected
/// instance's `schematics/` folder and map files into `maps/`, and submits new
/// blueprints or maps for moderation when signed in.
public final class MdtbbsResourcePane extends BorderPane implements PageAware {
    /// Entries fetched per page.
    private static final int PAGE_SIZE = 20;
    /// Scroll fraction that triggers the next page.
    private static final double LOAD_MORE_THRESHOLD = 0.88;
    /// Preview tile height.
    private static final double PREVIEW_HEIGHT = 150.0;
    /// Card gap in the two-column grid.
    private static final double CARD_GAP = 12.0;

    /// Shared account coordinator.
    private final MdtbbsCommunity community = CommunityServices.community();
    /// Shared game-content client.
    private final MdtbbsGameContentClient client = CommunityServices.gameContent();

    /// Debounced search field.
    private final JFXTextField search = new JFXTextField();
    /// Target instance selector.
    private final ComboBox<MindustryVersion> targetVersion = new ComboBox<>();
    /// Status line.
    private final Label status = new Label();
    /// Card grid.
    private final GridPane grid = new GridPane();
    /// Scrollable card grid.
    private final ScrollPane scroll = new ScrollPane(grid);
    /// Empty/loading/error notice.
    private final Label notice = new Label();
    /// Mode chips (blueprints / maps).
    private final FlowPane modeChips = new FlowPane(8, 8);
    /// Upload button whose action follows the current mode.
    private final JFXButton upload = FXUtils.newRaisedButton(i18n("xenon.resource.upload.blueprint"));
    /// Search debounce timer.
    private final PauseTransition searchPause = new PauseTransition(Duration.millis(350));

    /// Loaded blueprints.
    private final List<Blueprint> blueprints = new ArrayList<>();
    /// Loaded maps.
    private final List<MapItem> maps = new ArrayList<>();
    /// Decoded preview images keyed by URL.
    private final java.util.concurrent.ConcurrentHashMap<String, Image> previewCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    /// Preview URLs that already failed, so they are not requested again.
    private final java.util.Set<String> failedPreviews =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /// Whether the map mode is active.
    private boolean mapMode;
    /// Whether a page is in flight.
    private boolean loading;
    /// Whether more pages exist.
    private boolean hasMore = true;
    /// Cursor for the next page, or `null`.
    private @Nullable String cursor;
    /// Generation token guarding stale responses.
    private long generation;
    /// Last error message, or `null`.
    private @Nullable String lastError;

    /// Builds the resource pane and starts the initial load.
    public MdtbbsResourcePane() {
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.resource.title"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.resource.hint"));
        hint.setWrapText(true);
        hint.getStyleClass().add("subtitle-label");

        search.setPromptText(i18n("xenon.resource.search"));
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().addListener((obs, oldValue, newValue) -> searchPause.playFromStart());
        searchPause.setOnFinished(e -> restartQuery());

        targetVersion.getStyleClass().add("jfx-combo-box");
        targetVersion.setPromptText(i18n("xenon.resource.target"));
        targetVersion.setConverter(new StringConverter<>() {
            @Override
            public String toString(MindustryVersion version) {
                if (version == null) return "";
                String name = version.getName() == null ? version.getId() : version.getName();
                return name + "  ·  " + version.getVariant().getDisplayName();
            }

            @Override
            public MindustryVersion fromString(String string) {
                return null;
            }
        });

        JFXButton refresh = FXUtils.newRaisedButton(i18n("xenon.resource.refresh"));
        refresh.setOnAction(e -> restartQuery());

        upload.setOnAction(e -> openUploadDialog());

        HBox toolbar = new HBox(8, search, targetVersion, refresh, upload);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        modeChips.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, title, hint, toolbar, modeChips, status);
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);

        ColumnConstraints first = new ColumnConstraints();
        first.setPercentWidth(50);
        first.setHgrow(Priority.ALWAYS);
        first.setFillWidth(true);
        ColumnConstraints second = new ColumnConstraints();
        second.setPercentWidth(50);
        second.setHgrow(Priority.ALWAYS);
        second.setFillWidth(true);
        grid.getColumnConstraints().setAll(first, second);
        grid.setHgap(CARD_GAP);
        grid.setVgap(CARD_GAP);
        grid.setMaxWidth(Double.MAX_VALUE);

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        FXUtils.smoothScrolling(scroll);
        scroll.vvalueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue.doubleValue() >= LOAD_MORE_THRESHOLD) loadNextPage();
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

        rebuildModeChips();
        refreshTargetVersions();
        restartQuery();
    }

    @Override
    public void onPageShown() {
        refreshTargetVersions();
    }

    // ------------------------------------------------------------------
    // Data loading
    // ------------------------------------------------------------------

    /// Reloads the instance selector for the active profile.
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

    /// Clears the grid and starts from the first page.
    private void restartQuery() {
        generation++;
        blueprints.clear();
        maps.clear();
        grid.getChildren().clear();
        cursor = null;
        hasMore = true;
        loading = false;
        lastError = null;
        loadNextPage();
    }

    /// Loads the next page for the current mode.
    private void loadNextPage() {
        if (loading || !hasMore) return;
        loading = true;
        long token = generation;
        String query = search.getText() == null ? "" : search.getText().trim();
        String nextCursor = cursor;
        boolean mapsRequested = mapMode;
        updateNotice();
        updateStatus();
        Schedulers.io().execute(() -> {
            try {
                if (mapsRequested) {
                    ContentPage<MapItem> page = client.maps(query, nextCursor, PAGE_SIZE);
                    Platform.runLater(() -> publishMaps(token, page));
                } else {
                    ContentPage<Blueprint> page = client.blueprints(query, nextCursor, PAGE_SIZE);
                    Platform.runLater(() -> publishBlueprints(token, page));
                }
            } catch (IOException e) {
                Platform.runLater(() -> publishError(token, e));
            }
        });
    }

    /// Appends a blueprint page.
    private void publishBlueprints(long token, ContentPage<Blueprint> page) {
        if (token != generation || mapMode) return;
        loading = false;
        blueprints.addAll(page.items());
        cursor = page.nextCursor();
        hasMore = page.hasMore();
        renderGrid();
        updateNotice();
        updateStatus();
    }

    /// Appends a map page.
    private void publishMaps(long token, ContentPage<MapItem> page) {
        if (token != generation || !mapMode) return;
        loading = false;
        maps.addAll(page.items());
        cursor = page.nextCursor();
        hasMore = page.hasMore();
        renderGrid();
        updateNotice();
        updateStatus();
    }

    /// Publishes a failed page load.
    private void publishError(long token, IOException error) {
        if (token != generation) return;
        loading = false;
        lastError = MdtbbsMessages.describe(error);
        updateNotice();
        updateStatus();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /// Rebuilds the card grid for the current mode.
    private void renderGrid() {
        grid.getChildren().clear();
        if (mapMode) {
            for (int i = 0; i < maps.size(); i++) {
                javafx.scene.Node card = createMapCard(maps.get(i));
                GridPane.setHgrow(card, Priority.ALWAYS);
                grid.add(card, i % 2, i / 2);
            }
        } else {
            for (int i = 0; i < blueprints.size(); i++) {
                javafx.scene.Node card = createBlueprintCard(blueprints.get(i));
                GridPane.setHgrow(card, Priority.ALWAYS);
                grid.add(card, i % 2, i / 2);
            }
        }
    }

    /// Creates one blueprint card.
    private VBox createBlueprintCard(Blueprint blueprint) {
        VBox card = cardContainer();
        Label title = titleLabel(blueprint.title());
        HBox meta = authorMetaRow(blueprint.authorId(), blueprint.author(),
                blankAs(blueprint.gameVersion(), "—"));
        FlowPane tags = tagRow(blueprint.tags());
        Label stats = metaLabel(i18n("xenon.resource.stats", blueprint.downloads(), blueprint.likes()));
        Label summary = metaLabel(truncate(blueprint.summary(), 140));

        JFXButton install = FXUtils.newRaisedButton(i18n("xenon.resource.install"));
        install.setOnAction(e -> installBlueprint(blueprint));
        HBox footer = new HBox(install);
        footer.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().setAll(previewBox(
                        firstNonBlank(blueprint.previewUrl(),
                                client.blueprintPreviewUrl(blueprint.id())),
                        SVG.SCHEMA, i18n("xenon.resource.preview.blueprint")),
                title, meta, tags, stats, summary, footer);
        return card;
    }

    /// Creates one map card.
    private VBox createMapCard(MapItem map) {
        VBox card = cardContainer();
        Label title = titleLabel(map.title());
        HBox meta = authorMetaRow(map.authorId(), map.author(),
                blankAs(map.mode(), blankAs(map.gameVersion(), "—")));
        FlowPane tags = tagRow(map.tags());
        Label stats = metaLabel(i18n("xenon.resource.stats", map.downloads(), map.likes()));
        Label summary = metaLabel(truncate(map.summary(), 140));

        JFXButton install = FXUtils.newRaisedButton(i18n("xenon.resource.install"));
        install.setOnAction(e -> installMap(map));
        HBox footer = new HBox(install);
        footer.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().setAll(previewBox(
                        firstNonBlank(map.previewUrl(), client.mapPreviewUrl(map.id())),
                        SVG.LANDSCAPE, i18n("xenon.resource.preview.map")),
                title, meta, tags, stats, summary, footer);
        return card;
    }

    /// Builds a meta row with a clickable author and a trailing detail label.
    ///
    /// @param authorId author user id, or 0 when unknown
    /// @param author   author name, or `null`
    /// @param detail   trailing text such as the game version
    /// @return the assembled row
    private static HBox authorMetaRow(long authorId, @Nullable String author, String detail) {
        HBox row = new HBox(6, authorNode(authorId, author), metaLabel(detail));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /// Creates a clickable author label that opens the web profile.
    ///
    /// @param authorId author user id, or 0 when unknown
    /// @param author   author name, or `null`
    /// @return a link-styled label, or a plain label for unknown authors
    private static Node authorNode(long authorId, @Nullable String author) {
        if (author == null || author.isBlank()) {
            Label anonymous = new Label(i18n("xenon.community.anonymous"));
            anonymous.getStyleClass().add("subtitle-label");
            return anonymous;
        }
        Label link = new Label(author);
        link.getStyleClass().add("community-author");
        FXUtils.installFastTooltip(link, i18n("xenon.mdtbbs.author.open"));
        if (authorId > 0) {
            FXUtils.onClicked(link, () -> FXUtils.openLink(MdtbbsForumClient.profileUrl(authorId)));
        }
        return link;
    }

    /// Creates the base card container.
    private static VBox cardContainer() {
        VBox card = new VBox(8);
        card.getStyleClass().add("card");
        card.setMinWidth(0);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    /// Creates one wrapped card title.
    private static Label titleLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("title-label");
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    /// Creates one wrapped secondary label.
    private static Label metaLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("subtitle-label");
        label.setWrapText(true);
        label.setMinWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    /// Creates a tag row, empty when the resource has no tags.
    private static FlowPane tagRow(List<String> tags) {
        FlowPane row = new FlowPane(8, 6);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(0);
        row.setMaxWidth(Double.MAX_VALUE);
        row.getStyleClass().add("mindustry-map-tags");
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            Label chip = new Label(tag);
            chip.getStyleClass().add("mindustry-map-tag");
            row.getChildren().add(chip);
        }
        row.setVisible(!row.getChildren().isEmpty());
        row.setManaged(!row.getChildren().isEmpty());
        return row;
    }

    /// Creates a preview tile with a remote thumbnail and a type-icon placeholder.
    ///
    /// The placeholder stays visible whenever the URL is blank or the server
    /// fails to render an image, so a broken preview never tears the card down.
    ///
    /// @param url   preview image URL, possibly blank
    /// @param icon  fallback type icon
    /// @param label fallback label text
    /// @return the preview tile
    private StackPane previewBox(String url, SVG icon, String label) {
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
        loadPreview(imageView, url);

        Label placeholder = new Label(label);
        placeholder.setGraphic(icon.createIcon(28));
        placeholder.setContentDisplay(ContentDisplay.TOP);
        placeholder.getStyleClass().add("subtitle-label");
        placeholder.visibleProperty().bind(Bindings.isNull(imageView.imageProperty()));
        placeholder.managedProperty().bind(placeholder.visibleProperty());

        preview.getChildren().setAll(imageView, placeholder);
        return preview;
    }

    /// Loads a preview image quietly, caching successes and remembering failures.
    ///
    /// Unlike the shared remote-image task this path never raises a task error:
    /// a missing preview simply leaves the type-icon placeholder in place.
    ///
    /// @param view target image view
    /// @param url  preview URL, possibly blank
    private void loadPreview(ImageView view, @Nullable String url) {
        if (url == null || url.isBlank() || failedPreviews.contains(url)) return;
        Image cached = previewCache.get(url);
        if (cached != null) {
            view.setImage(cached);
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                byte[] bytes = client.downloadBytes(url);
                Image image = new Image(new java.io.ByteArrayInputStream(bytes), 720, 0, true, true);
                Platform.runLater(() -> {
                    if (image.isError()) {
                        failedPreviews.add(url);
                        return;
                    }
                    previewCache.put(url, image);
                    view.setImage(image);
                });
            } catch (IOException | RuntimeException e) {
                failedPreviews.add(url);
                LOG.info("MDTBBS preview unavailable: " + url + " (" + e.getMessage() + ")");
            }
        });
    }

    /// Returns the first non-blank URL.
    private static String firstNonBlank(@Nullable String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    /// Rebuilds the mode chips.
    private void rebuildModeChips() {
        modeChips.getChildren().setAll(
                newChip(i18n("xenon.resource.blueprints"), !mapMode, () -> {
                    if (mapMode) {
                        mapMode = false;
                        upload.setText(i18n("xenon.resource.upload.blueprint"));
                        rebuildModeChips();
                        restartQuery();
                    }
                }),
                newChip(i18n("xenon.resource.maps"), mapMode, () -> {
                    if (!mapMode) {
                        mapMode = true;
                        upload.setText(i18n("xenon.resource.upload.map"));
                        rebuildModeChips();
                        restartQuery();
                    }
                }));
    }

    /// Creates one filter chip.
    private static ToggleButton newChip(String text, boolean selected, Runnable action) {
        ToggleButton chip = new ToggleButton(text);
        chip.getStyleClass().add("mindustry-map-chip");
        chip.setSelected(selected);
        chip.setOnAction(e -> action.run());
        return chip;
    }

    /// Updates the empty/loading/error notice.
    private void updateNotice() {
        boolean showNotice = currentCount() == 0;
        notice.setVisible(showNotice);
        notice.setManaged(showNotice);
        scroll.setVisible(!showNotice);
        scroll.setManaged(!showNotice);
        if (!showNotice) return;
        if (loading) {
            notice.setText(i18n("xenon.resource.loading"));
        } else if (lastError != null) {
            notice.setText(i18n("xenon.resource.failed", lastError));
        } else {
            notice.setText(i18n("xenon.resource.empty"));
        }
    }

    /// Updates the status line.
    private void updateStatus() {
        if (loading && currentCount() == 0) {
            status.setText(i18n("xenon.resource.loading"));
        } else if (lastError != null && currentCount() == 0) {
            status.setText(i18n("xenon.resource.failed", lastError));
        } else {
            status.setText(i18n("xenon.resource.count", currentCount()));
        }
    }

    /// Retries or loads more when the notice is clicked.
    private void handleNoticeClick() {
        if (!loading) restartQuery();
    }

    /// Number of loaded entries for the current mode.
    private int currentCount() {
        return mapMode ? maps.size() : blueprints.size();
    }

    // ------------------------------------------------------------------
    // Install
    // ------------------------------------------------------------------

    /// Installs a blueprint into the selected instance.
    private void installBlueprint(Blueprint blueprint) {
        MindustryVersion target = requireInstance();
        if (target == null) return;
        Path schematicsDir = dataDirectory(target).resolve("schematics");
        Task<Void> task = Task.runAsync(Schedulers.io(), () -> {
            String code = client.blueprintCode(blueprint.id());
            if (code.isBlank()) throw new IOException(i18n("xenon.resource.blueprint.empty"));
            new SchematicManager(schematicsDir).importBase64(code);
        }).setName(i18n("xenon.resource.install.task", blueprint.title()));
        runTask(task,
                i18n("xenon.resource.install.blueprint.done", blueprint.title()),
                i18n("xenon.resource.install.failed", blueprint.title()));
    }

    /// Installs a map into the selected instance, asking before overwriting.
    private void installMap(MapItem map) {
        MindustryVersion target = requireInstance();
        if (target == null) return;
        Path mapsDir = dataDirectory(target).resolve("maps");
        Path destination = mapsDir.resolve("mdtbbs-" + map.id() + ".msav");
        if (Files.exists(destination)) {
            Controllers.confirm(
                    i18n("xenon.resource.overwrite.message", destination.getFileName().toString()),
                    i18n("xenon.resource.overwrite.title"),
                    () -> startMapInstall(map, mapsDir, destination), null);
        } else {
            startMapInstall(map, mapsDir, destination);
        }
    }

    /// Starts the map download and move pipeline.
    private void startMapInstall(MapItem map, Path mapsDir, Path destination) {
        Task<Void> task = Task.runAsync(Schedulers.io(), () -> {
            Path temp = Files.createTempFile("xenon-mdtbbs-map-", ".msav");
            try {
                client.downloadMap(map.id(), temp, null);
                SaveFileReader.readHeaderLenient(temp);
                Files.createDirectories(mapsDir);
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }).setName(i18n("xenon.resource.install.task", map.title()));
        runTask(task,
                i18n("xenon.resource.install.map.done", map.title()),
                i18n("xenon.resource.install.failed", map.title()));
    }

    /// Runs a background task with a cancellable dialog and toast feedback.
    private static void runTask(Task<Void> task, String title, String failurePrefix) {
        Task<Void> pipeline = task.whenComplete(Schedulers.javafx(), ex -> {
            if (ex == null) {
                Controllers.showToast(title);
            } else {
                String message = MdtbbsMessages.describe(ex);
                LOG.warning("MDTBBS resource task failed: " + message, ex);
                Controllers.dialog(failurePrefix + "\n" + message,
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR);
            }
        }).setName(title);
        TaskExecutor executor = pipeline.executor();
        TaskCancellationAction cancel = new TaskCancellationAction(
                it -> it.fireEvent(new DialogCloseEvent()));
        Controllers.taskDialog(executor, title, cancel);
        executor.start();
    }

    /// Returns the selected instance or warns the user.
    private @Nullable MindustryVersion requireInstance() {
        MindustryVersion target = targetVersion.getSelectionModel().getSelectedItem();
        if (target == null) {
            Controllers.dialog(i18n("xenon.resource.install.no_instance"),
                    i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
            return null;
        }
        return target;
    }

    /// Resolves the data directory of an instance.
    private static Path dataDirectory(MindustryVersion version) {
        XenonGameRepository repository = MindustryImportFlow.repositoryForVersion(version);
        return version.resolveDataDir(repository.getVersionRoot(version));
    }

    // ------------------------------------------------------------------
    // Upload
    // ------------------------------------------------------------------

    /// Opens the upload dialog matching the current mode.
    private void openUploadDialog() {
        if (!community.isLoggedIn()) {
            Controllers.showToast(i18n("xenon.resource.upload.need_login"));
            return;
        }
        if (mapMode) {
            openMapUploadDialog();
        } else {
            openBlueprintUploadDialog();
        }
    }

    /// Opens the blueprint composer.
    private void openBlueprintUploadDialog() {
        JFXTextField titleField = new JFXTextField();
        titleField.setPromptText(i18n("xenon.resource.upload.title"));
        JFXTextField tagsField = new JFXTextField();
        tagsField.setPromptText(i18n("xenon.resource.upload.tags"));
        JFXTextArea descriptionField = new JFXTextArea();
        descriptionField.setPrefRowCount(2);
        descriptionField.setPromptText(i18n("xenon.resource.upload.description"));
        JFXTextArea codeField = new JFXTextArea();
        codeField.setPrefRowCount(6);
        codeField.setPromptText(i18n("xenon.resource.upload.code"));

        JFXButton choose = FXUtils.newRaisedButton(i18n("xenon.resource.upload.choose"));
        choose.setOnAction(e -> chooseSchematic(codeField));

        VBox body = new VBox(8,
                new Label(i18n("xenon.resource.upload.title")), titleField,
                new Label(i18n("xenon.resource.upload.description")), descriptionField,
                new Label(i18n("xenon.resource.upload.tags")), tagsField,
                new Label(i18n("xenon.resource.upload.code")), codeField, choose);
        body.setPadding(new Insets(4, 0, 0, 0));

        JFXDialogLayout layout = new JFXDialogLayout();
        layout.setHeading(new Label(i18n("xenon.resource.upload.blueprint")));
        layout.setBody(body);

        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.setOnAction(e -> layout.fireEvent(new DialogCloseEvent()));
        JFXButton submit = new JFXButton(i18n("xenon.resource.upload.submit"));
        submit.getStyleClass().add("dialog-accept");
        submit.setOnAction(e -> {
            String title = titleField.getText() == null ? "" : titleField.getText().trim();
            String code = codeField.getText() == null ? "" : codeField.getText().replaceAll("\\s+", "");
            if (title.isBlank() || code.isBlank()) {
                Controllers.dialog(i18n("xenon.resource.upload.required"),
                        i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
                return;
            }
            String description = descriptionField.getText() == null ? "" : descriptionField.getText().trim();
            List<String> tags = parseTags(tagsField.getText());
            submit.setDisable(true);
            Schedulers.io().execute(() -> {
                try {
                    client.uploadBlueprint(title, description, tags, code);
                    Platform.runLater(() -> {
                        layout.fireEvent(new DialogCloseEvent());
                        Controllers.showToast(i18n("xenon.resource.upload.done"));
                        restartQuery();
                    });
                } catch (IOException ex) {
                    Platform.runLater(() -> {
                        submit.setDisable(false);
                        Controllers.dialog(MdtbbsMessages.describe(ex),
                                i18n("message.error"), MessageDialogPane.MessageType.ERROR);
                    });
                }
            });
        });
        layout.setActions(cancel, submit);
        Controllers.dialog(layout);
    }

    /// Opens the map composer after choosing a local `.msav` file.
    private void openMapUploadDialog() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("xenon.resource.upload.choose"));
        chooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("Mindustry map (*.msav)", "*.msav"));
        Path file = FileUtils.toPath(FXUtils.showOpenDialog(chooser, Controllers.getStage()));
        if (file == null) return;

        JFXTextField titleField = new JFXTextField();
        titleField.setPromptText(i18n("xenon.resource.upload.title"));
        JFXTextField tagsField = new JFXTextField();
        tagsField.setPromptText(i18n("xenon.resource.upload.tags"));
        JFXTextArea descriptionField = new JFXTextArea();
        descriptionField.setPrefRowCount(2);
        descriptionField.setPromptText(i18n("xenon.resource.upload.description"));

        VBox body = new VBox(8,
                new Label(file.getFileName().toString()),
                new Label(i18n("xenon.resource.upload.title")), titleField,
                new Label(i18n("xenon.resource.upload.description")), descriptionField,
                new Label(i18n("xenon.resource.upload.tags")), tagsField);
        body.setPadding(new Insets(4, 0, 0, 0));

        JFXDialogLayout layout = new JFXDialogLayout();
        layout.setHeading(new Label(i18n("xenon.resource.upload.map")));
        layout.setBody(body);

        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.setOnAction(e -> layout.fireEvent(new DialogCloseEvent()));
        JFXButton submit = new JFXButton(i18n("xenon.resource.upload.submit"));
        submit.getStyleClass().add("dialog-accept");
        submit.setOnAction(e -> {
            String title = titleField.getText() == null ? "" : titleField.getText().trim();
            if (title.isBlank()) {
                Controllers.dialog(i18n("xenon.resource.upload.required"),
                        i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
                return;
            }
            String description = descriptionField.getText() == null ? "" : descriptionField.getText().trim();
            List<String> tags = parseTags(tagsField.getText());
            layout.fireEvent(new DialogCloseEvent());
            Task<Void> task = Task.runAsync(Schedulers.io(), () -> {
                client.uploadMap(file, title, description, tags);
            }).setName(i18n("xenon.resource.upload.map"));
            runTask(task, i18n("xenon.resource.upload.done"),
                    i18n("xenon.resource.upload.failed"));
        });
        layout.setActions(cancel, submit);
        Controllers.dialog(layout);
    }

    /// Loads a local schematic file into the code field as base64.
    private static void chooseSchematic(JFXTextArea codeField) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("xenon.resource.upload.choose"));
        chooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("Mindustry schematic (*.msch)", "*.msch"));
        Path file = FileUtils.toPath(FXUtils.showOpenDialog(chooser, Controllers.getStage()));
        if (file == null) return;
        Schedulers.io().execute(() -> {
            try {
                String encoded = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
                Platform.runLater(() -> codeField.setText(encoded));
            } catch (IOException e) {
                Platform.runLater(() -> Controllers.dialog(
                        MdtbbsMessages.describe(e),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    /// Splits a comma-separated tag field.
    private static List<String> parseTags(@Nullable String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> tags = new ArrayList<>();
        for (String part : text.split("[,，]")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !tags.contains(trimmed)) tags.add(trimmed);
        }
        return tags;
    }

    /// Truncates long text for card previews.
    private static String truncate(@Nullable String text, int maxLength) {
        if (text == null) return "";
        String normalized = text.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }

    /// Returns the value, or the fallback when it is blank.
    private static String blankAs(@Nullable String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
