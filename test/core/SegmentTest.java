package core;

import bloomFilter.BloomFilter;
import core.key.KeyCodec;
import core.key.KeyCodecImpl.IntegerKeyCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SegmentTest {
    Segment<Integer> segment;
    KeyCodec<Integer> codec;

    @BeforeEach
    void setUp(@TempDir Path tempDir){
        codec = new IntegerKeyCodec();
        File dataFile = tempDir.resolve("dataFile-1234").toFile();
        File indexFile = tempDir.resolve("indexFile-1234").toFile();
        File bloomFilterFile = tempDir.resolve("bloomFilter-1234").toFile();

        BloomFilter bloomFilter = new BloomFilter(10, 0.02);
        bloomFilter.add(codec.encodeKey(10));
        bloomFilter.add(codec.encodeKey(50));
        bloomFilter.add(codec.encodeKey(100));

        segment = new Segment<>(dataFile, indexFile, bloomFilterFile, new ArrayList<IndexEntry>(), bloomFilter, 0, -1);
        segment.setMinKey(codec.encodeKey(10));
        segment.setMaxKey(codec.encodeKey(100));
    }

    @Test
    void keyDefinitelyNotInSegmentReturnsTrueWhenKeyIsLesserThanMinKey(){
        assertTrue(segment.keyDefinitelyNotInSegment(codec.encodeKey(1), codec), "keyDefinitelyNotInSegment should return true for key lesser than min Key");
    }

    @Test
    void keyDefinitelyNotInSegmentReturnsTrueWhenKeyIsGreaterThanMaxKey(){
        assertTrue(segment.keyDefinitelyNotInSegment(codec.encodeKey(200), codec), "keyDefinitelyNotInSegment should return true for key greater than max Key");
    }

    @Test
    void keyDefinitelyNotInSegmentReturnsTrueForInRangeKeyAbsentInBloomFilter(){
        assertTrue(segment.keyDefinitelyNotInSegment(codec.encodeKey(20), codec), "keyDefinitelyNotInSegment should return true for in range key that is absent in bloom filter");
    }

    @Test
    void keyDefinitelyNotInSegmentReturnsFalseForInRangeKeyPresentInBloomFilter(){
        assertFalse(segment.keyDefinitelyNotInSegment(codec.encodeKey(50), codec), "keyDefinitelyNotInSegment should return false for in range key that is present in bloom filter");
    }

    @Test
    void keyDefinitelyNotInSegmentReturnsFalseWhenKeyEqualsMinKey(){
        assertFalse(segment.keyDefinitelyNotInSegment(codec.encodeKey(10), codec), "keyDefinitelyNotInSegment should return false for key equal to min key");
    }

    @Test
    void keyDefinitelyNotInSegmentReturnsFalseWhenKeyEqualsMaxKey(){
        assertFalse(segment.keyDefinitelyNotInSegment(codec.encodeKey(100), codec), "keyDefinitelyNotInSegment should return false for key equal to max key");
    }

    @Test
    void keyDefinitelyNotInSegmentReturnsTrueWhenKeyIsNull(){
        assertTrue(segment.keyDefinitelyNotInSegment(null, codec), "keyDefinitelyNotInSegment should return true when provided key is null");
    }

    @Test
    void keyDefinitelyNotInSegmentReturnsTrueForEmptySegment(@TempDir Path temp){
        File dataFile = temp.resolve("dataFile-12345").toFile();
        File indexFile = temp.resolve("indexFile-12345").toFile();
        File bloomFilterFile = temp.resolve("bloomFilter-12345").toFile();

        BloomFilter bloomFilter = new BloomFilter(10, 0.02);

        Segment<Integer> newSegment = new Segment<>(dataFile, indexFile, bloomFilterFile, new ArrayList<IndexEntry>(), bloomFilter, 0, -1);
        assertTrue(newSegment.keyDefinitelyNotInSegment(codec.encodeKey(100), codec), "keyDefinitelyNotInSegment should return true when provided key is null");
    }
}
