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
import determination.xenon.Metadata;
import determination.xenon.mindustry.MindustryVersion;
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
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

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
                title, hint, toolbar, serverStatus,
                section(i18n("xenon.netplay.servers.title"), serverBox, 240),
                roomControls(),
                section(i18n("xenon.netplay.room.peers.title"), peerBox, 180));
        root.setFillWidth(true);

        ScrollPane scroll = new ScrollPane(root);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        FXUtils.smoothScrolling(scroll);
        setCenter(scroll);

        refreshServers();
        updateRoomStatus();
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
