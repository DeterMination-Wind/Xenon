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
package determination.xenon.util.platform.windows;

import determination.xenon.util.platform.NativeUtils;
import determination.xenon.util.platform.OperatingSystem;
import determination.xenon.util.platform.hardware.CentralProcessor;
import determination.xenon.util.platform.hardware.GraphicsCard;
import determination.xenon.util.platform.hardware.HardwareDetector;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static determination.xenon.util.logging.Logger.LOG;

/**
 * @author Glavo
 */
public final class WindowsHardwareDetector extends HardwareDetector {

    @Override
    public @Nullable CentralProcessor detectCentralProcessor() {
        if (!OperatingSystem.isWindows7OrLater())
            return null;
        return WindowsCPUDetector.detect();
    }

    @Override
    public List<GraphicsCard> detectGraphicsCards() {
        if (!OperatingSystem.isWindows7OrLater())
            return null;
        return WindowsGPUDetector.detect();
    }

    @Override
    public long getTotalMemorySize() {
        if (NativeUtils.USE_JNA) {
            Kernel32 kernel32 = Kernel32.INSTANCE;
            if (kernel32 != null) {
                WinTypes.MEMORYSTATUSEX status = new WinTypes.MEMORYSTATUSEX();
                if (kernel32.GlobalMemoryStatusEx(status))
                    return status.ullTotalPhys;
                else
                    LOG.warning("Failed to get memory status: " + kernel32.GetLastError());
            }
        }

        return super.getTotalMemorySize();
    }

    @Override
    public long getFreeMemorySize() {
        if (NativeUtils.USE_JNA) {
            Kernel32 kernel32 = Kernel32.INSTANCE;
            if (kernel32 != null) {
                WinTypes.MEMORYSTATUSEX status = new WinTypes.MEMORYSTATUSEX();
                if (kernel32.GlobalMemoryStatusEx(status))
                    return status.ullAvailPhys;
                else
                    LOG.warning("Failed to get memory status: " + kernel32.GetLastError());
            }
        }

        return super.getFreeMemorySize();
    }
}
