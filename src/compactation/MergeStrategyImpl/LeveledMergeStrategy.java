package compactation.MergeStrategyImpl;

import SSTable.SSTable;
import compactation.MergeStrategy;
import core.Segment;
import core.SegmentFiles;
import core.key.KeyCodec;
import cursor.EntrySource;
import cursor.MergeCursor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public class LeveledMergeStrategy<K extends Comparable<K>> implements MergeStrategy<K> {
    private final KeyCodec<K> codec;

    public LeveledMergeStrategy(KeyCodec<K> codec) {
        this.codec = codec;
    }


    @Override
    public List<Segment<K>> merge(List<Segment<K>> segments, Supplier<SegmentFiles> segmentFilesSupplier, int sampleEvery) throws IOException {
        int approxEntries = 0;
        int level = Integer.MAX_VALUE;
        byte[] minKey = null;
        byte[] maxKey = null;

        for (Segment<K> segment : segments) {
            approxEntries += segment.getEntryCount();

            level = Math.min(level, segment.getLevel());

            if (minKey == null ||
                    Arrays.compareUnsigned(segment.getMinKey(), minKey) < 0) {
                minKey = segment.getMinKey();
            }

            if (maxKey == null ||
                    Arrays.compareUnsigned(segment.getMinKey(), maxKey) > 0) {
                maxKey = segment.getMaxKey();
            }
        }
        SSTable<K> table = new SSTable<>(this.codec);

        SegmentFiles segmentFiles = segmentFilesSupplier.get();
        File dataFile = segmentFiles.dataFile();
        File indexFile = segmentFiles.indexFile();
        File bloomFilterFile = segmentFiles.bloomFilterFile();

        List<EntrySource<K>> sources = new ArrayList<>();
        for (Segment<K> segment : segments) {
            sources.add(table.openCursor(segment.getDataFile()));
        }

        try (EntrySource<K> source = new MergeCursor<>(sources)) {
            Segment<K> segment = table.write(source, dataFile, indexFile, bloomFilterFile, sampleEvery, approxEntries);
            segment.setLevel(level+1);
            segment.setMinKey(minKey);
            segment.setMaxKey(maxKey);
            return List.of(segment);
        }
    }
}
