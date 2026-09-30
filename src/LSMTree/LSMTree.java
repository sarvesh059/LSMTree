package LSMTree;

import RBT.Entry;
import SSTable.SSTable;
import WAL.WAL;
import compaction.CompactionStrategy;
import compaction.MergeStrategy;
import compaction.TombStoneRetentionPolicyImpl.DropAllTombStoneRetentionPolicy;
import constants.FileConstants;
import core.*;
import core.key.KeyCodec;
import cursor.EntrySource;
import cursor.MergeCursor;
import cursor.TombStoneRetentionPolicy;
import manifest.Manifest;
import memTable.MemTable;
import service.CleanUpService;
import service.CompactionService;
import service.RecoveryService;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


public class LSMTree<K extends Comparable<K>> implements Closeable {
    private final KeyCodec<K> codec;
    private final Path dataDir;
    private final WAL<K> wal;
    private final SSTable<K> ssTable;
    private final int memTableThreshold;
    private final int indexSampleRate;
    private final AtomicReference<Version<K>> version;
    private final AtomicLong eventCounter;
    private long getCallCount = 0;
    private long segmentsConsultedCount = 0;
    private final Object manifestLock = new Object();
    private final Map<Object, Long> openVersionPins = new ConcurrentHashMap<>();
    private final CompactionService<K> compactionService;
    private final CleanUpService<K> cleanUpService;
    private final RecoveryService<K> recoveryService;
    private final TombStoneRetentionPolicy<K> tombStoneRetentionPolicy;

    public LSMTree(KeyCodec<K> codec, Path dataDir, int indexSampleRate, int memTableThreshold, CompactionStrategy<K> compactionStrategy, MergeStrategy<K> mergeStrategy) throws IOException {
        Files.createDirectories(dataDir);
        this.codec = codec;
        this.dataDir = dataDir;
        this.memTableThreshold = memTableThreshold;
        this.indexSampleRate = indexSampleRate;
        this.wal = new WAL<>(this.codec, dataDir.resolve(FileConstants.WAL_FILE_NAME).toFile());
        this.ssTable = new SSTable<>(codec);
        this.version = new AtomicReference<>(new Version<K>(new MemTable<>(memTableThreshold), new ArrayList<>()));
        this.eventCounter = new AtomicLong(0);

        this.cleanUpService = new CleanUpService<>(dataDir, this.version, this.manifestLock, this::oldestOpenPinAgeInMillis);
        this.compactionService = new CompactionService<>(dataDir, indexSampleRate, compactionStrategy, mergeStrategy,
                this.version, this.manifestLock, this.cleanUpService);
        this.recoveryService = new RecoveryService<K>(dataDir, this.ssTable, this.wal, memTableThreshold,
                this.version, this.manifestLock);

        long maxEventId = this.recoveryService.recover();
        this.eventCounter.set(maxEventId);
        this.compactionService.checkAndCompactAsync();
        this.tombStoneRetentionPolicy = new DropAllTombStoneRetentionPolicy<>();
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
        String dataFileName = FileConstants.DATA_FILE_PREFIX + dataFileId;
        String indexFileName = FileConstants.INDEX_FILE_PREFIX + dataFileId;
        String bloomFilterFileName = FileConstants.BLOOM_FILTER_FILE_PREFIX + dataFileId;

        File dataFile = this.dataDir.resolve(dataFileName).toFile();
        File indexFile = this.dataDir.resolve(indexFileName).toFile();
        File bloomFilterFile = this.dataDir.resolve(bloomFilterFileName).toFile();

        Segment<K> segment = this.ssTable.write(memTableEntries, dataFile, indexFile, bloomFilterFile, this.indexSampleRate, 0);
        segment.setMinKey(this.codec.encodeKey(minKey));
        segment.setMaxKey(this.codec.encodeKey(maxKey));

        synchronized (this.manifestLock) {
            Manifest.append(dataFileName, manifestFile());
            this.version.updateAndGet(current -> {
                List<Segment<K>> newList = new ArrayList<>(current.segments());
                newList.add(segment);
                return new Version<>(new MemTable<>(this.memTableThreshold), newList);
            });
        }

        this.wal.reset();

        this.compactionService.checkAndCompactAsync();
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
                if (segment.keyDefinitelyNotInSegment(encodedKey, this.codec)) continue;
                this.segmentsConsultedCount++;
                Value segmentValue = this.ssTable.get(key, segment.getDataFile(), segment.getLoadedIndex());
                if (segmentValue != null) {
                    if (segment.getLevel() == 0) {
                        if (segmentValue.getId() > maxEventId) {
                            maxEventId = segmentValue.getId();
                            resultValue = segmentValue;
                        }
                    } else {
                        return resultValue != null ? resultValue : segmentValue;
                    }
                }
            }
            return resultValue;
        });
    }

    synchronized void compact() throws IOException {
        this.compactionService.compact();
    }

    void sharedCompactionLogic(List<Segment<K>> selectedSegments) throws IOException {
        this.compactionService.sharedCompactionLogic(selectedSegments);
    }

    EntrySource<K> scan(K low, K high) throws IOException {
        List<EntrySource<K>> cursors = new ArrayList<>();
        Version<K> currentVersion = this.version.get();
        List<Segment<K>> pinnedSegments = new ArrayList<>();
        Object token = trackOpenScanPin();

        try {
            byte[] encodedLow = this.codec.encodeKey(low);
            byte[] encodedHigh = this.codec.encodeKey(high);
            for (Segment<K> segment : currentVersion.segments()) {
                if (segment.isDeleted()) continue;
                if (this.codec.compareEncoded(encodedLow, segment.getMaxKey()) > 0 || this.codec.compareEncoded(encodedHigh, segment.getMinKey()) < 0)
                    continue;
                if (!segment.pin()) continue;
                pinnedSegments.add(segment);
                cursors.add(this.ssTable.rangeCursor(segment.getDataFile(), segment.getLoadedIndex(), encodedLow, encodedHigh));
            }

            List<Entry<K, Value>> memTableEntries = currentVersion.memTable().range(low, high);
            cursors.add(this.ssTable.memTableCursor(memTableEntries));

            return new MergeCursor<>(cursors, this.tombStoneRetentionPolicy, () -> {
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
        this.compactionService.shutdown();
    }

    public void awaitCompaction() throws InterruptedException {
        this.compactionService.awaitCompaction();
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
        return this.dataDir.resolve(FileConstants.WAL_FILE_NAME).toFile();
    }

    File manifestFile() {
        return this.dataDir.resolve(FileConstants.MANIFEST_FILE_NAME).toFile();
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

    Object trackOpenScanPin() {
        Object token = new Object();
        openVersionPins.put(token, System.nanoTime());
        return token;
    }

    void untrackOpenScanPin(Object token) {
        openVersionPins.remove(token);
    }

    int openVersionPinCount() {
        return this.openVersionPins.size();
    }

    long oldestOpenPinAgeInMillis() {
        long now = System.nanoTime();
        return openVersionPins.values().stream()
                .mapToLong(startTime -> (now - startTime) / 1_000_000)
                .max()
                .orElse(-1);
    }
}
