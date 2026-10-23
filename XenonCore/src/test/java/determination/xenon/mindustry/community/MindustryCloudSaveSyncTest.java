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
package determination.xenon.mindustry.community;

import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Quota;
import determination.xenon.mindustry.community.MdtbbsCloudSavesClient.Snapshot;
import determination.xenon.mindustry.community.MindustryCloudSaveSync.CheckResult;
import determination.xenon.mindustry.community.MindustryCloudSaveSync.CleanupPlan;
import determination.xenon.mindustry.community.MindustryCloudSaveSync.CloudHead;
import determination.xenon.mindustry.community.MindustryCloudSaveSync.SaveComparison;
import determination.xenon.mindustry.community.MindustryCloudSaveSync.SaveIdentity;
import determination.xenon.mindustry.community.MindustryCloudSaveSync.Verdict;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Tests the read-only cloud-save comparison and the quota-cleanup planner.
///
/// The comparison is a pure function of the two save states, so the whole
/// verdict matrix is checked directly; the file side is exercised on a jimfs
/// volume so a write would be observable rather than silently landing on the
/// developer's disk.
@NotNullByDefault
public final class MindustryCloudSaveSyncTest {

    /// Fixed base timestamp so every comparison is deterministic.
    private static final Instant BASE = Instant.parse("2026-10-12T10:00:00Z");

    /// An instant clearly inside the unambiguous window.
    private static final Instant CLOSE = BASE.plusSeconds(30);

    /// An instant clearly outside the unambiguous window.
    private static final Instant FAR = BASE.plus(Duration.ofHours(2));

    /// The window itself, to pin the boundary.
    private static final Instant BOUNDARY = BASE.plus(MindustryCloudSaveSync.UNANIMOUS_WINDOW);

    /// SHA-256 of {@code "first"}.
    private static final String HASH_FIRST = sha256("first");

    /// SHA-256 of {@code "second"}.
    private static final String HASH_SECOND = sha256("second");

    /// Identical content is in sync no matter how far the timestamps differ.
    @Test
    public void identicalHashIsInSync() {
        assertEquals(Verdict.IN_SYNC,
                MindustryCloudSaveSync.verdictFor(HASH_FIRST, BASE, HASH_FIRST, FAR));
        assertEquals(Verdict.IN_SYNC,
                MindustryCloudSaveSync.verdictFor(HASH_FIRST, BASE, HASH_FIRST, BASE));
        assertEquals(Verdict.IN_SYNC,
                MindustryCloudSaveSync.verdictFor(HASH_FIRST, FAR, HASH_FIRST, BASE));
    }

    /// A clear time gap orders the two sides, in both directions.
    @Test
    public void clearTimeGapPicksTheNewerSide() {
        assertEquals(Verdict.CLOUD_NEWER,
                MindustryCloudSaveSync.verdictFor(HASH_FIRST, BASE, HASH_SECOND, FAR));
        assertEquals(Verdict.LOCAL_NEWER,
                MindustryCloudSaveSync.verdictFor(HASH_FIRST, FAR, HASH_SECOND, BASE));
    }

    /// Timestamps inside the window cannot be ordered, so they are a conflict.
    @Test
    public void closeTimestampsAreAConflict() {
        assertEquals(Verdict.CONFLICT,
                MindustryCloudSaveSync.verdictFor(HASH_FIRST, BASE, HASH_SECOND, CLOSE));
        assertEquals(Verdict.CONFLICT,
                MindustryCloudSaveSync.verdictFor(HASH_FIRST, CLOSE, HASH_SECOND, BASE));
        assertEquals(Verdict.CONFLICT,
                MindustryCloudSaveSync.verdictFor(HASH_FIRST, BASE, HASH_SECOND, BASE));
    }

    /// The window's far edge is inclusive, so the boundary stays a conflict.
    @Test
    public void windowBoundaryIsInclusive() {
        assertEquals(Verdict.CONFLICT,
                MindustryCloudSaveSync.verdictFor(HASH_FIRST, BASE, HASH_SECOND, BOUNDARY));
        assertEquals(Verdict.CLOUD_NEWER,
                MindustryCloudSaveSync.verdictFor(HASH_FIRST, BASE, HASH_SECOND,
                        BOUNDARY.plusSeconds(1)));
    }

