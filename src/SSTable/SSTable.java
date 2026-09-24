package SSTable;

import RBT.Entry;
import bloomFilter.BloomFilter;
import core.DataFileMetaData;
import core.IndexEntry;
import core.Segment;
import core.Value;
import core.key.KeyCodec;
import cursor.DataFileCursor;
import cursor.EntrySource;
import cursor.RangeCursor;

import java.io.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SSTable<K extends Comparable<K>> {
    public final KeyCodec<K> keyCodec;

    public SSTable(KeyCodec<K> keyCodec) {
        this.keyCodec = keyCodec;
    }

    public Segment<K> write(List<Entry<K, Value>> entries, File dataFile, File indexFile, File bloomFilterFile, int sampleEvery, int level) throws IOException {
        Iterator<Entry<K, Value>> it = entries.iterator();
        EntrySource<K> source = memTableCursor(entries);

        return write(source, dataFile, indexFile, bloomFilterFile, sampleEvery, entries.size(), level);
    }

    public EntrySource<K> memTableCursor(List<Entry<K,Value>> entries){
        Iterator<Entry<K, Value>> it = entries.iterator();
        return new EntrySource<K>() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public Entry<K, Value> next() throws IOException {
                return it.next();
            }

            @Override
            public void close() throws IOException {

            }
        };
    }

    public Segment<K> write(EntrySource<K> entries, File dataFile, File indexFile, File bloomFilterFile, int sampleEvery, int approxEntries, int level) throws IOException {
        List<IndexEntry> indexEntries = new ArrayList<>();
        BloomFilter bloomFilter = new BloomFilter(approxEntries, 0.02);
        int newEntriesAdded = 0;
        long maxEventId = 0L;
        try (RandomAccessFile dataFileWriter = new RandomAccessFile(dataFile, "rw");
             RandomAccessFile indexFileWriter = new RandomAccessFile(indexFile, "rw");
             RandomAccessFile bloomFilterWriter = new RandomAccessFile(bloomFilterFile, "rw")) {
            updateDataFileMetaData(0, level,maxEventId,dataFileWriter);
            indexFileWriter.writeInt(0);

            while (entries.hasNext()) {
                Entry<K, Value> entry = entries.next();
                byte[] encodedKey = this.keyCodec.encodeKey(entry.getKey());
                if (newEntriesAdded % sampleEvery == 0) {
                    IndexEntry indexEntry = new IndexEntry(encodedKey, dataFileWriter.getFilePointer());
                    indexEntry.writeTo(indexFileWriter, this.keyCodec);
                    indexEntries.add(indexEntry);
                }
                dataFileWriter.write(encodedKey);
                entry.getValue().writeTo(dataFileWriter);
                maxEventId = Math.max(entry.getValue().getId(), maxEventId);
                bloomFilter.add(encodedKey);
                newEntriesAdded++;
            }

            updateDataFileMetaData(newEntriesAdded,level,maxEventId, dataFileWriter);
            indexFileWriter.seek(0);
            bloomFilterWriter.seek(0);
            indexFileWriter.writeInt(indexEntries.size());
            bloomFilter.writeTo(bloomFilterWriter);

            dataFileWriter.getFD().sync();
            indexFileWriter.getFD().sync();
            bloomFilterWriter.getFD().sync();
        }
        Segment<K> segment = new Segment<>(dataFile, indexEntries, bloomFilter, newEntriesAdded, maxEventId);
        segment.setLevel(level);
        return segment;
    }

    private void updateDataFileMetaData(int entryCount, int level, long maxEventId, RandomAccessFile dataFileWriter) throws IOException{
        dataFileWriter.seek(0);
        dataFileWriter.writeInt(entryCount);
        dataFileWriter.writeInt(level);
        dataFileWriter.writeLong(maxEventId);
    }

    public List<Entry<K, Value>> readAll(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            int entryCount = input.readInt();
            int level = input.readInt();
            long maxEventId = input.readLong();
            List<Entry<K, Value>> entries = new ArrayList<>();
            for (int i = 0; i < entryCount; i++) {
                K key = this.keyCodec.decode(input);
                Value v = Value.readFrom(input);
                entries.add(new Entry<>(key, v));
            }

            return entries;
        }
    }

    public List<IndexEntry> loadIndex(File indexFile) throws IOException {
        List<IndexEntry> indexEntries = new ArrayList<>();
        try (RandomAccessFile indexFileInput = new RandomAccessFile(indexFile, "r")) {
            int indexKeysCount = indexFileInput.readInt();
            for (int i = 0; i < indexKeysCount; i++) {
                indexEntries.add(IndexEntry.readFrom(indexFileInput, this.keyCodec));
            }
        }

        return indexEntries;
    }

    public Value get(K key, File dataFile, List<IndexEntry> loadedIndex) throws IOException {
        return scan(key, dataFile, loadedIndex).value();
    }

    ScanResult<Value> scan(K key, File dataFile, List<IndexEntry> loadedIndex) throws IOException {
        byte[] encodedKey = this.keyCodec.encodeKey(key);
        IndexEntry floorIndexEntry = findFloorSample(encodedKey, loadedIndex);
        int scannedEntries = 0;

        if (floorIndexEntry == null) return new ScanResult<>(null, scannedEntries);

        try (RandomAccessFile readStream = new RandomAccessFile(dataFile, "r")) {
            readStream.seek(floorIndexEntry.getOffset());
            while (true) {
                byte[] nextKey;
                Value value;
                try {
                    nextKey = this.keyCodec.readRawEncoded(readStream);
                    value = Value.readFrom(readStream);
                    scannedEntries++;
                } catch (EOFException e) {
                    return new ScanResult<>(null, scannedEntries);
                }

                int cmp = this.keyCodec.compareEncoded(nextKey,encodedKey);
                if (cmp == 0) return new ScanResult<>(value, scannedEntries);
                if (cmp > 0) return new ScanResult<>(null, scannedEntries);
            }
        }
    }

    IndexEntry findFloorSample(byte[] encodedKey, List<IndexEntry> loadedIndex) {
        IndexEntry floor = null;
        for (IndexEntry entry : loadedIndex) {
            if (this.keyCodec.compareEncoded(entry.getEncodedKey(), encodedKey) > 0) {
                break;
            }
            floor = entry;
        }
        return floor;
    }

    public EntrySource<K> openCursor(File dataFile) throws IOException {
        return new DataFileCursor<>(dataFile, this.keyCodec);
    }

    public EntrySource<K> rangeCursor(File dataFile, List<IndexEntry> loadedIndex, byte[] low, byte[] high) throws IOException{
        IndexEntry floorIndexEntry = findFloorSample(low, loadedIndex);

        long previousOffset = 0;
        if(floorIndexEntry != null) previousOffset = floorIndexEntry.getOffset();

        boolean noKeyReachesLow = false;
        try (RandomAccessFile readStream = new RandomAccessFile(dataFile, "r")) {
            if(floorIndexEntry == null){
                int totalEntries = readStream.readInt();
                int level = readStream.readInt();
                long maxEventId = readStream.readLong();
                previousOffset = readStream.getFilePointer();
            }
            readStream.seek(previousOffset);
            while (true) {
                byte[] nextKey;
                Value value;
                try {
                    nextKey = this.keyCodec.readRawEncoded(readStream);
                    value = Value.readFrom(readStream);
                } catch (EOFException e) {
                    noKeyReachesLow = true;
                    break;
                }

                int cmp = this.keyCodec.compareEncoded(nextKey,low);
                if (cmp >= 0) break;
                previousOffset = readStream.getFilePointer();
            }
        }

        RangeCursor<K> cursor = new RangeCursor<>(low, high, dataFile, this.keyCodec);
        cursor.seek(noKeyReachesLow ? dataFile.length() : previousOffset);
        return cursor;
    }

    public byte[] minKey(File datafile, List<IndexEntry> loadedIndex){
        return loadedIndex.getFirst().getEncodedKey();
    }

    public byte[] maxKey(File dataFile, List<IndexEntry> loadedIndex) throws IOException{
        IndexEntry lastIndexEntry = loadedIndex.getLast();
        try (RandomAccessFile readStream = new RandomAccessFile(dataFile, "r")){
            readStream.seek(lastIndexEntry.getOffset());
            byte[] lastKey = null;
            Value value;
            while (true) {
                try {
                    lastKey = this.keyCodec.readRawEncoded(readStream);
                    value = Value.readFrom(readStream);
                } catch (EOFException e) {
                    return lastKey;
                }
            }
        }
    }

    public DataFileMetaData getMetaData(File dataFile) throws IOException{
        try(RandomAccessFile dataFileReader = new RandomAccessFile(dataFile, "rw")){
            int entryCount = dataFileReader.readInt();
            int level = dataFileReader.readInt();
            long maxEventid = dataFileReader.readLong();
            return new DataFileMetaData(entryCount, level, maxEventid);
        }
    }

    record ScanResult<V>(V value, int entriesRead) {
    }
}
