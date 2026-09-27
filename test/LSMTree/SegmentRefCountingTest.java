package LSMTree;

import compaction.CompactStrategyImpl.FullCompactStrategy;
import compaction.MergeStrategyImpl.FullLoadMergeStrategy;
import core.Segment;
import core.Value;
import core.key.KeyCodecImpl.IntegerKeyCodec;
import cursor.EntrySource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SegmentRefCountingTest {
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

    @Test
    void getReturnsRefCountToZeroAfterAHit() throws IOException {
        this.lsmTree.put(1, Value.of("test".getBytes(StandardCharsets.UTF_8)));
        this.lsmTree.flush();

        Value value = this.lsmTree.get(1);

        for(Segment<Integer> segment: this.lsmTree.segments()){
            assertEquals(0, segment.getRefCount(), "Segment ref count should be zero");
        }
    }

    @Test
    void getReturnsRefCountToZeroAfterAMiss() throws IOException {
        Value value = this.lsmTree.get(1);

        for(Segment<Integer> segment: this.lsmTree.segments()){
            assertEquals(0, segment.getRefCount(), "Segment ref count should be zero");
        }
    }

    @Test
    void getReturnsRefCountToZeroOnTheMemTableHitEarlyReturnPath() throws IOException {
        this.lsmTree.put(1, Value.of("InitialKey".getBytes(StandardCharsets.UTF_8)));
        this.lsmTree.flush();

        this.lsmTree.put(2, Value.of("AfterFlushKey".getBytes(StandardCharsets.UTF_8)));
        Value value = this.lsmTree.get(2);

        for(Segment<Integer> segment: this.lsmTree.segments()){
            assertEquals(0, segment.getRefCount(), "Segment ref count should be zero");
        }
    }

    @Test
    void scanHoldsThePinForTheCursorsLifetimeNotJustDuringTheCall() throws IOException {
        this.lsmTree.put(1, Value.of("test".getBytes(StandardCharsets.UTF_8)));
        this.lsmTree.flush();

        try (EntrySource<Integer> cursor = this.lsmTree.scan(0, 10)) {
            for (Segment<Integer> segment : this.lsmTree.segments()) {
                assertEquals(1, segment.getRefCount(), "Segment should stay pinned while the scan cursor is still open");
            }
        }

        for (Segment<Integer> segment : this.lsmTree.segments()) {
            assertEquals(0, segment.getRefCount(), "Segment ref count should return to zero once the cursor is closed");
        }
    }

    @Test
    void scanUnpinsEvenWhenClosedBeforeBeingFullyDrained() throws IOException {
        this.lsmTree.put(1, Value.of("a".getBytes(StandardCharsets.UTF_8)));
        this.lsmTree.put(2, Value.of("b".getBytes(StandardCharsets.UTF_8)));
        this.lsmTree.put(3, Value.of("c".getBytes(StandardCharsets.UTF_8)));
        this.lsmTree.flush();

        try (EntrySource<Integer> cursor = this.lsmTree.scan(0, 10)) {
            assertTrue(cursor.hasNext(), "sanity check: the scan should have at least one entry");
            cursor.next();
        }

        for (Segment<Integer> segment : this.lsmTree.segments()) {
            assertEquals(0, segment.getRefCount(), "unpin must not depend on the cursor being fully drained");
        }
    }

    @Test
    void scanOnlyPinsSegmentsThatOverlapTheRequestedRange() throws IOException {
        this.lsmTree.put(1, Value.of("low".getBytes(StandardCharsets.UTF_8)));
        this.lsmTree.flush();
        this.lsmTree.put(100, Value.of("high".getBytes(StandardCharsets.UTF_8)));
        this.lsmTree.flush();

        assertEquals(2, this.lsmTree.segments().size(), "sanity check: two distinct, uncompacted segments should exist");
        // flush() appends in order, and nothing has compacted these away yet, so segment 0 holds
        // key 1 (in range) and segment 1 holds key 100 (out of range).
        Segment<Integer> inRangeSegment = this.lsmTree.segments().get(0);
        Segment<Integer> outOfRangeSegment = this.lsmTree.segments().get(1);

        EntrySource<Integer> cursor = this.lsmTree.scan(0, 10);
        try {
            assertEquals(1, inRangeSegment.getRefCount(),
                    "the segment overlapping [0,10] should be pinned for the cursor's lifetime");
            assertEquals(0, outOfRangeSegment.getRefCount(),
                    "a segment entirely outside the requested range should never be pinned at all");
        } finally {
            cursor.close();
        }

        for (Segment<Integer> segment : this.lsmTree.segments()) {
            assertEquals(0, segment.getRefCount(), "Segment ref count should return to zero once the cursor is closed");
        }
    }

    @Test
    void scanClosingNormallyRemovesItsEntryFromTheOpenPinTrackingMap() throws IOException {
        this.lsmTree.put(1, Value.of("a".getBytes(StandardCharsets.UTF_8)));
        this.lsmTree.flush();

        assertEquals(0, this.lsmTree.openVersionPinCount(), "sanity check: nothing open yet");

        try (EntrySource<Integer> cursor = this.lsmTree.scan(0, 10)) {
            assertEquals(1, this.lsmTree.openVersionPinCount(), "should be tracked while the cursor is open");
        }

        assertEquals(0, this.lsmTree.openVersionPinCount(),
                "closing normally must remove the tracking entry too, not just release the segment pin");
    }

    @Test
    void getNeverAddsAnEntryToTheOpenPinTrackingMap() throws IOException {
        this.lsmTree.put(1, Value.of("a".getBytes(StandardCharsets.UTF_8)));
        this.lsmTree.flush();

        this.lsmTree.get(1);

        assertEquals(0, this.lsmTree.openVersionPinCount(),
                "get()'s pin is bounded by the call itself -- nothing should ever be left tracked");
    }

    @Test
    void oldestOpenPinAgeIsNegativeOneWhenNothingIsPinned() throws IOException {
        this.lsmTree.put(1, Value.of("a".getBytes(StandardCharsets.UTF_8)));
        this.lsmTree.flush();

        assertEquals(-1, this.lsmTree.oldestOpenPinAgeInMillis(), "no open pins means no age to report");
    }

    @Test
    void oldestOpenPinAgeReflectsAnActuallyOpenScan() throws IOException {
        this.lsmTree.put(1, Value.of("a".getBytes(StandardCharsets.UTF_8)));
        this.lsmTree.flush();

        try (EntrySource<Integer> cursor = this.lsmTree.scan(0, 10)) {
            long age = this.lsmTree.oldestOpenPinAgeInMillis();
            assertTrue(age >= 0, "should report a real, non-negative age while a scan is open");
        }

        assertEquals(-1, this.lsmTree.oldestOpenPinAgeInMillis(), "back to -1 once the scan is closed");
    }
}
