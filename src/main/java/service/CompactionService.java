package service;

import lsmtree.Version;
import compaction.CompactionStrategy;
import compaction.MergeStrategy;
import constants.FileConstants;
import core.FileUtils;
import core.Segment;
import core.SegmentFiles;
import manifest.Manifest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AtomicBoolean compactionInProgress = new AtomicBoolean(false);
    private final SegmentFileFactory segmentFileFactory;

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
        this.segmentFileFactory = new SegmentFileFactory(dataDir, manifestLock);
    }

    public void checkAndCompactAsync() {
        List<Segment<K>> live = liveSegments(this.version.get());
        if (this.compactionStrategy.shouldCompact(live) && this.compactionInProgress.compareAndSet(false, true)) {
            List<Segment<K>> selectedSegments = this.compactionStrategy.select(live);
            compactionExecutor.submit(() -> runCompactionAsync(selectedSegments, live));
        }
    }

    public void sharedCompactionLogic(List<Segment<K>> selectedSegments) throws IOException {
        List<Segment<K>> live = liveSegments(this.version.get());
        sharedCompactionLogic(selectedSegments, live);
    }

    void sharedCompactionLogic(List<Segment<K>> selectedSegments, List<Segment<K>> segmentsSnapshot) throws IOException {
        File manifestFile = manifestFile();
        Supplier<SegmentFiles> fileFactory = () -> {
            try {
                return this.segmentFileFactory.create();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };

        List<Segment<K>> pinnedSegments = new ArrayList<>();
        for(Segment<K> segment: selectedSegments){
            if(segment.pin()){
                pinnedSegments.add(segment);
            }else{
                pinnedSegments.forEach(Segment::unpin);
                return;
            }
        }

        List<Segment<K>> wipSegments = this.mergeStrategy.merge(selectedSegments, segmentsSnapshot, fileFactory, this.indexSampleRate);
        List<Segment<K>> newSegments = new ArrayList<>();
        List<String> newDataFileNames = new ArrayList<>();
        for (Segment<K> wipSegment : wipSegments) {
            Segment<K> finalSegment = this.segmentFileFactory.promote(wipSegment);
            newSegments.add(finalSegment);
            newDataFileNames.add(finalSegment.getDataFile().getName());
        }

        FileUtils.fsyncDirectory(this.dataDir);

        selectedSegments.forEach(Segment::unpin);
        if(!tryReplaceSegments(selectedSegments, newSegments)){
            newSegments.forEach(Segment::markDeleted);
            cleanUpService.cleanUp(newSegments);
            return;
        }

        selectedSegments.forEach(Segment::markDeleted);

        synchronized (this.manifestLock) {
            for (String dataFileName : newDataFileNames) {
                Manifest.append(dataFileName, manifestFile);
            }
            for (Segment<K> segment : selectedSegments) {
                Manifest.append(FileConstants.DELETED_FILE_PREFIX + segment.getDataFile().getName(), manifestFile);
            }
        }

        cleanUpService.cleanUp(selectedSegments);
    }

    private void runCompactionAsync(List<Segment<K>> selectedSegments, List<Segment<K>> segmentsSnapshot) {
        try {
            sharedCompactionLogic(selectedSegments, segmentsSnapshot);
        } catch (Throwable _) {

        } finally {
            compactionInProgress.set(false);
            checkAndCompactAsync();
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
            if (!this.compactionInProgress.get()) return;
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

    private boolean tryReplaceSegments(List<Segment<K>> supersededSegments, List<Segment<K>> newSegments){
        Set<Segment<K>> selectedSegmentsSet = Collections.newSetFromMap(new IdentityHashMap<>());
        selectedSegmentsSet.addAll(supersededSegments);
        AtomicBoolean segmentListUnchanged = new AtomicBoolean(true);
        this.version.updateAndGet((currentVersion) -> {
            List<Segment<K>> newSegmentList = new ArrayList<>(currentVersion.segments());
            int initialSize = newSegmentList.size();
            newSegmentList.removeAll(selectedSegmentsSet);
            if(initialSize - selectedSegmentsSet.size() != newSegmentList.size()){
                segmentListUnchanged.set(false);
                return currentVersion;
            }
            newSegmentList.addAll(newSegments);
            return new Version<>(currentVersion.memTable(), newSegmentList);
        });

        return segmentListUnchanged.get();
    }
}
