package lsmtree;

import rbt.Entry;
import compaction.CompactStrategyImpl.FullCompactStrategy;
import compaction.CompactStrategyImpl.LeveledCompactionStrategy;
import compaction.CompactStrategyImpl.SizeTieredCompactStrategy;
import compaction.MergeStrategyImpl.FullLoadMergeStrategy;
import compaction.MergeStrategyImpl.StreamingMergeStrategy;
import constants.FileConstants;
import core.Segment;
import core.Value;
import core.key.KeyCodec;
import core.key.KeyCodecImpl.IntegerKeyCodec;
import cursor.EntrySource;
import manifest.Manifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class LSMTreeTest {
    // Each value below is a single-byte payload: size = 1 (flag) + 8 (id) + 4 (length) + 1 (payload) = 14 bytes.
    // threshold=50 means 3 puts (42 bytes) stay under it, and the 4th put (56 bytes) crosses it.
    private static final int THRESHOLD = 50;
    private static final int SAMPLE_EVERY = 5;

    private Path dataDir;
    private LSMTree<Integer> lsmTree;

    @BeforeEach
    void setup(@TempDir Path tempDir) throws IOException {
        this.dataDir = tempDir;
        this.lsmTree = new LSMTree<>(new IntegerKeyCodec(), dataDir, SAMPLE_EVERY, THRESHOLD, new FullCompactStrategy<>(5), new FullLoadMergeStrategy<>(new IntegerKeyCodec()));
    }

    @AfterEach
    void tearDown() throws IOException {
        lsmTree.close();
    }

    private Value singleByteValue(int i) {
        return Value.of(new byte[]{(byte) i});
    }

    private void putFourKeysCrossingThreshold() throws IOException {
        for (int i = 1; i <= 4; i++) lsmTree.put(i, singleByteValue(i));
    }

    private LSMTree<Integer> newTreeWithCompactionThreshold(Path dir, int compactionThreshold) throws IOException {
        return new LSMTree<>(new IntegerKeyCodec(), dir, SAMPLE_EVERY, THRESHOLD,
                new FullCompactStrategy<>(compactionThreshold), new FullLoadMergeStrategy<>(new IntegerKeyCodec()));
    }

    private File[] dataFiles(Path dir) {
        File[] files = dir.toFile().listFiles((_, name) -> name.startsWith("dataFile-"));
        assertNotNull(files, "expected the directory to exist and be listable");
        return files;
    }

    private List<Entry<Integer, Value>> scanEntries(LSMTree<Integer> tree, int low, int high) throws IOException {
        List<Entry<Integer, Value>> result = new ArrayList<>();
        try (EntrySource<Integer> cursor = tree.scan(low, high)) {
            while (cursor.hasNext()) result.add(cursor.next());
        }
        return result;
    }

    private List<Entry<Integer, Value>> scanEntries(int low, int high) throws IOException {
        return scanEntries(this.lsmTree, low, high);
    }

    @Test
    void crossingThresholdTriggersAutomaticFlush() throws IOException {
        lsmTree.put(1, singleByteValue(1));
        lsmTree.put(2, singleByteValue(2));
        lsmTree.put(3, singleByteValue(3));
        assertEquals(0, lsmTree.segmentCount(), "should not have flushed yet, still under threshold");

        lsmTree.put(4, singleByteValue(4)); // 24 bytes >= 20, crosses the threshold

        assertEquals(1, lsmTree.segmentCount(), "crossing the threshold should trigger exactly one flush");
    }

    @Test
    void memTableIsEmptyAfterFlush() throws IOException {
        putFourKeysCrossingThreshold();

        assertEquals(0, lsmTree.memTableSizeInBytes(), "memTable should be empty right after a flush");
    }

    @Test
    void walIsTruncatedAfterFlush() throws IOException {
        putFourKeysCrossingThreshold();

        assertEquals(0, lsmTree.walFile().length(), "WAL should be truncated after a flush");
    }

    @Test
    void flushedKeysReadableFromSSTable() throws IOException {
        putFourKeysCrossingThreshold();

        assertAll(() -> assertEquals(0, lsmTree.memTableSizeInBytes(), "sanity check: flush already happened"), () -> assertEquals(singleByteValue(1), lsmTree.get(1), "key 1 should read back from the flushed segment"), () -> assertEquals(singleByteValue(2), lsmTree.get(2), "key 2 should read back from the flushed segment"), () -> assertEquals(singleByteValue(3), lsmTree.get(3), "key 3 should read back from the flushed segment"), () -> assertEquals(singleByteValue(4), lsmTree.get(4), "key 4 should read back from the flushed segment"));
    }

    @Test
    void keyPutAfterFlushIsRetrievable() throws IOException {
        putFourKeysCrossingThreshold(); // triggers a flush

        lsmTree.put(100, singleByteValue(100));

        assertEquals(singleByteValue(100), lsmTree.get(100), "a key put after the flush should be retrievable from the fresh memTable");
    }

    @Test
    void updateAfterFlushShadowsOldSegmentValue() throws IOException {
        putFourKeysCrossingThreshold(); // key 2's original value is now in the segment

        Value updated = Value.of("updated".getBytes(StandardCharsets.UTF_8));
        lsmTree.put(2, updated);

        assertEquals(updated, lsmTree.get(2), "an update after flush should shadow the old value now sitting in the segment");
    }

    @Test
    void deleteAfterFlushReturnsTombstoneNotStaleValue() throws IOException {
        putFourKeysCrossingThreshold(); // key 3's original value is now in the segment

        lsmTree.put(3, Value.tombstone());
        Value result = lsmTree.get(3);

        assertAll(() -> assertNotNull(result, "a deleted key must return a Value, not null"), () -> assertTrue(result.isTombstone(), "should be a tombstone, not the stale segment value"));
    }

    @Test
    void absentKeyReturnsNull() throws IOException {
        putFourKeysCrossingThreshold();

        assertNull(lsmTree.get(999), "a key that was never written should be absent");
    }

    @Test
    void flushOnEmptyTreeIsNoOp() throws IOException {
        lsmTree.flush();

        assertEquals(0, lsmTree.segmentCount(), "flushing an empty memTable should not create a segment");
    }

    @Test
    void crossingThresholdTwiceProducesTwoSegments() throws IOException {
        putFourKeysCrossingThreshold(); // flush #1
        assertEquals(1, lsmTree.segmentCount(), "sanity check: first flush happened");

        for (int i = 5; i <= 8; i++) lsmTree.put(i, singleByteValue(i)); // flush #2

        assertEquals(2, lsmTree.segmentCount(), "crossing the threshold a second time should produce a second segment");
    }

    @Test
    void updateAcrossSegmentsShadowsOlderSegment() throws IOException {
        lsmTree.put(1, singleByteValue(1));
        lsmTree.flush(); // segment 1: key 1 = singleByteValue(1)

        Value updated = Value.of("updated".getBytes(StandardCharsets.UTF_8));
        lsmTree.put(1, updated);
        lsmTree.flush(); // segment 2: key 1 = updated

        assertAll(() -> assertEquals(2, lsmTree.segmentCount(), "sanity check: two segments exist"), () -> assertEquals(updated, lsmTree.get(1), "the newer segment's value should shadow the older segment's value"));
    }

    @Test
    void tombstoneAcrossSegmentsHidesOlderValue() throws IOException {
        lsmTree.put(1, singleByteValue(1));
        lsmTree.flush(); // segment 1: key 1 = singleByteValue(1)

        lsmTree.put(1, Value.tombstone());
        lsmTree.flush(); // segment 2: key 1 = tombstone

        Value result = lsmTree.get(1);
        assertAll(() -> assertEquals(2, lsmTree.segmentCount(), "sanity check: two segments exist"), () -> assertNotNull(result, "a deleted key must return a Value, not null"), () -> assertTrue(result.isTombstone(), "the newer segment's tombstone should hide the older segment's value"));
    }

    @Test
    void keyOnlyInOlderSegmentIsStillFound() throws IOException {
        lsmTree.put(1, singleByteValue(1));
        lsmTree.flush(); // segment 1: key 1 only

        lsmTree.put(2, singleByteValue(2));
        lsmTree.flush(); // segment 2: key 2 only, does not contain key 1

        assertAll(() -> assertEquals(2, lsmTree.segmentCount(), "sanity check: two segments exist"), () -> assertEquals(singleByteValue(1), lsmTree.get(1), "a key present only in an older segment should still be found by falling through the newer one"));
    }

    @Test
    void getSkipsSegmentWhoseFilterReportsAbsent() throws IOException {
        lsmTree.put(1, singleByteValue(1));
        lsmTree.flush(); // segment's data file now on disk, bloom filter built over {1}

        File[] dataFiles = dataDir.toFile().listFiles((_, name) -> name.startsWith("dataFile-"));
        assertNotNull(dataFiles);
        assertEquals(1, dataFiles.length, "sanity check: exactly one segment data file exists");
        assertTrue(dataFiles[0].delete());

        assertNull(lsmTree.get(999), "a key the bloom filter reports absent should never touch the (now-missing) data file");
    }

    @Test
    void keyInNoSegmentReturnsNull() throws IOException {
        lsmTree.put(1, singleByteValue(1));
        lsmTree.flush();

        lsmTree.put(2, singleByteValue(2));
        lsmTree.flush();

        assertNull(lsmTree.get(999), "a key absent from every segment and the memTable should return null");
    }

    @Test
    void crossingCompactionThresholdTriggersAutomaticCompaction() throws IOException, InterruptedException {
        try (LSMTree<Integer> tree = newTreeWithCompactionThreshold(dataDir.resolve("auto-compact"), 1)) {
            tree.put(1, singleByteValue(1));
            tree.put(2, singleByteValue(2));
            tree.put(3, singleByteValue(3));
            tree.put(4, singleByteValue(4)); // crosses memTable threshold -> flush #1, 1 segment (not yet > compaction threshold)
            assertEquals(1, tree.segmentCount(), "sanity check: first flush produced exactly one segment");

            for (int i = 5; i <= 8; i++)
                tree.put(i, singleByteValue(i)); // flush #2 -> 2 segments crosses compaction threshold(1)

            tree.awaitCompaction();
            assertEquals(1, tree.segmentCount(), "crossing the compaction threshold should automatically merge back down to a single segment");
            assertAll(
                    () -> assertEquals(singleByteValue(1), tree.get(1), "a key from the first (older) segment should survive the auto-compaction"),
                    () -> assertEquals(singleByteValue(8), tree.get(8), "a key from the second (newer) segment should survive the auto-compaction")
            );
        }
    }

    @Test
    void compactPreservesLatestValuePerKeyAcrossSegments() throws IOException {
        // high threshold: compact manually below
        try (LSMTree<Integer> tree = newTreeWithCompactionThreshold(dataDir.resolve("latest-wins"), 100)) {
            tree.put(1, singleByteValue(1));
            tree.flush();

            Value updated = Value.of("updated".getBytes(StandardCharsets.UTF_8));
            tree.put(1, updated);
            tree.flush(); // segment 2: key 1 = updated value

            tree.sharedCompactionLogic(tree.segments());

            assertAll(
                    () -> assertEquals(1, tree.segmentCount(), "compaction should merge both segments into one"),
                    () -> assertEquals(updated, tree.get(1), "the newest value across the merged segments should survive compaction")
            );
        }
    }

    @Test
    void compactionDropsTombstonesEntirely() throws IOException {
        try (LSMTree<Integer> tree = newTreeWithCompactionThreshold(dataDir.resolve("tombstone-drop"), 100)) {
            tree.put(1, singleByteValue(1));
            tree.flush(); // segment 1: key 1 = a real value

            tree.put(1, Value.tombstone());
            tree.flush(); // segment 2: key 1 = tombstone

            Value beforeCompact = tree.get(1);
            assertNotNull(beforeCompact, "before compaction: the tombstone must still be visible, not null");
            assertTrue(beforeCompact.isTombstone(), "before compaction: should read as a tombstone shadowing the older value");

            tree.sharedCompactionLogic(tree.segments());

            assertAll(
                    () -> assertEquals(1, tree.segmentCount(), "compaction should merge both segments into one"),
                    () -> assertNull(tree.get(1), "after compaction the tombStoned key should be gone entirely, not just hidden -- there's no older segment left for it to shadow")
            );
        }
    }

    @Test
    void readsAreUnchangedAcrossCompaction() throws IOException {
        try (LSMTree<Integer> tree = newTreeWithCompactionThreshold(dataDir.resolve("reads-unchanged"), 100)) {
            tree.put(1, singleByteValue(1));
            tree.put(2, singleByteValue(2));
            tree.flush(); // segment 1: key1=1, key2=2

            Value updated = Value.of("updated".getBytes(StandardCharsets.UTF_8));
            tree.put(2, updated);
            tree.put(3, singleByteValue(3));
            tree.flush(); // segment 2: key2=updated, key3=3

            Value key1Before = tree.get(1);
            Value key2Before = tree.get(2);
            Value key3Before = tree.get(3);
            Value key999Before = tree.get(999);

            tree.sharedCompactionLogic(tree.segments());

            assertAll(
                    () -> assertEquals(1, tree.segmentCount(), "sanity check: compaction merged both segments into one"),
                    () -> assertEquals(key1Before, tree.get(1), "a key present only in the older segment should read the same after compaction"),
                    () -> assertEquals(key2Before, tree.get(2), "an updated key should still read its newest value after compaction"),
                    () -> assertEquals(key3Before, tree.get(3), "a key present only in the newer segment should read the same after compaction"),
                    () -> assertEquals(key999Before, tree.get(999), "an absent key should still be absent after compaction")
            );
        }
    }

    @Test
    void compactedSegmentIsSmallerThanSumOfOriginalSegments() throws IOException {
        Path dir = dataDir.resolve("size-shrink");
        try (LSMTree<Integer> tree = newTreeWithCompactionThreshold(dir, 100)) {
            tree.put(1, singleByteValue(1));
            tree.put(2, Value.of(new byte[200])); // large payload, tombStoned below -- should vanish from the merge
            tree.flush(); // segment 1
            assertEquals(1, dataFiles(dir).length, "sanity check: first flush produced one data file");

            tree.put(1, singleByteValue(1)); // unchanged duplicate of key 1 -- redundant across segments
            tree.put(2, Value.tombstone());  // tombstones the large payload
            tree.flush(); // segment 2

            File[] beforeCompact = dataFiles(dir);
            assertEquals(2, beforeCompact.length, "sanity check: two data files exist before compaction");
            long preCompactTotalBytes = Arrays.stream(beforeCompact).mapToLong(File::length).sum();
            Set<String> preCompactNames = Arrays.stream(beforeCompact).map(File::getName).collect(Collectors.toSet());

            tree.sharedCompactionLogic(tree.segments());

            File[] afterCompact = dataFiles(dir);
            assertEquals(1, afterCompact.length, "old segment files should be physically deleted once compaction completes and nothing still references them");

            File mergedFile = Arrays.stream(afterCompact)
                    .filter(f -> !preCompactNames.contains(f.getName()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected exactly one newly created data file after compaction"));

            assertTrue(mergedFile.length() < preCompactTotalBytes,
                    "merged segment (" + mergedFile.length() + " bytes) should be smaller than the sum of the " +
                            "two segments it replaced (" + preCompactTotalBytes + " bytes), since the duplicate " +
                            "key and the large tombStoned payload are both eliminated by the merge");
        }
    }

    @Test
    void scanOnMemTableOnlyReturnsInRangeEntriesAscending() throws IOException {
        lsmTree.put(3, singleByteValue(3));
        lsmTree.put(1, singleByteValue(1));
        lsmTree.put(2, singleByteValue(2));

        List<Entry<Integer, Value>> result = scanEntries(1, 2);

        assertEquals(List.of(1, 2), result.stream().map(Entry::getKey).toList());
    }

    @Test
    void scanOnSingleFlushedSegmentReturnsInRangeEntries() throws IOException {
        putFourKeysCrossingThreshold();

        List<Entry<Integer, Value>> result = scanEntries(2, 3);

        assertEquals(List.of(2, 3), result.stream().map(Entry::getKey).toList());
    }

    @Test
    void scanMergesMemTableAndSegment() throws IOException {
        putFourKeysCrossingThreshold();
        lsmTree.put(100, singleByteValue(100));

        List<Entry<Integer, Value>> result = scanEntries(3, 100);

        assertEquals(List.of(3, 4, 100), result.stream().map(Entry::getKey).toList());
    }

    @Test
    void scanMemTableUpdateShadowsSegmentValue() throws IOException {
        putFourKeysCrossingThreshold();
        Value updated = Value.of("updated".getBytes(StandardCharsets.UTF_8));
        lsmTree.put(2, updated);

        List<Entry<Integer, Value>> result = scanEntries(1, 4);

        assertEquals(List.of(1, 2, 3, 4), result.stream().map(Entry::getKey).toList());
        assertEquals(updated, result.stream().filter(e -> e.getKey() == 2).findFirst().orElseThrow().getValue());
    }

    @Test
    void scanNewerSegmentShadowsOlderSegmentValue() throws IOException {
        lsmTree.put(1, singleByteValue(1));
        lsmTree.flush();

        Value updated = Value.of("updated".getBytes(StandardCharsets.UTF_8));
        lsmTree.put(1, updated);
        lsmTree.flush();

        List<Entry<Integer, Value>> result = scanEntries(0, 5);

        assertEquals(1, result.size());
        assertEquals(updated, result.getFirst().getValue());
    }

    @Test
    void scanOmitsTombstonedKeyEntirely() throws IOException {
        putFourKeysCrossingThreshold();
        lsmTree.put(3, Value.tombstone());

        List<Entry<Integer, Value>> result = scanEntries(1, 4);

        assertEquals(List.of(1, 2, 4), result.stream().map(Entry::getKey).toList());
    }

    @Test
    void scanTombstoneAcrossSegmentsOmitsKeyEntirely() throws IOException {
        lsmTree.put(1, singleByteValue(1));
        lsmTree.flush();

        lsmTree.put(1, Value.tombstone());
        lsmTree.flush();

        List<Entry<Integer, Value>> result = scanEntries(0, 5);

        assertEquals(List.of(), result);
    }

    @Test
    void scanOutsideAllPresentKeysReturnsEmpty() throws IOException {
        putFourKeysCrossingThreshold();

        List<Entry<Integer, Value>> result = scanEntries(100, 200);

        assertEquals(List.of(), result);
    }

    @Test
    void scanWithLowGreaterThanHighReturnsEmpty() throws IOException {
        putFourKeysCrossingThreshold();

        List<Entry<Integer, Value>> result = scanEntries(4, 1);

        assertEquals(List.of(), result);
    }

    @Test
    void scanCorrectAfterCompaction() throws IOException {
        LSMTree<Integer> tree = newTreeWithCompactionThreshold(dataDir.resolve("scan-post-compact"), 100);
        try {
            tree.put(1, singleByteValue(1));
            tree.put(2, singleByteValue(2));
            tree.flush();

            Value updated = Value.of("updated".getBytes(StandardCharsets.UTF_8));
            tree.put(2, updated);
            tree.put(3, singleByteValue(3));
            tree.flush();

            List<Entry<Integer, Value>> before = scanEntries(tree, 1, 3);

            tree.sharedCompactionLogic(tree.segments());

            List<Entry<Integer, Value>> after = scanEntries(tree, 1, 3);

            assertEquals(before.stream().map(Entry::getKey).toList(), after.stream().map(Entry::getKey).toList());
            assertEquals(before.stream().map(Entry::getValue).toList(), after.stream().map(Entry::getValue).toList());
        } finally {
            tree.close();
        }
    }

    @Test
    void sizeTieredCompactionOfNonContiguousSegmentsStillResolvesCorrectly() throws IOException, InterruptedException {
        Path dir = dataDir.resolve("size-tiered-noncontiguous");
        try (LSMTree<Integer> tree = new LSMTree<>(new IntegerKeyCodec(), dir, SAMPLE_EVERY, 1_000_000,
                new SizeTieredCompactStrategy<>(2, 0.5, 1.5), new FullLoadMergeStrategy<>(new IntegerKeyCodec()))) {

            tree.put(1, Value.of(new byte[]{1, 1, 1, 1}));
            tree.flush(); // S1: 1 entry, small -- key 1's original value

            tree.put(1, Value.of(new byte[]{2, 2, 2, 2})); // key 1's true latest value
            tree.put(2, Value.of(new byte[]{3, 3, 3, 3})); // key 2's older value
            for (int i = 1000; i < 1050; i++) tree.put(i, Value.of(new byte[200]));
            tree.flush(); // S2: many large entries -- sits between S1 and S3 in flush order but far outside their size band

            tree.put(2, Value.of(new byte[]{4, 4, 4, 4})); // key 2's true latest value
            tree.flush(); // S3: 1 entry, small (same size as S1) -- triggers auto-compaction of {S1, S3}, skipping S2

            tree.awaitCompaction();
            assertAll(
                    () -> assertEquals(2, tree.segmentCount(), "S2 stays untouched; S1 and S3 merge into one"),
                    () -> assertEquals(Value.of(new byte[]{2, 2, 2, 2}), tree.get(1),
                            "key 1's true latest value lives in the untouched S2, not the merged segment's stale copy from S1"),
                    () -> assertEquals(Value.of(new byte[]{4, 4, 4, 4}), tree.get(2),
                            "key 2's true latest value lives in the merged segment (from S3), not S2's stale older copy")
            );
        }
    }

    void createSegment(LSMTree<Integer> tree, int totalEntries, String valuePrefix) throws IOException {
        for(int i=0;i<totalEntries;i++){
            tree.put(i, Value.of((valuePrefix+i).getBytes(StandardCharsets.UTF_8)));
        }
        tree.flush();
    }

    @Test
    void streamingMergeStrategyHaveSameOutputAsLoadMergeStrategy() throws IOException {
        Path streamMergePath = dataDir.resolve("stream").toAbsolutePath();
        Path fullLoadMergePath = dataDir.resolve("fullLoad").toAbsolutePath();
        Files.createDirectory(streamMergePath);
        Files.createDirectory(fullLoadMergePath);
        LSMTree<Integer> streamMergeTree = new LSMTree<>(new IntegerKeyCodec(), streamMergePath, SAMPLE_EVERY, 500, new FullCompactStrategy<>(10), new StreamingMergeStrategy<>(new IntegerKeyCodec()));
        LSMTree<Integer> fullLoadMergeTree = new LSMTree<>(new IntegerKeyCodec(), fullLoadMergePath, SAMPLE_EVERY, 500, new FullCompactStrategy<>(10), new FullLoadMergeStrategy<>(new IntegerKeyCodec()));

        createSegment(streamMergeTree,15,"c");
        createSegment(streamMergeTree, 10, "b");
        createSegment(streamMergeTree, 5, "a");
        streamMergeTree.put(0, Value.tombstone());
        streamMergeTree.flush();

        createSegment(fullLoadMergeTree,15,"c");
        createSegment(fullLoadMergeTree, 10, "b");
        createSegment(fullLoadMergeTree, 5, "a");
        fullLoadMergeTree.put(0, Value.tombstone());
        fullLoadMergeTree.flush();

        streamMergeTree.sharedCompactionLogic(streamMergeTree.segments());
        fullLoadMergeTree.sharedCompactionLogic(fullLoadMergeTree.segments());

        assertAll(
                () -> assertEquals(1, streamMergeTree.segmentCount(), "sanity check: streaming compaction merged all 4 segments into one"),
                () -> assertEquals(1, fullLoadMergeTree.segmentCount(), "sanity check: full-load compaction merged all 4 segments into one"),
                () -> assertNull(streamMergeTree.get(0), "streaming strategy: tombStoned key should be gone entirely after compaction"),
                () -> assertNull(fullLoadMergeTree.get(0), "full-load strategy: tombStoned key should be gone entirely after compaction")
        );

        for(int i=1;i<15;i++){
            assertEquals(streamMergeTree.get(i), fullLoadMergeTree.get(i), "Both strategies should have same final state for key " + i);
        }
    }

    @Test
    void sharedCompactionLogicRespectsPassedSelectionNotLiveSegments() throws IOException {
        try (LSMTree<Integer> tree = newTreeWithCompactionThreshold(dataDir.resolve("dead-param"), Integer.MAX_VALUE)) {
            tree.put(1, singleByteValue(1));
            tree.flush();
            tree.put(2, singleByteValue(2));
            tree.flush();

            assertEquals(2, tree.segmentCount(), "sanity check: two segments exist before the call");

            tree.sharedCompactionLogic(List.of());

            assertEquals(3, tree.segmentCount(),
                    "an empty selection must be respected -- nothing removed, one (empty) merged segment added -- " +
                            "not silently re-derived from live segments");
        }
    }

    @Test
    void reopeningTreeWithQualifyingRecoveredSegmentsTriggersCompactionAutomatically() throws IOException, InterruptedException {
        Path dir = dataDir.resolve("recover-triggers-compaction");
        try (LSMTree<Integer> firstSession = new LSMTree<>(new IntegerKeyCodec(), dir, SAMPLE_EVERY, THRESHOLD,
                new FullCompactStrategy<>(1000), new FullLoadMergeStrategy<>(new IntegerKeyCodec()))) {
            for (int i = 1; i <= 8; i++) firstSession.put(i, singleByteValue(i));
            assertTrue(firstSession.segmentCount() >= 2, "sanity check: first session left multiple uncompacted segments on disk");
        }

        try (LSMTree<Integer> secondSession = new LSMTree<>(new IntegerKeyCodec(), dir, SAMPLE_EVERY, THRESHOLD,
                new FullCompactStrategy<>(2), new FullLoadMergeStrategy<>(new IntegerKeyCodec()))) {
            secondSession.awaitCompaction();

            assertEquals(1, secondSession.segmentCount(),
                    "recovering segments that already qualify for compaction should trigger it automatically, without any put()");
        }
    }

    @Test
    void reopeningTreeWithNoManifestFileRecoversMemTableCorrectlyFromWAl() throws IOException{
        Path dir = dataDir.resolve("wal-recover");
        try (LSMTree<Integer> firstSession = new LSMTree<>(new IntegerKeyCodec(), dir, SAMPLE_EVERY, THRESHOLD,
                new FullCompactStrategy<>(1000), new FullLoadMergeStrategy<>(new IntegerKeyCodec()))) {
            firstSession.put(1, singleByteValue(1));
            firstSession.put(2, singleByteValue(2));
        }

        assertFalse(Files.exists(dir.resolve("manifest.log", "sanity check: two small puts must stay under threshold and never trigger a flush")));

        try (LSMTree<Integer> secondSession = new LSMTree<>(new IntegerKeyCodec(), dir, SAMPLE_EVERY, THRESHOLD,
                new FullCompactStrategy<>(1000), new FullLoadMergeStrategy<>(new IntegerKeyCodec()))) {
            assertEquals(singleByteValue(1), secondSession.get(1), "Reopening tree should recover memTable from WAl correctly");
            assertEquals(singleByteValue(2), secondSession.get(2), "Reopening tree should recover memTable from WAl correctly");
        }
    }

    @Test
    void sizeTieredCompactionWithFullLoadStrategyCorrectlyHandlesTombstone() throws IOException, InterruptedException {
        Path dir = dataDir.resolve("size-tiered-noncontiguous");
        try (LSMTree<Integer> tree = new LSMTree<>(new IntegerKeyCodec(), dir, SAMPLE_EVERY, 1_000_000,
                new SizeTieredCompactStrategy<>(2, 0.5, 1.5), new FullLoadMergeStrategy<>(new IntegerKeyCodec()))) {

            tree.put(1, Value.of(new byte[]{1, 1, 1, 1}));
            for (int i = 1000; i < 1050; i++) tree.put(i, Value.of(new byte[200]));
            tree.flush(); // S1: 1 entry, large -- key 1's original value

            tree.put(1, Value.tombstone()); // key 1's true latest value
            tree.flush();

            tree.put(2, Value.of(new byte[]{4, 4, 4, 4})); // key 2's true latest value
            tree.flush(); // S3: 1 entry, small (same size as S2) -- triggers auto-compaction of {S2, S3}, skipping S1

            tree.awaitCompaction();
            assertAll(
                    () -> assertEquals(2, tree.segmentCount(), "S1 stays untouched; S3 and S2 merge into one"),
                    () -> assertEquals(Value.tombstone(), tree.get(1),
                            "key 1's true latest value tombstone is correctly retained"),
                    () -> assertEquals(Value.of(new byte[]{4, 4, 4, 4}), tree.get(2),
                            "key 2's true latest value lives in the merged segment (from S3)")
            );
        }
    }

    @Test
    void reopeningTreeWithEmptySegmentsThrowsNoError() throws IOException {
        Path dir = dataDir.resolve("empty-segment-recovery");
        try (LSMTree<Integer> firstSession = new LSMTree<>(new IntegerKeyCodec(), dir, SAMPLE_EVERY, THRESHOLD,
                new FullCompactStrategy<>(1000), new FullLoadMergeStrategy<>(new IntegerKeyCodec()))) {
            firstSession.put(1, singleByteValue(1));
            firstSession.flush();

            firstSession.put(1, Value.tombstone(3));
            firstSession.flush();
            assertEquals(2, firstSession.segmentCount(), "sanity check: first session left multiple uncompacted segments on disk");

            firstSession.sharedCompactionLogic(firstSession.segments());
            assertEquals(1, firstSession.segmentCount(), "sanity: both merged into one empty segment -- key 1's only value was tombstoned");
        }

        try (LSMTree<Integer> secondSession = new LSMTree<>(new IntegerKeyCodec(), dir, SAMPLE_EVERY, THRESHOLD,
                new FullCompactStrategy<>(2), new FullLoadMergeStrategy<>(new IntegerKeyCodec()))) {

            assertEquals(1, secondSession.segmentCount(),
                    "recovery correctly restores the empty segment without throwing an error");
            assertNull(secondSession.get(1), "Tombstone key should be dropped during compaction");
        }
    }

    @Test
    void staleCompactionResultIsNotPublishedAndCannotResurrectADeletedKey() throws IOException {
        Path dir = dataDir.resolve("test-compaction-overlap");
        KeyCodec<Integer> codec = new IntegerKeyCodec();
        try(LSMTree<Integer> tree = new LSMTree<>(codec, dir,SAMPLE_EVERY, THRESHOLD,
                new LeveledCompactionStrategy<>(), new StreamingMergeStrategy<>(codec))){
            tree.put(7, Value.of(1, new byte[0]));
            tree.flush();

            assertEquals(1, tree.segmentCount(),"Tree should have only 1 segment at this point");

            List<Segment<Integer>> segmentSnapshot = new ArrayList<>(tree.segments());

            tree.put(7, Value.tombstone(2));
            tree.flush();

            assertEquals(2, tree.segmentCount(), "Tree should have 2 segments at this point");

            try(EntrySource<Integer> cursor = tree.scan(7,7)){
                tree.sharedCompactionLogic(tree.segments());
                assertEquals(1, tree.segmentCount(),"Tree should have only 1 segment after compaction");
                assertNull(tree.get(7),
                        "precondition: the winner must drop the tombstone, not retain it");
                assertEquals(3, dataFiles(dir).length,
                        "two superseded files pinned by the cursor, plus the winner's output");
            }

            assertTrue(segmentSnapshot.getFirst().getDataFile().exists(),
                    "precondition: the held cursor must have blocked cleanUp");
            tree.sharedCompactionLogic(segmentSnapshot);
            assertEquals(1, tree.segmentCount(),"Tree should have same segments as before");
            assertNull(tree.get(7),
                    "the stale output must not be published; it still holds key 7's old value");
            assertEquals(1, dataFiles(dir).length, "Tree should have only 1 data file");
        }
    }

    @Test
    void abandonedCompactionLeavesTheVersionUntouchedAndDeletesItsOwnOutput() throws IOException {
        Path dir = dataDir.resolve("abandoned-install");
        KeyCodec<Integer> codec = new IntegerKeyCodec();
        try (LSMTree<Integer> tree = new LSMTree<>(codec, dir, SAMPLE_EVERY, THRESHOLD,
                new FullCompactStrategy<>(1000), new FullLoadMergeStrategy<>(codec))) {

            tree.put(1, singleByteValue(1));
            tree.flush();
            List<Segment<Integer>> staleSelection = new ArrayList<>(tree.segments());
            assertEquals(1, staleSelection.size(), "sanity: the stale selection is exactly one segment");

            tree.put(2, singleByteValue(2));
            tree.flush();
            assertEquals(2, tree.segmentCount(), "sanity: two uncompacted segments");

            try (EntrySource<Integer> heldCursor = tree.scan(1, 2)) {
                tree.sharedCompactionLogic(tree.segments());
                assertEquals(1, tree.segmentCount(), "the winner's install must succeed");
                assertEquals(3, dataFiles(dir).length,
                        "two superseded files pinned by the cursor, plus the winner's output");
            }

            assertTrue(staleSelection.getFirst().getDataFile().exists(),
                    "precondition: the held cursor must have blocked cleanUp");

            Segment<Integer> survivingSegment = tree.segments().getFirst();

            tree.sharedCompactionLogic(staleSelection);

            assertEquals(List.of(survivingSegment), tree.segments(),
                    "a failed install must leave the segment list untouched, nothing appended");
            assertEquals(1, dataFiles(dir).length, "the abandoned output must be deleted, not orphaned");
            assertEquals(singleByteValue(1), tree.get(1), "key 1 must still read correctly");
            assertEquals(singleByteValue(2), tree.get(2), "key 2 must still read correctly");
        }
    }

    @Test
    void concurrentCompactionsWithDisjointInputsBothInstall() throws Exception {
        Path dir = dataDir.resolve("disjoint-installs");
        KeyCodec<Integer> codec = new IntegerKeyCodec();
        try (LSMTree<Integer> tree = new LSMTree<>(codec, dir, SAMPLE_EVERY, THRESHOLD,
                new FullCompactStrategy<>(1000), new FullLoadMergeStrategy<>(codec))) {

            for (int key = 1; key <= 4; key++) {
                tree.put(key, singleByteValue(key));
                tree.flush();
            }
            assertEquals(4, tree.segmentCount(), "sanity: four single-key segments");

            List<Segment<Integer>> all = new ArrayList<>(tree.segments());
            List<Segment<Integer>> selectionA = List.of(all.get(0), all.get(1));
            List<Segment<Integer>> selectionB = List.of(all.get(2), all.get(3));

            AtomicReference<Throwable> failure = new AtomicReference<>();
            CyclicBarrier startTogether = new CyclicBarrier(2);

            Thread compactA = new Thread(() -> {
                try {
                    startTogether.await();
                    tree.sharedCompactionLogic(selectionA);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
            Thread compactB = new Thread(() -> {
                try {
                    startTogether.await();
                    tree.sharedCompactionLogic(selectionB);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });

            compactA.start();
            compactB.start();
            compactA.join();
            compactB.join();

            assertNull(failure.get(), () -> "a concurrent compaction threw: " + failure.get());
            assertEquals(2, tree.segmentCount(),
                    "disjoint inputs must both install; 3 means one was wrongly abandoned");
            for (int key = 1; key <= 4; key++) {
                assertEquals(singleByteValue(key), tree.get(key), "key " + key + " must survive both");
            }
        }
    }

    @Test
    void treeReopensWithNoOrphanManifestEntryAfterAnAbandonedCompaction() throws IOException {
        Path dir = dataDir.resolve("reopen-after-abandon");
        KeyCodec<Integer> codec = new IntegerKeyCodec();

        try (LSMTree<Integer> firstSession = new LSMTree<>(codec, dir, SAMPLE_EVERY, THRESHOLD,
                new FullCompactStrategy<>(1000), new FullLoadMergeStrategy<>(codec))) {

            firstSession.put(1, singleByteValue(1));
            firstSession.flush();
            List<Segment<Integer>> staleSelection = new ArrayList<>(firstSession.segments());

            firstSession.put(2, singleByteValue(2));
            firstSession.flush();

            try (EntrySource<Integer> heldCursor = firstSession.scan(1, 2)) {
                firstSession.sharedCompactionLogic(firstSession.segments());
            }

            assertTrue(staleSelection.getFirst().getDataFile().exists(),
                    "precondition: the held cursor must have blocked cleanUp");
            firstSession.sharedCompactionLogic(staleSelection);
            assertEquals(1, firstSession.segmentCount(), "sanity: the stale install was rejected");
        }

        List<String> manifestEntries = Manifest.readAll(dir.resolve(FileConstants.MANIFEST_FILE_NAME).toFile());
        assertTrue(manifestEntries.stream().noneMatch(entry -> entry.startsWith(FileConstants.WIP_FILE_PREFIX)),
                () -> "the abandoned compaction's WIP- entry must be gone: " + manifestEntries);

        try (LSMTree<Integer> secondSession = new LSMTree<>(codec, dir, SAMPLE_EVERY, THRESHOLD,
                new FullCompactStrategy<>(1000), new FullLoadMergeStrategy<>(codec))) {
            assertEquals(1, secondSession.segmentCount(), "reopen must recover only the winner's output");
            assertEquals(singleByteValue(1), secondSession.get(1), "key 1 must survive the reopen");
            assertEquals(singleByteValue(2), secondSession.get(2), "key 2 must survive the reopen");
        }
    }

    @Test
    void reopenRecoversTheValidPrefixOfACorruptWalAndStaysUsable() throws IOException {
        Path dir = dataDir.resolve("corrupt-wal-reopen");
        File walFile;

        // A memtable threshold nothing can reach, so no flush happens and all three records stay in
        // the WAL. Recovery therefore has to replay them rather than load segments -- which is what
        // makes this exercise the WAL path end to end instead of only the segment path.
        try (LSMTree<Integer> firstSession = new LSMTree<>(new IntegerKeyCodec(), dir, SAMPLE_EVERY,
                1_000_000, new FullCompactStrategy<>(1000), new FullLoadMergeStrategy<>(new IntegerKeyCodec()))) {
            firstSession.put(1, singleByteValue(1));
            firstSession.put(2, singleByteValue(2));
            firstSession.put(3, singleByteValue(3));
            assertEquals(0, firstSession.segmentCount(), "sanity: nothing flushed, so the WAL holds all three");
            walFile = firstSession.walFile();
        }

        // Each record is 20 (header) + 4 (key) + 14 (value) = 38 bytes, so record 3 spans [76,114).
        // Truncating to 90 leaves it 14 bytes, short of the 20-byte header -- the commonest crash
        // shape, a put that was still in flight and had not yet been acknowledged.
        try (RandomAccessFile raf = new RandomAccessFile(walFile, "rw")) {
            raf.setLength(90);
        }

        try (LSMTree<Integer> secondSession = new LSMTree<>(new IntegerKeyCodec(), dir, SAMPLE_EVERY,
                1_000_000, new FullCompactStrategy<>(1000), new FullLoadMergeStrategy<>(new IntegerKeyCodec()))) {
            assertAll(
                    () -> assertEquals(singleByteValue(1), secondSession.get(1),
                            "records durable before the crash must be recovered"),
                    () -> assertEquals(singleByteValue(2), secondSession.get(2),
                            "records durable before the crash must be recovered"),
                    () -> assertNull(secondSession.get(3),
                            "the record cut mid-write was never acknowledged and must not reappear")
            );

            // The point of an end-to-end test: the tree must be genuinely open and writable, not
            // merely have survived the constructor.
            secondSession.put(4, singleByteValue(4));
            assertEquals(singleByteValue(4), secondSession.get(4),
                    "the tree must accept writes after recovering from a truncated WAL");
        }
    }

    /** A tree whose memtable threshold is unreachable, so nothing flushes and the WAL holds everything. */
    private LSMTree<Integer> newTreeThatNeverFlushes(Path dir) throws IOException {
        return new LSMTree<>(new IntegerKeyCodec(), dir, SAMPLE_EVERY, 1_000_000,
                new FullCompactStrategy<>(1000), new FullLoadMergeStrategy<>(new IntegerKeyCodec()));
    }

    @Test
    void writesAfterRecoveringACorruptWalSurviveTheNextCrash() throws IOException {
        Path dir = dataDir.resolve("corrupt-wal-rewrite");
        File walFile;

        try (LSMTree<Integer> firstSession = newTreeThatNeverFlushes(dir)) {
            firstSession.put(1, singleByteValue(1));
            firstSession.put(2, singleByteValue(2));
            firstSession.put(3, singleByteValue(3));
            walFile = firstSession.walFile();
        }

        // Records are 38 bytes each, so record 3 spans [76,114). Truncating to 90 leaves it short of
        // its 20-byte header.
        try (RandomAccessFile raf = new RandomAccessFile(walFile, "rw")) {
            raf.setLength(90);
        }

        try (LSMTree<Integer> secondSession = newTreeThatNeverFlushes(dir)) {
            assertEquals(76, walFile.length(),
                    "recovery must truncate the WAL to the last good boundary before accepting writes");
            secondSession.put(4, singleByteValue(4));
        }

        try (LSMTree<Integer> thirdSession = newTreeThatNeverFlushes(dir)) {
            assertEquals(singleByteValue(4), thirdSession.get(4),
                    "key 4 was acknowledged after recovery, so it must survive a later crash; if the "
                            + "corrupt tail had been left in place the next replay would stop short of it");
            assertEquals(singleByteValue(1), thirdSession.get(1), "the original prefix must still be there");
            assertNull(thirdSession.get(3), "the record cut mid-write must stay gone");
        }
    }
}
