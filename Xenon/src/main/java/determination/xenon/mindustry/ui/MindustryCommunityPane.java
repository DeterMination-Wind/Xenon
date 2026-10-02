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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXComboBox;
import com.jfoenix.controls.JFXDialogLayout;
import com.jfoenix.controls.JFXTextArea;
import com.jfoenix.controls.JFXTextField;
import determination.xenon.mindustry.community.MdtbbsCommunity;
import determination.xenon.mindustry.community.MdtbbsForumClient;
import determination.xenon.mindustry.community.MdtbbsForumClient.Author;
import determination.xenon.mindustry.community.MdtbbsForumClient.Category;
import determination.xenon.mindustry.community.MdtbbsForumClient.Me;
import determination.xenon.mindustry.community.MdtbbsForumClient.Notification;
import determination.xenon.mindustry.community.MdtbbsForumClient.Page;
import determination.xenon.mindustry.community.MdtbbsForumClient.Reply;
import determination.xenon.mindustry.community.MdtbbsForumClient.ThreadDetail;
import determination.xenon.mindustry.community.MdtbbsForumClient.ThreadSummary;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.SVG;
import determination.xenon.ui.construct.DialogCloseEvent;
import determination.xenon.ui.construct.MessageDialogPane;
import determination.xenon.ui.construct.PageAware;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Native MDTBBS forum page for the launcher sidebar.
///
/// The page lists threads with category chips and server-side search, renders
/// the canonical Tiptap body natively (no `WebView` is available), and supports
/// OAuth login, notifications, replies, likes and bookmarks. Write actions stay
/// hidden until the corresponding capability and user permission are available.
public final class MindustryCommunityPane extends BorderPane implements PageAware {
    /// Threads fetched per page.
    private static final int PAGE_SIZE = 20;
    /// Scroll fraction that triggers the next thread page.
    private static final double LOAD_MORE_THRESHOLD = 0.88;
    /// Timestamp format used in list and reply headers.
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /// Shared account coordinator.
    private final MdtbbsCommunity community = CommunityServices.community();
    /// Shared forum client.
    private final MdtbbsForumClient forum = CommunityServices.forum();

    /// Page title.
    private final Label title = new Label(i18n("xenon.community"));
    /// Short usage hint.
    private final Label hint = new Label(i18n("xenon.community.hint"));
    /// Back-to-list button, visible only in detail mode.
    private final JFXButton back = FXUtils.newRaisedButton(i18n("xenon.community.back"));
    /// Debounced search field.
    private final JFXTextField search = new JFXTextField();
    /// Login/account controls.
    private final HBox accountBox = new HBox(8);
    /// Category filter chips.
    private final FlowPane categoryChips = new FlowPane(8, 8);
    /// List status line.
    private final Label status = new Label();
    /// Thread card container.
    private final VBox threadList = new VBox(10);
    /// Scrollable thread list.
    private final ScrollPane listScroll = new ScrollPane(threadList);
    /// Empty/loading/error notice overlaying the list.
    private final Label notice = new Label();
    /// Swappable center area.
    private final StackPane center = new StackPane();
    /// Detail content container.
    private final VBox detail = new VBox(10);
    /// Scrollable detail view.
    private final ScrollPane detailScroll = new ScrollPane(detail);
    /// Reply list in the open thread.
    private final VBox replyList = new VBox(8);
    /// Load-more button for replies.
    private final JFXButton moreReplies = FXUtils.newRaisedButton(i18n("xenon.community.replies.more"));
    /// Search debounce timer.
    private final PauseTransition searchPause = new PauseTransition(Duration.millis(350));

    /// Loaded categories.
    private final List<Category> categories = new ArrayList<>();
    /// Loaded thread summaries.
    private final List<ThreadSummary> threads = new ArrayList<>();

    /// Selected category filter, or `null` for all.
    private @Nullable Long selectedCategoryId;
    /// Currently open thread id, or -1 in list mode.
    private long openThreadId = -1;
    /// Cached current user, or `null` when unknown/logged out.
    private @Nullable Me me;
    /// Loaded notification count.
    private long unread;
    /// Whether a thread page is in flight.
    private boolean loading;
    /// Whether more thread pages exist.
    private boolean hasMore = true;
    /// Next thread page number.
    private int nextPage = 1;
    /// Next reply page number.
    private int nextReplyPage = 1;
    /// Generation token guarding stale list responses.
    private long generation;
    /// Generation token guarding stale detail responses.
    private long detailGeneration;
    /// Last list error message, or `null`.
    private @Nullable String lastError;

