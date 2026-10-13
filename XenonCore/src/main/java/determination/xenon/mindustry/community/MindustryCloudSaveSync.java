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
import determination.xenon.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/// Read-only comparison between one instance's local saves and the MDTBBS cloud.
///
/// The check answers one question per save file: is the cloud copy the same as
/// the local one, clearly newer, clearly older, or have both moved at nearly
/// the same time? It never uploads, downloads, merges or writes anything —
/// every method only reads the saves directory and the cloud metadata, so a
/// background check can run without the user's consent and without risking a
/// local file.
///
/// Because the launcher stores no "last synced" baseline, cloud-vs-local can
/// only be ordered by timestamp. A pair whose hashes differ while the two
/// timestamps fall inside [MindustryCloudSaveSync#UNANIMOUS_WINDOW] cannot be
/// ordered honestly, so it is reported as
/// [MindustryCloudSaveSync.Verdict#CONFLICT] and the user decides. Everything
/// outside that window is an unambiguous one-sided change. Persisting a sync
/// baseline would remove the guesswork and is tracked as a follow-up; adding
/// one is deliberately out of scope here because it would need a write on the
/// check path.
@NotNullByDefault
public final class MindustryCloudSaveSync {

    /// Timestamp window inside which neither side is considered clearly newer.
    ///
    /// Two edits closer together than this cannot be ordered by time alone, so
    /// the pair is surfaced as a conflict instead of being resolved silently.
    public static final Duration UNANIMOUS_WINDOW = Duration.ofMinutes(5);

    /// File extension of a Mindustry save.
    private static final String SAVE_SUFFIX = ".msav";

    /// How a local save relates to its cloud head.
    public enum Verdict {
        /// The local file and the cloud head are byte-identical.
        IN_SYNC,
        /// The cloud head is clearly newer than the local file.
        CLOUD_NEWER,
        /// The local file is clearly newer than the cloud head.
        LOCAL_NEWER,
        /// Both sides differ and neither timestamp dominates.
        CONFLICT
    }

    /// One side of a comparison.
    ///
    /// @param fileName  save file name
    /// @param size      size in bytes
    /// @param timestamp local modification time, or cloud snapshot creation time
    /// @param sha256    lowercase-hex SHA-256, or empty when unknown
    public record SaveIdentity(String fileName, long size, Instant timestamp, String sha256) {
    }

    /// One local-vs-cloud comparison.
    ///
    /// @param fileName name shared by both sides
    /// @param verdict  how the two sides relate
    /// @param local    local save identity
    /// @param cloud    cloud head identity
    public record SaveComparison(String fileName, Verdict verdict,
                                 SaveIdentity local, SaveIdentity cloud) {
        /// Whether this file needs the user's attention.
        ///
        /// @return `true` for anything other than
        ///         [MindustryCloudSaveSync.Verdict#IN_SYNC]
        public boolean needsAttention() {
            return verdict != Verdict.IN_SYNC;
        }
    }

    /// The newest snapshot of one cloud slot.
    ///
    /// @param slotId    slot id
    /// @param slotName  display name of the slot
    /// @param fileName  original save file name of the newest snapshot
    /// @param size      size of the newest snapshot in bytes
    /// @param sha256    content hash of the newest snapshot, or empty
    /// @param createdAt creation time of the newest snapshot
    public record CloudHead(String slotId, String slotName, String fileName, long size,
                            String sha256, Instant createdAt) {
    }

    /// Supplies the cloud heads a check compares against.
    ///
    /// Kept as an interface so the check can be exercised without a network,
    /// and so a caller can decide up front whether it is allowed to talk to
    /// the cloud at all.
    @FunctionalInterface
    public interface CloudHeads {
        /// Loads the newest snapshot of every slot.
        ///
        /// @return one head per slot, possibly empty
        /// @throws IOException when the cloud cannot be reached
        List<CloudHead> heads() throws IOException;
    }

    /// The result of one read-only check.
    ///
    /// @param comparisons per-file comparisons, sorted by file name
    public record CheckResult(List<SaveComparison> comparisons) {
        /// Copies the list so the result stays immutable.
        public CheckResult {
            comparisons = List.copyOf(comparisons);
        }

