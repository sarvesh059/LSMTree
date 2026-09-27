package LSMTree;

import RBT.Entry;
import SSTable.SSTable;
import WAL.WAL;
import bloomFilter.BloomFilter;
import compactation.CompactionStrategy;
import compactation.MergeStrategy;
import core.*;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;


public class LSMTree<K extends Comparable<K>> implements Closeable {
    private static final String WAL_FILE_NAME = "wal.log";
    private static final String MANIFEST_FILE_NAME = "manifest.log";
    private static final String DATA_FILE_PREFIX = "dataFile-";
    private static final String INDEX_FILE_PREFIX = "indexFile-";
    private static final String WIP_FILE_PREFIX = "WIP-";
    private static final String DELETED_FILE_PREFIX = "DELETED-";
    private static final String BLOOM_FILTER_FILE_PREFIX = "bloomFilter-";
    private static final long STALE_PIN_WARNING_THRESHOLD_IN_MILLIS = 30_000;
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
    private final Map<Object, Long> openVersionPins = new ConcurrentHashMap<>();

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
        Version<K> currentVersion = this.version.get();
        MemTable<K> memTable = currentVersion.memTable();
        if (memTable.getSizeInBytes() == 0) return;

        List<Entry<K, Value>> memTableEntries = memTable.entries();
        K minKey = memTableEntries.stream()
                .min(Comparator.comparing(Entry::getKey))
                .orElseThrow()
                .getKey();
        K maxKey = memTableEntries.stream()
                .max(Comparator.comparing(Entry::getKey))
                .orElseThrow()
                .getKey();
        String dataFileId = UUID.randomUUID().toString();
        String dataFileName = DATA_FILE_PREFIX + dataFileId;
        String indexFileName = INDEX_FILE_PREFIX + dataFileId;
        String bloomFilterFileName = BLOOM_FILTER_FILE_PREFIX+dataFileId;

        File dataFile = this.dataDir.resolve(dataFileName).toFile();
        File indexFile = this.dataDir.resolve(indexFileName).toFile();
        File bloomFilterFile = this.dataDir.resolve(bloomFilterFileName).toFile();

        Segment<K> segment = this.ssTable.write(memTableEntries, dataFile, indexFile, bloomFilterFile, this.indexSampleRate, 0);
        segment.setMinKey(this.codec.encodeKey(minKey));
        segment.setMaxKey(this.codec.encodeKey(maxKey));

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

        List<Segment<K>> segmentsSnapshot = new ArrayList<>(currentVersion.segments());
        segmentsSnapshot.sort((a, b) -> {
            if (a.getLevel() == b.getLevel()) {
                return this.codec.compareEncoded(a.getMinKey(), b.getMinKey());
            } else {
                return Integer.compare(a.getLevel(), b.getLevel());
            }
        });
        byte[] encodedKey = this.codec.encodeKey(key);

