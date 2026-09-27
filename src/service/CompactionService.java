package service;

import LSMTree.Version;
import compaction.CompactionStrategy;
import compaction.MergeStrategy;
import constants.FileConstants;
import core.Segment;
import core.SegmentFiles;
import manifest.Manifest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class CompactionService<K extends Comparable<K>> {
    private final Path dataDir;
    private final int indexSampleRate;
    private final CompactionStrategy<K> compactionStrategy;
    private final MergeStrategy<K> mergeStrategy;
    private final AtomicReference<Version<K>> version;
    private final Object manifestLock;
    private final CleanUpService<K> cleanUpService;
    private final ExecutorService compactionExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean compactionInProgress = false;

    public CompactionService(Path dataDir, int indexSampleRate, CompactionStrategy<K> compactionStrategy,
                              MergeStrategy<K> mergeStrategy, AtomicReference<Version<K>> version,
                              Object manifestLock, CleanUpService<K> cleanUpService) {
        this.dataDir = dataDir;
        this.indexSampleRate = indexSampleRate;
        this.compactionStrategy = compactionStrategy;
        this.mergeStrategy = mergeStrategy;
        this.version = version;
        this.manifestLock = manifestLock;
        this.cleanUpService = cleanUpService;
    }

    public synchronized void compact() throws IOException {
        List<Segment<K>> live = liveSegments(this.version.get());
        List<Segment<K>> selectedSegments = this.compactionStrategy.select(live);
        sharedCompactionLogic(selectedSegments);
    }

    public void checkAndCompactAsync() {
        List<Segment<K>> live = liveSegments(this.version.get());
        if (this.compactionStrategy.shouldCompact(live) && !compactionInProgress) {
            List<Segment<K>> selectedSegments = this.compactionStrategy.select(live);
            this.compactionInProgress = true;
            compactionExecutor.submit(() -> runCompactionAsync(selectedSegments));
        }
    }

    public void sharedCompactionLogic(List<Segment<K>> selectedSegments) throws IOException {
        File manifestFile = manifestFile();
        Supplier<SegmentFiles> fileFactory = () -> {
            String uuid = UUID.randomUUID().toString();
            String wipDataFileName = FileConstants.WIP_FILE_PREFIX + FileConstants.DATA_FILE_PREFIX + uuid;
            String indexFileName = FileConstants.INDEX_FILE_PREFIX + uuid;
            String bloomFilterFileName = FileConstants.BLOOM_FILTER_FILE_PREFIX + uuid;

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
            String dataFileName = wipDataFileName.substring(FileConstants.WIP_FILE_PREFIX.length());
            File dataFile = this.dataDir.resolve(dataFileName).toFile();

            Files.move(wipSegment.getDataFile().toPath(), dataFile.toPath(), StandardCopyOption.ATOMIC_MOVE);

            Segment<K> finalSegment = new Segment<>(dataFile, wipSegment.getIndexFile(), wipSegment.getBloomFilterFile(),
                    wipSegment.getLoadedIndex(), wipSegment.getBloomFilter(), wipSegment.getEntryCount(), wipSegment.getMaxEventId());
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
                Manifest.append(FileConstants.DELETED_FILE_PREFIX + segment.getDataFile().getName(), manifestFile);
            }
        }

        cleanUpService.cleanUp();
    }

    private void runCompactionAsync(List<Segment<K>> selectedSegments) {
        try {
            sharedCompactionLogic(selectedSegments);
        } catch (Throwable _) {

        } finally {
            synchronized (this) {
                this.compactionInProgress = false;
                List<Segment<K>> live = liveSegments(this.version.get());
                if (this.compactionStrategy.shouldCompact(live)) {
                    List<Segment<K>> nextSelectedSegments = this.compactionStrategy.select(live);
                    this.compactionInProgress = true;
                    compactionExecutor.submit(() -> runCompactionAsync(nextSelectedSegments));
                }
            }
        }
    }

    private List<Segment<K>> liveSegments(Version<K> version) {
        return version.segments().stream().filter(s -> !s.isDeleted()).toList();
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

    public void shutdown() {
        this.compactionExecutor.shutdown();
        try {
            this.compactionExecutor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private File manifestFile() {
        return this.dataDir.resolve(FileConstants.MANIFEST_FILE_NAME).toFile();
    }
}