    /// Builds the community page and starts the initial load.
    public MindustryCommunityPane() {
        setPadding(new Insets(12));

        title.getStyleClass().add("title");
        hint.setWrapText(true);
        hint.getStyleClass().add("subtitle-label");

        back.setGraphic(SVG.ARROW_BACK.createIcon(18));
        back.setVisible(false);
        back.setManaged(false);
        back.setOnAction(e -> showList());

        search.setPromptText(i18n("xenon.community.search"));
        HBox.setHgrow(search, Priority.ALWAYS);
        search.textProperty().addListener((obs, oldValue, newValue) -> searchPause.playFromStart());
        searchPause.setOnFinished(e -> restartQuery());

        JFXButton refresh = FXUtils.newRaisedButton(i18n("xenon.community.refresh"));
        refresh.setOnAction(e -> restartQuery());

        HBox toolbar = new HBox(8, search, refresh);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        accountBox.setAlignment(Pos.CENTER_RIGHT);

        HBox titleRow = new HBox(8, back, title, spacer(), accountBox);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        categoryChips.setAlignment(Pos.CENTER_LEFT);

        VBox header = new VBox(6, titleRow, hint, toolbar, categoryChips, status);
        header.setPadding(new Insets(0, 0, 8, 0));
        setTop(header);

        listScroll.setFitToWidth(true);
        listScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        FXUtils.smoothScrolling(listScroll);
        listScroll.vvalueProperty().addListener((obs, oldValue, newValue) -> {
            if (openThreadId < 0 && newValue.doubleValue() >= LOAD_MORE_THRESHOLD) {
                loadNextPage();
            }
        });

        detailScroll.setFitToWidth(true);
        detailScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        FXUtils.smoothScrolling(detailScroll);

        notice.getStyleClass().add("subtitle-label");
        notice.setWrapText(true);
        notice.setTextOverrun(OverrunStyle.CLIP);
        notice.setMaxWidth(Double.MAX_VALUE);
        notice.setAlignment(Pos.CENTER);
        notice.setPadding(new Insets(24));
        FXUtils.onClicked(notice, this::handleNoticeClick);

        center.getChildren().setAll(listScroll, notice);
        StackPane.setAlignment(notice, Pos.TOP_CENTER);
        setCenter(center);

        rebuildCategoryChips();
        refreshAccountBox();
        restartQuery();
        loadCategories();
    }

    @Override
    public void onPageShown() {
        refreshAccountBox();
        if (community.isLoggedIn()) {
            loadMe();
            refreshUnread();
        }
    }

    // ------------------------------------------------------------------
    // Data loading
    // ------------------------------------------------------------------

    /// Loads categories once per page instance.
    private void loadCategories() {
        Schedulers.io().execute(() -> {
            try {
                List<Category> loadedCategories = forum.categories();
                Platform.runLater(() -> {
                    categories.clear();
                    categories.addAll(loadedCategories);
                    rebuildCategoryChips();
                });
            } catch (IOException e) {
                LOG.warning("Failed to load MDTBBS categories", e);
            }
        });
    }

    /// Reloads the current user profile.
    private void loadMe() {
        Schedulers.io().execute(() -> {
            try {
                Me profile = forum.me();
                Platform.runLater(() -> {
                    me = profile;
                    refreshAccountBox();
                });
            } catch (IOException e) {
                LOG.warning("Failed to load MDTBBS profile", e);
            }
        });
    }

    /// Reloads the unread notification count.
    private void refreshUnread() {
        Schedulers.io().execute(() -> {
            try {
                long count = forum.unreadNotificationCount();
                Platform.runLater(() -> {
                    unread = count;
                    refreshAccountBox();
                });
            } catch (IOException e) {
                LOG.warning("Failed to load MDTBBS notifications", e);
            }
        });
    }

    /// Clears the list and starts from page one.
    private void restartQuery() {
        generation++;
        threads.clear();
        threadList.getChildren().clear();
        nextPage = 1;
        hasMore = true;
        loading = false;
        lastError = null;
        showList();
        loadNextPage();
    }

    /// Loads the next thread page.
    private void loadNextPage() {
        if (loading || !hasMore) return;
        loading = true;
        long token = generation;
        int page = nextPage;
        String query = search.getText() == null ? "" : search.getText().trim();
        Long categoryId = selectedCategoryId;
        updateNotice();
        updateStatus();
        Schedulers.io().execute(() -> {
            try {
                Page<ThreadSummary> result = forum.threads(categoryId, page, PAGE_SIZE, query);
                Platform.runLater(() -> publishPage(token, result));
            } catch (IOException e) {
                Platform.runLater(() -> publishError(token, e));
            }
        });
    }

    /// Appends a loaded page to the list.
    private void publishPage(long token, Page<ThreadSummary> result) {
        if (token != generation) return;
        loading = false;
        threads.addAll(result.items());
        if (threads.size() >= result.total() && result.total() >= 0) {
            hasMore = false;
        } else {
            hasMore = result.hasMore() || result.items().size() >= PAGE_SIZE;
        }
        nextPage++;
        renderThreads();
        updateNotice();
        updateStatus();
    }

    /// Publishes a failed page load.
    private void publishError(long token, IOException error) {
        if (token != generation) return;
        loading = false;
        lastError = describe(error);
        updateNotice();
        updateStatus();
    }

    // ------------------------------------------------------------------
    // List rendering
    // ------------------------------------------------------------------