        return withPinnedSegments(segmentsSnapshot, pinnedSegments -> {
            Value resultValue = null;
            long maxEventId = -1L;
            for (Segment<K> segment : pinnedSegments) {
                if(this.codec.compareEncoded(encodedKey,segment.getMinKey()) < 0 ||
                        this.codec.compareEncoded(encodedKey, segment.getMaxKey()) > 0 ||
                        !segment.getBloomFilter().mightContain(encodedKey)) continue;
                this.segmentsConsultedCount++;
                Value segmentValue = this.ssTable.get(key, segment.getDataFile(), segment.getLoadedIndex());
                if(segmentValue != null){
                    if(segment.getLevel() == 0){
                        if(segmentValue.getId() > maxEventId){
                            maxEventId = segmentValue.getId();
                            resultValue = segmentValue;
                        }
                    }else{
                        return resultValue != null ? resultValue : segmentValue;
                    }
                }
            }
            return resultValue;
        });
    }

    void recover() throws IOException {
        File manifestFile = this.dataDir.resolve(MANIFEST_FILE_NAME).toFile();
        if (!manifestFile.exists()) return;

        Version<K> currentVersion = this.version.get();
        List<Segment<K>> segments = new ArrayList<>(currentVersion.segments());
        List<String> dataFilesNames = Manifest.readAll(manifestFile);
        LinkedHashSet<String> liveDataFiles = new LinkedHashSet<>();
        LinkedHashSet<String> pendingDeletionDataFiles = new LinkedHashSet<>();
        for (String fileName : dataFilesNames) {
            if (fileName.startsWith(WIP_FILE_PREFIX)) continue;
            if (fileName.startsWith(DELETED_FILE_PREFIX)) {
                String underlyingName = fileName.substring(DELETED_FILE_PREFIX.length());
                liveDataFiles.remove(underlyingName);
                pendingDeletionDataFiles.add(underlyingName);
            } else {
                liveDataFiles.add(fileName);
                pendingDeletionDataFiles.remove(fileName);
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
                try(RandomAccessFile bloomFilterFileReader = new RandomAccessFile(bloomFilterFile, "r")){
                    List<IndexEntry> index = this.ssTable.loadIndex(indexFile);
                    byte[] minKey = this.ssTable.minKey(dataFile, index);
                    byte[] maxKey = this.ssTable.maxKey(dataFile,index);
                    DataFileMetaData dataFileMetaData = this.ssTable.getMetaData(dataFile);
                    Segment<K> segment = new Segment<>(dataFile, index, BloomFilter.readFrom(bloomFilterFileReader), dataFileMetaData.entryCount(), dataFileMetaData.maxEventId());
                    segment.setMinKey(minKey);
                    segment.setMaxKey(maxKey);
                    segment.setLevel(dataFileMetaData.level());
                    maxEventId = Math.max(maxEventId, segment.getMaxEventId());
                    segments.add(segment);
                }
            }
        }

        MemTable<K> newMemTable = new MemTable<>(this.memTableThreshold);
        this.wal.replay(newMemTable);
        this.version.updateAndGet((_) -> new Version<K>(newMemTable, segments));

        // Nothing survives a restart to hold a pin, so anything still marked DELETED at this
        // point is unconditionally safe to purge -- it was only left behind because the process
        // exited before a prior cleanUp() call got to it.
        for (String dataFileName : pendingDeletionDataFiles) {
            try {
                deleteSegmentFilesByName(dataFileName);
            } catch (IOException e) {
                System.err.println("WARNING: failed to physically delete orphaned segment file "
                        + dataFileName + " left over from a prior crash: " + e.getMessage());
            }
        }
        if (!pendingDeletionDataFiles.isEmpty()) {
            synchronized (this.manifestLock) {
                Manifest.rewrite(new ArrayList<>(liveDataFiles), manifestFile);
            }
        }

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
        List<Segment<K>> purgedSegments = new ArrayList<>();
        List<String> manifestEntries = new ArrayList<>();

        for (Segment<K> segment : currentVersion.segments()) {
            if (!segment.isDeleted()) {
                manifestEntries.add(segment.getDataFile().getName());
                continue;
            }
            if (segment.tryClaimForDeletion()) {
                try {
                    deleteSegmentFiles(segment);
                } catch (IOException e) {
                    System.err.println("WARNING: failed to physically delete segment file "
                            + segment.getDataFile().getName() + ": " + e.getMessage()
                            + " -- it will no longer be tracked, but a stray file may remain on disk");
                }
                purgedSegments.add(segment);
            } else {
                manifestEntries.add(DELETED_FILE_PREFIX + segment.getDataFile().getName());
            }
        }

        synchronized (this.manifestLock) {
            Manifest.rewrite(manifestEntries, this.dataDir.resolve(MANIFEST_FILE_NAME).toFile());
        }

        if (!purgedSegments.isEmpty()) {
            this.version.updateAndGet(v -> {
                List<Segment<K>> newList = new ArrayList<>(v.segments());
                newList.removeAll(purgedSegments);
                return new Version<>(v.memTable(), newList);
            });
        }

        long oldestPinAge = oldestOpenPinAgeInMillis();
        if (oldestPinAge > STALE_PIN_WARNING_THRESHOLD_IN_MILLIS) {
            System.err.println("WARNING: a scan() snapshot has been held open for " + oldestPinAge
                    + "ms, delaying physical segment-file deletion for whatever it's still referencing");
        }
    }

    private void deleteSegmentFiles(Segment<K> segment) throws IOException {
        deleteSegmentFilesByName(segment.getDataFile().getName());
    }

    private void deleteSegmentFilesByName(String dataFileName) throws IOException {
        String suffix = dataFileName.substring(DATA_FILE_PREFIX.length());
        Files.deleteIfExists(this.dataDir.resolve(dataFileName));
        Files.deleteIfExists(this.dataDir.resolve(INDEX_FILE_PREFIX + suffix));
        Files.deleteIfExists(this.dataDir.resolve(BLOOM_FILTER_FILE_PREFIX + suffix));
    }

    synchronized void compact() throws IOException {
        Version<K> currentVersion = this.version.get();
        List<Segment<K>> selectedSegments = this.compactionStrategy.select(currentVersion.segments());
        this.sharedCompactionLogic(selectedSegments);
    }

    void sharedCompactionLogic(List<Segment<K>> selectedSegments) throws IOException {
        File manifestFile = manifestFile();
        Supplier<SegmentFiles> fileFactory = () -> {
            String uuid = UUID.randomUUID().toString();
            String wipDataFileName = WIP_FILE_PREFIX + DATA_FILE_PREFIX + uuid;
            String indexFileName = INDEX_FILE_PREFIX + uuid;
            String bloomFilterFileName = BLOOM_FILTER_FILE_PREFIX + uuid;

            synchronized (this.manifestLock) {
                try {
                    Manifest.append(wipDataFileName, manifestFile);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            return new SegmentFiles(
                    this.dataDir.resolve(wipDataFileName).toFile(),
                    this.dataDir.resolve(indexFileName).toFile(),
                    this.dataDir.resolve(bloomFilterFileName).toFile()
            );
        };

        selectedSegments.forEach(Segment::pin);

        List<Segment<K>> wipSegments = this.mergeStrategy.merge(selectedSegments, fileFactory, this.indexSampleRate);
        List<Segment<K>> newSegments = new ArrayList<>();
        List<String> newDataFileNames = new ArrayList<>();
        for (Segment<K> wipSegment : wipSegments) {
            String wipDataFileName = wipSegment.getDataFile().getName();
            String dataFileName = wipDataFileName.substring(WIP_FILE_PREFIX.length());
            File dataFile = this.dataDir.resolve(dataFileName).toFile();

            Files.move(wipSegment.getDataFile().toPath(), dataFile.toPath(), StandardCopyOption.ATOMIC_MOVE);

            Segment<K> finalSegment = new Segment<>(dataFile, wipSegment.getLoadedIndex(),
                    wipSegment.getBloomFilter(), wipSegment.getEntryCount(), wipSegment.getMaxEventId());
            finalSegment.setLevel(wipSegment.getLevel());
            finalSegment.setMinKey(wipSegment.getMinKey());
            finalSegment.setMaxKey(wipSegment.getMaxKey());

            newSegments.add(finalSegment);
            newDataFileNames.add(dataFileName);
        }

        selectedSegments.forEach(Segment::unpin);
        this.version.updateAndGet((currentVersion) -> {
            List<Segment<K>> newSegmentList = new ArrayList<>(currentVersion.segments());
            newSegmentList.addAll(newSegments);
            return new Version<>(currentVersion.memTable(), newSegmentList);
        });
        selectedSegments.forEach(Segment::markDeleted);

        synchronized (this.manifestLock) {
            for (String dataFileName : newDataFileNames) {
                Manifest.append(dataFileName, manifestFile);
            }
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
        List<Segment<K>> pinnedSegments = new ArrayList<>();
        Object token = trackOpenScanPin();

        try {
            byte[] encodedLow = this.codec.encodeKey(low);
            byte[] encodedHigh = this.codec.encodeKey(high);
            for(Segment<K> segment : currentVersion.segments()){
                if(segment.isDeleted()) continue;
                if(this.codec.compareEncoded(encodedLow,segment.getMaxKey())>0 || this.codec.compareEncoded(encodedHigh,segment.getMinKey())<0) continue;
                if(!segment.pin()) continue;
                pinnedSegments.add(segment);
                cursors.add(this.ssTable.rangeCursor(segment.getDataFile(),segment.getLoadedIndex(), encodedLow, encodedHigh));
            }

            List<Entry<K, Value>> memTableEntries = currentVersion.memTable().range(low, high);
            cursors.add(this.ssTable.memTableCursor(memTableEntries));

            return new MergeCursor<>(cursors, () -> {
                pinnedSegments.forEach(Segment::unpin);
                untrackOpenScanPin(token);
            });
        } catch (Throwable t) {
            pinnedSegments.forEach(Segment::unpin);
            untrackOpenScanPin(token);
            throw t;
        }
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

    private <T> T withPinnedSegments(List<Segment<K>> candidates, PinnedSegmentsAction<K, T> body) throws IOException {
        List<Segment<K>> pinnedSegments = new ArrayList<>();
        for (Segment<K> segment : candidates) {
            if (segment.isDeleted()) continue;
            if (!segment.pin()) continue;
            pinnedSegments.add(segment);
        }
        try {
            return body.apply(pinnedSegments);
        } finally {
            pinnedSegments.forEach(Segment::unpin);
        }
    }

    private interface PinnedSegmentsAction<K, T> {
        T apply(List<Segment<K>> pinnedSegments) throws IOException;
    }

    Object trackOpenScanPin(){
        Object token = new Object();
        openVersionPins.put(token, System.nanoTime());
        return token;
    }

    void untrackOpenScanPin(Object token){
        openVersionPins.remove(token);
    }

    int openVersionPinCount() {
        return this.openVersionPins.size();
    }

    long oldestOpenPinAgeInMillis(){
        long now = System.nanoTime();
        return openVersionPins.values().stream()
                .mapToLong(startTime -> (now-startTime)/1_000_000 )
                .max()
                .orElse(-1);
    }
}
