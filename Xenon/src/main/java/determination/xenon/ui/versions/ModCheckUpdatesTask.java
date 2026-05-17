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

import determination.xenon.download.DownloadProvider;
import determination.xenon.mod.LocalModFile;
import determination.xenon.mod.RemoteMod;
import determination.xenon.task.Schedulers;
import determination.xenon.task.Task;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static determination.xenon.util.logging.Logger.LOG;

public class ModCheckUpdatesTask extends Task<List<LocalModFile.ModUpdate>> {
    private final DownloadProvider downloadProvider;
    private final List<Task<LocalModFile.ModUpdate>> dependents;

    public ModCheckUpdatesTask(DownloadProvider downloadProvider, String gameVersion, Collection<LocalModFile> mods) {
        this.downloadProvider = downloadProvider;
        dependents = mods.stream().map(mod ->
                Task.supplyAsync(Schedulers.io(), () -> {
                    LocalModFile.ModUpdate candidate = null;
                    for (RemoteMod.Type type : RemoteMod.Type.values()) {
                        LocalModFile.ModUpdate update = null;
                        try {
                            update = mod.checkUpdates(downloadProvider, gameVersion, type.getRemoteModRepository());
                        } catch (IOException e) {
                            LOG.warning(String.format("Cannot check update for mod %s.", mod.getFileName()), e);
                        }
                        if (update == null) {
                            continue;
                        }

                        if (candidate == null || candidate.getCandidate().getDatePublished().isBefore(update.getCandidate().getDatePublished())) {
                            candidate = update;
                        }
                    }

                    return candidate;
                }).setName(mod.getFileName()).setSignificance(TaskSignificance.MAJOR).withCounter("update.checking")
        ).toList();

        setStage("update.checking");
        getProperties().put("total", dependents.size());
    }

    @Override
    public boolean doPreExecute() {
        return true;
    }

    @Override
    public void preExecute() {
        notifyPropertiesChanged();
    }

    @Override
    public Collection<? extends Task<?>> getDependents() {
        return dependents;
    }

    @Override
    public boolean isRelyingOnDependents() {
        return false;
    }

    @Override
    public void execute() throws Exception {
        setResult(dependents.stream()
                .map(Task::getResult)
                .filter(Objects::nonNull).toList());
    }
}