        /// Whether there is nothing to compare.
        ///
        /// @return `true` when neither side holds a save
        public boolean isEmpty() {
            return comparisons.isEmpty();
        }

        /// The comparisons that need the user's attention.
        ///
        /// @return every comparison whose verdict is not
        ///         [MindustryCloudSaveSync.Verdict#IN_SYNC]
        public List<SaveComparison> needingAttention() {
            return comparisons.stream().filter(SaveComparison::needsAttention).toList();
        }

        /// Whether any pair is ambiguous.
        ///
        /// @return `true` when at least one pair is
        ///         [MindustryCloudSaveSync.Verdict#CONFLICT]
        public boolean hasConflict() {
            return comparisons.stream()
                    .anyMatch(comparison -> comparison.verdict() == Verdict.CONFLICT);
        }
    }

    /// One snapshot a cleanup plan intends to delete.
    ///
    /// @param snapshotId snapshot id
    /// @param createdAt  creation time, used for the oldest-first ordering
    /// @param size       size in bytes
    /// @param fileName   original file name, or empty
    public record PlannedDeletion(String snapshotId, Instant createdAt, long size,
                                  String fileName) {
    }

    /// A quota cleanup plan.
    ///
    /// @param deletions  snapshots to delete, oldest first
    /// @param freedBytes bytes reclaimed by `deletions`
    /// @param sufficient whether deleting them makes room for the upload
    public record CleanupPlan(List<PlannedDeletion> deletions, long freedBytes,
                              boolean sufficient) {
        /// Copies the list so the plan stays immutable.
        public CleanupPlan {
            deletions = List.copyOf(deletions);
        }

        /// Whether the plan deletes anything.
        ///
        /// @return `true` when at least one snapshot is scheduled for deletion
        public boolean isEmpty() {
            return deletions.isEmpty();
        }
    }

    /// Computes the verdict for one pair of save states.
    ///
    /// @param localHash local SHA-256, empty when it could not be read
    /// @param localTime local modification time
    /// @param cloudHash cloud SHA-256, empty when the metadata omitted it
    /// @param cloudTime cloud snapshot creation time
    /// @return how the two sides relate
    public static Verdict verdictFor(String localHash, Instant localTime,
                                     String cloudHash, Instant cloudTime) {
        boolean hashKnown = !localHash.isBlank() && !cloudHash.isBlank();
        if (hashKnown && localHash.equalsIgnoreCase(cloudHash)) {
            return Verdict.IN_SYNC;
        }
        Duration skew = Duration.between(localTime, cloudTime).abs();
        if (skew.compareTo(UNANIMOUS_WINDOW) <= 0) {
            // Without a baseline and without a clear time gap there is no
            // honest way to pick a winner.
            return Verdict.CONFLICT;
        }
        return cloudTime.isAfter(localTime) ? Verdict.CLOUD_NEWER : Verdict.LOCAL_NEWER;
    }

