package core;

import bloomFilter.BloomFilter;

import java.io.File;
import java.util.List;

public class Segment<K> {
    private final File dataFile;
    private final List<IndexEntry> loadedIndex;
    private final BloomFilter bloomFilter;
    private final int entryCount;
    private final long maxEventId;


    public Segment(File dataFile, List<IndexEntry> loadedIndex, BloomFilter bloomFilter, int entryCount, long maxEventId) {
        this.dataFile = dataFile;
        this.loadedIndex = loadedIndex;
        this.bloomFilter = bloomFilter;
        this.entryCount = entryCount;
        this.maxEventId = maxEventId;
    }

    public File getDataFile() {
        return dataFile;
    }

    public List<IndexEntry> getLoadedIndex() {
        return loadedIndex;
    }

    public BloomFilter getBloomFilter(){
        return this.bloomFilter;
    }

    public int getEntryCount(){
        return this.entryCount;
    }

    public long getMaxEventId(){
        return this.maxEventId;
    }
}
