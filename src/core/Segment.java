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
    private int level;
    private byte[] minKey;
    private byte[] maxKey;


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

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public byte[] getMinKey() {
        return minKey;
    }

    public void setMinKey(byte[] minKey) {
        this.minKey = minKey;
    }

    public byte[] getMaxKey() {
        return maxKey;
    }

    public void setMaxKey(byte[] maxKey) {
        this.maxKey = maxKey;
    }

    public long getMaxEventId(){
        return this.maxEventId;
    }

    public long getSize(){
        return this.dataFile.length();
    }
}
