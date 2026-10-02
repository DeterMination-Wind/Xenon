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
import determination.xenon.mindustry.community.MdtbbsSocialClient.FriendPresence;
import determination.xenon.mindustry.community.MdtbbsSocialClient.Invite;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.DialogCloseEvent;
import determination.xenon.ui.construct.MessageDialogPane;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// Friends presence and multiplayer invite dialog.
///
/// Shows online friends with their current activity and pending invites with
/// accept/decline actions. All data comes from the MDTBBS social surface; the
/// dialog requires a signed-in session.
public final class MdtbbsSocialDialog {
    /// Timestamp format for invite expiry.
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /// Opens the social dialog.
    public static void show() {
        if (!CommunityServices.community().isLoggedIn()) {
            Controllers.showToast(i18n("xenon.social.login"));
            return;
        }
        JFXDialogLayout layout = new JFXDialogLayout();
        layout.setHeading(new Label(i18n("xenon.social.title")));
        layout.setBody(new Label(i18n("xenon.social.loading")));
        JFXButton close = new JFXButton(i18n("button.ok"));
        close.getStyleClass().add("dialog-accept");
        close.setOnAction(e -> layout.fireEvent(new DialogCloseEvent()));
        layout.setActions(close);
        Controllers.dialog(layout);
        reload(layout);
    }

    /// Reloads friends and invites into the dialog body.
    private static void reload(JFXDialogLayout layout) {
        layout.setBody(new Label(i18n("xenon.social.loading")));
        Schedulers.io().execute(() -> {
            try {
                List<FriendPresence> friends = CommunityServices.social().friendsPresence(1, 50);
                List<Invite> invites = CommunityServices.social().invites();
                Platform.runLater(() -> render(layout, friends, invites));
            } catch (IOException e) {
                LOG.warning("Failed to load MDTBBS social data", e);
                Platform.runLater(() -> layout.setBody(new Label(
                        i18n("xenon.social.failed", MdtbbsMessages.describe(e)))));
            }
        });
    }

    /// Renders both sections.
    private static void render(JFXDialogLayout layout, List<FriendPresence> friends,
                               List<Invite> invites) {
        Label friendsTitle = new Label(i18n("xenon.social.friends"));
        friendsTitle.getStyleClass().add("title-label");
        VBox friendsBox = new VBox(4);
        if (friends.isEmpty()) {
            friendsBox.getChildren().add(new Label(i18n("xenon.social.friends.empty")));
        }
        for (FriendPresence friend : friends) {
            String name = friend.username().isBlank() ? "#" + friend.userId() : friend.username();
            String activity = friend.activityName().isBlank() ? friend.status() : friend.activityName();
            Label row = new Label(i18n("xenon.social.friend.meta", name, activity));
            row.getStyleClass().add("subtitle-label");
            friendsBox.getChildren().add(row);
        }

        Label invitesTitle = new Label(i18n("xenon.social.invites"));
        invitesTitle.getStyleClass().add("title-label");
        VBox invitesBox = new VBox(6);
        if (invites.isEmpty()) {
            invitesBox.getChildren().add(new Label(i18n("xenon.social.invites.empty")));
        }
        for (Invite invite : invites) {
            invitesBox.getChildren().add(inviteRow(layout, invite));
        }

        VBox body = new VBox(10, friendsTitle, friendsBox, invitesTitle, invitesBox);
        body.setPadding(new Insets(4, 0, 0, 0));
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(320);
        layout.setBody(scroll);
    }

    /// Builds one invite row with accept and decline actions.
    private static HBox inviteRow(JFXDialogLayout layout, Invite invite) {
        String inviter = invite.inviter().isBlank() ? "#" + invite.sessionId() : invite.inviter();
        Label meta = new Label(i18n("xenon.social.invite.meta", inviter,
                DATE.format(invite.expiresAt())));
        meta.getStyleClass().add("subtitle-label");
        HBox.setHgrow(meta, Priority.ALWAYS);

        JFXButton accept = FXUtils.newRaisedButton(i18n("xenon.social.accept"));
        accept.setOnAction(e -> respond(layout, () -> CommunityServices.social().acceptInvite(invite.id())));
        JFXButton decline = FXUtils.newRaisedButton(i18n("xenon.social.decline"));
        decline.setOnAction(e -> respond(layout, () -> CommunityServices.social().declineInvite(invite.id())));

        HBox actions = new HBox(6, accept, decline);
        actions.setAlignment(Pos.CENTER_RIGHT);
        HBox row = new HBox(8, meta, actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6));
        row.getStyleClass().add("community-reply");
        return row;
    }

    /// Runs an invite mutation and reloads the dialog.
    private static void respond(JFXDialogLayout layout, InviteAction action) {
        Schedulers.io().execute(() -> {
            try {
                action.run();
                Platform.runLater(() -> reload(layout));
            } catch (IOException e) {
                Platform.runLater(() -> Controllers.dialog(
                        MdtbbsMessages.describe(e),
                        i18n("message.error"), MessageDialogPane.MessageType.ERROR));
            }
        });
    }

    /// Invite mutation executed on the IO scheduler.
    @FunctionalInterface
    private interface InviteAction {
        /// Performs the request.
        void run() throws IOException;
    }

    private MdtbbsSocialDialog() {
    }
}
