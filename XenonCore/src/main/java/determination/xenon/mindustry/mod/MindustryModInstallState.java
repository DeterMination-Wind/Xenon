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
package determination.xenon.mindustry.mod;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/// The install state of one remote mod entry against an instance's `mods/`
/// folder.
///
/// A row can be absent (`NOT_INSTALLED`), present and current (`INSTALLED`),
/// present with a newer upstream release (`UPDATE_AVAILABLE`), or present with
/// a build newer than the index tag (`LOCAL_NEWER`). The later two carry the
/// locally installed version so the browser can show `local → latest`.
///
/// @param state            install state of the mod
/// @param installedVersion locally installed version, or `null` when absent or
///                         unparsable
@NotNullByDefault
public record MindustryModInstallState(State state, @Nullable String installedVersion) {

    /// The four states a remote mod row can be in.
    public enum State {
        /// No archive in the instance's mods folder provides the mod.
        NOT_INSTALLED,
        /// An archive is installed and no newer release was detected.
        INSTALLED,
        /// An archive is installed and the remote tag is newer.
        UPDATE_AVAILABLE,
        /// An archive is installed with a version newer than the remote tag.
        LOCAL_NEWER
    }

    /// Resolves the state of one remote mod against its local copies.
    ///
    /// Unparsable archives are ignored. When several archives provide the same
    /// mod, the highest version wins; enabled archives win a version tie, and
    /// otherwise the first match is kept.
    ///
    /// @param latestTag remote release tag, possibly `null`
    /// @param matches   local archives that provide this mod
    /// @return the resolved state, never `null`
    public static MindustryModInstallState resolve(@Nullable String latestTag,
                                                   List<MindustryLocalMod> matches) {
        MindustryLocalMod best = null;
        for (MindustryLocalMod candidate : matches) {
            if (candidate == null || candidate.isUnparsable()) {
                continue;
            }
            if (best == null || isBetter(candidate, best)) {
                best = candidate;
            }
        }
        if (best == null) {
            return new MindustryModInstallState(State.NOT_INSTALLED, null);
        }

        String installed = best.getVersion();
        if (latestTag == null || latestTag.isBlank()) {
            return new MindustryModInstallState(State.INSTALLED, installed);
        }
        Integer compared = MindustryModVersion.compare(latestTag, installed);
        if (compared == null || compared == 0) {
            return new MindustryModInstallState(State.INSTALLED, installed);
        }
        return new MindustryModInstallState(
                compared > 0 ? State.UPDATE_AVAILABLE : State.LOCAL_NEWER, installed);
    }

    /// Returns whether a local archive provides this mod in any state.
    ///
    /// @return `true` unless the state is [`State#NOT_INSTALLED`]
    public boolean isInstalled() {
        return state != State.NOT_INSTALLED;
    }

    /// Returns whether the remote row is newer than the local archive.
    ///
    /// @return `true` for [`State#UPDATE_AVAILABLE`]
    public boolean isUpdateAvailable() {
        return state == State.UPDATE_AVAILABLE;
    }

    /// Selects the better of two local copies of the same mod.
    ///
    /// @param candidate new candidate
    /// @param current   current best
    /// @return `true` when `candidate` should replace `current`
    private static boolean isBetter(MindustryLocalMod candidate, MindustryLocalMod current) {
        Integer compared = MindustryModVersion.compare(candidate.getVersion(), current.getVersion());
        if (compared != null && compared > 0) {
            return true;
        }
        if (compared != null && compared < 0) {
            return false;
        }
        return candidate.isEnabled() && !current.isEnabled();
    }
}
