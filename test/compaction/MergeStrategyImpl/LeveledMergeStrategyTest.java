package compaction.MergeStrategyImpl;

import RBT.Entry;
import SSTable.SSTable;
import compaction.MergeStrategy;
import constants.FileConstants;
import core.Segment;
import core.SegmentFiles;
import core.Value;
import core.key.KeyCodec;
import core.key.KeyCodecImpl.IntegerKeyCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class LeveledMergeStrategyTest {

    KeyCodec<Integer> codec;
    Path directory;
    Supplier<SegmentFiles> fileFactory;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        this.codec = new IntegerKeyCodec();
        directory = dir;
        fileFactory = () -> {
            String uuid = UUID.randomUUID().toString();
            String wipDataFileName = FileConstants.WIP_FILE_PREFIX + FileConstants.DATA_FILE_PREFIX + uuid;
            String indexFileName = FileConstants.INDEX_FILE_PREFIX + uuid;
            String bloomFilterFileName = FileConstants.BLOOM_FILTER_FILE_PREFIX + uuid;

            return new SegmentFiles(
                    this.directory.resolve(wipDataFileName).toFile(),
                    this.directory.resolve(indexFileName).toFile(),
                    this.directory.resolve(bloomFilterFileName).toFile()
            );
        };
    }

    Segment<Integer> createSegment(List<Entry<Integer, Value>> entries, int nextLevel) throws IOException {
        SSTable<Integer> table = new SSTable<>(codec);
        SegmentFiles files = this.fileFactory.get();
        File dataFile = files.dataFile();
        File indexFile = files.indexFile();
        File bloomFilterFile = files.bloomFilterFile();

        Segment<Integer> segment = table.write(entries, dataFile, indexFile, bloomFilterFile, 5, nextLevel);
        segment.setMinKey(entries.stream().map(Entry::getKey).map(codec::encodeKey).min(codec::compareEncoded).orElse(null));
        segment.setMaxKey(entries.stream().map(Entry::getKey).map(codec::encodeKey).max(codec::compareEncoded).orElse(null));

        return segment;
    }

    List<Entry<Integer, Value>> extractEntries(Segment<Integer> segment) throws IOException {
        SSTable<Integer> table = new SSTable<Integer>(codec);
        return table.readAll(segment.getDataFile());
    }

    @Test
    void tombStoneKeyEntryIsRetainedWhenSameKeyEntryIsPresentInHigherLevels() throws IOException {
        Segment<Integer> newSegment = createSegment(List.of(new Entry<Integer, Value>(1, Value.tombstone(3))), 0);
        Segment<Integer> midSegment = createSegment(List.of(new Entry<Integer, Value>(2, Value.of(2, new byte[1]))), 1);
        Segment<Integer> oldSegment = createSegment(List.of(new Entry<Integer, Value>(1, Value.of(1, new byte[1]))), 2);

        MergeStrategy<Integer> strategy = new LeveledMergeStrategy<>(codec, 50);
        List<Segment<Integer>> mergedSegments = strategy.merge(List.of(newSegment, midSegment), List.of(newSegment, midSegment, oldSegment), this.fileFactory, 5);

        List<Entry<Integer,Value>> entries = new ArrayList<>();
        for(Segment<Integer> segment : mergedSegments){
            entries.addAll(extractEntries(segment));
        }

        Entry<Integer, Value> entry = entries.stream().filter(kEntry -> kEntry.getKey().equals(1)).findFirst().orElse(null);
        assertNotNull(entry, "Segments should contain the entry");
        assertTrue(entry.getValue().isTombstone(), "Entry's value should be a tombStone");
    }

    @Test
    void tombStoneKeyEntryIsDroppedWhenSameKeyEntryIsNotPresentInHigherLevels() throws IOException {
        Segment<Integer> newSegment = createSegment(List.of(new Entry<Integer, Value>(1, Value.tombstone(3))), 0);
        Segment<Integer> midSegment = createSegment(List.of(new Entry<Integer, Value>(2, Value.of(2, new byte[1]))), 1);
        Segment<Integer> oldSegment = createSegment(List.of(new Entry<Integer, Value>(3, Value.of(1, new byte[1]))), 2);

        MergeStrategy<Integer> strategy = new LeveledMergeStrategy<>(codec, 50);
        List<Segment<Integer>> mergedSegments = strategy.merge(List.of(newSegment, midSegment), List.of(newSegment, midSegment, oldSegment), this.fileFactory, 5);

        List<Entry<Integer,Value>> entries = new ArrayList<>();
        for(Segment<Integer> segment : mergedSegments){
            entries.addAll(extractEntries(segment));
        }
        Entry<Integer, Value> entry = entries.stream().filter(kEntry -> kEntry.getKey().equals(1)).findFirst().orElse(null);
        assertNull(entry, "Merged operation should drop the tombstone entry");
    }

    @Test
    void tombStoneKeyEntryIsDroppedWhenThereIsNoDeeperSegments() throws IOException {
        Segment<Integer> newSegment = createSegment(List.of(new Entry<Integer, Value>(1, Value.tombstone(3))), 0);
        Segment<Integer> midSegment = createSegment(List.of(new Entry<Integer, Value>(2, Value.of(2, new byte[1]))), 0);

        MergeStrategy<Integer> strategy = new LeveledMergeStrategy<>(codec, 50);
        List<Segment<Integer>> mergedSegments = strategy.merge(List.of(newSegment, midSegment), List.of(newSegment, midSegment), this.fileFactory, 5);

        List<Entry<Integer,Value>> entries = new ArrayList<>();
        for(Segment<Integer> segment : mergedSegments){
            entries.addAll(extractEntries(segment));
        }
        Entry<Integer, Value> entry = entries.stream().filter(kEntry -> kEntry.getKey().equals(1)).findFirst().orElse(null);
        assertNull(entry, "Merged operation should drop the tombstone entry");
    }

    @Test
    void tombStoneKeyEntryIsDroppedWhenItIsOldestSameKeyEntry() throws IOException {
        Segment<Integer> newSegment = createSegment(List.of(new Entry<Integer, Value>(1, Value.of(2, new byte[1]))), 0);
        Segment<Integer> oldSegment = createSegment(List.of(new Entry<Integer, Value>(1, Value.tombstone(1))), 1);

        MergeStrategy<Integer> strategy = new LeveledMergeStrategy<>(codec, 50);
        List<Segment<Integer>> mergedSegments = strategy.merge(List.of(newSegment, oldSegment), List.of(newSegment, oldSegment), this.fileFactory, 5);

        List<Entry<Integer,Value>> entries = new ArrayList<>();
        for(Segment<Integer> segment : mergedSegments){
            entries.addAll(extractEntries(segment));
        }
        Entry<Integer, Value> entry = entries.stream().filter(kEntry -> kEntry.getKey().equals(1)).findFirst().orElse(null);
        assertNotNull(entry, "Merged operation should not drop the recent non tombstone entry");
        assertFalse(entry.getValue().isTombstone(), "Retained entry shouldn't be a tombStone");
    }

    @Test
    void tombStoneIsDroppedWhenTheOlderValueLivesInAnInputSegment() throws IOException {
        Segment<Integer> tombStoneSegment = createSegment(List.of(new Entry<Integer, Value>(1, Value.tombstone(3))), 0);
        Segment<Integer> inputAtNextLevel = createSegment(List.of(new Entry<Integer, Value>(1, Value.of(2, new byte[1]))), 1);

        MergeStrategy<Integer> strategy = new LeveledMergeStrategy<>(codec, 50);
        List<Segment<Integer>> mergedSegments = strategy.merge(
                List.of(tombStoneSegment, inputAtNextLevel),
                List.of(tombStoneSegment, inputAtNextLevel),
                this.fileFactory, 5);

        List<Entry<Integer, Value>> entries = new ArrayList<>();
        for (Segment<Integer> segment : mergedSegments) {
            entries.addAll(extractEntries(segment));
        }

        Entry<Integer, Value> entry = entries.stream().filter(kEntry -> kEntry.getKey().equals(1)).findFirst().orElse(null);
        assertNull(entry, "Input segment sitting at the output level must not count as a candidate, both the tombstone and the value it shadows must be dropped");
    }
}
