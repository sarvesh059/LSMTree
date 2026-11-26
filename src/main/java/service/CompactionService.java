package service;

import constants.SegmentAction;
import core.ManagedFileSupplier;
import lsmtree.Version;
import compaction.CompactionStrategy;
import compaction.MergeStrategy;
import core.FileUtils;
import core.Segment;
import manifest.Manifest;
import manifest.ManifestPayload;
import manifest.SegmentChangeLog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    private final Manifest manifest;

    public CompactionService(Path dataDir, int indexSampleRate, CompactionStrategy<K> compactionStrategy,
                              MergeStrategy<K> mergeStrategy, AtomicReference<Version<K>> version,
                              Object manifestLock, CleanUpService<K> cleanUpService, Manifest manifest) {
        this.dataDir = dataDir;
        this.indexSampleRate = indexSampleRate;
        this.compactionStrategy = compactionStrategy;
        this.mergeStrategy = mergeStrategy;
        this.version = version;
        this.manifestLock = manifestLock;
        this.cleanUpService = cleanUpService;
        this.segmentFileFactory = new SegmentFileFactory(dataDir);
        this.manifest = manifest;
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

        List<Segment<K>> pinnedSegments = new ArrayList<>();
        for(Segment<K> segment: selectedSegments){
            if(segment.pin()){
                pinnedSegments.add(segment);
            }else{
                pinnedSegments.forEach(Segment::unpin);
                return;
            }
        }

        List<Segment<K>> newSegments = new ArrayList<>();
        try(ManagedFileSupplier fileFactory = new ManagedFileSupplier(segmentFileFactory::create)){
            newSegments.addAll(this.mergeStrategy.merge(selectedSegments, segmentsSnapshot, fileFactory, this.indexSampleRate));
            FileUtils.fsyncDirectory(this.dataDir);
            fileFactory.commit();
        }finally {
            selectedSegments.forEach(Segment::unpin);
        }


        // Install before recording, not the reverse: tryReplaceSegments can fail validation, and
        // recording first would then leave the manifest asserting a change memory never made.
        // A crash in the gap only rolls the compaction back.
        synchronized (this.manifestLock) {
            if(!tryReplaceSegments(selectedSegments, newSegments)){
                newSegments.forEach(Segment::markDeleted);
                cleanUpService.cleanUp(newSegments);
                return;
            }
            List<SegmentChangeLog> segmentChangeLogs = new ArrayList<>(newSegments.stream().map(kSegment -> new SegmentChangeLog(kSegment.getDataFile().getName(), SegmentAction.ADDED)).toList());
            segmentChangeLogs.addAll(selectedSegments.stream().map(kSegment -> new SegmentChangeLog(kSegment.getDataFile().getName(), SegmentAction.REMOVED)).toList());
            this.manifest.append(new ManifestPayload(segmentChangeLogs));
        }

        selectedSegments.forEach(Segment::markDeleted);

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

    private boolean tryReplaceSegments(List<Segment<K>> supersededSegments, List<Segment<K>> newSegments){
        Set<Segment<K>> supersededSegmentsSet = Collections.newSetFromMap(new IdentityHashMap<>());
        supersededSegmentsSet.addAll(supersededSegments);
        AtomicBoolean segmentListUnchanged = new AtomicBoolean(true);
        this.version.updateAndGet((currentVersion) -> {
            List<Segment<K>> currentVersionSegmentList = new ArrayList<>(currentVersion.segments());
            int initialSize = currentVersionSegmentList.size();
            currentVersionSegmentList.removeAll(supersededSegmentsSet);
            if(initialSize - supersededSegmentsSet.size() != currentVersionSegmentList.size()){
                segmentListUnchanged.set(false);
                return currentVersion;
            }
            currentVersionSegmentList.addAll(newSegments);
            return new Version<>(currentVersion.memTable(), currentVersionSegmentList);
        });

        return segmentListUnchanged.get();
    }
}
