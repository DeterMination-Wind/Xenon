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
package determination.xenon.mindustry.trash;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests the local recycle bin.
@NotNullByDefault
public final class MindustryTrashServiceTest {

    /// A deleted instance moves to the trash and restores in place.
    @Test
    public void trashesAndRestores(@TempDir Path dir) throws Exception {
        Path instance = Files.createDirectories(dir.resolve("versions/inst"));
        Files.writeString(instance.resolve("version.json"), "{}");
        MindustryTrashService trash = new MindustryTrashService(dir);

        var entry = trash.trash(instance, "instance");
        assertFalse(Files.exists(instance));
        assertTrue(Files.isRegularFile(entry.directory().resolve("version.json")));
        assertEquals(1, trash.list().size());

        trash.restore(entry);
        assertTrue(Files.isRegularFile(instance.resolve("version.json")));
        assertEquals(0, trash.list().size());
    }

    /// Restoring over an existing path is refused.
    @Test
    public void refusesRestoreConflict(@TempDir Path dir) throws Exception {
        Path instance = Files.createDirectories(dir.resolve("versions/inst"));
        Files.writeString(instance.resolve("version.json"), "{}");
        MindustryTrashService trash = new MindustryTrashService(dir);
        var entry = trash.trash(instance, "instance");
        Files.createDirectories(instance);
        assertThrows(java.io.IOException.class, () -> trash.restore(entry));
    }

    /// Permanent deletion removes the entry directory.
    @Test
    public void permanentlyDeletes(@TempDir Path dir) throws Exception {
        Path instance = Files.createDirectories(dir.resolve("versions/inst"));
        MindustryTrashService trash = new MindustryTrashService(dir);
        var entry = trash.trash(instance, "instance");
        trash.deletePermanently(entry);
        assertFalse(Files.exists(entry.directory()));
    }
}
