package compactation.MergeStrategyImpl;

import RBT.Entry;
import SSTable.SSTable;
import compactation.MergeStrategy;
import core.Segment;
import core.SegmentFiles;
import core.Value;
import core.key.KeyCodec;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class FullLoadMergeStrategy<K extends Comparable<K>> implements MergeStrategy<K> {
    private final KeyCodec<K> codec;

    public FullLoadMergeStrategy(KeyCodec<K> keyCodec){
        this.codec = keyCodec;
    }

    @Override
    public List<Segment<K>> merge(List<Segment<K>> segments, Supplier<SegmentFiles> segmentFilesSupplier, int sampleEvery) throws IOException {
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

        List<Entry<K,Value>> updatedEntries = kWayMerge(entriesList);

        return List.of(table.write(updatedEntries, dataFile, indexFile, bloomFilterFile, sampleEvery, 0));
    }

    List<Entry<K, Value>> kWayMerge(List<List<Entry<K, Value>>> entriesList){
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
            if(!currentEntry.getValue().isTombstone()) result.add(currentEntry);
            currentEntry = null;
            tiedIndices.clear();
        }

        return result;
    }
}