    /// Rebuilds all thread cards.
    private void renderThreads() {
        threadList.getChildren().setAll(threads.stream()
                .map(this::createThreadCard)
                .toList());
    }

    /// Creates one clickable thread card.
    private Node createThreadCard(ThreadSummary thread) {
        Label titleLabel = new Label(thread.title());
        titleLabel.getStyleClass().add("title-label");
        titleLabel.setWrapText(true);
        titleLabel.setMaxWidth(Double.MAX_VALUE);

        HBox badges = new HBox(6);
        badges.setAlignment(Pos.CENTER_LEFT);
        if (thread.pinned()) badges.getChildren().add(badge(i18n("xenon.community.pinned")));
        if (thread.locked()) badges.getChildren().add(badge(i18n("xenon.community.locked")));
        if (thread.category() != null && !thread.category().name().isBlank()) {
            badges.getChildren().add(badge(thread.category().name()));
        }
        badges.setVisible(!badges.getChildren().isEmpty());
        badges.setManaged(!badges.getChildren().isEmpty());

        Label meta = new Label(i18n("xenon.community.thread.meta.detail",
                DATE_FORMAT.format(thread.createdAt()), thread.viewCount(), thread.replyCount()));
        meta.getStyleClass().add("subtitle-label");
        meta.setWrapText(true);
        HBox metaRow = new HBox(6, authorNode(thread.author()), meta);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        Label excerpt = new Label(truncate(thread.excerpt(), 180));
        excerpt.getStyleClass().add("subtitle-label");
        excerpt.setWrapText(true);

        VBox card = new VBox(6, badges, titleLabel, metaRow, excerpt);
        card.getStyleClass().add("community-card");
        card.setPadding(new Insets(12));
        card.setMaxWidth(Double.MAX_VALUE);
        FXUtils.onClicked(card, () -> openThread(thread.id()));
        return card;
    }

    /// Creates a clickable author label that opens the web profile.
    ///
    /// @param author author summary, or `null` when the API omitted it
    /// @return a link-styled label, or a plain label for unknown authors
    private static Node authorNode(@Nullable Author author) {
        if (author == null || author.username().isBlank()) {
            Label anonymous = new Label(i18n("xenon.community.anonymous"));
            anonymous.getStyleClass().add("subtitle-label");
            return anonymous;
        }
        Label link = new Label(author.username());
        link.getStyleClass().add("community-author");
        FXUtils.installFastTooltip(link, i18n("xenon.mdtbbs.author.open"));
        if (author.id() > 0) {
            FXUtils.onClicked(link, () -> FXUtils.openLink(MdtbbsForumClient.profileUrl(author.id())));
        }
        return link;
    }

    /// Creates one small badge label.
    private static Label badge(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("mindustry-map-tag");
        return label;
    }

    /// Rebuilds the category chips from the loaded categories.
    private void rebuildCategoryChips() {
        List<ToggleButton> chips = new ArrayList<>();
        chips.add(newChip(i18n("xenon.community.all"), selectedCategoryId == null, () -> {
            selectedCategoryId = null;
            rebuildCategoryChips();
            restartQuery();
        }));
        for (Category category : categories) {
            boolean selected = selectedCategoryId != null && selectedCategoryId == category.id();
            chips.add(newChip(category.name(), selected, () -> {
                selectedCategoryId = selected ? null : category.id();
                rebuildCategoryChips();
                restartQuery();
            }));
        }
        categoryChips.getChildren().setAll(chips);
    }

    /// Creates one toggle-style filter chip.
    private static ToggleButton newChip(String text, boolean selected, Runnable action) {
        ToggleButton chip = new ToggleButton(text);
        chip.getStyleClass().add("mindustry-map-chip");
        chip.setSelected(selected);
        chip.setOnAction(e -> action.run());
        return chip;
    }

    /// Updates the empty/loading/error notice.
    private void updateNotice() {
        boolean showNotice = openThreadId < 0 && threads.isEmpty();
        notice.setVisible(showNotice);
        notice.setManaged(showNotice);
        listScroll.setVisible(!showNotice);
        listScroll.setManaged(!showNotice);
        if (!showNotice) return;
        if (loading) {
            notice.setText(i18n("xenon.community.loading"));
        } else if (lastError != null) {
            notice.setText(i18n("xenon.community.failed", lastError));
        } else if (!hasMore) {
            notice.setText(i18n("xenon.community.empty"));
        } else {
            notice.setText(i18n("xenon.community.loading"));
        }
    }

    /// Updates the list status line.
    private void updateStatus() {
        if (openThreadId >= 0) {
            status.setText("");
            return;
        }
        if (loading && threads.isEmpty()) {
            status.setText(i18n("xenon.community.loading"));
            return;
        }
        if (lastError != null && threads.isEmpty()) {
            status.setText(i18n("xenon.community.failed", lastError));
            return;
        }
        status.setText(i18n("xenon.community.count", threads.size()));
    }

