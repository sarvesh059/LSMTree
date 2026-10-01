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
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public class StreamingMergeStrategyTest {
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

    Segment<Integer> createSegment(List<Entry<Integer, Value>> entries) throws IOException {
        SSTable<Integer> table = new SSTable<>(codec);
        SegmentFiles files = this.fileFactory.get();
        File dataFile = files.dataFile();
        File indexFile = files.indexFile();
        File bloomFilterFile = files.bloomFilterFile();

        Segment<Integer> segment = table.write(entries, dataFile, indexFile, bloomFilterFile, 5, 0);
        segment.setMinKey(entries.stream().map(Entry::getKey).map(codec::encodeKey).min(codec::compareEncoded).orElse(null));
        segment.setMaxKey(entries.stream().map(Entry::getKey).map(codec::encodeKey).max(codec::compareEncoded).orElse(null));

        return segment;
    }

    List<Entry<Integer, Value>> extractEntries(Segment<Integer> segment) throws IOException {
        SSTable<Integer> table = new SSTable<Integer>(codec);
        return table.readAll(segment.getDataFile());
    }

    @Test
    void cursorsAreClosedWhenAnotherSegmentFailsMidMerge(@TempDir Path dir) throws IOException {
        SSTable<Integer> table = new SSTable<>(new IntegerKeyCodec());

        var dataGood = dir.resolve("good.data").toFile();
        var idxGood = dir.resolve("good.idx").toFile();
        var bloomGood = dir.resolve("good.filter").toFile();
        Segment<Integer> goodSegment = table.write(List.of(new Entry<Integer,Value>(1, Value.of(new byte[]{1})), new Entry<Integer,Value>(2, Value.of(new byte[]{2}))), dataGood, idxGood, bloomGood, 5, 0);

        var dataBad = dir.resolve("bad.data").toFile();
        var idxBad = dir.resolve("bad.idx").toFile();
        var bloomBad = dir.resolve("bad.filter").toFile();
        Segment<Integer> badSegment = table.write(List.of(new Entry<Integer,Value>(10, Value.of(new byte[]{10})), new Entry<Integer,Value>(11, Value.of(new byte[]{11}))), dataBad, idxBad, bloomBad, 5, 0);
        try (RandomAccessFile raf = new RandomAccessFile(dataBad, "rw")) {
            raf.setLength(raf.length() - 3);
        }

        StreamingMergeStrategy<Integer> merger = new StreamingMergeStrategy<>(new IntegerKeyCodec());
        var outData = dir.resolve("out.data").toFile();
        var outIdx = dir.resolve("out.idx").toFile();
        var outBloom = dir.resolve("out.filter").toFile();
        Supplier<SegmentFiles> fileFactory = () -> new SegmentFiles(outData, outIdx, outBloom);

        assertThrows(IOException.class,
                () -> merger.merge(List.of(goodSegment, badSegment), List.of(goodSegment, badSegment), fileFactory, 5),
                "a truncated entry in one segment should surface as an IOException, not be silently absorbed");

        assertTrue(dataGood.delete(), "the good segment's file handle should have been released despite the other segment's failure");
    }

    @Test
    void tombStoneKeyEntryIsRetainedWhenSameKeyEntryIsPresentInHigherLevels() throws IOException {
        Segment<Integer> newSegment = createSegment(List.of(new Entry<Integer, Value>(1, Value.tombstone(3))));
        Segment<Integer> midSegment = createSegment(List.of(new Entry<Integer, Value>(2, Value.of(2, new byte[1]))));
        Segment<Integer> oldSegment = createSegment(List.of(new Entry<Integer, Value>(1, Value.of(1, new byte[1]))));

        MergeStrategy<Integer> strategy = new StreamingMergeStrategy<>(codec);
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
        Segment<Integer> newSegment = createSegment(List.of(new Entry<Integer, Value>(1, Value.tombstone(3))));
        Segment<Integer> midSegment = createSegment(List.of(new Entry<Integer, Value>(2, Value.of(2, new byte[1]))));
        Segment<Integer> oldSegment = createSegment(List.of(new Entry<Integer, Value>(3, Value.of(1, new byte[1]))));

        MergeStrategy<Integer> strategy = new StreamingMergeStrategy<>(codec);
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
        Segment<Integer> newSegment = createSegment(List.of(new Entry<Integer, Value>(1, Value.tombstone(3))));
        Segment<Integer> midSegment = createSegment(List.of(new Entry<Integer, Value>(2, Value.of(2, new byte[1]))));

        MergeStrategy<Integer> strategy = new StreamingMergeStrategy<>(codec);
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
        Segment<Integer> newSegment = createSegment(List.of(new Entry<Integer, Value>(1, Value.of(2, new byte[1]))));
        Segment<Integer> oldSegment = createSegment(List.of(new Entry<Integer, Value>(1, Value.tombstone(1))));

        MergeStrategy<Integer> strategy = new StreamingMergeStrategy<>(codec);
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
        Segment<Integer> tombStoneSegment = createSegment(List.of(new Entry<Integer, Value>(1, Value.tombstone(3))));
        Segment<Integer> inputAtNextLevel = createSegment(List.of(new Entry<Integer, Value>(1, Value.of(2, new byte[1]))));

        MergeStrategy<Integer> strategy = new StreamingMergeStrategy<>(codec);
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
