package compaction.CompactStrategyImpl;

import bloomFilter.BloomFilter;
import core.Segment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class FullCompactStrategyTest {
    private Path tempDir;

    @BeforeEach
    void setup(@TempDir Path tempDir) {
        this.tempDir = tempDir;
    }

    private Segment<Integer> segment() throws IOException {
        Path f = tempDir.resolve("seg-" + UUID.randomUUID());
        Files.write(f, new byte[10]);
        return new Segment<>(f.toFile(), f.toFile(), f.toFile(), List.of(), new BloomFilter(1, 0.02), 1, 0);
    }

    @Test
    void shouldCompactReturnsFalseForFewerThanTwoSegmentsRegardlessOfThreshold() throws IOException {
        FullCompactStrategy<Integer> strategy = new FullCompactStrategy<>(1);

        assertAll(
                () -> assertFalse(strategy.shouldCompact(List.of()), "an empty segment list should never trigger compaction"),
                () -> assertFalse(strategy.shouldCompact(List.of(segment())), "a single segment has nothing to merge with, even at threshold=1")
        );
    }

    @Test
    void shouldCompactReturnsTrueOnceSegmentCountReachesThreshold() throws IOException {
        FullCompactStrategy<Integer> strategy = new FullCompactStrategy<>(3);
        List<Segment<Integer>> twoSegments = List.of(segment(), segment());
        List<Segment<Integer>> threeSegments = List.of(segment(), segment(), segment());

        assertAll(
                () -> assertFalse(strategy.shouldCompact(twoSegments), "below the configured threshold should not trigger"),
                () -> assertTrue(strategy.shouldCompact(threeSegments), "reaching the configured threshold should trigger")
        );
    }

    @Test
    void selectReturnsEverySegment() throws IOException {
        FullCompactStrategy<Integer> strategy = new FullCompactStrategy<>(2);
        List<Segment<Integer>> segments = List.of(segment(), segment(), segment());

        List<Segment<Integer>> selected = strategy.select(segments);

        assertEquals(new java.util.HashSet<>(segments), new java.util.HashSet<>(selected),
                "FullCompactStrategy always selects every segment for a full merge");
    }
}
