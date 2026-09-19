package LSMTree;

import RBT.Entry;
import SSTable.SSTable;
import WAL.WAL;
import bloomFilter.BloomFilter;
import compactation.CompactionStrategy;
import compactation.MergeStrategy;
import core.Segment;
import core.Value;
import core.key.KeyCodec;
import manifest.Manifest;
import memTable.MemTable;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;


public class LSMTree<K extends Comparable<K>> implements Closeable {
    private static final String WAL_FILE_NAME = "wal.log";
    private static final String MANIFEST_FILE_NAME = "manifest.log";
    private static final String DATA_FILE_PREFIX = "dataFile-";
    private static final String INDEX_FILE_PREFIX = "indexFile-";
    private static final String WIP_FILE_PREFIX = "WIP-";
    private static final String DELETED_FILE_PREFIX = "DELETED-";
    private static final String BLOOM_FILTER_FILE_PREFIX = "bloomFilter-";
    private final KeyCodec<K> codec;
    private final Path dataDir;
    private final WAL<K> wal;
    private final SSTable<K> ssTable;
    private final List<Segment<K>> segments;
    private final int memTableThreshold;
    private final int indexSampleRate;
    private final CompactionStrategy<K> compactionStrategy;
    private final MergeStrategy<K> mergeStrategy;
    private MemTable<K> memTable;

    public LSMTree(KeyCodec<K> codec, Path dataDir, int indexSampleRate, int memTableThreshold, CompactionStrategy<K> compactionStrategy, MergeStrategy<K> mergeStrategy) throws IOException {
        Files.createDirectories(dataDir);
        this.codec = codec;
        this.dataDir = dataDir;
        this.memTable = new MemTable<>(memTableThreshold);
        this.memTableThreshold = memTableThreshold;
        this.wal = new WAL<>(this.codec, dataDir.resolve(WAL_FILE_NAME).toFile());
        this.ssTable = new SSTable<>(codec);
        this.segments = new ArrayList<>();
        this.indexSampleRate = indexSampleRate;
        this.compactionStrategy = compactionStrategy;
        this.mergeStrategy = mergeStrategy;

        this.recover();
        this.cleanUp();
    }

    public void flush() throws IOException {
        if (memTable.getSizeInBytes() == 0) return;

        List<Entry<K, Value>> memTableEntries = memTable.entries();
        String dataFileId = UUID.randomUUID().toString();
        String dataFileName = DATA_FILE_PREFIX + dataFileId;
        String indexFileName = INDEX_FILE_PREFIX + dataFileId;
        String bloomFilterFileName = BLOOM_FILTER_FILE_PREFIX+dataFileId;

        File dataFile = this.dataDir.resolve(dataFileName).toFile();
        File indexFile = this.dataDir.resolve(indexFileName).toFile();
        File bloomFilterFile = this.dataDir.resolve(bloomFilterFileName).toFile();

        Segment<K> segment = this.ssTable.write(memTableEntries, dataFile, indexFile, bloomFilterFile, this.indexSampleRate);
        Manifest.append(dataFileName, manifestFile());

        this.segments.add(segment);

        this.wal.reset();
        this.memTable = new MemTable<>(this.memTableThreshold);

        if (this.compactionStrategy.shouldCompact(this.segments)) compact();
    }

    public void put(K key, Value value) throws IOException {
        this.wal.append(key, value);
        this.wal.fsync();
        this.memTable.put(key, value);

        if (memTable.isFull()) this.flush();
    }

    public Value get(K key) throws IOException {
        Value value = this.memTable.get(key);
        if (value != null) return value;

        int segmentsCount = this.segments.size();
        byte[] encodedKey = this.codec.encodeKey(key);
        for (int i = segmentsCount - 1; i >= 0; i--) {
            Segment<K> segment = this.segments.get(i);
            if(!segment.getBloomFilter().mightContain(encodedKey)) continue;;
            Value segmentValue = this.ssTable.get(key, segment.getDataFile(), segment.getLoadedIndex());
            if (segmentValue != null) return segmentValue;
        }

        return null;
    }

