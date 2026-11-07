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
import determination.xenon.Metadata;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.ClientApp;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Preferences;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.Session;
import determination.xenon.mindustry.community.MdtbbsMultiplayerClient.SessionPage;
import determination.xenon.mindustry.community.MdtbbsSocialClient;
import determination.xenon.mindustry.netplay.MdtbbsNetplayManager;
import determination.xenon.mindustry.netplay.MdtbbsNetplayManager.JoinRequest;
import determination.xenon.netplay.EasyTierRuntime;
import determination.xenon.netplay.PublicServerCatalog;
import determination.xenon.setting.Profiles;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.AdvancedListItem;
import determination.xenon.ui.construct.MessageDialogPane;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Netplay workspace: Mindustry's public server directory plus EasyTier P2P
/// rooms.
///
/// Public servers come from Anuken's official server list. A P2P room joins
/// an EasyTier virtual network derived from a six-digit room code, so two
/// players behind NAT can reach each other; hosting and joining still happen
/// inside Mindustry.
@NotNullByDefault
public final class MindustryNetplayPane extends BorderPane {

    /// Shared runtime so navigating away does not orphan a room process.
    private static EasyTierRuntime sharedRuntime;

    private final PublicServerCatalog catalog =
            new PublicServerCatalog(Metadata.getCachesDirectory());
    private final EasyTierRuntime runtime = runtime();

    private final VBox serverBox = new VBox(2);
    private final VBox peerBox = new VBox(2);
    private final Label serverStatus = new Label();
    private final Label roomStatus = new Label();
    private final Label hostAddress = new Label();
    private final TextField roomField = new TextField();

    /// Shared MDTBBS multiplayer session manager.
    private final MdtbbsNetplayManager mdtbbs = CommunityServices.netplay();
    private final ComboBox<Choice> mdtbbsVisibility = new ComboBox<>();
    private final ComboBox<Choice> mdtbbsJoinPolicy = new ComboBox<>();
    private final CheckBox mdtbbsApproval = new CheckBox();
    private final Label mdtbbsStatus = new Label();
    private final Label mdtbbsAddress = new Label();
    private final VBox mdtbbsLobbyBox = new VBox(2);
    private final Label mdtbbsLobbyStatus = new Label();
    private final VBox mdtbbsApprovalBox = new VBox(2);
    private final JFXButton mdtbbsDefaultClient = FXUtils.newRaisedButton(i18n("xenon.netplay.mdtbbs.default_client"));
    private boolean lobbyLoaded;
    private final TextField mdtbbsCodeField = new TextField();
    private final JFXButton mdtbbsCreate = FXUtils.newRaisedButton(i18n("xenon.netplay.mdtbbs.create"));
    private final JFXButton mdtbbsInvite = FXUtils.newRaisedButton(i18n("xenon.netplay.mdtbbs.invite"));
    private final JFXButton mdtbbsLeave = FXUtils.newRaisedButton(i18n("xenon.netplay.mdtbbs.leave"));
    private final JFXButton mdtbbsJoin = FXUtils.newRaisedButton(i18n("xenon.netplay.mdtbbs.join"));
    private final JFXButton mdtbbsCopyAddress = FXUtils.newRaisedButton(i18n("xenon.netplay.mdtbbs.copy_address"));
    private final JFXButton mdtbbsCopyCode = FXUtils.newRaisedButton(i18n("xenon.netplay.mdtbbs.copy_code"));
    private final JFXButton mdtbbsLobbyRefresh = FXUtils.newRaisedButton(i18n("button.refresh"));

    /// Reloads the server-authoritative pending join-request list.
    private final JFXButton mdtbbsApprovalRefresh = FXUtils.newRaisedButton(i18n("button.refresh"));

    /// Feedback label of the open invite dialog, or `null` when it is closed.
    private @Nullable Label mdtbbsInviteFeedback;

    /// Friend name targeted by the last invite click in the open dialog.
    private String mdtbbsInviteTarget = "";

    /// User id targeted by the last invite click in the open dialog.
    private String mdtbbsInviteTargetId = "";

    /// Retry affordance for the `SESSION_ALREADY_ACTIVE` zombie-session recovery.
    private final JFXButton mdtbbsRetryCreate =
            FXUtils.newRaisedButton(i18n("xenon.netplay.mdtbbs.session_active.retry"));

    /// Returns the process-wide EasyTier runtime, installing the shutdown
    /// hook that stops the room when the launcher exits.
    private static synchronized EasyTierRuntime runtime() {
        if (sharedRuntime == null) {
            sharedRuntime = new EasyTierRuntime(
                    Metadata.XENON_GLOBAL_DIRECTORY.resolve("netplay").resolve("easytier"),
                    Metadata.getCachesDirectory());
            Runtime.getRuntime().addShutdownHook(
                    new Thread(sharedRuntime::stop, "xenon-easytier-stop"));
        }
        return sharedRuntime;
    }

    /// Builds the page.
    public MindustryNetplayPane() {
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.netplay.title"));
        title.getStyleClass().add("title");
        Label hint = new Label(i18n("xenon.netplay.hint"));
        hint.setWrapText(true);

        JFXButton refresh = FXUtils.newRaisedButton(i18n("button.refresh"));
        refresh.setOnAction(e -> refreshServers());
        JFXButton install = FXUtils.newRaisedButton(i18n("xenon.netplay.easytier.install"));
        install.setOnAction(e -> ensureEasyTier(this::updateRoomStatus));
        HBox toolbar = new HBox(8, refresh, install);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10,
                title, hint,
                mdtbbsControls(),
                mdtbbsApprovalSection(),
                mdtbbsLobbySection(),
                toolbar, serverStatus,
                section(i18n("xenon.netplay.servers.title"), serverBox, 240),
                roomControls(),
                section(i18n("xenon.netplay.room.peers.title"), peerBox, 180));
        root.setFillWidth(true);

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        FXUtils.smoothScrolling(scroll);
        setCenter(scroll);

