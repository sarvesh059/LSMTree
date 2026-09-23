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
import cursor.EntrySource;
import cursor.MergeCursor;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


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
    private final int memTableThreshold;
    private final int indexSampleRate;
    private final CompactionStrategy<K> compactionStrategy;
    private final MergeStrategy<K> mergeStrategy;
    private final AtomicReference<Version<K>> version;
    private final AtomicLong eventCounter;
    private long getCallCount = 0;
    private long segmentsConsultedCount = 0;
    private final ExecutorService compactionExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean compactionInProgress = false;
    private final Object manifestLock = new Object();

    public LSMTree(KeyCodec<K> codec, Path dataDir, int indexSampleRate, int memTableThreshold, CompactionStrategy<K> compactionStrategy, MergeStrategy<K> mergeStrategy) throws IOException {
        Files.createDirectories(dataDir);
        this.codec = codec;
        this.dataDir = dataDir;
        this.memTableThreshold = memTableThreshold;
        this.wal = new WAL<>(this.codec, dataDir.resolve(WAL_FILE_NAME).toFile());
        this.ssTable = new SSTable<>(codec);
        this.version = new AtomicReference<>(new Version<K>(new MemTable<>(memTableThreshold), new ArrayList<>()));
        this.indexSampleRate = indexSampleRate;
        this.compactionStrategy = compactionStrategy;
        this.mergeStrategy = mergeStrategy;
        this.eventCounter = new AtomicLong(0);

        this.recover();
        this.cleanUp();
    }

    synchronized void flush() throws IOException {
        MemTable<K> memTable = this.version.get().memTable();
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

        synchronized (this.manifestLock){
            Manifest.append(dataFileName, manifestFile());
        }

        this.version.updateAndGet(current -> {
            List<Segment<K>> newList = new ArrayList<>(current.segments());
            newList.add(segment);
            return new Version<>(new MemTable<>(this.memTableThreshold), newList);
        });

        this.wal.reset();

        checkAndCompactAsync();
    }

    public synchronized void put(K key, Value value) throws IOException {
        Version<K> currentVersion = this.version.get();

        Value valueWithId = Value.withId(this.eventCounter.incrementAndGet(), value);
        this.wal.append(key, valueWithId);
        this.wal.fsync();
        currentVersion.memTable().put(key, valueWithId);

        if (currentVersion.memTable().isFull()) this.flush();
    }

    public Value get(K key) throws IOException {
        this.getCallCount++;
        Version<K> currentVersion = this.version.get();

        Value value = currentVersion.memTable().get(key);
        if (value != null) return value;

        List<Segment<K>> segmentsSnapshot = currentVersion.segments();
        int segmentsCount = segmentsSnapshot.size();
        byte[] encodedKey = this.codec.encodeKey(key);
        long maxEventId = -1L;
        Value resultValue = null;
        for (int i = segmentsCount - 1; i >= 0; i--) {
            Segment<K> segment = segmentsSnapshot.get(i);
            if(!segment.getBloomFilter().mightContain(encodedKey)) continue;;
            this.segmentsConsultedCount++;
            Value segmentValue = this.ssTable.get(key, segment.getDataFile(), segment.getLoadedIndex());
            if(segmentValue != null && segmentValue.getId() > maxEventId){
                maxEventId = segmentValue.getId();
                resultValue = segmentValue;
            }
        }

        return resultValue;
    }

    void recover() throws IOException {
        File manifestFile = this.dataDir.resolve(MANIFEST_FILE_NAME).toFile();
        if (!manifestFile.exists()) return;

        Version<K> currentVersion = this.version.get();
        List<Segment<K>> segments = new ArrayList<>(currentVersion.segments());
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

        long maxEventId = 0;
        for (String dataFileName : liveDataFiles) {
            String indexFileName = INDEX_FILE_PREFIX + dataFileName.substring(DATA_FILE_PREFIX.length());
            String bloomFilterFileName = BLOOM_FILTER_FILE_PREFIX + dataFileName.substring(DATA_FILE_PREFIX.length());
            File dataFile = this.dataDir.resolve(dataFileName).toFile();
            File indexFile = this.dataDir.resolve(indexFileName).toFile();
            File bloomFilterFile = this.dataDir.resolve(bloomFilterFileName).toFile();
            if (dataFile.exists() && indexFile.exists() && bloomFilterFile.exists()) {
                try(RandomAccessFile dataFileReader = new RandomAccessFile(dataFile, "r");
                    RandomAccessFile bloomFilterFileReader = new RandomAccessFile(bloomFilterFile, "r")){
                    Segment<K> segment = new Segment<>(dataFile, this.ssTable.loadIndex(indexFile), BloomFilter.readFrom(bloomFilterFileReader), dataFileReader.readInt(), dataFileReader.readLong());
                    maxEventId = Math.max(maxEventId, segment.getMaxEventId());
                    segments.add(segment);
                }
            }
        }

        MemTable<K> newMemTable = new MemTable<>(this.memTableThreshold);
        this.wal.replay(newMemTable);
        this.version.updateAndGet((_) -> new Version<K>(newMemTable, segments));
        

        maxEventId = Math.max(maxEventId, this.wal.getLatestEventId());
        this.eventCounter.set(maxEventId);

        checkAndCompactAsync();
    }

    private void checkAndCompactAsync(){
        Version<K> currentVersion = this.version.get();
        if (this.compactionStrategy.shouldCompact(currentVersion.segments()) && !compactionInProgress){
            List<Segment<K>> selectedSegments = this.compactionStrategy.select(currentVersion.segments());
            this.compactionInProgress = true;
            compactionExecutor.submit(() -> runCompactionAsync(selectedSegments));
        }
    }

    private void cleanUp() throws IOException {
        Version<K> currentVersion = this.version.get();
        List<String> dataFileNames = currentVersion.segments().stream().map(kSegment -> kSegment.getDataFile().getName()).toList();
        synchronized (this.manifestLock) {
            Manifest.rewrite(dataFileNames, this.dataDir.resolve(MANIFEST_FILE_NAME).toFile());
        }
    }

    synchronized void compact() throws IOException {
        Version<K> currentVersion = this.version.get();
        List<Segment<K>> selectedSegments = this.compactionStrategy.select(currentVersion.segments());
        this.sharedCompactionLogic(selectedSegments);
    }

    void sharedCompactionLogic(List<Segment<K>> selectedSegments) throws IOException {
        File manifestFile = manifestFile();
        String uuid = UUID.randomUUID().toString();
        String wipDatafileName = WIP_FILE_PREFIX + DATA_FILE_PREFIX + uuid;
        String dataFileName = DATA_FILE_PREFIX + uuid;
        String indexFileName = INDEX_FILE_PREFIX + uuid;
        String bloomFilterFileName = BLOOM_FILTER_FILE_PREFIX+uuid;

        synchronized (this.manifestLock) {
            Manifest.append(wipDatafileName, manifestFile);
        }

        File wipDataFile = this.dataDir.resolve(wipDatafileName).toFile();
        File dataFile = this.dataDir.resolve(dataFileName).toFile();
        File indexFile = this.dataDir.resolve(indexFileName).toFile();
        File bloomFilterFile = this.dataDir.resolve(bloomFilterFileName).toFile();

        Segment<K> wipSegment = this.mergeStrategy.merge(selectedSegments, wipDataFile, indexFile, bloomFilterFile, this.indexSampleRate);
        Files.move(wipDataFile.toPath(), dataFile.toPath(), StandardCopyOption.ATOMIC_MOVE);

        Segment<K> newSegment = new Segment<>(dataFile, wipSegment.getLoadedIndex(), wipSegment.getBloomFilter(), wipSegment.getEntryCount(), wipSegment.getMaxEventId());

        this.version.updateAndGet((currentVersion) -> {

            List<Segment<K>> newSegmentList = new ArrayList<>(currentVersion.segments());
            newSegmentList.removeAll(selectedSegments);
            newSegmentList.add(newSegment);

            return new Version<>(currentVersion.memTable(), newSegmentList);
        });

        synchronized (this.manifestLock) {
            Manifest.append(dataFileName, manifestFile);
            for (Segment<K> segment : selectedSegments) {
                Manifest.append(DELETED_FILE_PREFIX + segment.getDataFile().getName(), manifestFile);
            }
        }

        cleanUp();
    }

    void runCompactionAsync(List<Segment<K>> selectedSegments){
        try{
            this.sharedCompactionLogic(selectedSegments);
        } catch (Throwable _){

        }finally {
            synchronized (this){
                Version<K> currentVersion = this.version.get();
                this.compactionInProgress = false;
                if (this.compactionStrategy.shouldCompact(currentVersion.segments())) {
                    List<Segment<K>> nextSelectedSegments = this.compactionStrategy.select(currentVersion.segments());
                    this.compactionInProgress = true;
                    compactionExecutor.submit(() -> runCompactionAsync(nextSelectedSegments));
                }
            }
        }
    }

    EntrySource<K> scan(K low, K high) throws IOException{
        List<EntrySource<K>> cursors = new ArrayList<>();
        Version<K> currentVersion = this.version.get();
        for(Segment<K> segment : currentVersion.segments()){
            cursors.add(this.ssTable.rangeCursor(segment.getDataFile(),segment.getLoadedIndex(), this.codec.encodeKey(low), this.codec.encodeKey(high)));
        }

        List<Entry<K, Value>> memTableEntries = currentVersion.memTable().range(low, high);;
        cursors.add(this.ssTable.memTableCursor(memTableEntries));

        return new MergeCursor<>(cursors);
    }

    @Override
    public void close() throws IOException {
        this.wal.close();
        this.compactionExecutor.shutdown();
        try {
            this.compactionExecutor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void awaitCompaction() throws InterruptedException {
        while (true) {
            try {
                this.compactionExecutor.submit(() -> {}).get();
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
            if (!this.compactionInProgress) return;
        }
    }

    int memTableSizeInBytes() {
        return this.version.get().memTable().getSizeInBytes();
    }

    int segmentCount() {
        return this.version.get().segments().size();
    }

    long getCallCount() {
        return this.getCallCount;
    }

    long segmentsConsultedCount() {
        return this.segmentsConsultedCount;
    }

    File walFile() {
        return this.dataDir.resolve(WAL_FILE_NAME).toFile();
    }

    File manifestFile() {
        return this.dataDir.resolve(MANIFEST_FILE_NAME).toFile();
    }

    List<Segment<K>> segments() {
        return this.version.get().segments();
    }
}