    void recover() throws IOException {
        File manifestFile = this.dataDir.resolve(MANIFEST_FILE_NAME).toFile();
        if (!manifestFile.exists()) return;

        List<String> dataFilesNames = Manifest.readAll(manifestFile);
        LinkedHashSet<String> liveDataFiles = new LinkedHashSet<>();
        for (String fileName : dataFilesNames) {
            if (fileName.startsWith(WIP_FILE_PREFIX)) continue;
            if (fileName.startsWith(DELETED_FILE_PREFIX)) {
                liveDataFiles.remove(fileName.substring(DELETED_FILE_PREFIX.length()));
            } else {
                liveDataFiles.add(fileName);
            }
        }

        for (String dataFileName : liveDataFiles) {
            String indexFileName = INDEX_FILE_PREFIX + dataFileName.substring(DATA_FILE_PREFIX.length());
            String bloomFilterFileName = BLOOM_FILTER_FILE_PREFIX + dataFileName.substring(DATA_FILE_PREFIX.length());
            File dataFile = this.dataDir.resolve(dataFileName).toFile();
            File indexFile = this.dataDir.resolve(indexFileName).toFile();
            File bloomFilterFile = this.dataDir.resolve(bloomFilterFileName).toFile();
            if (dataFile.exists() && indexFile.exists() && bloomFilterFile.exists()) {
                try(RandomAccessFile dataFileReader = new RandomAccessFile(dataFile, "r");
                RandomAccessFile bloomFilterFileReader = new RandomAccessFile(bloomFilterFile, "r")){
                this.segments.add(new Segment<>(dataFile, this.ssTable.loadIndex(indexFile), BloomFilter.readFrom(bloomFilterFileReader), dataFileReader.readInt()));
                }
            }
        }

        this.wal.replay(this.memTable);
    }

    private void cleanUp() throws IOException {
        List<String> dataFileNames = this.segments.stream().map(kSegment -> kSegment.getDataFile().getName()).toList();
        Manifest.rewrite(dataFileNames, this.dataDir.resolve(MANIFEST_FILE_NAME).toFile());
    }

    public void compact() throws IOException {
        File manifestFile = manifestFile();
        List<Segment<K>> selectedSegments = this.compactionStrategy.select(this.segments);
        String uuid = UUID.randomUUID().toString();
        String wipDatafileName = WIP_FILE_PREFIX + DATA_FILE_PREFIX + uuid;
        String dataFileName = DATA_FILE_PREFIX + uuid;
        String indexFileName = INDEX_FILE_PREFIX + uuid;
        String bloomFilterFileName = BLOOM_FILTER_FILE_PREFIX+uuid;
        Manifest.append(wipDatafileName, manifestFile);

        File wipDataFile = this.dataDir.resolve(wipDatafileName).toFile();
        File dataFile = this.dataDir.resolve(dataFileName).toFile();
        File indexFile = this.dataDir.resolve(indexFileName).toFile();
        File bloomFilterFile = this.dataDir.resolve(bloomFilterFileName).toFile();

        Segment<K> wipSegment = this.mergeStrategy.merge(selectedSegments, wipDataFile, indexFile, bloomFilterFile, this.indexSampleRate);
        Files.move(wipDataFile.toPath(), dataFile.toPath(), StandardCopyOption.ATOMIC_MOVE);

        this.segments.add(new Segment<>(dataFile, wipSegment.getLoadedIndex(), wipSegment.getBloomFilter(), wipSegment.getEntryCount()));
        this.segments.removeAll(selectedSegments);
        Manifest.append(dataFileName, manifestFile);

        for (Segment<K> segment : selectedSegments) {
            Manifest.append(DELETED_FILE_PREFIX + segment.getDataFile().getName(), manifestFile);
        }

        cleanUp();
    }

    @Override
    public void close() throws IOException {
        this.wal.close();
    }

    int memTableSizeInBytes() {
        return this.memTable.getSizeInBytes();
    }

    int segmentCount() {
        return this.segments.size();
    }

    List<Segment<K>> segments() {
        return this.segments;
    }

    File walFile() {
        return this.dataDir.resolve(WAL_FILE_NAME).toFile();
    }

    File manifestFile() {
        return this.dataDir.resolve(MANIFEST_FILE_NAME).toFile();
    }
}