        mdtbbs.setStateListener(() -> Platform.runLater(this::refreshMdtbbs));
        refreshServers();
        updateRoomStatus();
        refreshMdtbbs();
    }

    /// Builds the host-side join-request block.
    private VBox mdtbbsApprovalSection() {
        Label title = new Label(i18n("xenon.netplay.mdtbbs.approval.title"));
        title.setStyle("-fx-font-weight: BOLD;");
        mdtbbsApprovalRefresh.setOnAction(e -> mdtbbs.refreshJoinRequests());
        HBox header = new HBox(8, title, mdtbbsApprovalRefresh);
        header.setAlignment(Pos.CENTER_LEFT);
        ScrollPane scroll = new ScrollPane(mdtbbsApprovalBox);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(120);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        FXUtils.smoothScrolling(scroll);
        return new VBox(4, header, scroll);
    }

    /// Renders the pending join requests of the hosted room.
    private void refreshApprovals() {
        mdtbbsApprovalBox.getChildren().clear();
        List<JoinRequest> requests = mdtbbs.joinRequests();
        if (requests.isEmpty()) {
            mdtbbsApprovalBox.getChildren().add(
                    new Label(i18n("xenon.netplay.mdtbbs.approval.empty")));
            return;
        }
        for (JoinRequest request : requests) {
            AdvancedListItem item = new AdvancedListItem();
            item.setTitle(request.username().isBlank()
                    ? "#" + request.userId() : request.username());
            item.setSubtitle(request.sessionId());
            JFXButton accept = FXUtils.newRaisedButton(i18n("xenon.netplay.mdtbbs.approval.accept"));
            accept.setOnAction(e -> mdtbbs.approveJoinRequest(request.id()));
            JFXButton reject = FXUtils.newRaisedButton(i18n("xenon.netplay.mdtbbs.approval.reject"));
            reject.setOnAction(e -> mdtbbs.rejectJoinRequest(request.id()));
            HBox actions = new HBox(6, accept, reject);
            actions.setAlignment(Pos.CENTER_RIGHT);
            item.setRightGraphic(actions);
            mdtbbsApprovalBox.getChildren().add(item);
        }
    }

    /// Sets the registered Xenon client as the default join-intent launcher.
    private void setDefaultMdtbbsClient() {
        Schedulers.io().execute(() -> {
            try {
                Preferences preferences = CommunityServices.multiplayer().preferences();
                ClientApp target = null;
                for (ClientApp client : preferences.clients()) {
                    String haystack = (client.clientId() + " " + client.name() + " "
                            + client.launchUriTemplate()).toLowerCase(java.util.Locale.ROOT);
                    if (haystack.contains("xenon")) {
                        target = client;
                        break;
                    }
                }
                if (target == null) {
                    Platform.runLater(() -> mdtbbsStatus.setText(
                            i18n("xenon.netplay.mdtbbs.default_client.missing")));
                    return;
                }
                ClientApp chosen = target;
                CommunityServices.multiplayer().setDefaultClient(chosen.clientId());
                Platform.runLater(() -> mdtbbsStatus.setText(
                        i18n("xenon.netplay.mdtbbs.default_client.done", chosen.name())));
            } catch (IOException e) {
                LOG.warning("Cannot update MDTBBS multiplayer preferences", e);
                Platform.runLater(() -> mdtbbsStatus.setText(i18n(
                        "xenon.netplay.mdtbbs.default_client.failed", MdtbbsMessages.describe(e))));
            }
        });
    }

    /// Builds the public-lobby block.
    private VBox mdtbbsLobbySection() {
        Label title = new Label(i18n("xenon.netplay.mdtbbs.lobby.title"));
        title.setStyle("-fx-font-weight: BOLD;");
        mdtbbsLobbyRefresh.setOnAction(e -> refreshMdtbbsLobby());
        HBox header = new HBox(8, title, mdtbbsLobbyRefresh);
        header.setAlignment(Pos.CENTER_LEFT);
        mdtbbsLobbyStatus.setWrapText(true);
        ScrollPane scroll = new ScrollPane(mdtbbsLobbyBox);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(220);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        FXUtils.smoothScrolling(scroll);
        return new VBox(4, header, mdtbbsLobbyStatus, scroll);
    }

    /// Loads the first page of discoverable sessions.
    private void refreshMdtbbsLobby() {
        if (!mdtbbs.isLoggedIn()) {
            mdtbbsLobbyBox.getChildren().clear();
            mdtbbsLobbyStatus.setText(i18n("xenon.netplay.mdtbbs.login"));
            return;
        }
        lobbyLoaded = true;
        mdtbbsLobbyStatus.setText(i18n("message.doing"));
        Schedulers.io().execute(() -> {
            try {
                // Cache the advertised limits before the first page size is
                // chosen, so lobbyPageSize() reflects the server values.
                mdtbbs.preloadCapabilities();
                SessionPage page = CommunityServices.multiplayer().sessions(1, mdtbbs.lobbyPageSize());
                Platform.runLater(() -> showMdtbbsLobby(page));
            } catch (IOException e) {
                LOG.warning("Cannot load the MDTBBS public lobby", e);
                Platform.runLater(() -> mdtbbsLobbyStatus.setText(
                        i18n("xenon.netplay.mdtbbs.lobby.failed", MdtbbsMessages.describe(e))));
            }
        });
    }

    /// Renders one lobby page.
    private void showMdtbbsLobby(SessionPage page) {
        mdtbbsLobbyBox.getChildren().clear();
        if (page.sessions().isEmpty()) {
            mdtbbsLobbyBox.getChildren().add(new Label(i18n("xenon.netplay.mdtbbs.lobby.empty")));
            mdtbbsLobbyStatus.setText(i18n("xenon.netplay.mdtbbs.lobby.empty"));
            return;
        }
        mdtbbsLobbyStatus.setText(page.hasMore()
                ? i18n("xenon.netplay.mdtbbs.lobby.more")
                : i18n("xenon.netplay.mdtbbs.lobby.count", page.sessions().size()));
        for (Session session : page.sessions()) {
            mdtbbsLobbyBox.getChildren().add(mdtbbsLobbyItem(session));
        }
    }

    /// Builds one lobby row with the join action the server snapshot allows.
    ///
    /// A session is joined directly, joined through a host approval request, or
    /// shown as unavailable when the server refused both. Blocked rows append
    /// the localized reason so the raw protocol code never reaches the player.
    private AdvancedListItem mdtbbsLobbyItem(Session session) {
        AdvancedListItem item = new AdvancedListItem();
        String name = session.activityName().isBlank() ? session.id() : session.activityName();
        item.setTitle(name);
        boolean direct = session.joinable() && !session.requireApproval();
        // An explicit `can_request_join:false` must win over a joinable room
        // that still requires approval.
        boolean request = session.canRequestJoin();
        boolean blocked = !direct && !request;
        String subtitle = i18n("xenon.netplay.mdtbbs.lobby.players",
                session.currentPlayers(), session.maxPlayers())
                + "  ·  " + sessionVisibilityLabel(session.visibility())
                + (session.gameVersion().isBlank() ? "" : "  ·  " + session.gameVersion());
        @Nullable String blockedReason = session.blockedReason();
        if (blocked && blockedReason != null && !blockedReason.isBlank()) {
            subtitle = subtitle + "  ·  " + blockedReasonLabel(blockedReason);
        }
        item.setSubtitle(subtitle);
        JFXButton join;
        if (direct) {
            join = FXUtils.newRaisedButton(i18n("xenon.netplay.mdtbbs.lobby.join"));
            join.setOnAction(e -> mdtbbs.joinByCode(session.id()));
        } else if (request) {
            join = FXUtils.newRaisedButton(i18n("xenon.netplay.mdtbbs.lobby.request"));
            join.setOnAction(e -> mdtbbs.requestJoin(session.id()));
        } else {
            join = FXUtils.newRaisedButton(i18n("xenon.netplay.mdtbbs.lobby.join.blocked"));
        }
        join.setDisable(blocked || mdtbbs.hasSession() || mdtbbs.awaitingApproval());
        item.setRightGraphic(join);
        return item;
    }

    /// Maps a lobby `blocked_reason` code to a localized explanation.
    ///
    /// Unknown and blank codes fall back to a generic explanation, so a raw
    /// protocol value is never shown to the player.
    private static String blockedReasonLabel(@Nullable String reason) {
        String code = reason == null ? "" : reason.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (code) {
            case "ALREADY_IN_SESSION" -> i18n("xenon.netplay.mdtbbs.lobby.blocked.ALREADY_IN_SESSION");
            case "PRIVACY_DENIED" -> i18n("xenon.netplay.mdtbbs.lobby.blocked.PRIVACY_DENIED");
            case "SESSION_NOT_JOINABLE" -> i18n("xenon.netplay.mdtbbs.lobby.blocked.SESSION_NOT_JOINABLE");
            case "FRIEND_REQUIRED" -> i18n("xenon.netplay.mdtbbs.lobby.blocked.FRIEND_REQUIRED");
            case "SESSION_FULL" -> i18n("xenon.netplay.mdtbbs.lobby.blocked.SESSION_FULL");
            case "JOIN_REQUEST_REQUIRED" -> i18n("xenon.netplay.mdtbbs.lobby.blocked.JOIN_REQUEST_REQUIRED");
            default -> i18n("xenon.netplay.mdtbbs.lobby.blocked.unknown");
        };
    }

    /// Maps a visibility value to its localized label.
    private static String sessionVisibilityLabel(String visibility) {
        return switch (visibility == null ? "" : visibility) {
            case "private" -> i18n("xenon.netplay.mdtbbs.visibility.private");
            case "public" -> i18n("xenon.netplay.mdtbbs.visibility.public");
            default -> i18n("xenon.netplay.mdtbbs.visibility.friends");
        };
    }

    /// One localized choice carrying the wire value.
    ///
    /// @param value wire value sent to MDTBBS
    /// @param label localized label shown in the combo box
    private record Choice(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /// Builds the MDTBBS session control block.
    private VBox mdtbbsControls() {
        Label title = new Label(i18n("xenon.netplay.mdtbbs.title"));
        title.setStyle("-fx-font-weight: BOLD;");

        mdtbbsVisibility.getItems().setAll(
                new Choice("private", i18n("xenon.netplay.mdtbbs.visibility.private")),
                new Choice("friends", i18n("xenon.netplay.mdtbbs.visibility.friends")),
                new Choice("public", i18n("xenon.netplay.mdtbbs.visibility.public")));
        mdtbbsVisibility.getSelectionModel().select(1);
        FXUtils.setLimitWidth(mdtbbsVisibility, 260);
        mdtbbsVisibility.setPromptText(i18n("xenon.netplay.mdtbbs.visibility"));

        mdtbbsJoinPolicy.getItems().setAll(
                new Choice("invite_only", i18n("xenon.netplay.mdtbbs.join.invite_only")),
                new Choice("friends", i18n("xenon.netplay.mdtbbs.join.friends")),
                new Choice("public", i18n("xenon.netplay.mdtbbs.join.public")));
        mdtbbsJoinPolicy.getSelectionModel().select(1);
        FXUtils.setLimitWidth(mdtbbsJoinPolicy, 240);
        mdtbbsJoinPolicy.setPromptText(i18n("xenon.netplay.mdtbbs.join_policy"));

        mdtbbsApproval.setText(i18n("xenon.netplay.mdtbbs.approval"));
        mdtbbsJoinPolicy.valueProperty().addListener((observable, old, next) ->
                mdtbbsApproval.setDisable(next != null && "invite_only".equals(next.value())));

        JFXButton create = mdtbbsCreate;
        create.setOnAction(e -> createMdtbbsRoom(false));
        JFXButton codeRoom = FXUtils.newRaisedButton(i18n("xenon.netplay.mdtbbs.code_room"));
        codeRoom.setOnAction(e -> createMdtbbsRoom(true));
        JFXButton invite = mdtbbsInvite;
        invite.setOnAction(e -> openInviteDialog());
        JFXButton leave = mdtbbsLeave;
        leave.setOnAction(e -> mdtbbs.leave());
        mdtbbsRetryCreate.setOnAction(e -> mdtbbs.retryCreateRoom());
        mdtbbsRetryCreate.setTooltip(
                new Tooltip(i18n("xenon.netplay.mdtbbs.session_active.body")));
        mdtbbsCopyCode.setOnAction(e -> {
            String code = mdtbbs.joinCode();
            if (!code.isBlank()) {
                FXUtils.copyText(code);
            }
        });
        HBox axisRow = new HBox(8, mdtbbsVisibility, mdtbbsJoinPolicy, mdtbbsApproval);
        axisRow.setAlignment(Pos.CENTER_LEFT);
        HBox createRow = new HBox(8, create, codeRoom, invite, mdtbbsDefaultClient,
                mdtbbsCopyCode, leave);
        createRow.setAlignment(Pos.CENTER_LEFT);
        mdtbbsDefaultClient.setOnAction(e -> setDefaultMdtbbsClient());

        mdtbbsCodeField.setPromptText(i18n("xenon.netplay.mdtbbs.code.prompt"));
        FXUtils.setLimitWidth(mdtbbsCodeField, 200);
        JFXButton join = mdtbbsJoin;
        join.setOnAction(e -> {
            String code = mdtbbsCodeField.getText();
            if (code != null && !code.isBlank()) {
                mdtbbs.joinByCode(code.trim());
            }
        });
        JFXButton copyAddress = mdtbbsCopyAddress;
        copyAddress.setOnAction(e -> {
            String address = mdtbbs.localAddress();
            if (!address.isBlank()) {
                FXUtils.copyText(address);
            }
        });
        HBox joinRow = new HBox(8, mdtbbsCodeField, join, copyAddress);
        joinRow.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label(i18n("xenon.netplay.mdtbbs.hint"));
        hint.setWrapText(true);
        mdtbbsStatus.setWrapText(true);
        mdtbbsAddress.setWrapText(true);
        HBox statusRow = new HBox(8, mdtbbsStatus, mdtbbsRetryCreate);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(mdtbbsStatus, Priority.ALWAYS);

        return new VBox(6, title, axisRow, createRow, joinRow, statusRow, mdtbbsAddress, hint);
    }

    /// Creates a room from the two orthogonal choices.
    ///
    /// @param codeRoom whether to force the old "join code" preset, which
    ///                 migrates to `private + public`
    private void createMdtbbsRoom(boolean codeRoom) {
        String version = selectedGameVersion();
        if (codeRoom) {
            mdtbbs.createRoom("private", "public", false, version);
            return;
        }
        Choice visibility = mdtbbsVisibility.getSelectionModel().getSelectedItem();
        Choice joinPolicy = mdtbbsJoinPolicy.getSelectionModel().getSelectedItem();
        boolean approval = mdtbbsApproval.isSelected()
                && !(joinPolicy != null && "invite_only".equals(joinPolicy.value()));
        mdtbbs.createRoom(visibility == null ? "private" : visibility.value(),
                joinPolicy == null ? "friends" : joinPolicy.value(), approval, version);
    }

    /// Refreshes the MDTBBS status block from the session manager.
    private void refreshMdtbbs() {
        boolean working = mdtbbs.phase() == MdtbbsNetplayManager.Phase.WORKING;
        boolean busy = working || mdtbbs.awaitingApproval();
        boolean attached = mdtbbs.hasSession();
        boolean ready = mdtbbs.isLoggedIn();
        boolean sessionActive = mdtbbs.phase() == MdtbbsNetplayManager.Phase.ERROR
                && "SESSION_ALREADY_ACTIVE".equals(mdtbbs.errorCode());
        boolean canRetryCreate = sessionActive
                && mdtbbs.conflictSessionId() != null
                && !mdtbbs.hasSession();
        @Nullable String detail = mdtbbs.statusDetail();
        if (ready && !lobbyLoaded) {
            refreshMdtbbsLobby();
        }
        mdtbbsCreate.setDisable(!ready || busy || attached);
        mdtbbsJoin.setDisable(!ready || busy || attached);
        mdtbbsLeave.setDisable(!attached && !working);
        mdtbbsInvite.setDisable(!ready || !mdtbbs.hosting());
        mdtbbsCopyAddress.setDisable(mdtbbs.localAddress().isBlank());
        mdtbbsCopyCode.setDisable(mdtbbs.joinCode().isBlank());
        mdtbbsDefaultClient.setDisable(!ready || busy);
        mdtbbsApprovalRefresh.setDisable(!mdtbbs.hosting());
        mdtbbsRetryCreate.setDisable(!canRetryCreate);
        refreshApprovals();
        refreshInviteFeedback();
        if (!ready) {
            mdtbbsStatus.setText(i18n("xenon.netplay.mdtbbs.login"));
            mdtbbsAddress.setText("");
            return;
        }
        switch (mdtbbs.phase()) {
            case WORKING -> mdtbbsStatus.setText(i18n("message.doing"));
            case HOSTING -> {
                String code = mdtbbs.joinCode();
                mdtbbsStatus.setText(code.isBlank()
                        ? i18n("xenon.netplay.mdtbbs.hosting")
                        : i18n("xenon.netplay.mdtbbs.hosting.code", code));
            }
            case GUEST_WAITING -> mdtbbsStatus.setText(mdtbbs.awaitingApproval()
                    ? i18n("xenon.netplay.mdtbbs.lobby.waiting")
                    : i18n("xenon.netplay.mdtbbs.waiting"));
            case GUEST_READY -> mdtbbsStatus.setText(i18n("xenon.netplay.mdtbbs.ready"));
            case ERROR -> mdtbbsStatus.setText(sessionActive
                    ? i18n("xenon.netplay.mdtbbs.error.session_active",
                            detail == null ? "" : detail)
                    : i18n("xenon.netplay.mdtbbs.failed",
                            mdtbbsErrorText(mdtbbs.errorCode(), detail)));
            default -> mdtbbsStatus.setText(i18n("xenon.netplay.mdtbbs.idle"));
        }
        mdtbbsAddress.setText(mdtbbs.localAddress().isBlank()
                ? ""
                : i18n("xenon.netplay.mdtbbs.address", mdtbbs.localAddress()));
    }

    /// Maps a stable error code to a player-facing message.
    private static String mdtbbsErrorText(@Nullable String code, @Nullable String detail) {
        String key = switch (code == null ? "" : code) {
            case "AUTH_REQUIRED", "TOKEN_INVALID", "TOKEN_EXPIRED" -> "xenon.netplay.mdtbbs.error.auth";
            case "CLIENT_CAPABILITY_NOT_APPROVED", "SESSIONS_DISABLED", "FEATURE_DISABLED" -> "xenon.netplay.mdtbbs.error.capability";
            case "INSUFFICIENT_SCOPE", "SCOPE_REQUIRED" -> "xenon.netplay.mdtbbs.error.scope";
            case "RATE_LIMITED" -> "xenon.netplay.mdtbbs.error.rate";
            case "JOIN_CODE_INVALID" -> "xenon.netplay.mdtbbs.error.code";
            case "JOIN_REQUEST_REQUIRED" -> "xenon.netplay.mdtbbs.error.approval";
            case "SESSION_FULL" -> "xenon.netplay.mdtbbs.error.full";
            case "SESSION_ALREADY_ACTIVE" -> "xenon.netplay.mdtbbs.error.session_active";
            case "SESSION_NOT_FOUND", "SESSION_EXPIRED", "SESSION_CLOSED" -> "xenon.netplay.mdtbbs.error.missing";
            case "SESSION_NOT_JOINABLE", "PRIVACY_DENIED", "FRIEND_REQUIRED" -> "xenon.netplay.mdtbbs.error.closed";
            case "RELAY_UNAVAILABLE", "RELAY_LIMIT_REACHED", "RELAY_ERROR" -> "xenon.netplay.mdtbbs.error.relay";
            case "INVITE_LIMIT_REACHED" -> "xenon.netplay.mdtbbs.error.invite_limit";
            case "PRESENCE_CONNECTION_LIMIT_REACHED" -> "xenon.netplay.mdtbbs.error.presence_limit";
            case "JOIN_INTENT_CLIENT_MISMATCH" -> "xenon.netplay.mdtbbs.error.client";
            case "JOIN_INTENT_EXPIRED", "JOIN_INTENT_RECOVERY_EXPIRED", "JOIN_INTENT_INVALID" -> "xenon.netplay.mdtbbs.error.intent";
            case "PEER_EXPIRED", "PEER_RESUME_INVALID", "PEER_UNAVAILABLE" -> "xenon.netplay.mdtbbs.error.peer";
            case "JOIN_REQUEST_REJECTED" -> "xenon.netplay.mdtbbs.error.rejected";
            case "HOST_LEFT" -> "xenon.netplay.mdtbbs.error.host_left";
            case "AUTH_INVALID" -> "xenon.netplay.mdtbbs.error.credential";
            case "AUTH_EXPIRED" -> "xenon.netplay.mdtbbs.error.expired";
            default -> "xenon.netplay.mdtbbs.error.generic";
        };
        return i18n(key, detail == null ? "" : detail);
    }

    /// Builds the version text shown to friends for the selected instance.
    private static String selectedGameVersion() {
        String id = Profiles.getSelectedVersion();
        Optional<MindustryVersion> version = id == null ? Optional.empty() : MindustryRoutes.get(id);
        return version.map(value -> value.getBuild() > 0 ? "build " + value.getBuild() : "")
                .orElse("");
    }

    /// Loads friends and opens the invite chooser.
    private void openInviteDialog() {
        if (!mdtbbs.hosting()) {
            mdtbbsStatus.setText(i18n("xenon.netplay.mdtbbs.invite.host_only"));
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                List<MdtbbsSocialClient.FriendPresence> friends =
                        CommunityServices.social().friendsPresence(1, 50);
                Platform.runLater(() -> showInviteDialog(friends));
            } catch (IOException e) {
                LOG.warning("Cannot load MDTBBS friends for inviting", e);
                Platform.runLater(() -> mdtbbsStatus.setText(
                        i18n("xenon.netplay.mdtbbs.failed", MdtbbsMessages.describe(e))));
            }
        });
    }

    /// Shows the friend list with invite buttons and a non-friend user search.
    ///
    /// The dialog stays open after an invite so several friends can be invited
    /// at once. Each attempt is counted conservatively against the advertised
    /// pending-invite limit, and an asynchronous server failure is surfaced in
    /// the dialog by [#refreshInviteFeedback()]. Search results reuse the same
    /// invite buttons and feedback label as the friend rows.
    private void showInviteDialog(List<MdtbbsSocialClient.FriendPresence> friends) {
        JFXDialogLayout layout = new JFXDialogLayout();
        layout.setHeading(new Label(i18n("xenon.netplay.mdtbbs.invite.title")));
        VBox body = new VBox(6);
        long inviteLimit = mdtbbs.maxPendingInvites();
        Label feedback = new Label();
        feedback.setWrapText(true);
        Label limitNotice = new Label();
        limitNotice.setWrapText(true);
        mdtbbsInviteFeedback = feedback;
        mdtbbsInviteTarget = "";
        mdtbbsInviteTargetId = "";
        List<JFXButton> inviteButtons = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger searchToken = new AtomicInteger();

        TextField searchField = new TextField();
        searchField.setPromptText(i18n("xenon.netplay.mdtbbs.invite.search.prompt"));
        FXUtils.setLimitWidth(searchField, 240);
        JFXButton search = FXUtils.newRaisedButton(
                i18n("xenon.netplay.mdtbbs.invite.search.action"));
        VBox searchResults = new VBox(2);
        HBox searchRow = new HBox(6, searchField, search);
        searchRow.setAlignment(Pos.CENTER_LEFT);
        search.setOnAction(e -> searchMdtbbsUsers(searchField.getText(), searchResults,
                inviteLimit, feedback, limitNotice, inviteButtons, attempts, searchToken));
        searchField.setOnAction(e -> searchMdtbbsUsers(searchField.getText(), searchResults,
                inviteLimit, feedback, limitNotice, inviteButtons, attempts, searchToken));
        body.getChildren().addAll(searchRow, searchResults);

        if (friends.isEmpty()) {
            body.getChildren().add(new Label(i18n("xenon.netplay.mdtbbs.invite.empty")));
        }
        for (MdtbbsSocialClient.FriendPresence friend : friends) {
            AdvancedListItem item = new AdvancedListItem();
            item.setTitle(friend.username());
            item.setSubtitle(friend.status());
            item.setRightGraphic(mdtbbsInviteButton(friend.username(), friend.userId(),
                    inviteLimit, feedback, limitNotice, inviteButtons, attempts));
            body.getChildren().add(item);
        }
        // Search results can also send invites, so the shared feedback stays
        // visible even when the friend list is empty.
        body.getChildren().add(feedback);
        if (inviteLimit > 0) {
            body.getChildren().add(limitNotice);
        }
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(280);
        layout.setBody(scroll);
        JFXButton close = new JFXButton(i18n("button.close"));
        close.getStyleClass().add("dialog-accept");
        close.setOnAction(e -> layout.fireEvent(
                new determination.xenon.ui.construct.DialogCloseEvent()));
        layout.setActions(close);
        layout.addEventHandler(determination.xenon.ui.construct.DialogCloseEvent.CLOSE,
                event -> {
                    mdtbbsInviteFeedback = null;
                    mdtbbsInviteTarget = "";
                    mdtbbsInviteTargetId = "";
                });
        Controllers.dialog(layout);
    }

    /// Creates one invite button bound to the dialog's shared limit state.
    private JFXButton mdtbbsInviteButton(String username, long userId, long inviteLimit,
                                         Label feedback, Label limitNotice,
                                         List<JFXButton> inviteButtons, AtomicInteger attempts) {
        JFXButton invite = FXUtils.newRaisedButton(i18n("xenon.netplay.mdtbbs.invite.send"));
        invite.setOnAction(e -> {
            mdtbbsInviteTarget = username;
            mdtbbsInviteTargetId = Long.toString(userId);
            feedback.setText(i18n("xenon.netplay.mdtbbs.invite.sent", username));
            mdtbbs.inviteFriend(userId);
            int count = attempts.incrementAndGet();
            if (inviteLimit > 0 && count >= inviteLimit) {
                for (JFXButton button : inviteButtons) {
                    button.setDisable(true);
                }
                limitNotice.setText(i18n("xenon.netplay.mdtbbs.invite.limit"));
            }
        });
        if (inviteLimit > 0 && attempts.get() >= inviteLimit) {
            invite.setDisable(true);
        }
        inviteButtons.add(invite);
        return invite;
    }

    /// Runs one non-friend user search and replaces only the result rows.
    ///
    /// A blank query clears the previous results without a request. Each call
    /// takes a new token so an older, slower response cannot overwrite newer
    /// results.
    private void searchMdtbbsUsers(@Nullable String query, VBox results, long inviteLimit,
                                   Label feedback, Label limitNotice,
                                   List<JFXButton> inviteButtons, AtomicInteger attempts,
                                   AtomicInteger searchToken) {
        int token = searchToken.incrementAndGet();
        String text = query == null ? "" : query.trim();
        results.getChildren().clear();
        if (text.isEmpty()) {
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                List<MdtbbsSocialClient.UserSummary> users =
                        CommunityServices.social().searchUsers(text, 20);
                Platform.runLater(() -> {
                    if (searchToken.get() != token) {
                        return;
                    }
                    results.getChildren().clear();
                    if (users.isEmpty()) {
                        results.getChildren().add(new Label(
                                i18n("xenon.netplay.mdtbbs.invite.search.empty")));
                        return;
                    }
                    for (MdtbbsSocialClient.UserSummary user : users) {
                        AdvancedListItem item = new AdvancedListItem();
                        item.setTitle(user.username());
                        item.setSubtitle(i18n("xenon.netplay.mdtbbs.invite.search.non_friend"));
                        item.setRightGraphic(mdtbbsInviteButton(user.username(), user.id(),
                                inviteLimit, feedback, limitNotice, inviteButtons, attempts));
                        results.getChildren().add(item);
                    }
                });
            } catch (IOException e) {
                LOG.warning("Cannot search MDTBBS users", e);
                Platform.runLater(() -> {
                    if (searchToken.get() != token) {
                        return;
                    }
                    results.getChildren().clear();
                    results.getChildren().add(new Label(
                            i18n("xenon.netplay.mdtbbs.invite.search.failed",
                                    MdtbbsMessages.describe(e))));
                });
            }
        });
    }

    /// Surfaces an asynchronous invite failure in the open invite dialog.
    ///
    /// Runs from the manager state listener on the FX thread; a successful
    /// invite leaves the optimistic "sent" text in place.
    private void refreshInviteFeedback() {
        @Nullable Label feedback = mdtbbsInviteFeedback;
        if (feedback == null || mdtbbsInviteTarget.isBlank()) {
            return;
        }
        @Nullable String error = mdtbbs.lastInviteError();
        if (error == null) {
            return;
        }
        if (!mdtbbsInviteTargetId.equals(mdtbbs.lastInviteTarget())) {
            // The failure belongs to an earlier attempt for another user;
            // keep the neutral "sent" text for the current target.
            return;
        }
        feedback.setText(i18n("xenon.netplay.mdtbbs.invite.failed",
                mdtbbsInviteTarget, mdtbbsInviteErrorLabel(error)));
    }

    /// Maps a stable invite failure code to readable text.
    private static String mdtbbsInviteErrorLabel(String code) {
        if ("INVITE_LIMIT_REACHED".equals(code)) {
            return i18n("xenon.netplay.mdtbbs.invite.limit");
        }
        return mdtbbsErrorText(code, null);
    }

    /// Wraps one list section with a title and a fixed-height scroll area.
    private static VBox section(String title, VBox content, double height) {
        Label label = new Label(title);
        label.setStyle("-fx-font-weight: BOLD;");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(height);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        FXUtils.smoothScrolling(scroll);
        return new VBox(4, label, scroll);
    }

    /// Builds the P2P room control block.
    private VBox roomControls() {
        Label title = new Label(i18n("xenon.netplay.room.title"));
        title.setStyle("-fx-font-weight: BOLD;");

        roomField.setPromptText(i18n("xenon.netplay.room.code"));
        FXUtils.setLimitWidth(roomField, 120);

        JFXButton random = FXUtils.newRaisedButton(i18n("xenon.netplay.room.random"));
        random.setOnAction(e -> roomField.setText(String.format("%06d",
                ThreadLocalRandom.current().nextInt(1_000_000))));

        JFXButton start = FXUtils.newRaisedButton(i18n("xenon.netplay.room.start"));
        start.setOnAction(e -> startRoom());

        JFXButton stop = FXUtils.newRaisedButton(i18n("xenon.netplay.room.stop"));
        stop.setOnAction(e -> {
            runtime.stop();
            updateRoomStatus();
            refreshPeers();
        });

        JFXButton copyHost = FXUtils.newRaisedButton(i18n("xenon.netplay.room.copy_host"));
        copyHost.setOnAction(e -> copyHostAddress());

        HBox row = new HBox(8, roomField, random, start, stop, copyHost);
        row.setAlignment(Pos.CENTER_LEFT);

        Label roomHint = new Label(i18n("xenon.netplay.room.hint"));
        roomHint.setWrapText(true);

        return new VBox(6, title, row, roomStatus, hostAddress, roomHint);
    }

    /// Loads the public server directory.
    private void refreshServers() {
        serverStatus.setText(i18n("xenon.netplay.servers.loading"));
        serverBox.getChildren().clear();
        Schedulers.io().execute(() -> {
            try {
                PublicServerCatalog.LoadResult result = catalog.load();
                Platform.runLater(() -> {
                    serverBox.getChildren().clear();
                    for (PublicServerCatalog.Server server : result.servers()) {
                        serverBox.getChildren().add(serverItem(server));
                    }
                    serverStatus.setText(result.cached()
                            ? i18n("xenon.netplay.servers.cached", result.servers().size())
                            : i18n("xenon.netplay.servers.count", result.servers().size()));
                });
            } catch (IOException e) {
                LOG.warning("Failed to load the public server list", e);
                Platform.runLater(() -> serverStatus.setText(
                        i18n("xenon.netplay.servers.failed", e.getMessage())));
            }
        });
    }

    /// Builds one public-server row.
    private AdvancedListItem serverItem(PublicServerCatalog.Server server) {
        AdvancedListItem item = new AdvancedListItem();
        item.setTitle(server.name());
        item.setSubtitle(String.join("  ·  ", server.addresses()));

        JFXButton copy = FXUtils.newRaisedButton(i18n("xenon.netplay.copy"));
        copy.setOnAction(e -> FXUtils.copyText(server.primaryAddress()));
        JFXButton join = FXUtils.newRaisedButton(i18n("xenon.netplay.launch"));
        join.setOnAction(e -> {
            // Vanilla Mindustry has no launch flag for "join this server", so
            // the address goes to the clipboard and the player pastes it into
            // the in-game join dialog.
            FXUtils.copyText(server.primaryAddress());
            launchSelectedInstance();
        });
        HBox actions = new HBox(6, copy, join);
        actions.setAlignment(Pos.CENTER_RIGHT);
        item.setRightGraphic(actions);
        return item;
    }

    /// Launches the selected instance, if one exists.
    private static void launchSelectedInstance() {
        String id = Profiles.getSelectedVersion();
        Optional<MindustryVersion> version = id == null
                ? Optional.empty()
                : MindustryRoutes.get(id);
        if (version.isEmpty()) {
            Controllers.dialog(i18n("xenon.netplay.no_instance"),
                    i18n("xenon.netplay.title"), MessageDialogPane.MessageType.WARNING);
            return;
        }
        MindustryRoutes.launch(version.get());
    }

    /// Starts a room, installing EasyTier first when needed.
    private void startRoom() {
        String code = roomField.getText() == null ? "" : roomField.getText().trim();
        if (!EasyTierRuntime.isValidRoomCode(code)) {
            roomStatus.setText(i18n("xenon.netplay.room.code.invalid"));
            return;
        }
        ensureEasyTier(() -> {
            roomStatus.setText(i18n("message.doing"));
            Schedulers.io().execute(() -> {
                try {
                    runtime.start(code);
                    Platform.runLater(() -> {
                        updateRoomStatus();
                        refreshPeers();
                    });
                } catch (IOException e) {
                    LOG.warning("Failed to start the EasyTier room", e);
                    Platform.runLater(() -> roomStatus.setText(
                            i18n("xenon.netplay.easytier.failed", e.getMessage())
                                    + "  " + i18n("xenon.netplay.easytier.admin")));
                }
            });
        });
    }

    /// Installs EasyTier when missing, then runs `onReady`.
    private void ensureEasyTier(Runnable onReady) {
        if (runtime.isInstalled()) {
            onReady.run();
            return;
        }
        roomStatus.setText(i18n("xenon.netplay.easytier.installing"));
        Schedulers.io().execute(() -> {
            try {
                String version = runtime.install(null);
                Platform.runLater(() -> {
                    roomStatus.setText(i18n("xenon.netplay.easytier.installed", version));
                    onReady.run();
                });
            } catch (IOException e) {
                LOG.warning("Failed to install EasyTier", e);
                Platform.runLater(() -> roomStatus.setText(
                        i18n("xenon.netplay.easytier.failed", e.getMessage())));
            }
        });
    }

    /// Refreshes the room status line.
    private void updateRoomStatus() {
        if (runtime.isRunning()) {
            String room = runtime.currentRoom();
            roomStatus.setText(i18n("xenon.netplay.room.running", room == null ? "?" : room));
            return;
        }
        Optional<String> version = runtime.installedVersion();
        roomStatus.setText(version
                .map(value -> i18n("xenon.netplay.easytier.installed", value))
                .orElseGet(() -> i18n("xenon.netplay.easytier.missing")));
    }

    /// Copies the local virtual `ip:port` peers should join.
    private void copyHostAddress() {
        Schedulers.io().execute(() -> {
            try {
                String ip = runtime.virtualIp();
                Platform.runLater(() -> {
                    if (ip == null) {
                        hostAddress.setText(i18n("xenon.netplay.room.ip.pending"));
                        return;
                    }
                    String address = ip + ":" + EasyTierRuntime.MINDUSTRY_PORT;
                    hostAddress.setText(address);
                    FXUtils.copyText(address);
                });
            } catch (IOException e) {
                LOG.warning("Cannot query the EasyTier virtual IP", e);
                Platform.runLater(() -> hostAddress.setText(
                        i18n("xenon.netplay.easytier.failed", e.getMessage())));
            }
        });
    }

    /// Refreshes the peer list.
    private void refreshPeers() {
        if (!runtime.isInstalled()) {
            return;
        }
        Schedulers.io().execute(() -> {
            try {
                List<EasyTierRuntime.Peer> peers = runtime.peers();
                Platform.runLater(() -> {
                    peerBox.getChildren().clear();
                    if (peers.isEmpty()) {
                        Label empty = new Label(i18n("xenon.netplay.room.peers.empty"));
                        peerBox.getChildren().add(empty);
                        return;
                    }
                    for (EasyTierRuntime.Peer peer : peers) {
                        AdvancedListItem item = new AdvancedListItem();
                        item.setTitle(peer.hostname().isBlank() ? peer.ipv4() : peer.hostname());
                        item.setSubtitle(peer.ipv4() + "  ·  " + peer.cost()
                                + (peer.latencyMs().isBlank() || "*".equals(peer.latencyMs())
                                ? "" : "  ·  " + peer.latencyMs() + " ms"));
                        peerBox.getChildren().add(item);
                    }
                });
            } catch (IOException e) {
                LOG.warning("Cannot query EasyTier peers", e);
            }
        });
    }
}