    /// Retries the current query when the notice is clicked.
    private void handleNoticeClick() {
        if (loading) return;
        if (lastError != null && threads.isEmpty()) {
            restartQuery();
        } else if (!hasMore) {
            restartQuery();
        } else {
            loadNextPage();
        }
    }

    // ------------------------------------------------------------------
    // Detail view
    // ------------------------------------------------------------------

    /// Opens a thread and loads its detail.
    private void openThread(long threadId) {
        openThreadId = threadId;
        long token = ++detailGeneration;
        showDetail();
        detail.getChildren().setAll(new Label(i18n("xenon.community.loading")));
        Schedulers.io().execute(() -> {
            try {
                ThreadDetail thread = forum.thread(threadId);
                Platform.runLater(() -> {
                    if (token == detailGeneration) renderDetail(thread);
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    if (token != detailGeneration) return;
                    showList();
                    Controllers.dialog(describe(e), i18n("message.error"),
                            MessageDialogPane.MessageType.ERROR);
                });
            }
        });
    }

    /// Switches the center area to detail mode.
    private void showDetail() {
        back.setVisible(true);
        back.setManaged(true);
        center.getChildren().setAll(detailScroll);
        updateStatus();
    }

    /// Switches the center area back to the list.
    private void showList() {
        openThreadId = -1;
        detailGeneration++;
        back.setVisible(false);
        back.setManaged(false);
        center.getChildren().setAll(listScroll, notice);
        updateNotice();
        updateStatus();
    }

    /// Renders one loaded thread detail.
    private void renderDetail(ThreadDetail thread) {
        Label titleLabel = new Label(thread.title());
        titleLabel.getStyleClass().add("title");
        titleLabel.setWrapText(true);

        HBox badges = new HBox(6);
        badges.setAlignment(Pos.CENTER_LEFT);
        if (thread.pinned()) badges.getChildren().add(badge(i18n("xenon.community.pinned")));
        if (thread.locked()) badges.getChildren().add(badge(i18n("xenon.community.locked")));
        if (thread.category() != null && !thread.category().name().isBlank()) {
            badges.getChildren().add(badge(thread.category().name()));
        }

        Label meta = new Label(i18n("xenon.community.thread.meta.detail",
                DATE_FORMAT.format(thread.createdAt()), thread.viewCount(), thread.replyCount()));
        meta.getStyleClass().add("subtitle-label");
        meta.setWrapText(true);
        HBox metaRow = new HBox(6, authorNode(thread.author()), meta);
        metaRow.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(8);
        body.setMaxWidth(Double.MAX_VALUE);
        renderRichContent(thread.contentJson(), thread.contentText(), body);

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (community.isLoggedIn()) {
            ToggleButton like = new ToggleButton(thread.likeCount() > 0
                    ? i18n("xenon.community.like.count", thread.likeCount())
                    : i18n("xenon.community.like"));
            like.getStyleClass().add("mindustry-map-chip");
            like.setSelected(thread.liked());
            like.setOnAction(e -> setLiked(thread.id(), like));
            ToggleButton bookmark = new ToggleButton(i18n("xenon.community.bookmark"));
            bookmark.getStyleClass().add("mindustry-map-chip");
            bookmark.setSelected(thread.bookmarked());
            bookmark.setOnAction(e -> setBookmarked(thread.id(), bookmark));
            actions.getChildren().setAll(like, bookmark);
        }

        replyList.getChildren().setAll(thread.replies().stream()
                .map(this::createReplyNode)
                .toList());
        replyList.setMaxWidth(Double.MAX_VALUE);
        nextReplyPage = 1;
        moreReplies.setVisible(thread.replies().size() < thread.replyCount());
        moreReplies.setManaged(moreReplies.isVisible());
        moreReplies.setOnAction(e -> loadMoreReplies(thread.id()));

        Label repliesTitle = new Label(i18n("xenon.community.replies", thread.replyCount()));
        repliesTitle.getStyleClass().add("title-label");

        detail.getChildren().setAll(
                titleLabel,
                badges,
                metaRow,
                body,
                actions,
                new Separator(),
                repliesTitle,
                replyList,
                moreReplies,
                createComposer(thread));
    }

    /// Appends the next reply page to the open thread.
    private void loadMoreReplies(long threadId) {
        moreReplies.setDisable(true);
        int page = ++nextReplyPage;
        Schedulers.io().execute(() -> {
            try {
                Page<Reply> result = forum.replies(threadId, page, PAGE_SIZE);
                Platform.runLater(() -> {
                    moreReplies.setDisable(false);
                    if (openThreadId != threadId) return;
                    for (Reply reply : result.items()) {
                        replyList.getChildren().add(createReplyNode(reply));
                    }
                    if (result.items().size() < PAGE_SIZE || !result.hasMore()) {
                        moreReplies.setVisible(false);
                        moreReplies.setManaged(false);
                    }
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    moreReplies.setDisable(false);
                    Controllers.dialog(describe(e), i18n("message.error"),
                            MessageDialogPane.MessageType.ERROR);
                });
            }
        });
    }

