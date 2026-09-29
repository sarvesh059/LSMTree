package compaction.MergeStrategyImpl;

import core.SegmentFiles;
import cursor.EntrySource;
import SSTable.SSTable;
import compaction.MergeStrategy;
import core.Segment;
import core.key.KeyCodec;
import cursor.MergeCursor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public class StreamingMergeStrategy<K extends Comparable<K>> implements MergeStrategy<K> {
    private final KeyCodec<K> codec;

    public StreamingMergeStrategy(KeyCodec<K> codec) {
        this.codec = codec;
    }

    @Override
    public List<Segment<K>> merge(List<Segment<K>> segments, List<Segment<K>> segmentSnapshot, Supplier<SegmentFiles> segmentFilesSupplier, int sampleEvery) throws IOException {
        int approxEntries = segments.stream().mapToInt(Segment::getEntryCount).sum();
        SegmentFiles segmentFiles = segmentFilesSupplier.get();
        File dataFile = segmentFiles.dataFile();
        File indexFile = segmentFiles.indexFile();
        File bloomFilterFile = segmentFiles.bloomFilterFile();

        SSTable<K> table = new SSTable<>(this.codec);

        List<EntrySource<K>> sources = new ArrayList<>();
        byte[] minKey = null;
        byte[] maxKey = null;
        for (Segment<K> segment : segments) {
            if(minKey == null || Arrays.compareUnsigned(minKey, segment.getMinKey()) > 0) minKey=segment.getMinKey();
            if(maxKey == null || Arrays.compareUnsigned(maxKey, segment.getMaxKey()) < 0) maxKey=segment.getMaxKey();
            sources.add(table.openCursor(segment.getDataFile()));
        }

        try (EntrySource<K> source = new MergeCursor<>(sources)) {
            Segment<K> segment = table.write(source, dataFile, indexFile, bloomFilterFile, sampleEvery, approxEntries,0);
            segment.setMinKey(minKey);
            segment.setMaxKey(maxKey);
            return List.of(segment);
        }
    }
}
