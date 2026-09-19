package compactation.MergeStrategyImpl;

import cursor.EntrySource;
import SSTable.SSTable;
import compactation.MergeStrategy;
import core.Segment;
import core.key.KeyCodec;
import cursor.MergeCursor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class StreamingMergeStrategy<K extends Comparable<K>> implements MergeStrategy<K> {
    private final KeyCodec<K> codec;

    public StreamingMergeStrategy(KeyCodec<K> codec) {
        this.codec = codec;
    }

    @Override
    public Segment<K> merge(List<Segment<K>> segments, File dataFile, File indexFile, File bloomFilterFile, int sampleEvery) throws IOException {
        int approxEntries = segments.stream().mapToInt(Segment::getEntryCount).sum();
        SSTable<K> table = new SSTable<K>(this.codec);

        List<EntrySource<K>> sources = new ArrayList<>();
        for (Segment<K> segment : segments) {
            sources.add(table.openCursor(segment.getDataFile()));
        }

        try (EntrySource<K> source = new MergeCursor<>(sources)) {
            return table.write(source, dataFile, indexFile, bloomFilterFile, sampleEvery, approxEntries);
        }
    }
}
