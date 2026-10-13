/*
 * Xenon Launcher
 * Copyright (C) 2021-2026  Xenon contributors
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
package determination.xenon.setting;

import determination.xenon.mindustry.download.DownloadThrottle;
import determination.xenon.mindustry.download.MindustryDownloadSource;
import javafx.beans.InvalidationListener;
import org.jetbrains.annotations.NotNullByDefault;

import static determination.xenon.util.logging.Logger.LOG;

/// Mirrors the global-config entries of the Mindustry download chain into XenonCore.
///
/// The download layer lives in XenonCore, which cannot see [GlobalConfig] and
/// must not depend on JavaFX. This class pushes the two relevant settings — the
/// speed limit and the source tier — into the static holders every downloader
/// reads, and keeps them in sync while the launcher runs.
///
/// Both holders are consulted per download instead of being captured once, so
/// changing a setting applies to the next chunk rather than to the next launch.
@NotNullByDefault
public final class MindustryDownloadSettings {

    /// Installs the synchronization listeners and applies the current values.
    ///
    /// Must be called after the global config has been loaded; calling it again
    /// would install duplicate listeners.
    ///
    /// @param config global configuration that owns the persisted settings
    public static void init(GlobalConfig config) {
        InvalidationListener onChange = observable -> apply(config);
        config.mindustryDownloadSpeedKbpsProperty().addListener(onChange);
        config.mindustryDownloadSourceProperty().addListener(onChange);
        apply(config);
    }

    /// Pushes the current configuration into the XenonCore holders.
    ///
    /// @param config global configuration to read
    private static void apply(GlobalConfig config) {
        int kbps = config.getMindustryDownloadSpeedKbps();
        MindustryDownloadSource source = config.getMindustryDownloadSource();
        DownloadThrottle.setGlobalLimitKbps(kbps);
        MindustryDownloadSource.setCurrent(source);
        LOG.info("Mindustry download settings: limit="
                + (kbps == DownloadThrottle.UNLIMITED ? "unlimited" : kbps + " KiB/s")
                + ", source=" + source);
    }

    private MindustryDownloadSettings() {
    }
}
