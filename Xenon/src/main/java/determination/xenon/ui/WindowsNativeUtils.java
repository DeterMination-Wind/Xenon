/*
 * Xenon Launcher
 * Copyright (C) 2025-2026  Xenon contributors
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

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import determination.xenon.util.platform.NativeUtils;
import determination.xenon.util.platform.OSVersion;
import determination.xenon.util.platform.OperatingSystem;
import determination.xenon.util.platform.windows.Dwmapi;
import determination.xenon.util.platform.windows.WinConstants;
import determination.xenon.util.platform.windows.WinTypes;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.OptionalLong;

import static determination.xenon.util.logging.Logger.LOG;

/// @author Glavo
public final class WindowsNativeUtils {

    public static OptionalLong getWindowHandle(Stage stage) {
        // Primary path: ask user32 directly by window title. Works on
        // JavaFX 25 where the reflection-based path below trips
        // "module javafx.graphics does not open javafx.stage". We need
        // user32 anyway for ensureTaskbarVisible, so the extra binding
        // is free.
        if (OperatingSystem.CURRENT_OS == OperatingSystem.WINDOWS && NativeUtils.USE_JNA) {
            String title = stage.getTitle();
            if (title != null && !title.isEmpty()) {
                try {
                    Pointer hwnd = User32Min.INSTANCE.FindWindowW(null, new WString(title));
                    if (hwnd != null && Pointer.nativeValue(hwnd) != 0L) {
                        return OptionalLong.of(Pointer.nativeValue(hwnd));
                    }
                } catch (Throwable ex) {
                    LOG.warning("FindWindowW lookup failed, falling back to reflection", ex);
                }
            }
        }

        // Fallback: JavaFX internals. Works on JavaFX <=24 and on builds
        // that --add-opens javafx.graphics/com.sun.javafx.tk to ALL-UNNAMED.
        try {
            Class<?> windowStageClass = Class.forName("com.sun.javafx.tk.quantum.WindowStage");
            Class<?> glassWindowClass = Class.forName("com.sun.glass.ui.Window");
            Class<?> tkStageClass = Class.forName("com.sun.javafx.tk.TKStage");

            Object tkStage = MethodHandles.privateLookupIn(Window.class, MethodHandles.lookup())
                    .findVirtual(Window.class, "getPeer", MethodType.methodType(tkStageClass))
                    .invoke(stage);

            MethodHandles.Lookup windowStageLookup = MethodHandles.privateLookupIn(windowStageClass, MethodHandles.lookup());
            MethodHandle getPlatformWindow = windowStageLookup.findVirtual(windowStageClass, "getPlatformWindow", MethodType.methodType(glassWindowClass));
            Object platformWindow = getPlatformWindow.invoke(tkStage);

            long handle = (long) MethodHandles.privateLookupIn(glassWindowClass, MethodHandles.lookup())
                    .findVirtual(glassWindowClass, "getNativeWindow", MethodType.methodType(long.class))
                    .invoke(platformWindow);

            return OptionalLong.of(handle);
        } catch (Throwable ex) {
            LOG.warning("Failed to get window handle", ex);
            return OptionalLong.empty();
        }
    }

    /**
     * Force the given stage to show up as a regular, pinnable, focusable
     * window on the Windows taskbar.
     *
     * <p>{@link javafx.stage.StageStyle#TRANSPARENT} stages don't get the
     * {@code WS_EX_APPWINDOW} extended style by default, which is what tells
     * the taskbar "this is a real top-level app window, give it an icon and
     * a proxy entry." Without it, single-click-to-focus and pin-to-taskbar
     * are unreliable. We OR it in (and clear {@code WS_EX_TOOLWINDOW} just
     * in case JavaFX set it) once the HWND is real.
     *
     * <p>No-op on non-Windows or when JNA isn't on the classpath.
     */
    public static void ensureTaskbarVisible(Stage stage) {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS || !NativeUtils.USE_JNA) return;
        OptionalLong handle = getWindowHandle(stage);
        if (handle.isEmpty()) return;
        try {
            Pointer hwnd = new Pointer(handle.getAsLong());
            int gwlExStyle = -20;
            int wsExAppWindow = 0x00040000;
            int wsExToolWindow = 0x00000080;
            int swpFlags = 0x0001 /*NOSIZE*/ | 0x0002 /*NOMOVE*/ | 0x0004 /*NOZORDER*/
                    | 0x0010 /*NOACTIVATE*/ | 0x0020 /*FRAMECHANGED*/;
            User32Min u32 = User32Min.INSTANCE;
            int style = u32.GetWindowLongW(hwnd, gwlExStyle);
            int newStyle = (style | wsExAppWindow) & ~wsExToolWindow;
            if (newStyle != style) {
                u32.SetWindowLongW(hwnd, gwlExStyle, newStyle);
                u32.SetWindowPos(hwnd, Pointer.NULL, 0, 0, 0, 0, swpFlags);
                LOG.info("Applied WS_EX_APPWINDOW to taskbar stage (was=0x"
                        + Integer.toHexString(style) + ", now=0x"
                        + Integer.toHexString(newStyle) + ")");
            }
        } catch (Throwable ex) {
            LOG.warning("Failed to set WS_EX_APPWINDOW on stage", ex);
        }
    }

    /**
     * Set the AppUserModelID for the current process. Windows uses this
     * string to group taskbar entries: every running Xenon instance shares
     * the same icon group, separate from generic "Java(TM) Platform"
     * windows.
     *
     * <p>Must be called as early as possible — before any window is shown —
     * because Windows latches the value when it creates the first taskbar
     * proxy. No-op on non-Windows or without JNA.
     */
    public static void setAppUserModelID(String id) {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS || !NativeUtils.USE_JNA) return;
        try {
            Shell32Aumid.INSTANCE.SetCurrentProcessExplicitAppUserModelID(new WString(id));
            LOG.info("AppUserModelID set to " + id);
        } catch (Throwable ex) {
            LOG.warning("Failed to set AppUserModelID", ex);
        }
    }

    /**
     * Minimal binding for the three {@code user32} entry points we need.
     * jna-platform isn't on the classpath, so we declare just what's used.
     */
    /**
     * Ask the Win11 DWM to round the four corners of the given stage.
     *
     * <p>{@link javafx.stage.StageStyle#UNDECORATED} stages are normal
     * opaque windows — anything inside the scene that isn't covered (the
     * area outside our rounded-content panel) shows through as the JavaFX
     * default white. Letting the OS clip the whole window rectangle into
     * a rounded shape removes the corner crumbs cleanly without going
     * back to a transparent layered window.</p>
     *
     * <p>No-op pre-Win11, on non-Windows, or when JNA isn't loaded.</p>
     */
    public static void applyDwmRoundedCorners(Stage stage) {
        if (OperatingSystem.CURRENT_OS != OperatingSystem.WINDOWS) return;
        if (!OperatingSystem.SYSTEM_VERSION.isAtLeast(OSVersion.WINDOWS_11)) return;
        if (!NativeUtils.USE_JNA || Dwmapi.INSTANCE == null) return;

        OptionalLong handle = getWindowHandle(stage);
        if (handle.isEmpty() || handle.getAsLong() == WinTypes.HANDLE.INVALID_VALUE) return;
        try {
            int rc = Dwmapi.INSTANCE.DwmSetWindowAttribute(
                    new WinTypes.HANDLE(Pointer.createConstant(handle.getAsLong())),
                    WinConstants.DWMWA_WINDOW_CORNER_PREFERENCE,
                    new IntByReference(WinConstants.DWMWCP_ROUND),
                    4);
            if (rc != 0) {
                LOG.warning("DwmSetWindowAttribute(WINDOW_CORNER_PREFERENCE) returned 0x"
                        + Integer.toHexString(rc));
            } else {
                LOG.info("Applied Win11 DWM rounded corners to launcher stage");
            }
        } catch (Throwable ex) {
            LOG.warning("Failed to apply DWM rounded corners", ex);
        }
    }

    private interface User32Min extends StdCallLibrary {
        User32Min INSTANCE = Native.load("user32", User32Min.class);
        Pointer FindWindowW(WString lpClassName, WString lpWindowName);
        int GetWindowLongW(Pointer hWnd, int nIndex);
        int SetWindowLongW(Pointer hWnd, int nIndex, int dwNewLong);
        boolean SetWindowPos(Pointer hWnd, Pointer hWndInsertAfter,
                             int X, int Y, int cx, int cy, int uFlags);
        boolean ShowWindow(Pointer hWnd, int nCmdShow);
    }

    /**
     * Minimal binding for {@code shell32!SetCurrentProcessExplicitAppUserModelID}.
     */
    private interface Shell32Aumid extends StdCallLibrary {
        Shell32Aumid INSTANCE = Native.load("shell32", Shell32Aumid.class);
        int SetCurrentProcessExplicitAppUserModelID(WString appID);
    }

    private WindowsNativeUtils() {
    }
}