    /// An unknown hash on either side cannot prove equality, so time decides.
    @Test
    public void missingHashFallsBackToTimestamps() {
        assertEquals(Verdict.CLOUD_NEWER,
                MindustryCloudSaveSync.verdictFor("", BASE, HASH_SECOND, FAR));
        assertEquals(Verdict.LOCAL_NEWER,
                MindustryCloudSaveSync.verdictFor(HASH_FIRST, FAR, "", BASE));
        assertEquals(Verdict.CONFLICT,
                MindustryCloudSaveSync.verdictFor("", BASE, "", CLOSE));
    }

    /// The full verdict matrix, written out for review.
    @Test
    public void verdictMatrixIsStable() {
        assertEquals(Verdict.IN_SYNC,
                MindustryCloudSaveSync.verdictFor(HASH_FIRST, BASE, HASH_FIRST, FAR));
        assertEquals(Verdict.CLOUD_NEWER,
                MindustryCloudSaveSync.verdictFor(HASH_FIRST, BASE, HASH_SECOND, FAR));
        assertEquals(Verdict.LOCAL_NEWER,
                MindustryCloudSaveSync.verdictFor(HASH_FIRST, FAR, HASH_SECOND, BASE));
        assertEquals(Verdict.CONFLICT,
                MindustryCloudSaveSync.verdictFor(HASH_FIRST, BASE, HASH_SECOND, CLOSE));
    }

    /// A check reads the cloud exactly once, however many local files exist.
    @Test
    public void checkReadsTheCloudExactlyOnce(@TempDir Path dir) throws Exception {
        Path saves = dir.resolve("saves");
        Files.createDirectories(saves);
        write(saves.resolve("a.msav"), "first", BASE);
        write(saves.resolve("b.msav"), "second", BASE);
        AtomicInteger reads = new AtomicInteger();

        MindustryCloudSaveSync.check(saves, () -> {
            reads.incrementAndGet();
            return List.of(head("a.msav", "first", FAR), head("b.msav", "second", FAR));
        });

        assertEquals(1, reads.get(), "the cloud must be read once per check, not once per file");
    }

    /// The check never writes to the saves directory, not even a temp file.
    ///
    /// The save tree is mounted from something the platform genuinely refuses
    /// to write to, so *any* write — creating a file, creating a temp file, or
    /// touching an mtime — fails instead of silently succeeding. That turns
    /// "the check is read-only" from a code-review claim into an enforced
    /// property.
    ///
    /// Two fixture strategies are needed because neither works everywhere: a
    /// zip mounted with `accessMode=readOnly`, and a POSIX directory with its
    /// write bits cleared. The fixture is verified before it is relied on, and
    /// the test is skipped when the host can write to it anyway (a root user
    /// ignores the POSIX bits) rather than reporting a false failure.
    ///
    /// @param dir scratch directory for the fixture
    /// @throws IOException when the fixture cannot be built
    @Test
    public void checkNeverWritesToTheSavesDirectory(@TempDir Path dir) throws Exception {
        @Nullable ReadOnlyTree tree = buildReadOnlyTree(dir);
        assumeTrue(tree != null, "no read-only fixture is available on this platform/JDK");
        try (tree) {
            Path saves = tree.savesDir();
            assumeTrue(rejectsWrites(saves),
                    "the fixture is writable here, so it cannot prove anything");

            CheckResult result = MindustryCloudSaveSync.check(saves,
                    () -> List.of(head("live.msav", "first", FAR)));

            // The backup copy must not be mistaken for a live save, so the
            // only comparison is the one live file, and it matches.
            assertEquals(List.of("live.msav"),
                    result.comparisons().stream().map(SaveComparison::fileName).toList());
            assertEquals(Verdict.IN_SYNC, result.comparisons().get(0).verdict());
            assertEquals(List.of("backups", "live.msav"), listNames(saves));
            assertEquals("first", Files.readString(saves.resolve("live.msav")));
            assertEquals(List.of("live-20260101.msav"), listNames(saves.resolve("backups")));

            // The check completed without a single write having been able to
            // land, and the fixture still refuses writes afterwards.
            assertTrue(rejectsWrites(saves),
                    "the fixture must stay read-only after the check");
        }
    }

