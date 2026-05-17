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
package determination.xenon.mindustry.install;

import determination.xenon.mindustry.DataDirectoryPolicy;
import determination.xenon.mindustry.VersionVariant;
import determination.xenon.mindustry.download.MindustryRemoteVersion;
import determination.xenon.util.SettingsMap;

/**
 * Shared {@link SettingsMap} keys used by the Xenon install wizard pages.
 * Centralised so that page classes don't drift on string literals.
 */
public final class WizardKeys {
    private WizardKeys() {
    }

    public static final SettingsMap.Key<VersionVariant> VARIANT = new SettingsMap.Key<>("xenon.variant");
    public static final SettingsMap.Key<MindustryRemoteVersion> REMOTE_VERSION = new SettingsMap.Key<>("xenon.remote_version");
    public static final SettingsMap.Key<String> VERSION_ID = new SettingsMap.Key<>("xenon.version_id");
    public static final SettingsMap.Key<DataDirectoryPolicy> DATA_DIR_POLICY = new SettingsMap.Key<>("xenon.data_dir_policy");
    public static final SettingsMap.Key<String> CUSTOM_DATA_DIR = new SettingsMap.Key<>("xenon.custom_data_dir");
    public static final SettingsMap.Key<Boolean> OVERRIDE_EXISTING = new SettingsMap.Key<>("xenon.override_existing");
}