    /// Compares the local saves of one instance against the cloud heads.
    ///
    /// Read-only: the saves directory is only listed, stat-ed and read, and no
    /// file, temp file or directory is ever created. A missing or empty saves
    /// directory is a normal state and yields an empty result.
    ///
    /// @param savesDir instance `saves/` directory
    /// @param cloud    cloud heads to compare against
    /// @return the per-file comparisons
    /// @throws IOException when the saves directory cannot be listed or a save
    ///                     cannot be read
    public static CheckResult check(Path savesDir, CloudHeads cloud) throws IOException {
        Map<String, SaveIdentity> local = readLocalSaves(savesDir);
        Map<String, CloudHead> remote = new LinkedHashMap<>();
        for (CloudHead head : cloud.heads()) {
            if (head.fileName() == null || head.fileName().isBlank()) continue;
            // Several slots may hold the same file name; the newest head wins
            // so the user is compared against the most recent upload.
            CloudHead existing = remote.get(head.fileName());
            if (existing == null || head.createdAt().isAfter(existing.createdAt())) {
                remote.put(head.fileName(), head);
            }
        }

        List<String> names = new ArrayList<>(local.keySet());
        for (String name : remote.keySet()) {
            if (!local.containsKey(name)) names.add(name);
        }
        names.sort(Comparator.naturalOrder());

        List<SaveComparison> comparisons = new ArrayList<>(names.size());
        for (String name : names) {
            SaveIdentity localSide = local.get(name);
            CloudHead head = remote.get(name);
            if (localSide != null && head != null) {
                comparisons.add(new SaveComparison(name,
                        verdictFor(localSide.sha256(), localSide.timestamp(),
                                head.sha256(), head.createdAt()),
                        localSide,
                        new SaveIdentity(head.fileName(), head.size(), head.createdAt(),
                                head.sha256())));
            } else if (localSide != null) {
                // Local-only: there is nothing to conflict with yet, so this
                // is reported as a local change the user may want to upload.
                comparisons.add(new SaveComparison(name, Verdict.LOCAL_NEWER, localSide,
                        new SaveIdentity(name, 0L, localSide.timestamp(), "")));
            } else if (head != null) {
                comparisons.add(new SaveComparison(name, Verdict.CLOUD_NEWER,
                        new SaveIdentity(name, 0L, head.createdAt(), ""),
                        new SaveIdentity(head.fileName(), head.size(), head.createdAt(),
                                head.sha256())));
            }
        }
        return new CheckResult(comparisons);
    }

