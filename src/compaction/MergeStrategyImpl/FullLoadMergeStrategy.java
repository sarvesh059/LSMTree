package compaction.MergeStrategyImpl;

import RBT.Entry;
import SSTable.SSTable;
import compaction.MergeStrategy;
import compaction.TombStoneRetentionPolicyImpl.CoverageBasedTombStoneRetentionPolicy;
import core.Segment;
import core.SegmentFiles;
import core.Value;
import core.key.KeyCodec;
import cursor.TombStoneRetentionPolicy;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public class FullLoadMergeStrategy<K extends Comparable<K>> implements MergeStrategy<K> {
    private final KeyCodec<K> codec;

    public FullLoadMergeStrategy(KeyCodec<K> keyCodec){
        this.codec = keyCodec;
    }

    @Override
    public List<Segment<K>> merge(List<Segment<K>> segments, List<Segment<K>> segmentSnapshot, Supplier<SegmentFiles> segmentFilesSupplier, int sampleEvery) throws IOException {
        List<List<Entry<K, Value>>> entriesList = new ArrayList<>();

        SSTable<K> table = new SSTable<>(this.codec);
        SegmentFiles segmentFiles = segmentFilesSupplier.get();
        File dataFile = segmentFiles.dataFile();
        File indexFile = segmentFiles.indexFile();
        File bloomFilterFile = segmentFiles.bloomFilterFile();

        for(Segment<K> segment: segments){
            List<Entry<K, Value>> entries = table.readAll(segment.getDataFile());
            entriesList.add(entries);
        }

        TombStoneRetentionPolicy<K> tombStoneRetentionPolicy = new CoverageBasedTombStoneRetentionPolicy<>(candidateSegments(segments, segmentSnapshot), codec);
        List<Entry<K,Value>> updatedEntries = kWayMerge(entriesList, tombStoneRetentionPolicy);

        Segment<K> segment = table.write(updatedEntries, dataFile, indexFile, bloomFilterFile, sampleEvery, 0);
        if (!updatedEntries.isEmpty()) {
            segment.setMinKey(this.codec.encodeKey(updatedEntries.getFirst().getKey()));
            segment.setMaxKey(this.codec.encodeKey(updatedEntries.getLast().getKey()));
        }
        return List.of(segment);
    }

    List<Entry<K, Value>> kWayMerge(List<List<Entry<K, Value>>> entriesList, TombStoneRetentionPolicy<K> tombStoneRetentionPolicy){
        List<Entry<K, Value>> result = new ArrayList<>();
        int total = entriesList.size();
        int[] indexList = new int[total];
        Entry<K,Value> currentEntry = null;
        List<Integer> tiedIndices = new ArrayList<>();
        while(true){
            for(int i=0;i<total;i++){
                int listIndex = indexList[i];
                if(listIndex >= entriesList.get(i).size()) continue;
                Entry<K, Value> listEntry = entriesList.get(i).get(indexList[i]);
                int cmp = currentEntry == null ? -1 : currentEntry.getKey().compareTo(listEntry.getKey());
                if(currentEntry == null || cmp > 0){
                    tiedIndices.clear();
                    tiedIndices.add(i);
                    currentEntry = listEntry;
                } else if (cmp == 0) {
                    tiedIndices.add(i);
                    currentEntry = (currentEntry.getValue().getId() < listEntry.getValue().getId()) ? listEntry : currentEntry;
                }
            }
            if(currentEntry == null) break;
            for (int idx : tiedIndices) indexList[idx]++;
            if(!currentEntry.getValue().isTombstone() || tombStoneRetentionPolicy.shouldRetain(currentEntry)) result.add(currentEntry);
            currentEntry = null;
            tiedIndices.clear();
        }

        return result;
    }

    private List<Segment<K>> candidateSegments(List<Segment<K>> selectedSegments,List<Segment<K>> segmentSnapshot){
        Set<Segment<K>> segmentSet = Collections.newSetFromMap(new IdentityHashMap<>());
        segmentSet.addAll(selectedSegments);
        return segmentSnapshot.stream().filter(ksegment -> !ksegment.isDeleted() && !segmentSet.contains(ksegment)).toList();
    }
}
