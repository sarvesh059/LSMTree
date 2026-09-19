package compactation.CompactStrategyImpl;

import bloomFilter.BloomFilter;
import core.Segment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class SizeTieredCompactStrategyTest {
    private Path tempDir;

    @BeforeEach
    void setup(@TempDir Path tempDir) {
        this.tempDir = tempDir;
    }

    private Segment<Integer> segmentOfSize(int size) throws IOException {
        Path f = tempDir.resolve("seg-" + UUID.randomUUID());
        Files.write(f, new byte[size]);
        return new Segment<>(f.toFile(), List.of(), new BloomFilter(1, 0.02), 1, 0);
    }

    @Test
    void shouldCompactReturnsFalseWhenNoBucketReachesThreshold() throws IOException {
        SizeTieredCompactStrategy<Integer> strategy = new SizeTieredCompactStrategy<>(2, 0.5, 1.5);
        List<Segment<Integer>> segments = List.of(
                segmentOfSize(10), segmentOfSize(100), segmentOfSize(1000), segmentOfSize(10000));

        assertFalse(strategy.shouldCompact(segments));
    }

    @Test
    void shouldCompactReturnsTrueWhenABucketReachesThreshold() throws IOException {
        SizeTieredCompactStrategy<Integer> strategy = new SizeTieredCompactStrategy<>(4, 0.5, 1.5);
        List<Segment<Integer>> segments = List.of(
                segmentOfSize(100), segmentOfSize(105), segmentOfSize(98), segmentOfSize(102));

        assertTrue(strategy.shouldCompact(segments));
    }

    @Test
    void selectReturnsOnlyTriggeringBucketNotAllSegments() throws IOException {
        SizeTieredCompactStrategy<Integer> strategy = new SizeTieredCompactStrategy<>(4, 0.5, 1.5);
        Segment<Integer> outlier = segmentOfSize(100000);
        List<Segment<Integer>> segments = List.of(
                segmentOfSize(100), segmentOfSize(105), segmentOfSize(98), segmentOfSize(102), outlier);

        List<Segment<Integer>> selected = strategy.select(segments);

        assertAll(
                () -> assertEquals(4, selected.size()),
                () -> assertFalse(selected.contains(outlier))
        );
    }

    @Test
    void selectPicksSmallestQualifyingBucketWhenMultipleQualify() throws IOException {
        SizeTieredCompactStrategy<Integer> strategy = new SizeTieredCompactStrategy<>(4, 0.5, 1.5);
        List<Segment<Integer>> smallTier = List.of(
                segmentOfSize(100), segmentOfSize(105), segmentOfSize(98), segmentOfSize(102));
        List<Segment<Integer>> mediumTier = List.of(
                segmentOfSize(5000), segmentOfSize(5100), segmentOfSize(4950), segmentOfSize(5050));
        List<Segment<Integer>> segments = new ArrayList<>();
        segments.addAll(smallTier);
        segments.addAll(mediumTier);

        List<Segment<Integer>> selected = strategy.select(segments);

        assertEquals(new HashSet<>(smallTier), new HashSet<>(selected));
    }

    @Test
    void segmentsOutsideRatioBandFormSeparateBuckets() throws IOException {
        SizeTieredCompactStrategy<Integer> strategy = new SizeTieredCompactStrategy<>(2, 0.5, 1.5);
        Segment<Integer> outlier = segmentOfSize(1000);
        List<Segment<Integer>> segments = List.of(
                segmentOfSize(100), segmentOfSize(100), segmentOfSize(100), segmentOfSize(100), outlier);

        List<Segment<Integer>> selected = strategy.select(segments);

        assertAll(
                () -> assertEquals(4, selected.size()),
                () -> assertFalse(selected.contains(outlier))
        );
    }

    @Test
    void emptySegmentListNeverTriggersCompaction() {
        SizeTieredCompactStrategy<Integer> strategy = new SizeTieredCompactStrategy<>(2, 0.5, 1.5);

        assertAll(
                () -> assertFalse(strategy.shouldCompact(List.of())),
                () -> assertNull(strategy.select(List.of()))
        );
    }

    @Test
    void singleSegmentNeverTriggersCompaction() throws IOException {
        SizeTieredCompactStrategy<Integer> strategy = new SizeTieredCompactStrategy<>(2, 0.5, 1.5);
        List<Segment<Integer>> segments = List.of(segmentOfSize(100));

        assertAll(
                () -> assertFalse(strategy.shouldCompact(segments)),
                () -> assertNull(strategy.select(segments))
        );
    }
}