    /// Creates one reply node.
    private Node createReplyNode(Reply reply) {
        Label time = new Label(DATE_FORMAT.format(reply.createdAt()));
        time.getStyleClass().add("subtitle-label");
        HBox header = new HBox(6, authorNode(reply.author()), time);
        header.setAlignment(Pos.CENTER_LEFT);

        TextFlow body = new TextFlow();
        body.setMaxWidth(Double.MAX_VALUE);
        renderRichContent(reply.contentJson(), reply.contentText(), body);

        VBox node = new VBox(4, header, body);
        node.getStyleClass().add("community-reply");
        node.setPadding(new Insets(8));
        node.setMaxWidth(Double.MAX_VALUE);
        return node;
    }

    /// Creates the reply composer for the open thread.
    private Node createComposer(ThreadDetail thread) {
        VBox box = new VBox(6);
        box.setMaxWidth(Double.MAX_VALUE);
        if (!community.isLoggedIn()) {
            box.getChildren().add(hintLabel(i18n("xenon.community.reply.login")));
            return box;
        }
        if (thread.locked()) {
            box.getChildren().add(hintLabel(i18n("xenon.community.locked.hint")));
            return box;
        }
        if (me != null && !me.canReply()) {
            box.getChildren().add(hintLabel(reasonText(me.replyCreateReason())));
            return box;
        }

        JFXTextArea input = new JFXTextArea();
        input.setPromptText(i18n("xenon.community.reply.placeholder"));
        input.setPrefRowCount(3);
        input.setMaxWidth(Double.MAX_VALUE);

        JFXButton send = FXUtils.newRaisedButton(i18n("xenon.community.reply.send"));
        send.setOnAction(e -> {
            String markdown = input.getText() == null ? "" : input.getText().trim();
            if (markdown.isBlank()) return;
            send.setDisable(true);
            Schedulers.io().execute(() -> {
                try {
                    forum.createReply(thread.id(), markdown, null);
                    Platform.runLater(() -> {
                        Controllers.showToast(i18n("xenon.community.reply.sent"));
                        openThread(thread.id());
                    });
                } catch (IOException ex) {
                    Platform.runLater(() -> {
                        send.setDisable(false);
                        Controllers.dialog(describe(ex), i18n("message.error"),
                                MessageDialogPane.MessageType.ERROR);
                    });
                }
            });
        });

        box.getChildren().setAll(input, send);
        return box;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /// Updates the like state of a thread.
    private void setLiked(long threadId, ToggleButton button) {
        boolean liked = button.isSelected();
        Schedulers.io().execute(() -> {
            try {
                forum.setThreadLiked(threadId, liked);
            } catch (IOException e) {
                Platform.runLater(() -> {
                    button.setSelected(!liked);
                    Controllers.dialog(describe(e), i18n("message.error"),
                            MessageDialogPane.MessageType.ERROR);
                });
            }
        });
    }

    /// Updates the bookmark state of a thread.
    private void setBookmarked(long threadId, ToggleButton button) {
        boolean bookmarked = button.isSelected();
        Schedulers.io().execute(() -> {
            try {
                forum.setThreadBookmarked(threadId, bookmarked);
            } catch (IOException e) {
                Platform.runLater(() -> {
                    button.setSelected(!bookmarked);
                    Controllers.dialog(describe(e), i18n("message.error"),
                            MessageDialogPane.MessageType.ERROR);
                });
            }
        });
    }

    /// Starts the interactive browser login.
    private void login() {
        Controllers.showToast(i18n("xenon.community.login.pending"));
        Schedulers.io().execute(() -> {
            try {
                community.login(FXUtils::openLink);
                Me profile = forum.me();
                long count = forum.unreadNotificationCount();
                Platform.runLater(() -> {
                    me = profile;
                    unread = count;
                    CommunityServices.presence().start();
                    refreshAccountBox();
                    Controllers.showToast(i18n("xenon.community.login.done", profile.username()));
                });
            } catch (IOException e) {
                LOG.warning("MDTBBS login failed", e);
                Platform.runLater(() -> Controllers.dialog(describe(e),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    /// Opens the new-thread composer.
    private void openComposeDialog() {
        if (!community.isLoggedIn()) {
            Controllers.showToast(i18n("xenon.community.reply.login"));
            return;
        }
        if (me != null && !me.canCreateThread()) {
            Controllers.dialog(reasonText(me.threadCreateReason()), i18n("message.warning"),
                    MessageDialogPane.MessageType.WARNING);
            return;
        }

        JFXTextField titleField = new JFXTextField();
        titleField.setPromptText(i18n("xenon.community.new_thread.title"));

        JFXComboBox<Category> categoryField = new JFXComboBox<>();
        categoryField.getItems().setAll(categories);
        categoryField.setConverter(new StringConverter<>() {
            @Override
            public String toString(Category category) {
                return category == null ? "" : category.name();
            }

            @Override
            public Category fromString(String string) {
                return null;
            }
        });
        if (!categories.isEmpty()) categoryField.getSelectionModel().selectFirst();

        JFXTextArea bodyField = new JFXTextArea();
        bodyField.setPromptText(i18n("xenon.community.new_thread.body"));
        bodyField.setPrefRowCount(8);

        VBox body = new VBox(8,
                new Label(i18n("xenon.community.new_thread.title")), titleField,
                new Label(i18n("xenon.community.new_thread.category")), categoryField,
                new Label(i18n("xenon.community.new_thread.body")), bodyField);
        body.setPadding(new Insets(4, 0, 0, 0));

        JFXDialogLayout layout = new JFXDialogLayout();
        layout.setHeading(new Label(i18n("xenon.community.new_thread")));
        layout.setBody(body);

        JFXButton cancel = new JFXButton(i18n("button.cancel"));
        cancel.setOnAction(e -> layout.fireEvent(new DialogCloseEvent()));
        JFXButton submit = new JFXButton(i18n("xenon.community.new_thread.submit"));
        submit.getStyleClass().add("dialog-accept");
        submit.setOnAction(e -> {
            String titleText = titleField.getText() == null ? "" : titleField.getText().trim();
            String bodyText = bodyField.getText() == null ? "" : bodyField.getText().trim();
            if (titleText.isBlank() || bodyText.isBlank()) {
                Controllers.dialog(i18n("xenon.community.new_thread.required"),
                        i18n("message.warning"), MessageDialogPane.MessageType.WARNING);
                return;
            }
            Category category = categoryField.getSelectionModel().getSelectedItem();
            long categoryId = category == null ? 0 : category.id();
            submit.setDisable(true);
            Schedulers.io().execute(() -> {
                try {
                    forum.createThread(titleText, categoryId, bodyText);
                    Platform.runLater(() -> {
                        layout.fireEvent(new DialogCloseEvent());
                        Controllers.showToast(i18n("xenon.community.thread.sent"));
                        restartQuery();
                    });
                } catch (IOException ex) {
                    Platform.runLater(() -> {
                        submit.setDisable(false);
                        Controllers.dialog(describe(ex), i18n("message.error"),
                                MessageDialogPane.MessageType.ERROR);
                    });
                }
            });
        });
        layout.setActions(cancel, submit);
        Controllers.dialog(layout);
    }

    /// Opens the notification list.
    private void openNotifications() {
        Schedulers.io().execute(() -> {
            try {
                Page<Notification> page = forum.notifications(1, 20);
                Platform.runLater(() -> {
                    VBox list = new VBox(8);
                    if (page.items().isEmpty()) {
                        list.getChildren().add(hintLabel(i18n("xenon.community.notifications.empty")));
                    }
                    for (Notification notification : page.items()) {
                        Label headline = new Label(notification.title().isBlank()
                                ? notification.type() : notification.title());
                        headline.getStyleClass().add("title-label");
                        headline.setWrapText(true);
                        Label bodyLabel = new Label(truncate(notification.body(), 160));
                        bodyLabel.getStyleClass().add("subtitle-label");
                        bodyLabel.setWrapText(true);
                        VBox entry = new VBox(3, headline, bodyLabel);
                        entry.setPadding(new Insets(6));
                        list.getChildren().add(entry);
                    }
                    ScrollPane scroll = new ScrollPane(list);
                    scroll.setFitToWidth(true);
                    scroll.setPrefViewportHeight(320);
                    JFXDialogLayout layout = new JFXDialogLayout();
                    layout.setHeading(new Label(i18n("xenon.community.notifications")));
                    layout.setBody(scroll);
                    JFXButton close = new JFXButton(i18n("button.ok"));
                    close.getStyleClass().add("dialog-accept");
                    close.setOnAction(e -> layout.fireEvent(new DialogCloseEvent()));
                    layout.setActions(close);
                    Controllers.dialog(layout);
                });
            } catch (IOException e) {
                Platform.runLater(() -> Controllers.dialog(describe(e),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    // ------------------------------------------------------------------
    // Account controls
    // ------------------------------------------------------------------

    /// Rebuilds the account area for the current session state.
    private void refreshAccountBox() {
        accountBox.getChildren().clear();
        if (!community.isConfigured()) {
            Label missing = hintLabel(i18n("xenon.community.client_id.missing"));
            FXUtils.installFastTooltip(missing, i18n("xenon.community.client_id.hint"));
            accountBox.getChildren().add(missing);
            return;
        }
        if (!community.isLoggedIn()) {
            JFXButton login = FXUtils.newRaisedButton(i18n("xenon.community.login"));
            login.setOnAction(e -> login());
            accountBox.getChildren().add(login);
            return;
        }

        String name = me == null ? i18n("xenon.community.account.pending") : me.username();
        Label account = new Label(name);
        account.getStyleClass().add("title-label");

        JFXButton notifications = FXUtils.newRaisedButton(unread > 0
                ? i18n("xenon.community.notifications.unread", unread)
                : i18n("xenon.community.notifications"));
        notifications.setOnAction(e -> openNotifications());

        JFXButton friends = FXUtils.newRaisedButton(i18n("xenon.social.title"));
        friends.setOnAction(e -> MdtbbsSocialDialog.show());

        JFXButton compose = FXUtils.newRaisedButton(i18n("xenon.community.new_thread"));
        compose.setGraphic(SVG.ADD.createIcon(16));
        compose.setOnAction(e -> openComposeDialog());

        JFXButton logout = FXUtils.newRaisedButton(i18n("xenon.community.logout"));
        logout.setOnAction(e -> {
            CommunityServices.presence().stop();
            community.logout();
            me = null;
            unread = 0;
            refreshAccountBox();
            Controllers.showToast(i18n("xenon.community.logout.done"));
        });

        accountBox.getChildren().setAll(account, notifications, friends, compose, logout);
    }

    // ------------------------------------------------------------------
    // Rich content rendering
    // ------------------------------------------------------------------

    /// Renders a Tiptap document into a container, falling back to plain text.
    private void renderRichContent(String contentJson, String contentText, VBox container) {
        if (contentJson == null || contentJson.isBlank()) {
            container.getChildren().add(paragraphLabel(contentText));
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(contentJson);
            if (!parsed.isJsonObject()) throw new IllegalStateException("not an object");
            JsonObject document = parsed.getAsJsonObject();
            appendBlockNodes(document.getAsJsonArray("content"), container);
            if (container.getChildren().isEmpty()) {
                container.getChildren().add(paragraphLabel(contentText));
            }
        } catch (RuntimeException e) {
            container.getChildren().add(paragraphLabel(contentText));
        }
    }

    /// Renders a Tiptap document into a text flow, falling back to plain text.
    private void renderRichContent(String contentJson, String contentText, TextFlow flow) {
        if (contentJson == null || contentJson.isBlank()) {
            flow.getChildren().add(new Text(contentText));
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(contentJson);
            if (!parsed.isJsonObject()) throw new IllegalStateException("not an object");
            JsonObject document = parsed.getAsJsonObject();
            appendInlineNodes(document.getAsJsonArray("content"), flow);
            if (flow.getChildren().isEmpty()) flow.getChildren().add(new Text(contentText));
        } catch (RuntimeException e) {
            flow.getChildren().add(new Text(contentText));
        }
    }

    /// Renders block-level Tiptap nodes.
    private void appendBlockNodes(@Nullable JsonArray nodes, VBox container) {
        if (nodes == null) return;
        for (JsonElement element : nodes) {
            if (!element.isJsonObject()) continue;
            JsonObject node = element.getAsJsonObject();
            String type = nodeType(node);
            switch (type) {
                case "paragraph" -> {
                    TextFlow flow = new TextFlow();
                    flow.setMaxWidth(Double.MAX_VALUE);
                    appendInlineNodes(node.getAsJsonArray("content"), flow);
                    if (!flow.getChildren().isEmpty()) container.getChildren().add(flow);
                }
                case "heading" -> {
                    TextFlow flow = new TextFlow();
                    flow.getStyleClass().add("community-heading");
                    flow.setMaxWidth(Double.MAX_VALUE);
                    appendInlineNodes(node.getAsJsonArray("content"), flow);
                    container.getChildren().add(flow);
                }
                case "bulletList", "orderedList", "taskList" -> {
                    appendList(node, container, type);
                }
                case "blockquote" -> {
                    VBox quote = new VBox(6);
                    quote.getStyleClass().add("community-quote");
                    appendBlockNodes(node.getAsJsonArray("content"), quote);
                    container.getChildren().add(quote);
                }
                case "codeBlock" -> container.getChildren().add(codeLabel(textOf(node)));
                case "horizontalRule" -> container.getChildren().add(new Separator());
                case "image" -> appendImage(node, container);
                default -> {
                    JsonArray children = node.getAsJsonArray("content");
                    if (children != null) appendBlockNodes(children, container);
                }
            }
        }
    }

    /// Renders inline Tiptap nodes into a text flow.
    private void appendInlineNodes(@Nullable JsonArray nodes, TextFlow flow) {
        if (nodes == null) return;
        for (JsonElement element : nodes) {
            if (!element.isJsonObject()) continue;
            JsonObject node = element.getAsJsonObject();
            String type = nodeType(node);
            switch (type) {
                case "text" -> flow.getChildren().add(styledText(node));
                case "hardBreak" -> flow.getChildren().add(new Text("\n"));
                case "mention", "customEmoji" -> {
                    Text mention = new Text(attributeText(node, "label"));
                    mention.getStyleClass().add("community-mention");
                    flow.getChildren().add(mention);
                }
                case "image" -> {
                    JsonArray content = node.getAsJsonArray("content");
                    if (content != null) appendInlineNodes(content, flow);
                }
                default -> {
                    JsonArray content = node.getAsJsonArray("content");
                    if (content != null) appendInlineNodes(content, flow);
                    else if (node.has("text")) flow.getChildren().add(styledText(node));
                }
            }
        }
    }

    /// Renders a list node as bullet rows.
    private void appendList(JsonObject node, VBox container, String type) {
        JsonArray items = node.getAsJsonArray("content");
        if (items == null) return;
        boolean ordered = "orderedList".equals(type);
        boolean tasks = "taskList".equals(type);
        int index = 1;
        for (JsonElement element : items) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            VBox itemBody = new VBox(4);
            itemBody.setMaxWidth(Double.MAX_VALUE);
            appendBlockNodes(item.getAsJsonArray("content"), itemBody);
            String marker;
            if (tasks) {
                marker = item.has("attrs") && item.getAsJsonObject("attrs").has("checked")
                        && item.getAsJsonObject("attrs").get("checked").getAsBoolean() ? "☑" : "☐";
            } else {
                marker = ordered ? index + "." : "•";
            }
            Label bullet = new Label(marker);
            bullet.getStyleClass().add("subtitle-label");
            HBox.setHgrow(itemBody, Priority.ALWAYS);
            HBox row = new HBox(8, bullet, itemBody);
            row.setMaxWidth(Double.MAX_VALUE);
            container.getChildren().add(row);
            index++;
        }
    }

    /// Renders an image block.
    private void appendImage(JsonObject node, VBox container) {
        String source = attributeText(node, "src");
        if (source.isBlank() || !source.startsWith("http")) return;
        ImageView view = new ImageView();
        view.setPreserveRatio(true);
        view.setSmooth(true);
        view.setFitWidth(480);
        view.imageProperty().bind(FXUtils.newRemoteImage(source, 960, 540, true, true));
        container.getChildren().add(view);
    }

    /// Builds a styled text node from Tiptap marks.
    private static Text styledText(JsonObject node) {
        Text text = new Text(node.has("text") ? node.get("text").getAsString() : "");
        JsonArray marks = node.getAsJsonArray("marks");
        if (marks == null) return text;
        for (JsonElement element : marks) {
            if (!element.isJsonObject()) continue;
            JsonObject mark = element.getAsJsonObject();
            switch (nodeType(mark)) {
                case "bold" -> text.setStyle("-fx-font-weight: bold;");
                case "italic" -> text.setStyle(text.getStyle() + "-fx-font-style: italic;");
                case "strike" -> text.setStrikethrough(true);
                case "underline" -> text.setUnderline(true);
                case "code" -> text.setStyle(text.getStyle() + "-fx-font-family: monospace;");
                case "link" -> {
                    text.getStyleClass().add("community-link");
                    text.setUnderline(true);
                    String href = attributeText(mark, "href");
                    if (href.startsWith("http")) {
                        text.setOnMouseClicked(e -> FXUtils.openLink(href));
                    }
                }
                default -> {
                }
            }
        }
        return text;
    }

    /// Extracts the concatenated text of a Tiptap node.
    private static String textOf(JsonObject node) {
        JsonArray content = node.getAsJsonArray("content");
        if (content == null) return "";
        StringBuilder builder = new StringBuilder();
        for (JsonElement element : content) {
            if (!element.isJsonObject()) continue;
            JsonObject child = element.getAsJsonObject();
            if (child.has("text")) builder.append(child.get("text").getAsString());
        }
        return builder.toString();
    }

    /// Reads the `type` field of a Tiptap node.
    private static String nodeType(JsonObject node) {
        JsonElement type = node.get("type");
        return type == null || type.isJsonNull() ? "" : type.getAsString();
    }

    /// Reads a string attribute of a Tiptap node.
    private static String attributeText(JsonObject node, String key) {
        JsonElement attrs = node.get("attrs");
        if (attrs == null || !attrs.isJsonObject()) return "";
        JsonElement value = attrs.getAsJsonObject().get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /// Builds a wrapped plain-text paragraph.
    private static Label paragraphLabel(String text) {
        Label label = new Label(text == null || text.isBlank() ? i18n("xenon.community.no_content") : text);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    /// Builds a monospace code label.
    private static Label codeLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("community-code");
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    /// Builds a small hint label.
    private static Label hintLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("subtitle-label");
        label.setWrapText(true);
        return label;
    }

    /// Flexible spacer for header rows.
    private static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    /// Maps permission denial reasons to localized text.
    private static String reasonText(@Nullable String reason) {
        if (reason == null || reason.isBlank()) return i18n("xenon.community.permission.denied");
        return switch (reason) {
            case "PHONE_VERIFICATION_REQUIRED" -> i18n("xenon.community.error.phone");
            case "TERMS_ACCEPTANCE_REQUIRED" -> i18n("xenon.community.error.terms");
            case "FEATURE_DISABLED" -> i18n("xenon.community.error.disabled");
            case "USER_BANNED" -> i18n("xenon.community.error.banned");
            default -> i18n("xenon.community.permission.denied");
        };
    }

    /// Maps API errors to user-facing text.
    private static String describe(IOException error) {
        return MdtbbsMessages.describe(error);
    }

    /// Truncates long text for card previews.
    private static String truncate(@Nullable String text, int maxLength) {
        if (text == null) return "";
        String normalized = text.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "…";
    }
}