    /// Something the platform will not let a test write to.
    ///
    /// @param savesDir    root of the save tree to hand to the check
    /// @param zipFileSystem mounted zip backing the tree, or `null` for the POSIX fixture
    /// @param posixDirectory directory whose write bits were cleared, or `null`
    private record ReadOnlyTree(Path savesDir, FileSystem zipFileSystem,
                               Path posixDirectory) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (zipFileSystem != null) {
                zipFileSystem.close();
            }
        }
    }

    /// Builds a save tree that rejects writes, or returns `null` when the host offers none.
    ///
    /// @param work scratch directory
    /// @return the fixture, or `null` when neither strategy is available
    /// @throws IOException when the fixture cannot be created
    private static @Nullable ReadOnlyTree buildReadOnlyTree(Path work) throws IOException {
        Path zip = work.resolve("saves.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            putEntry(out, "saves/live.msav", "first");
            putEntry(out, "saves/backups/live-20260101.msav", "second");
        }
        try {
            // Only honoured from JDK 21 on; older providers silently return a
            // writable file system, which isReadOnly() then reports.
            FileSystem fs = FileSystems.newFileSystem(zip, Map.of("accessMode", "readOnly"));
            if (fs.isReadOnly()) {
                return new ReadOnlyTree(fs.getPath("/saves"), fs, null);
            }
            fs.close();
        } catch (IOException | UnsupportedOperationException ignored) {
            // Fall through to the POSIX strategy.
        }

        Path root = work.resolve("posix");
        Path saves = root.resolve("saves");
        write(saves.resolve("live.msav"), "first", BASE);
        write(saves.resolve("backups/live-20260101.msav"), "second", BASE);
        try {
            Files.setPosixFilePermissions(saves,
                    PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (UnsupportedOperationException | IOException e) {
            return null;
        }
        return new ReadOnlyTree(saves, null, saves);
    }

    /// Whether a save tree really rejects writes.
    ///
    /// Doubles as the fixture's positive control: a root user on Linux can
    /// still write into a `chmod 0555` directory, and a test that quietly
    /// passed there would prove nothing.
    ///
    /// @param savesDir tree to probe
    /// @return `true` when a probe file could not be created
    private static boolean rejectsWrites(Path savesDir) {
        Path probe = savesDir.resolve("xenon-readonly-probe.tmp");
        try {
            Files.writeString(probe, "probe");
            Files.deleteIfExists(probe);
            return false;
        } catch (IOException | RuntimeException e) {
            // A read-only zip file system reports `ReadOnlyFileSystemException`,
            // an `UnsupportedOperationException` rather than an `IOException`.
            return true;
        }
    }

    /// Sub-directories such as `saves/backups` are not live saves.
    @Test
    public void checkIgnoresBackupSubdirectory(@TempDir Path dir) throws Exception {
        Path saves = dir.resolve("saves");
        Files.createDirectories(saves.resolve("backups"));
        write(saves.resolve("live.msav"), "first", BASE);
        write(saves.resolve("backups/live-20260101.msav"), "second", BASE);

        CheckResult result = MindustryCloudSaveSync.check(saves, List::of);

        assertEquals(List.of("live.msav"),
                result.comparisons().stream().map(SaveComparison::fileName).toList());
    }

    /// Non-save files sitting next to the saves are ignored.
    @Test
    public void checkIgnoresNonSaveFiles(@TempDir Path dir) throws Exception {
        Path saves = dir.resolve("saves");
        Files.createDirectories(saves);
        write(saves.resolve("live.msav"), "first", BASE);
        write(saves.resolve("notes.txt"), "second", BASE);
        write(saves.resolve("replay.mrep"), "second", BASE);

        CheckResult result = MindustryCloudSaveSync.check(saves, List::of);

        assertEquals(List.of("live.msav"),
                result.comparisons().stream().map(SaveComparison::fileName).toList());
    }

    /// A missing saves directory is a normal state, not an error.
    @Test
    public void missingSavesDirectoryYieldsAnEmptyResult(@TempDir Path dir) throws Exception {
        CheckResult result = MindustryCloudSaveSync.check(dir.resolve("nope"), List::of);

        assertTrue(result.isEmpty());
        assertFalse(result.hasConflict());
    }

    /// Each side is reported on its own, with the other side's size left at zero.
    @Test
    public void oneSidedFilesAreReportedSeparately(@TempDir Path dir) throws Exception {
        Path saves = dir.resolve("saves");
        Files.createDirectories(saves);
        write(saves.resolve("local-only.msav"), "first", BASE);

        CheckResult result = MindustryCloudSaveSync.check(saves,
                () -> List.of(head("cloud-only.msav", "second", FAR)));

        assertEquals(List.of("cloud-only.msav", "local-only.msav"),
                result.comparisons().stream().map(SaveComparison::fileName).toList());

        SaveComparison localOnly = result.comparisons().get(1);
        assertEquals(Verdict.LOCAL_NEWER, localOnly.verdict());
        assertTrue(localOnly.needsAttention());
        assertEquals(0L, localOnly.cloud().size());
        assertEquals("first".length(), localOnly.local().size());

        SaveComparison cloudOnly = result.comparisons().get(0);
        assertEquals(Verdict.CLOUD_NEWER, cloudOnly.verdict());
        assertEquals(0L, cloudOnly.local().size());
    }

    /// Attention reporting and the conflict flag summarise the result.
    @Test
    public void resultSummarisesAttentionAndConflicts(@TempDir Path dir) throws Exception {
        Path saves = dir.resolve("saves");
        Files.createDirectories(saves);
        write(saves.resolve("same.msav"), "first", BASE);
        write(saves.resolve("moved.msav"), "first", BASE);
        write(saves.resolve("tangled.msav"), "first", BASE);

        CheckResult result = MindustryCloudSaveSync.check(saves, () -> List.of(
                head("same.msav", "first", FAR),
                head("moved.msav", "second", FAR),
                head("tangled.msav", "second", CLOSE)));

        assertEquals(3, result.comparisons().size());
        assertEquals(2, result.needingAttention().size());
        assertTrue(result.hasConflict());
    }

    /// The newest snapshot of a slot is never a deletion candidate.
    @Test
    public void quotaCleanupNeverDeletesTheNewestSnapshot() {
        Quota quota = new Quota(900L, 1000L, 0L);
        List<Snapshot> snapshots = List.of(
                snapshot("oldest", 200L, false, BASE),
                snapshot("newest", 500L, false, FAR));

        CleanupPlan plan = MindustryCloudSaveSync.planQuotaCleanup(quota, snapshots, 150L);

        assertEquals(List.of("oldest"), idsOf(plan),
                "only the unpinned non-newest snapshot may be dropped");
        assertTrue(plan.sufficient(), "200 bytes is enough room for a 150 byte upload");
    }

    /// A big newest snapshot is protected even when deleting it would be the only way.
    @Test
    public void quotaCleanupReportsInsufficiencyWhenOnlyTheNewestWouldHelp() {
        Quota quota = new Quota(900L, 1000L, 0L);
        List<Snapshot> snapshots = List.of(
                snapshot("oldest", 100L, false, BASE),
                snapshot("newest", 800L, false, FAR));

        CleanupPlan plan = MindustryCloudSaveSync.planQuotaCleanup(quota, snapshots, 250L);

        assertEquals(List.of("oldest"), idsOf(plan));
        assertFalse(plan.sufficient(),
                "the newest snapshot must not be deleted to make room");
    }

    /// A pinned snapshot is never a deletion candidate, even as the oldest.
    @Test
    public void quotaCleanupNeverDeletesPinnedSnapshots() {
        Quota quota = new Quota(900L, 1000L, 0L);
        List<Snapshot> snapshots = List.of(
                snapshot("pinned-oldest", 400L, true, BASE),
                snapshot("unpinned-middle", 300L, false, CLOSE),
                snapshot("newest", 200L, false, FAR));

        CleanupPlan plan = MindustryCloudSaveSync.planQuotaCleanup(quota, snapshots, 200L);

        assertEquals(List.of("unpinned-middle"), idsOf(plan));
        assertTrue(plan.sufficient(), "300 bytes is enough room for a 200 byte upload");
    }

    /// Deletions are ordered oldest first so retention drops the least history.
    @Test
    public void quotaCleanupDeletesOldestFirst() {
        Quota quota = new Quota(1000L, 1000L, 0L);
        List<Snapshot> snapshots = List.of(
                snapshot("newest", 100L, false, FAR),
                snapshot("third", 100L, false, CLOSE),
                snapshot("second", 100L, false, BASE.plusSeconds(10)),
                snapshot("oldest", 100L, false, BASE));

        CleanupPlan plan = MindustryCloudSaveSync.planQuotaCleanup(quota, snapshots, 250L);

        assertEquals(List.of("oldest", "second", "third"), idsOf(plan));
        assertEquals(300L, plan.freedBytes());
        assertTrue(plan.sufficient());
    }

    /// When every candidate is protected the plan reports that it cannot help.
    @Test
    public void quotaCleanupReportsInsufficiencyWhenNothingIsEligible() {
        Quota quota = new Quota(1000L, 1000L, 0L);
        List<Snapshot> snapshots = List.of(
                snapshot("only", 900L, false, BASE));

        CleanupPlan plan = MindustryCloudSaveSync.planQuotaCleanup(quota, snapshots, 50L);

        assertTrue(plan.isEmpty(), "the only snapshot is also the newest");
        assertFalse(plan.sufficient());
    }

    /// An upload that already fits plans nothing at all.
    @Test
    public void quotaCleanupIsANoOpWhenTheUploadFits() {
        Quota quota = new Quota(100L, 1000L, 0L);
        List<Snapshot> snapshots = List.of(snapshot("only", 100L, false, BASE));

        CleanupPlan plan = MindustryCloudSaveSync.planQuotaCleanup(quota, snapshots, 500L);

        assertTrue(plan.isEmpty());
        assertTrue(plan.sufficient());
        assertEquals(0L, plan.freedBytes());
    }

    /// An unlimited quota never deletes anything.
    @Test
    public void unlimitedQuotaNeverDeletes() {
        Quota quota = new Quota(10_000L, 0L, 0L);
        List<Snapshot> snapshots = List.of(
                snapshot("oldest", 400L, false, BASE),
                snapshot("newest", 400L, false, FAR));

        CleanupPlan plan = MindustryCloudSaveSync.planQuotaCleanup(quota, snapshots, 400L);

        assertTrue(plan.isEmpty());
        assertTrue(plan.sufficient());
    }

    /// A quota that cannot be satisfied even after deleting everything says so.
    @Test
    public void quotaCleanupReportsInsufficiencyWhenEverythingIsDeleted() {
        Quota quota = new Quota(1000L, 1000L, 0L);
        List<Snapshot> snapshots = List.of(
                snapshot("oldest", 100L, false, BASE),
                snapshot("newest", 100L, false, FAR));

        CleanupPlan plan = MindustryCloudSaveSync.planQuotaCleanup(quota, snapshots, 2000L);

        assertEquals(List.of("oldest"), idsOf(plan));
        assertFalse(plan.sufficient(), "an upload larger than the whole quota cannot be made to fit");
    }

    /// The local reader reports size, mtime and hash for each save.
    @Test
    public void localReaderReportsSizeTimeAndHash(@TempDir Path dir) throws Exception {
        Path saves = dir.resolve("saves");
        Files.createDirectories(saves);
        write(saves.resolve("save.msav"), "first", BASE);

        var identities = MindustryCloudSaveSync.readLocalSaves(saves);

        assertEquals(1, identities.size());
        SaveIdentity identity = identities.get("save.msav");
        assertNotNull(identity);
        assertEquals(HASH_FIRST, identity.sha256());
        assertEquals("first".length(), identity.size());
        assertEquals(BASE, identity.timestamp());
    }

    /// Writes a save file with fixed content and modification time.
    ///
    /// @param file    destination
    /// @param content file body
    /// @param mtime   modification time to set
    /// @throws IOException when the file cannot be written
    private static void write(Path file, String content, Instant mtime) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(mtime));
    }

    /// Adds one UTF-8 text entry to an archive.
    ///
    /// @param out  archive to write to
    /// @param name entry path inside the archive
    /// @param body entry body
    /// @throws IOException when the entry cannot be written
    private static void putEntry(ZipOutputStream out, String name, String body) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    /// Builds a cloud head for one file.
    ///
    /// @param fileName save file name
    /// @param content  body whose hash the head advertises
    /// @param createdAt cloud snapshot creation time
    /// @return the head
    private static CloudHead head(String fileName, String content, Instant createdAt) {
        return new CloudHead("slot-1", "Main", fileName, content.length(), sha256(content),
                createdAt);
    }

    /// Builds a cloud snapshot.
    ///
    /// @param id        snapshot id
    /// @param size      size in bytes
    /// @param pinned    whether the snapshot is pinned
    /// @param createdAt creation time
    /// @return the snapshot
    private static Snapshot snapshot(String id, long size, boolean pinned, Instant createdAt) {
        return new Snapshot(id, size, sha256(id), id + ".msav", pinned, createdAt);
    }

    /// Extracts the snapshot ids of a cleanup plan, in order.
    ///
    /// @param plan plan to read
    /// @return planned ids
    private static List<String> idsOf(CleanupPlan plan) {
        return plan.deletions().stream().map(d -> d.snapshotId()).toList();
    }

    /// Lists the entry names of a directory, sorted.
    ///
    /// @param dir directory to list
    /// @return sorted entry names
    /// @throws IOException when the directory cannot be listed
    private static List<String> listNames(Path dir) throws IOException {
        List<String> names = new ArrayList<>();
        try (var entries = Files.list(dir)) {
            entries.forEach(path -> names.add(path.getFileName().toString()));
        }
        names.sort(java.util.Comparator.naturalOrder());
        return names;
    }

    /// Computes the lowercase-hex SHA-256 of a string's UTF-8 bytes.
    ///
    /// @param content input text
    /// @return the digest as lowercase hex
    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 is missing", e);
        }
    }
}
