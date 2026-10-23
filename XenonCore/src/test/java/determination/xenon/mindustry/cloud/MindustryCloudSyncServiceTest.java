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
package determination.xenon.mindustry.cloud;

import determination.xenon.mindustry.cloud.MindustryCloudSyncService.CloudState;
import determination.xenon.mindustry.cloud.MindustryCloudSyncService.Kind;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Tests the whole-instance sync baseline and decision matrix.
@NotNullByDefault
public final class MindustryCloudSyncServiceTest {

    /// A baseline round-trips through its JSON file.
    @Test
    public void baselineRoundTrips(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(MindustryCloudSyncBaseline.FILE_NAME);
        MindustryCloudSyncBaseline baseline = new MindustryCloudSyncBaseline(
                MindustryCloudSyncBaseline.SCHEMA, "slot1", "snap1", "abc", 42,
                "finger", "mods", Instant.parse("2026-10-01T12:00:00Z"));
        MindustryCloudSyncBaseline.save(file, baseline);

        MindustryCloudSyncBaseline loaded = MindustryCloudSyncBaseline.load(file);
        assertNotNull(loaded);
        assertEquals("snap1", loaded.snapshotId());
        assertEquals(42, loaded.archiveSize());
        assertEquals(Instant.parse("2026-10-01T12:00:00Z"), loaded.syncedAt());
    }

    /// A missing or invalid baseline is ignored.
    @Test
    public void rejectsBrokenBaseline(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(MindustryCloudSyncBaseline.FILE_NAME);
        assertNull(MindustryCloudSyncBaseline.load(file));
        Files.writeString(file, "{}");
        assertNull(MindustryCloudSyncBaseline.load(file));
    }

    /// The decision matrix distinguishes push, pull and conflict.
    @Test
    public void decidesSyncDirection(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("saves"));
        Files.writeString(dir.resolve("saves/world.msav"), "one");
        String fingerprint = MindustryCloudSyncService.fingerprint(dir);
        MindustryCloudSyncBaseline baseline = new MindustryCloudSyncBaseline(
                MindustryCloudSyncBaseline.SCHEMA, "slot1", "snap1", "", 0,
                fingerprint, "", Instant.EPOCH);
        CloudState same = new CloudState("snap1", 10, "", Instant.EPOCH);

        assertEquals(Kind.UP_TO_DATE,
                MindustryCloudSyncService.decide(dir, same, baseline).kind());
        assertEquals(Kind.PULL, MindustryCloudSyncService.decide(dir,
                new CloudState("snap2", 11, "", Instant.EPOCH), baseline).kind());
        assertEquals(Kind.NO_BASELINE, MindustryCloudSyncService.decide(dir, same, null).kind());

        Files.writeString(dir.resolve("saves/world.msav"), "two");
        assertEquals(Kind.PUSH, MindustryCloudSyncService.decide(dir, same, baseline).kind());
        assertEquals(Kind.CONFLICT, MindustryCloudSyncService.decide(dir,
                new CloudState("snap2", 11, "", Instant.EPOCH), baseline).kind());
    }

    /// Backups do not look like user edits.
    @Test
    public void ignoresBackupDirectory(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("saves/backups"));
        Files.writeString(dir.resolve("saves/world.msav"), "one");
        String before = MindustryCloudSyncService.fingerprint(dir);
        Files.writeString(dir.resolve("saves/backups/world-old.msav"), "old");
        assertEquals(before, MindustryCloudSyncService.fingerprint(dir));
    }
}
