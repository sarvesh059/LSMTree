package compaction.MergeStrategyImpl;

import RBT.Entry;
import SSTable.SSTable;
import compaction.MergeStrategy;
import compaction.TombStoneRetentionPolicyImpl.CoverageBasedTombStoneRetentionPolicy;
import core.Segment;
import core.SegmentFiles;
import core.Value;
import core.key.KeyCodec;
import cursor.EntrySource;
import cursor.MergeCursor;
import cursor.TombStoneRetentionPolicy;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class LeveledMergeStrategy<K extends Comparable<K>> implements MergeStrategy<K> {
    private final KeyCodec<K> codec;
    private final long targetSegmentSizeBytes;

    public LeveledMergeStrategy(KeyCodec<K> codec, long targetSegmentSizeBytes) {
        this.codec = codec;
        this.targetSegmentSizeBytes = targetSegmentSizeBytes;
    }


    @Override
    public List<Segment<K>> merge(List<Segment<K>> segments, List<Segment<K>> segmentSnapshot, Supplier<SegmentFiles> segmentFilesSupplier, int sampleEvery) throws IOException {
        int level = Integer.MAX_VALUE;

        for (Segment<K> segment : segments) {
            level = Math.min(level, segment.getLevel());
        }
        SSTable<K> table = new SSTable<>(this.codec);

        List<EntrySource<K>> sources = new ArrayList<>();
        for (Segment<K> segment : segments) {
            sources.add(table.openCursor(segment.getDataFile()));
        }

        int nextLevel = level+1;
        TombStoneRetentionPolicy<K> tombStoneRetentionPolicy = new CoverageBasedTombStoneRetentionPolicy<>(candidateSegments(segments, segmentSnapshot, nextLevel), this.codec);

        List<Segment<K>> newSegments = new ArrayList<>();
        try (EntrySource<K> source = new MergeCursor<>(sources, tombStoneRetentionPolicy)) {
            long currentListSize = 0;
            List<Entry<K, Value>> entries = new ArrayList<>();
            while(source.hasNext()){
                Entry<K, Value> entry = source.next();
                entries.add(entry);
                byte[] encodedKey = this.codec.encodeKey(entry.getKey());
                currentListSize += encodedKey.length + entry.getValue().getSizeInBytes();
                if(currentListSize >= this.targetSegmentSizeBytes){
                    Segment<K> segment = createSegment(segmentFilesSupplier, entries, table, sampleEvery, nextLevel);
                    segment.setMinKey(this.codec.encodeKey(entries.getFirst().getKey()));
                    segment.setMaxKey(this.codec.encodeKey(entries.getLast().getKey()));
                    newSegments.add(segment);
                    entries = new ArrayList<>();
                    currentListSize = 0;
                }
            }

            if(currentListSize != 0){
                Segment<K> segment = createSegment(segmentFilesSupplier, entries, table, sampleEvery, nextLevel);
                segment.setMinKey(this.codec.encodeKey(entries.getFirst().getKey()));
                segment.setMaxKey(this.codec.encodeKey(entries.getLast().getKey()));
                newSegments.add(segment);
            }
        }

        return newSegments;
    }

    private Segment<K> createSegment(Supplier<SegmentFiles> segmentFilesSupplier, List<Entry<K, Value>> entries, SSTable<K> table, int sampleEvery, int nextLevel) throws IOException{
        SegmentFiles segmentFiles = segmentFilesSupplier.get();
        File dataFile = segmentFiles.dataFile();
        File indexFile = segmentFiles.indexFile();
        File bloomFilterFile = segmentFiles.bloomFilterFile();
        Segment<K> segment = table.write(entries, dataFile, indexFile, bloomFilterFile, sampleEvery, nextLevel);

        return segment;
    }

    private List<Segment<K>> candidateSegments(List<Segment<K>> selectedSegments,List<Segment<K>> segmentSnapshot, int nextLevel){
        Set<Segment<K>> segmentSet = Collections.newSetFromMap(new IdentityHashMap<>());
        segmentSet.addAll(selectedSegments);
        return segmentSnapshot.stream().filter(ksegment -> !ksegment.isDeleted() && !segmentSet.contains(ksegment) && ksegment.getLevel() >= nextLevel).toList();
    }
}
