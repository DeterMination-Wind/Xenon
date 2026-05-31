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
package determination.xenon.mindustry.ui.server;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextField;
import determination.xenon.mindustry.server.ServerConsoleSession;
import determination.xenon.mindustry.server.ServerInstance;
import determination.xenon.mindustry.server.ServerInstanceManager;
import determination.xenon.mindustry.server.ServerRuntimeHandle;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;
import determination.xenon.ui.construct.MessageDialogPane;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/**
 * Live console for a single server instance. Owns the running
 * {@link ServerSessionRunner}, streams its events into a {@link TextArea},
 * and exposes a one-line command box that writes to the server's stdin.
 *
 * <p>Lifecycle: the runner is only created when the user clicks Start.
 * Switching tabs in the parent {@link MindustryServerDetailPage} keeps
 * this pane alive (TransitionPane caches), so a started server keeps
 * running while the user looks at Config / Maps / etc. Closing the
 * detail page does <em>not</em> stop the server — leaving a server up
 * across launcher pages is intentional.</p>
 */
public final class MindustryServerConsolePane extends BorderPane {

    /** Cap so a chatty server can't blow JavaFX text-area memory. */
    private static final int MAX_LINES = 5_000;

    private final ServerInstance inst;
    private final ServerInstanceManager manager;

    private final TextArea console = new TextArea();
    private final JFXTextField input = new JFXTextField();
    private final JFXButton startBtn;
    private final JFXButton stopBtn;
    private final JFXButton clearBtn;
    private final JFXButton sendBtn;
    private final CheckBox autoScroll = new CheckBox(i18n("xenon.mindustry.server.console.autoscroll"));
    private final Label status = new Label();

    private final ServerRuntimeHandle runtime;
    private final Runnable unsubscribe;

    public MindustryServerConsolePane(ServerInstance inst, ServerInstanceManager manager) {
        this.inst = inst;
        this.manager = manager;
        try {
            this.runtime = manager.getRuntimeRegistry().get(inst.getId());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        setPadding(new Insets(12));

        Label title = new Label(i18n("xenon.mindustry.server.tab.console"));
        title.getStyleClass().add("title");

        startBtn = FXUtils.newRaisedButton(i18n("xenon.mindustry.server.console.start"));
        startBtn.setOnAction(e -> startServer());
        stopBtn = FXUtils.newRaisedButton(i18n("xenon.mindustry.server.console.stop"));
        stopBtn.setOnAction(e -> stopServer());
        stopBtn.setDisable(true);
        clearBtn = FXUtils.newRaisedButton(i18n("xenon.mindustry.server.console.clear"));
        clearBtn.setOnAction(e -> console.clear());

        autoScroll.setSelected(true);

        HBox toolbar = new HBox(8, startBtn, stopBtn, clearBtn, autoScroll, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        console.setEditable(false);
        console.setWrapText(false);
        console.setStyle("-fx-font-family: 'Consolas', 'Menlo', monospace; -fx-font-size: 12;");
        VBox.setVgrow(console, Priority.ALWAYS);

        input.setPromptText(i18n("xenon.mindustry.server.console.input.hint"));
        input.setOnAction(e -> sendCommand());
        sendBtn = FXUtils.newRaisedButton(i18n("xenon.mindustry.server.console.send"));
        sendBtn.setOnAction(e -> sendCommand());
        HBox inputRow = new HBox(8, input, sendBtn);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(input, Priority.ALWAYS);

        VBox center = new VBox(8, toolbar, console, inputRow);
        VBox.setVgrow(console, Priority.ALWAYS);
        setTop(title);
        BorderPane.setMargin(title, new Insets(0, 0, 8, 0));
        setCenter(center);

        for (String line : runtime.getBufferedLines()) {
            appendLine(line);
        }
        syncButtons();
        this.unsubscribe = runtime.subscribe(this::onEvent);
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null && newScene == null) {
                unsubscribe.run();
            }
        });
    }

    private void startServer() {
        appendInfo(i18n("xenon.mindustry.server.console.starting"));
        try {
            runtime.start();
            syncButtons();
        } catch (RuntimeException ex) {
            LOG.warning("Failed to start server " + inst.getId(), ex);
            Controllers.dialog(ex.getMessage(),
                    i18n("message.error"), MessageDialogPane.MessageType.ERROR);
        }
    }

    private void stopServer() {
        appendInfo(i18n("xenon.mindustry.server.console.stopping"));
        new Thread(() -> {
            try {
                runtime.stop();
            } catch (RuntimeException ex) {
                LOG.warning("Failed to stop server " + inst.getId(), ex);
            }
        }, "xenon-server-stop-" + inst.getId()).start();
    }

    private void sendCommand() {
        String cmd = input.getText();
        if (cmd == null || cmd.isBlank()) return;
        cmd = cmd.trim();
        try {
            runtime.sendCommand(cmd);
            appendLine("> " + cmd);
            input.clear();
        } catch (Exception ex) {
            appendLine("[error sending command] " + ex.getMessage());
        }
    }

    /**
     * Console-event sink. Called from background threads; every UI mutation
     * has to bounce to the FX thread. We funnel everything through
     * {@link #appendLine} so the line cap and auto-scroll logic live in
     * one place.
     *
     * <p>Java 17 source level here, so we use {@code instanceof} pattern
     * matching rather than pattern switch.</p>
     */
    private void onEvent(ServerConsoleSession.ConsoleEvent event) {
        if (event instanceof ServerConsoleSession.Started) {
            Platform.runLater(() -> {
                appendInfo(i18n("xenon.mindustry.server.console.started"));
                syncButtons();
            });
        } else if (event instanceof ServerConsoleSession.StdoutLine line) {
            Platform.runLater(() -> appendLine(line.text()));
        } else if (event instanceof ServerConsoleSession.StderrLine line) {
            Platform.runLater(() -> appendLine("[stderr] " + line.text()));
        } else if (event instanceof ServerConsoleSession.Exited e) {
            Platform.runLater(() -> {
                appendInfo(i18n("xenon.mindustry.server.console.exited", e.code()));
                syncButtons();
            });
        } else if (event instanceof ServerConsoleSession.Restarted r) {
            Platform.runLater(() -> {
                appendInfo(i18n("xenon.mindustry.server.console.restarted", r.attempt()));
                syncButtons();
            });
        }
    }

    private void appendInfo(String text) {
        appendLine("[xenon] " + text);
    }

    private void appendLine(String text) {
        console.appendText(text + "\n");
        // Cap the buffer; deleting the head is much cheaper than rebuilding
        // the whole TextArea every time.
        int len = console.getLength();
        if (len > MAX_LINES * 200) {
            console.deleteText(0, len - MAX_LINES * 200);
        }
        if (autoScroll.isSelected()) {
            console.positionCaret(console.getLength());
        }
    }

    private void syncButtons() {
        boolean running = runtime.isRunning();
        startBtn.setDisable(running);
        stopBtn.setDisable(!running);
        input.setDisable(!running);
        sendBtn.setDisable(!running);
        status.setText(runtime.getStatusMessage().isBlank()
                ? (running ? i18n("xenon.mindustry.server.status.running")
                           : i18n("xenon.mindustry.server.status.stopped"))
                : runtime.getStatusMessage());
    }
}
