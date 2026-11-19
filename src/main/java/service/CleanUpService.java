package service;

import constants.FileConstants;
import core.Segment;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class CleanUpService<K extends Comparable<K>> {
    private final Supplier<Long> stalePinAgeSupplier;
    private final Set<Segment<K>> pendingDeletionSet;
    private static final System.Logger LOG = System.getLogger(CleanUpService.class.getName());

    public CleanUpService(Supplier<Long> stalePinAgeSupplier) {
        this.stalePinAgeSupplier = stalePinAgeSupplier;
        this.pendingDeletionSet = ConcurrentHashMap.newKeySet();
    }

    /**
     * Unlinks the files of every segment queued for deletion that is no longer pinned.
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
     * <p>Pass only segments the manifest does not name: inputs retired by a committed
     * compaction, or outputs abandoned before they were recorded. Deleting the files of a segment
     * the manifest still lists makes the next open refuse, because a listed segment must have all
     * of its files. Because such segments are unlisted, nothing is lost if an unlink keeps failing
     * or the process dies first: the scan at the next open deletes every segment file the manifest
     * does not name.
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

        for (Segment<K> segment : pendingDeletionSet) {
            if (segment.tryClaimForDeletion()) {
                try {
                    segment.deleteFiles();
                    pendingDeletionSet.remove(segment);
                } catch (IOException e) {
                    LOG.log(System.Logger.Level.WARNING,"could not delete every file of retired segment "
                            + segment.getDataFile().getName() + ": it stays queued and is retried on the next clean-up pass (the next open's scan reclaims it otherwise)",e);
                }
            }
        }

        long oldestPinAge = stalePinAgeSupplier.get();
        if (oldestPinAge > FileConstants.STALE_PIN_WARNING_THRESHOLD_IN_MILLIS) {
            LOG.log(System.Logger.Level.INFO,"a scan() snapshot has been held open for " + oldestPinAge
                    + "ms, delaying physical segment-file deletion for whatever it's still referencing");
        }
    }
}
