package SSTable;

import RBT.Entry;
import bloomFilter.BloomFilter;
import core.IndexEntry;
import core.Segment;
import core.Value;
import core.key.KeyCodec;

import java.io.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class SSTable<K extends Comparable<K>> {
    public final KeyCodec<K> keyCodec;

    public SSTable(KeyCodec<K> keyCodec) {
        this.keyCodec = keyCodec;
    }

    public Segment<K> write(List<Entry<K, Value>> entries, File dataFile, File indexFile, File bloomFilterFile, int sampleEvery) throws IOException {
        Iterator<Entry<K, Value>> it = entries.iterator();
        EntrySource<K> source = new EntrySource<K>() {
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

        return write(source, dataFile, indexFile, bloomFilterFile, sampleEvery, entries.size());
    }

    public Segment<K> write(EntrySource<K> entries, File dataFile, File indexFile, File bloomFilterFile, int sampleEvery, int approxEntries) throws IOException {
        List<IndexEntry> indexEntries = new ArrayList<>();
        BloomFilter bloomFilter = new BloomFilter(approxEntries, 0.02);
        int newEntriesAdded = 0;
        try (RandomAccessFile dataFileWriter = new RandomAccessFile(dataFile, "rw");
             RandomAccessFile indexFileWriter = new RandomAccessFile(indexFile, "rw");
             RandomAccessFile bloomFilterWriter = new RandomAccessFile(bloomFilterFile, "rw")) {
            dataFileWriter.writeInt(0);
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
                bloomFilter.add(encodedKey);
                newEntriesAdded++;
            }

            dataFileWriter.seek(0);
            indexFileWriter.seek(0);
            bloomFilterWriter.seek(0);
            dataFileWriter.writeInt(newEntriesAdded);
            indexFileWriter.writeInt(indexEntries.size());
            bloomFilter.writeTo(bloomFilterWriter);

            dataFileWriter.getFD().sync();
            indexFileWriter.getFD().sync();
            bloomFilterWriter.getFD().sync();
        }
        return new Segment<>(dataFile, indexEntries, bloomFilter, newEntriesAdded);
    }

    public List<Entry<K, Value>> readAll(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            int entryCount = input.readInt();
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

    public EntryCursor openCursor(File dataFile) throws IOException {
        return new EntryCursor(dataFile);
    }

    record ScanResult<V>(V value, int entriesRead) {
    }

    public class EntryCursor implements EntrySource<K> {
        private final RandomAccessFile input;
        private final int entryCount;
        private int entriesRead = 0;

        private EntryCursor(File dataFile) throws IOException {
            this.input = new RandomAccessFile(dataFile, "r");
            this.entryCount = input.readInt();
        }

        public boolean hasNext() {
            return entriesRead < entryCount;
        }

        public Entry<K, Value> next() throws IOException {
            if (entriesRead >= entryCount) return null;
            K key = SSTable.this.keyCodec.decode(input);
            Value value = Value.readFrom(input);
            entriesRead++;
            return new Entry<>(key, value);
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }
}
