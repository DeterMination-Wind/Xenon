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
package determination.xenon.ui.versions;

import determination.xenon.mindustry.ui.MindustryRoutes;
import determination.xenon.setting.Profile;
import determination.xenon.ui.Controllers;
import determination.xenon.ui.FXUtils;

import java.util.concurrent.CompletableFuture;

/// Version-list actions shared by the game list, the sidebar and the popup
/// menus.
///
/// Every action routes to {@link MindustryRoutes}, which owns instance
/// manifests and data directories. An id that is no longer registered resolves
/// to nothing and the action becomes a no-op, so stale selections left in
/// `profile.json` cannot reach a removed code path.
public final class Versions {
    private Versions() {
    }

    /// Opens the install surface that offers the Mindustry variants.
    public static void addNewGame() {
        Controllers.getDownloadPage().showGameDownloads();
        Controllers.navigate(Controllers.getDownloadPage());
    }

    /// Asks for confirmation and deletes the instance directory.
    ///
    /// The profile is forwarded so the version list is rebuilt and a selection
    /// that pointed at the removed instance is re-pointed after the delete.
    ///
    /// @param profile the profile that owns the instance
    /// @param version the instance id
    public static void deleteVersion(Profile profile, String version) {
        MindustryRoutes.get(profile, version).ifPresent(v ->
                MindustryRoutes.deleteVersion(profile, v));
    }

    /// Prompts for a new instance id and renames the manifest.
    public static CompletableFuture<String> renameVersion(Profile profile, String version) {
        return MindustryRoutes.renameVersion(profile, version);
    }

    /// Exports the instance as a `.xenon` modpack.
    public static void exportVersion(Profile profile, String version) {
        MindustryRoutes.get(profile, version).ifPresent(MindustryRoutes::exportModpack);
    }

    /// Opens the instance's working directory in the system file manager.
    public static void openFolder(Profile profile, String version) {
        MindustryRoutes.get(profile, version).ifPresent(v ->
                FXUtils.openFolder(v.resolveWorkingDirectory(
                        determination.xenon.mindustry.MindustryImportFlow.versionRoot(v))));
    }

    /// Duplicates the instance under a new id.
    public static void duplicateVersion(Profile profile, String version) {
        MindustryRoutes.duplicateVersion(profile, version);
    }

    /// Launches the instance and closes the launcher unless "keep open" is set.
    public static void launch(Profile profile, String id) {
        MindustryRoutes.get(profile, id).ifPresent(MindustryRoutes::launch);
    }

    /// Launches the instance in test mode.
    public static void testGame(Profile profile, String id) {
        MindustryRoutes.get(profile, id).ifPresent(MindustryRoutes::launch);
    }

    /// Opens the instance detail page.
    ///
    /// @param profile the profile that owns the instance
    /// @param version the instance id
    public static void modifyGameSettings(Profile profile, String version) {
        MindustryRoutes.get(profile, version).ifPresent(v ->
                MindustryRoutes.openVersionPage(profile, v));
    }
}
