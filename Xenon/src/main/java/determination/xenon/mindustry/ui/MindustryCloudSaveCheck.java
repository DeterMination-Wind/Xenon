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

import determination.xenon.mindustry.MindustryImportFlow;
import determination.xenon.mindustry.MindustryVersion;
import determination.xenon.mindustry.community.MindustryCloudSaveSync;
import determination.xenon.mindustry.community.MindustryCloudSaveSync.CheckResult;
import determination.xenon.mindustry.community.MindustryCloudSaveSync.CloudHead;
import determination.xenon.mindustry.community.MindustryCloudSaveSync.SaveComparison;
import determination.xenon.setting.Profiles;
import determination.xenon.task.Schedulers;
import determination.xenon.ui.Controllers;
import javafx.application.Platform;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static determination.xenon.util.i18n.I18n.i18n;
import static determination.xenon.util.logging.Logger.LOG;

/// One read-only cloud-save check shortly after the launcher window is ready.
///
/// The check compares every opted-in instance's local `saves/` with the newest
/// snapshot of each MDTBBS slot, and tells the user only when something needs
/// attention. It never uploads, downloads, merges or writes: the cloud is read
/// once for the whole launcher, the local files are only stat-ed and hashed,
/// and the only visible effect is a toast.
///
/// Signed out, offline, or a missing instance are all normal outcomes and are
/// logged rather than surfaced — an automatic convenience must not nag.
@NotNullByDefault
public final class MindustryCloudSaveCheck {

    /// Delay after the main window appears before the single check runs.
    ///
    /// Long enough that the check never competes with the window becoming
    /// interactive or with the update check, short enough to still feel
    /// attached to the launch.
    private static final long STARTUP_DELAY_MILLIS = 8000L;

    /// Whether a check has already been scheduled for this process.
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    /// Schedules the single startup check.
    ///
    /// Safe to call more than once; only the first call has an effect.
    public static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        // The IO scheduler is a plain executor, so the delay comes from a
        // delayed executor bound to it rather than from a scheduled pool:
        // the check still runs on the launcher's shared IO threads.
        CompletableFuture
                .delayedExecutor(STARTUP_DELAY_MILLIS, TimeUnit.MILLISECONDS, Schedulers.io())
                .execute(MindustryCloudSaveCheck::runOnce);
    }

    /// Runs the check once, never throwing into the scheduler.
    private static void runOnce() {
        try {
            if (!CommunityServices.community().isLoggedIn()) {
                LOG.info("Cloud save check skipped: no MDTBBS session");
                return;
            }

            // One cloud read for the whole launcher: the slot list and its
            // newest snapshots are the same for every local instance.
            List<CloudHead> heads = MindustryCloudSaveSync
                    .cloudHeads(CommunityServices.cloudSaves()).heads();
            if (heads.isEmpty()) {
                LOG.info("Cloud save check skipped: no cloud snapshot to compare against");
                return;
            }

            int flagged = 0;
            int conflicts = 0;
            boolean anyChecked = false;
            for (MindustryVersion version : MindustryImportFlow
                    .visibleVersions(Profiles.getSelectedProfile())) {
                if (version == null || !version.isCloudSaveCheckEnabled()) continue;
                Path versionRoot = MindustryImportFlow.versionRoot(version);
                Path savesDir = version.resolveDataDir(versionRoot).resolve("saves");
                CheckResult result = MindustryCloudSaveSync.check(savesDir, () -> heads);
                anyChecked = true;
                for (SaveComparison comparison : result.needingAttention()) {
                    flagged++;
                    if (result.hasConflict()) conflicts++;
                    LOG.info("Cloud save check: " + version.getId() + " / "
                            + comparison.fileName() + " is " + comparison.verdict());
                }
            }
            if (!anyChecked) {
                LOG.info("Cloud save check skipped: no instance has it enabled");
                return;
            }
            LOG.info("Cloud save check finished: " + heads.size() + " cloud head(s), "
                    + flagged + " file(s) need attention, " + conflicts + " conflicting file(s)");
            if (flagged > 0) {
                // Copy for the lambda: the counter itself is not effectively final.
                int reported = flagged;
                Platform.runLater(() -> Controllers.showToast(
                        i18n("xenon.cloud.check.found", reported)));
            }
        } catch (Throwable e) {
            // Offline, signed out mid-flight, an unreadable save directory — all
            // of them are ordinary reasons for a background convenience to stay
            // quiet, so this is logged at info level rather than warned.
            LOG.info("Cloud save check skipped: " + e);
        }
    }

    private MindustryCloudSaveCheck() {
    }
}
