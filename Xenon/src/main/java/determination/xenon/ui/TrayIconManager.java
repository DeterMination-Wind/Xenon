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
package determination.xenon.ui;

import determination.xenon.Launcher;
import determination.xenon.Metadata;
import javafx.application.Platform;
import javafx.stage.Stage;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;

import static determination.xenon.util.logging.Logger.LOG;

/**
 * System-tray (notification-area) icon. Single-click brings the launcher
 * window back to the foreground; the right-click menu has Show / Exit.
 *
 * <p>This is the user-facing answer to "I want a launcher icon I can click
 * to open it again." Windows pre-Win11 keeps tray icons collapsed in the
 * overflow chevron by default; the user has to drag-pin it once for it
 * to live on the visible row.</p>
 *
 * <p>Threading: {@link SystemTray} is AWT, so install runs on the EDT and
 * each click handler bounces back to {@link Platform#runLater} before
 * touching the JavaFX stage.</p>
 */
public final class TrayIconManager {

    private static final AtomicReference<TrayIcon> INSTALLED = new AtomicReference<>();

    private TrayIconManager() {
    }

    /**
     * Install the tray icon. Safe to call multiple times — second and
     * later calls no-op. Returns silently on platforms / sessions where
     * the system tray isn't available (headless servers, some Linux
     * desktops without an indicator host, etc.).
     *
     * @param stage the primary JavaFX stage; click brings it back.
     */
    public static void install(Stage stage) {
        if (INSTALLED.get() != null) return;
        if (!SystemTray.isSupported()) {
            LOG.info("System tray not supported on this platform; skipping tray icon");
            return;
        }
        java.awt.EventQueue.invokeLater(() -> {
            try {
                installOnEdt(stage);
            } catch (Throwable ex) {
                LOG.warning("Failed to install tray icon", ex);
            }
        });
    }

    private static void installOnEdt(Stage stage) throws AWTException {
        SystemTray tray = SystemTray.getSystemTray();

        // The icon path picks up the Xenon brand image bundled in
        // resources. Using the AWT toolkit avoids pulling in JavaFX
        // image decoders for a 16x16 PNG.
        URL iconUrl = TrayIconManager.class.getResource("/assets/img/icon@2x.png");
        if (iconUrl == null) {
            // Fall back to the launcher.jpeg if @2x is missing; we only
            // log + skip when nothing is loadable, otherwise the tray
            // would show a blank rectangle.
            iconUrl = TrayIconManager.class.getResource("/assets/img/mindustry/launcher.jpeg");
        }
        if (iconUrl == null) {
            LOG.warning("No tray icon resource found; skipping tray installation");
            return;
        }
        Image image = Toolkit.getDefaultToolkit().getImage(iconUrl);

        PopupMenu menu = new PopupMenu();
        MenuItem show = new MenuItem("Show Xenon");
        show.addActionListener(e -> showStage(stage));
        MenuItem exit = new MenuItem("Exit");
        exit.addActionListener(e -> Platform.runLater(Launcher::stopApplication));
        menu.add(show);
        menu.addSeparator();
        menu.add(exit);

        TrayIcon icon = new TrayIcon(image, Metadata.FULL_TITLE, menu);
        // Auto-size scales the image to whatever the OS expects (16/24/32),
        // so we don't need to ship per-size variants.
        icon.setImageAutoSize(true);
        icon.setToolTip(Metadata.FULL_TITLE);
        // ActionListener fires on platform-default activation: double-click
        // on Windows, single-click on macOS / Linux indicators. We add a
        // mouse listener as well so single-click works on Windows too —
        // otherwise users have to know to double-click.
        icon.addActionListener(e -> showStage(stage));
        icon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1) {
                    showStage(stage);
                }
            }
        });

        tray.add(icon);
        INSTALLED.set(icon);
        LOG.info("Tray icon installed");
    }

    /** Return true after the tray icon has been added successfully. */
    public static boolean isInstalled() {
        return INSTALLED.get() != null;
    }

    /**
     * Bring the JavaFX stage back to a visible, focused, non-iconified
     * state. Always hop to the FX thread; AWT click handlers run on the
     * EDT and JavaFX stage mutation off-thread will throw.
     */
    private static void showStage(Stage stage) {
        Platform.runLater(() -> {
            if (!stage.isShowing()) stage.show();
            if (stage.isIconified()) stage.setIconified(false);
            stage.toFront();
            stage.requestFocus();
        });
    }

    /** Remove the tray icon. Called on application stop. */
    public static void uninstall() {
        TrayIcon icon = INSTALLED.getAndSet(null);
        if (icon == null) return;
        java.awt.EventQueue.invokeLater(() -> {
            try {
                SystemTray.getSystemTray().remove(icon);
            } catch (Throwable ex) {
                LOG.warning("Failed to remove tray icon", ex);
            }
        });
    }
}
