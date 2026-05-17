/*
 * Xenon Launcher
 * Copyright (C) 2020-2026  Xenon contributors
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
package determination.xenon.mod;

import determination.xenon.game.DefaultGameRepository;
import determination.xenon.task.Task;
import determination.xenon.util.io.FileUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;

public class ModpackUpdateTask extends Task<Void> {

    private final DefaultGameRepository repository;
    private final String id;
    private final Task<?> updateTask;
    private final Path backupFolder;

    public ModpackUpdateTask(DefaultGameRepository repository, String id, Task<?> updateTask) {
        this.repository = repository;
        this.id = id;
        this.updateTask = updateTask;

        Path backup = repository.getBaseDirectory().resolve("backup");
        while (true) {
            int num = (int)(Math.random() * 10000000);
            if (!Files.exists(backup.resolve(id + "-" + num))) {
                backupFolder = backup.resolve(id + "-" + num);
                break;
            }
        }
    }

    @Override
    public Collection<Task<?>> getDependencies() {
        return Collections.singleton(updateTask);
    }

    @Override
    public void execute() throws Exception {
        FileUtils.copyDirectory(repository.getVersionRoot(id), backupFolder);
    }

    @Override
    public boolean doPostExecute() {
        return true;
    }

    @Override
    public void postExecute() throws Exception {
        if (isDependenciesSucceeded()) {
            // Keep backup game version for further repair.
        } else {
            // Restore backup
            repository.removeVersionFromDisk(id);

            FileUtils.copyDirectory(backupFolder, repository.getVersionRoot(id));

            repository.refreshVersionsAsync().start();
        }
    }
}