    /// Reads every `*.msav` directly inside `savesDir`, hashing each file.
    ///
    /// Sub-directories such as `saves/backups` are skipped: the game does not
    /// see them as live saves, so they must not drive a comparison.
    ///
    /// @param savesDir instance `saves/` directory
    /// @return identities keyed by file name, empty when the directory is absent
    /// @throws IOException when the directory cannot be listed or a save cannot be read
    public static Map<String, SaveIdentity> readLocalSaves(Path savesDir) throws IOException {
        Map<String, SaveIdentity> result = new LinkedHashMap<>();
        if (!Files.isDirectory(savesDir)) return result;
        try (Stream<Path> entries = Files.list(savesDir)) {
            List<Path> files = entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .toLowerCase(Locale.ROOT).endsWith(SAVE_SUFFIX))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            for (Path file : files) {
                BasicFileAttributes attributes = Files.readAttributes(file,
                        BasicFileAttributes.class);
                String name = file.getFileName().toString();
                result.put(name, new SaveIdentity(name,
                        attributes.size(),
                        attributes.lastModifiedTime().toInstant(),
                        sha256Hex(file)));
            }
        }
        return result;
    }

    /// Builds a [CloudHeads] source over the real cloud-save client.
    ///
    /// One head per slot, taken from that slot's newest snapshot. The newest
    /// snapshot is what a restore offers, so it is the only cloud state worth
    /// comparing a local file against.
    ///
    /// @param client authenticated cloud-save client
    /// @return a source that reads every slot's newest snapshot
    public static CloudHeads cloudHeads(MdtbbsCloudSavesClient client) {
        return () -> {
            List<CloudHead> heads = new ArrayList<>();
            String cursor = null;
            // The slot list is cursor-paged; two pages cover every realistic
            // account while keeping a check to a handful of requests.
            for (int page = 0; page < MAX_SLOT_PAGES; page++) {
                MdtbbsCloudSavesClient.SlotPage slots = client.slots(cursor, SLOT_PAGE_SIZE);
                for (MdtbbsCloudSavesClient.Slot slot : slots.slots()) {
                    if (slot.id().isBlank()) continue;
                    List<Snapshot> snapshots = client.snapshots(slot.id());
                    if (snapshots.isEmpty()) continue;
                    Snapshot newest = snapshots.stream()
                            .max(Comparator.comparing(Snapshot::createdAt))
                            .orElse(null);
                    if (newest == null) continue;
                    heads.add(new CloudHead(slot.id(), slot.name(),
                            newest.fileName(), newest.size(), newest.sha256(),
                            newest.createdAt()));
                }
                if (!slots.hasMore() || slots.nextCursor().isBlank()) break;
                cursor = slots.nextCursor();
            }
            return heads;
        };
    }

    /// Plans how to make room for an upload inside the storage quota.
    ///
    /// Only unpinned snapshots are eligible, oldest first, and the newest
    /// snapshot of the slot is never deleted: it is the one a restore would
    /// offer. The plan is computed before anything is deleted so a caller can
    /// log it, and so the rules are testable without a network.
    ///
    /// @param quota       current quota; `quotaBytes` of 0 means unlimited
    /// @param snapshots   snapshots of the target slot
    /// @param neededBytes bytes the pending upload requires
    /// @return the planned deletions and whether they suffice
    public static CleanupPlan planQuotaCleanup(Quota quota, List<Snapshot> snapshots,
                                              long neededBytes) {
        long need = Math.max(0L, neededBytes);
        if (quota.quotaBytes() <= 0L || quota.usedBytes() + need <= quota.quotaBytes()) {
            return new CleanupPlan(List.of(), 0L, true);
        }

        // Newest first, so the head of that list is the snapshot that must
        // survive even when everything else is unpinned.
        List<Snapshot> newestFirst = new ArrayList<>(snapshots);
        newestFirst.sort(Comparator.comparing(Snapshot::createdAt).reversed());
        @Nullable String protectedId = newestFirst.isEmpty() ? null : newestFirst.get(0).id();

        List<Snapshot> oldestFirst = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(oldestFirst);

        List<PlannedDeletion> deletions = new ArrayList<>();
        long reclaimed = 0L;
        for (Snapshot snapshot : oldestFirst) {
            if (quota.usedBytes() - reclaimed + need <= quota.quotaBytes()) break;
            if (snapshot.pinned()) continue;
            if (protectedId != null && protectedId.equals(snapshot.id())) continue;
            deletions.add(new PlannedDeletion(snapshot.id(), snapshot.createdAt(),
                    snapshot.size(), snapshot.fileName()));
            reclaimed += Math.max(0L, snapshot.size());
        }
        return new CleanupPlan(deletions, reclaimed,
                quota.usedBytes() - reclaimed + need <= quota.quotaBytes());
    }

    /// Plans and applies a quota cleanup for one slot.
    ///
    /// Every deletion is logged with the snapshot id and its creation time
    /// *before* the request is sent, because the operation cannot be undone.
    ///
    /// @param client      authenticated cloud-save client
    /// @param slotId      slot the upload targets
    /// @param neededBytes bytes the pending upload requires
    /// @return the plan that was applied
    /// @throws IOException when the quota cannot be read or a deletion fails
    public static CleanupPlan enforceQuota(MdtbbsCloudSavesClient client, String slotId,
                                          long neededBytes) throws IOException {
        Quota quota = client.quota();
        List<Snapshot> snapshots = client.snapshots(slotId);
        CleanupPlan plan = planQuotaCleanup(quota, snapshots, neededBytes);
        for (PlannedDeletion deletion : plan.deletions()) {
            Logger.LOG.info("Cloud save quota cleanup: deleting unpinned snapshot "
                    + deletion.snapshotId() + " created " + deletion.createdAt()
                    + " (" + deletion.size() + " bytes, " + deletion.fileName() + ")");
            client.deleteSnapshot(slotId, deletion.snapshotId());
        }
        if (!plan.isEmpty()) {
            Logger.LOG.info("Cloud save quota cleanup: reclaimed " + plan.freedBytes()
                    + " bytes; upload still fits=" + plan.sufficient());
        }
        return plan;
    }

    /// Computes the lowercase-hex SHA-256 of a file by streaming it.
    ///
    /// @param file file to hash
    /// @return the digest as lowercase hex
    /// @throws IOException when the file cannot be read or SHA-256 is missing
    private static String sha256Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /// Maximum slot pages a single check walks.
    private static final int MAX_SLOT_PAGES = 2;

    /// Page size used when listing slots.
    private static final int SLOT_PAGE_SIZE = 50;

    private MindustryCloudSaveSync() {
    }
}
