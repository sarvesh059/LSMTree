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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public class CleanUpService<K extends Comparable<K>> {
    private final Path dataDir;
    private final AtomicReference<Version<K>> version;
    private final Object manifestLock;
    private final Supplier<Long> stalePinAgeSupplier;

    public CleanUpService(Path dataDir, AtomicReference<Version<K>> version, Object manifestLock,
                           Supplier<Long> stalePinAgeSupplier) {
        this.dataDir = dataDir;
        this.version = version;
        this.manifestLock = manifestLock;
        this.stalePinAgeSupplier = stalePinAgeSupplier;
    }

    public void cleanUp() throws IOException {
        List<Segment<K>> purgedSegments = new ArrayList<>();

        for (Segment<K> segment : this.version.get().segments()) {
            if (!segment.isDeleted()) continue;
            if (segment.tryClaimForDeletion()) {
                try {
                    segment.deleteFiles();
                } catch (IOException e) {
                    System.err.println("WARNING: failed to physically delete segment file "
                            + segment.getDataFile().getName() + ": " + e.getMessage()
                            + " -- it will no longer be tracked, but a stray file may remain on disk");
                }
                purgedSegments.add(segment);
            }
        }

        if (!purgedSegments.isEmpty()) {
            this.version.updateAndGet(v -> {
                List<Segment<K>> newList = new ArrayList<>(v.segments());
                newList.removeAll(purgedSegments);
                return new Version<>(v.memTable(), newList);
            });
        }

        synchronized (this.manifestLock) {
            List<String> manifestEntries = new ArrayList<>();
            for (Segment<K> segment : this.version.get().segments()) {
                manifestEntries.add(segment.isDeleted()
                        ? FileConstants.DELETED_FILE_PREFIX + segment.getDataFile().getName()
                        : segment.getDataFile().getName());
            }
            Manifest.rewrite(manifestEntries, manifestFile());
        }

        long oldestPinAge = stalePinAgeSupplier.get();
        if (oldestPinAge > FileConstants.STALE_PIN_WARNING_THRESHOLD_IN_MILLIS) {
            System.err.println("WARNING: a scan() snapshot has been held open for " + oldestPinAge
                    + "ms, delaying physical segment-file deletion for whatever it's still referencing");
        }
    }

    private File manifestFile() {
        return this.dataDir.resolve(FileConstants.MANIFEST_FILE_NAME).toFile();
    }
}
