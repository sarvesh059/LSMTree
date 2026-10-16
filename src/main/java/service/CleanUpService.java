package service;

import LSMTree.Version;
import constants.FileConstants;
import core.Segment;
import manifest.Manifest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class CleanUpService<K extends Comparable<K>> {
    private final Path dataDir;
    private final AtomicReference<Version<K>> version;
    private final Object manifestLock;
    private final Supplier<Long> stalePinAgeSupplier;
    private final Set<Segment<K>> pendingDeletionSet;
    private static final System.Logger LOG = System.getLogger(CleanUpService.class.getName());

    public CleanUpService(Path dataDir, AtomicReference<Version<K>> version, Object manifestLock,
                           Supplier<Long> stalePinAgeSupplier) {
        this.dataDir = dataDir;
        this.version = version;
        this.manifestLock = manifestLock;
        this.stalePinAgeSupplier = stalePinAgeSupplier;
        this.pendingDeletionSet = ConcurrentHashMap.newKeySet();
    }

    public void cleanUp(List<Segment<K>> segments) throws IOException {
        pendingDeletionSet.addAll(segments);

        for (Segment<K> segment : pendingDeletionSet) {
            if (segment.tryClaimForDeletion()) {
                try {
                    segment.deleteFiles();
                    pendingDeletionSet.remove(segment);
                } catch (IOException e) {
                    LOG.log(System.Logger.Level.ERROR,"WARNING: failed to physically delete segment file "
                            + segment.getDataFile().getName() + ": " + e.getMessage());
                }
            }
        }


        synchronized (this.manifestLock) {
            List<String> manifestEntries = new ArrayList<>();
            for (Segment<K> segment : Stream.concat(
                    this.version.get().segments().stream(),
                    pendingDeletionSet.stream()
            ).toList()) {
                manifestEntries.add(segment.isDeleted()
                        ? FileConstants.DELETED_FILE_PREFIX + segment.getDataFile().getName()
                        : segment.getDataFile().getName());
            }
            Manifest.rewrite(manifestEntries, manifestFile());
        }

        long oldestPinAge = stalePinAgeSupplier.get();
        if (oldestPinAge > FileConstants.STALE_PIN_WARNING_THRESHOLD_IN_MILLIS) {
            LOG.log(System.Logger.Level.ERROR,"WARNING: a scan() snapshot has been held open for " + oldestPinAge
                    + "ms, delaying physical segment-file deletion for whatever it's still referencing");
        }
    }

    private File manifestFile() {
        return this.dataDir.resolve(FileConstants.MANIFEST_FILE_NAME).toFile();
    }
}
