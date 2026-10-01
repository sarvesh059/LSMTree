package compaction.TombStoneRetentionPolicyImpl;

import RBT.Entry;
import SSTable.SSTable;
import bloomFilter.BloomFilter;
import core.Segment;
import core.Value;
import core.key.KeyCodec;
import core.key.KeyCodecImpl.IntegerKeyCodec;
import cursor.TombStoneRetentionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CoverageBasedTombStoneRetentionPolicyTest {
    List<Segment<Integer>> segments;
    KeyCodec<Integer> codec;
    TombStoneRetentionPolicy<Integer> tombStoneRetentionPolicy;
    Path directory;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        segments = new ArrayList<>();
        this.codec = new IntegerKeyCodec();
        directory = dir;
        Segment<Integer> segment = createSegment(List.of(new Entry<Integer, Value>(1, Value.of(new byte[]{1})), new Entry<Integer,Value>(10, Value.of(new byte[]{2}))));
        segments.add(segment);
        tombStoneRetentionPolicy = new CoverageBasedTombStoneRetentionPolicy<>(segments, codec);
    }

    Segment<Integer> createSegment(List<Entry<Integer,Value>> entries) throws IOException {
        SSTable<Integer> table = new SSTable<>(codec);
        String uuid = UUID.randomUUID().toString();
        File dataFile = directory.resolve("dataFile-".concat(uuid)).toFile();
        File indexFile = directory.resolve("indexFile-".concat(uuid)).toFile();
        File bloomFilterFile = directory.resolve("bloomFilterFile-".concat(uuid)).toFile();

        Segment<Integer> segment = table.write(entries, dataFile, indexFile, bloomFilterFile, 5, 0);
        segment.setMinKey(entries.stream().map(Entry::getKey).map(codec::encodeKey).min(codec::compareEncoded).orElse(null));
        segment.setMaxKey(entries.stream().map(Entry::getKey).map(codec::encodeKey).max(codec::compareEncoded).orElse(null));

        return segment;
    }

    @Test
    void shouldRetainReturnsFalseWhenKeyIsAbsentInBloomFilter(){
        BloomFilter filter = segments.getFirst().getBloomFilter();
        assertFalse(filter.mightContain(codec.encodeKey(5)), "Bloom filter shouldn't contain the key that was never added to it");
        assertFalse(tombStoneRetentionPolicy.shouldRetain(new Entry<>(5, Value.of(new byte[1]))), "Coverage Based TombStone Retention Policy should return false if file is not present in bloom filter");
    }

    @Test
    void shouldRetainReturnsTrueWhenKeyIsPresentInBloomFilter(){
        BloomFilter filter = segments.getFirst().getBloomFilter();
        assertTrue(filter.mightContain(codec.encodeKey(1)), "Bloom filter should contain the key that was added to it");
        assertTrue(tombStoneRetentionPolicy.shouldRetain(new Entry<>(1, Value.of(new byte[1]))), "Coverage Based TombStone Retention Policy should return true if file might be present in bloom filter");
    }

    @Test
    void shouldRetainReturnsFalseWhenSegmentsListIsEmpty(){
        TombStoneRetentionPolicy<Integer> policy = new CoverageBasedTombStoneRetentionPolicy<>(new ArrayList<>(), codec);
        assertFalse(policy.shouldRetain(new Entry<>(1, Value.of(new byte[1]))), "Coverage Based TombStone Retention Policy should return false if it has no segments to check");
    }

    @Test
    void shouldRetainReturnsTrueWhenAnySegmentMightContainKey() throws IOException {
        Segment<Integer> segment1 = createSegment(List.of(new Entry<Integer, Value>(1, Value.of(new byte[]{1})), new Entry<Integer,Value>(10, Value.of(new byte[]{2}))));
        Segment<Integer> segment2 = createSegment(List.of(new Entry<Integer, Value>(100, Value.of(new byte[]{1})), new Entry<Integer,Value>(200, Value.of(new byte[]{2}))));

        tombStoneRetentionPolicy = new CoverageBasedTombStoneRetentionPolicy<>(List.of(segment1, segment2), codec);

        assertTrue(tombStoneRetentionPolicy.shouldRetain(new Entry<>(1, Value.of(new byte[1]))), "Coverage Based TombStone Retention Policy should return true if any segment might contain the key");
        assertTrue(tombStoneRetentionPolicy.shouldRetain(new Entry<>(100, Value.of(new byte[1]))), "Coverage Based TombStone Retention Policy should return true if any segment might contain the key");
    }
}
