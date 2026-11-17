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
import org.jetbrains.annotations.Nullable;

import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final AtomicReference<MenuItem> STATUS_ITEM = new AtomicReference<>();

    /// Message `SystemTray.getSystemTray()` uses when the tray disappeared.
    private static final String TRAY_UNSUPPORTED_MESSAGE =
            "The system tray is not supported on the current platform.";

    /// Guards the one-time EDT handler installation.
    private static final AtomicBoolean DISPATCH_GUARD_INSTALLED = new AtomicBoolean();

    /// Set once the JDK reported that the notification area vanished.
    private static final AtomicBoolean TRAY_DEGRADED = new AtomicBoolean();

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
        // A fresh install starts a new tray lifecycle, so drop any latch
        // recorded by a previous one.
        TRAY_DEGRADED.set(false);
        if (!SystemTray.isSupported()) {
            LOG.info("System tray not supported on this platform; skipping tray icon");
            return;
        }
        EventQueue.invokeLater(() -> {
            installDispatchGuard();
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

    /// Whether hiding the launcher window still leaves a way back.
    ///
    /// Three conditions must hold: an icon was installed, the platform still
    /// reports a tray, and no notification-area failure has been observed in
    /// this session. The third condition is a conservative latch: once the JDK
    /// drops its icon, `SystemTray.isSupported()` can flip back to `true` before
    /// the icon is re-attached (and the JDK swallows `addNotify` failures), so a
    /// hidden window could stay unreachable. Refusing to hide after such a
    /// failure degrades to the normal "close exits" path instead.
    ///
    /// @return `true` when closing the window may hide it into the tray
    public static boolean canHideToTray() {
        return isInstalled() && !TRAY_DEGRADED.get() && SystemTray.isSupported();
    }

    /// Installs a narrow uncaught-exception guard on the current EDT thread.
    ///
    /// When the Linux notification area disappears, `sun.awt.X11.XTrayIconPeer`
    /// removes its icon from its own EDT task and `SystemTray.getSystemTray()`
    /// throws because support has already flipped to `false`. That exception
    /// bypasses [#uninstall] and would reach the crash reporter as an ERROR plus
    /// an analytics event, so it is filtered here. Any failure that does not
    /// match [#isJdkSystemTrayFailure] is delegated to the previous handler, so
    /// real AWT failures keep their existing reporting path.
    ///
    /// Only the [EventQueue] thread that runs the installation task gets the
    /// guard; other threads are untouched.
    private static void installDispatchGuard() {
        if (!DISPATCH_GUARD_INSTALLED.compareAndSet(false, true)) return;
        Thread edt = Thread.currentThread();
        Thread.UncaughtExceptionHandler previous = edt.getUncaughtExceptionHandler();
        edt.setUncaughtExceptionHandler((thread, error) -> {
            if (isJdkSystemTrayFailure(error)) {
                // The icon may never be re-attached: stop offering hide-to-tray.
                TRAY_DEGRADED.set(true);
                LOG.info("Notification area disappeared; ignoring the JDK tray failure");
                return;
            }
            previous.uncaughtException(thread, error);
        });
    }

    /// Whether a failure is the JDK's own "system tray disappeared" signal.
    ///
    /// All three conditions must hold: it is an
    /// [UnsupportedOperationException], its message is exactly the one
    /// `SystemTray.getSystemTray()` uses, and a stack frame belongs to `sun.awt.`
    /// where the JDK implements the per-platform notification area. Launcher
    /// code can never satisfy the last condition, so our own tray failures are
    /// never swallowed.
    ///
    /// @param error failure to classify
    /// @return `true` when the failure comes from the JDK tray implementation
    static boolean isJdkSystemTrayFailure(Throwable error) {
        if (!(error instanceof UnsupportedOperationException)) return false;
        if (!TRAY_UNSUPPORTED_MESSAGE.equals(error.getMessage())) return false;
        for (StackTraceElement frame : error.getStackTrace()) {
            if (frame.getClassName().startsWith("sun.awt.")) return true;
        }
        return false;
    }

    /**
     * Show a transient status line in the tray menu, for example upload
     * progress. Passing {@code null} removes the line and restores the
     * plain tooltip.
     *
     * <p>Safe to call from any thread and a no-op before [#install(Stage)]
     * ran or when the platform has no tray. AWT menu mutation happens on the
     * EDT; failures are logged instead of reaching the caller.</p>
     *
     * @param status status text to show, or {@code null} to clear it
     */
    public static void setStatus(@Nullable String status) {
        TrayIcon icon = INSTALLED.get();
        if (icon == null) return;
        EventQueue.invokeLater(() -> {
            try {
                applyStatus(icon, status);
            } catch (Throwable ex) {
                LOG.warning("Failed to update the tray status", ex);
            }
        });
    }

    /// Applies one status update on the EDT.
    private static void applyStatus(TrayIcon icon, @Nullable String status) {
        PopupMenu menu = icon.getPopupMenu();
        MenuItem item = STATUS_ITEM.get();
        if (status == null || status.isBlank()) {
            if (item != null) {
                menu.remove(item);
                STATUS_ITEM.set(null);
            }
            icon.setToolTip(Metadata.FULL_TITLE);
            return;
        }
        if (item == null) {
            // Disabled so the line reads as state, not as another action.
            item = new MenuItem(status);
            item.setEnabled(false);
            // Keep it directly below "Show Xenon" so the state is visible at a glance.
            menu.insert(item, 1);
            STATUS_ITEM.set(item);
        } else {
            item.setLabel(status);
        }
        icon.setToolTip(Metadata.FULL_TITLE + " — " + status);
    }

    /// Bring the JavaFX stage back to a visible, focused, non-iconified
    /// state. Always hop to the FX thread; AWT click handlers run on the
    /// EDT and JavaFX stage mutation off-thread will throw.
    ///
    /// @param stage launcher stage to restore and focus
    private static void showStage(Stage stage) {
        Platform.runLater(() -> WindowUtils.bringToFront(stage));
    }

    /** Remove the tray icon. Called on application stop. */
    public static void uninstall() {
        TrayIcon icon = INSTALLED.getAndSet(null);
        STATUS_ITEM.set(null);
        if (icon == null) return;
        EventQueue.invokeLater(() -> {
            try {
                SystemTray.getSystemTray().remove(icon);
            } catch (Throwable ex) {
                LOG.warning("Failed to remove tray icon", ex);
            }
        });
    }
}
