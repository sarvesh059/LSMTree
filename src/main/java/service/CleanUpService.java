package service;

import constants.FileConstants;
import core.Segment;
import manifest.Manifest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class CleanUpService<K extends Comparable<K>> {
    private final Path dataDir;
    private final Object manifestLock;
    private final Supplier<Long> stalePinAgeSupplier;
    private final Set<Segment<K>> pendingDeletionSet;
    private static final System.Logger LOG = System.getLogger(CleanUpService.class.getName());

    public CleanUpService(Path dataDir, Object manifestLock,
                          Supplier<Long> stalePinAgeSupplier) {
        this.dataDir = dataDir;
        this.manifestLock = manifestLock;
        this.stalePinAgeSupplier = stalePinAgeSupplier;
        this.pendingDeletionSet = ConcurrentHashMap.newKeySet();
    }

    /**
     * Unlinks the files of every segment queued for deletion that is no longer pinned, and drops
     * the corresponding manifest entries.
     *
     * <p>Callers mark their own segments via {@link Segment#markDeleted()} before calling this,
     * and must do so only once the segment has left the current {@code Version}. This method
     * deliberately does <em>not</em> mark them itself: marking is irreversible, and a caller that
     * accidentally passed a live set would then retire it silently. Validating instead makes that
     * mistake throw, and throwing up front rather than inside the loop below keeps it
     * all-or-nothing — otherwise the first few segments are already unlinked before the offending
     * one is reached.
     *
     * <p>Deletion is best-effort and retried: a segment still pinned by an open {@code scan()}, or
     * whose unlink fails, stays queued and is attempted again on the next call. An empty list
     * therefore means "drain the queue".
     *
     * <p>A name leaves the manifest only after its files are gone: the rewrite derives from the
     * previous manifest contents and removes only the names unlinked in this pass, so entries this
     * service knows nothing about are carried forward rather than erased.
     *
     * @throws IllegalStateException if any segment passed in has not been marked deleted
     */
    public void cleanUp(List<Segment<K>> segments) throws IOException {
        for (Segment<K> segment : segments) {
            if (!segment.isDeleted()) {
                throw new IllegalStateException("refusing to clean up "
                        + segment.getDataFile().getName() + ": it has not been marked deleted, so it "
                        + "may still be live. Callers must mark their own segments once those "
                        + "segments have left the current Version.");
            }
        }
        pendingDeletionSet.addAll(segments);

        List<String> deletedEntries = new ArrayList<>();
        for (Segment<K> segment : pendingDeletionSet) {
            if (segment.tryClaimForDeletion()) {
                try {
                    segment.deleteFiles();
                    deletedEntries.add(segment.getDataFile().getName());
                    pendingDeletionSet.remove(segment);
                } catch (IOException e) {
                    LOG.log(System.Logger.Level.WARNING,"failed to physically delete segment file "
                            + segment.getDataFile().getName() + ": " + e.getMessage());
                }
            }
        }


        synchronized (this.manifestLock) {
            Set<String> manifestEntries = new LinkedHashSet<>(Manifest.readAll(manifestFile()));
            for (String fileName : deletedEntries) {
                manifestEntries.remove(fileName);
                manifestEntries.remove(FileConstants.WIP_FILE_PREFIX+fileName);
                manifestEntries.remove(FileConstants.DELETED_FILE_PREFIX+fileName);
            }
            Manifest.rewrite(new ArrayList<>(manifestEntries), manifestFile());
        }

        long oldestPinAge = stalePinAgeSupplier.get();
        if (oldestPinAge > FileConstants.STALE_PIN_WARNING_THRESHOLD_IN_MILLIS) {
            LOG.log(System.Logger.Level.INFO,"a scan() snapshot has been held open for " + oldestPinAge
                    + "ms, delaying physical segment-file deletion for whatever it's still referencing");
        }
    }

    private File manifestFile() {
        return this.dataDir.resolve(FileConstants.MANIFEST_FILE_NAME).toFile();
    }
}
