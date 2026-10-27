package service;

import bloomFilter.BloomFilter;
import constants.FileConstants;
import core.Segment;
import core.SegmentFiles;
import manifest.Manifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CleanUpServiceTest {
    Path dir;
    SegmentFileFactory segmentFileFactory;
    Object manifestLock;
    File manifestFile;

    @BeforeEach
    void setUp(@TempDir Path dir){
        this.dir = dir;
        manifestLock = new Object();
        segmentFileFactory = new SegmentFileFactory(dir, manifestLock);
        manifestFile = dir.resolve(FileConstants.MANIFEST_FILE_NAME).toFile();
    }

    @Test
    void cleanUpThatUnlinksNothingLeavesManifestUnchanged() throws IOException {
        SegmentFiles inFlightSegment = this.segmentFileFactory.create();
        String inFlightName = inFlightSegment.dataFile().getName();

        String unknownLiveName = FileConstants.DATA_FILE_PREFIX + UUID.randomUUID();
        Manifest.append(unknownLiveName, this.manifestFile);

        List<String> before = Manifest.readAll(this.manifestFile);
        assertTrue(before.contains(inFlightName),
                () -> "precondition: create() must have recorded the in-flight segment: " + before);
        assertTrue(before.contains(unknownLiveName),
                () -> "precondition: the seeded live entry must have been recorded: " + before);

        CleanUpService<Integer> cleanUpService =
                new CleanUpService<>(this.dir, this.manifestLock, () -> 1L);
        cleanUpService.cleanUp(new ArrayList<>());

        List<String> after = Manifest.readAll(this.manifestFile);
        assertEquals(before, after,
                "a cleanUp that unlinked nothing must leave the manifest unchanged; entries absent "
                        + "from the in-memory Version must not be erased");
    }

    @Test
    void rewritingCollapsesDuplicateManifestEntries() throws IOException {
        String name = FileConstants.DATA_FILE_PREFIX + UUID.randomUUID().toString();
        Manifest.append(name, this.manifestFile);
        Manifest.append(name, this.manifestFile);

        List<String> before = Manifest.readAll(this.manifestFile);
        assertEquals(2L, before.stream().filter(name::equals).count(),
                () -> "precondition: the manifest must start with two identical entries: " + before);

        CleanUpService<Integer> cleanUpService =
                new CleanUpService<>(this.dir, this.manifestLock, () -> 1L);
        cleanUpService.cleanUp(new ArrayList<>());

        List<String> after = Manifest.readAll(this.manifestFile);
        assertEquals(1L, after.stream().filter(name::equals).count(),
                () -> "a rewrite must collapse duplicate entries to one: " + after);
    }

    @Test
    void cleanUpRefusesSegmentsTheCallerDidNotMarkWithoutUnlinkingAnything() throws IOException {
        Segment<Integer> marked = segmentOnDisk();
        Segment<Integer> unmarked = segmentOnDisk();
        marked.markDeleted();

        assertTrue(marked.isDeleted(), "precondition: the first segment is marked");
        assertFalse(unmarked.isDeleted(), "precondition: the second segment is not");

        CleanUpService<Integer> cleanUpService =
                new CleanUpService<>(this.dir, this.manifestLock, () -> 1L);

        assertThrows(IllegalStateException.class,
                () -> cleanUpService.cleanUp(List.of(marked, unmarked)),
                "cleanUp must refuse a segment the caller has not marked deleted");

        assertTrue(marked.getDataFile().exists(),
                "the refusal must happen before anything is unlinked: the properly marked segment "
                        + "in the same batch must be untouched");
        assertTrue(unmarked.getDataFile().exists(), "the unmarked segment must be untouched");
        assertEquals(List.of(marked.getDataFile().getName(), unmarked.getDataFile().getName()),
                Manifest.readAll(this.manifestFile),
                "a refused cleanUp must not touch the manifest");
    }

    private Segment<Integer> segmentOnDisk() throws IOException {
        String uuid = UUID.randomUUID().toString();
        File dataFile = this.dir.resolve(FileConstants.DATA_FILE_PREFIX + uuid).toFile();
        File indexFile = this.dir.resolve(FileConstants.INDEX_FILE_PREFIX + uuid).toFile();
        File bloomFilterFile = this.dir.resolve(FileConstants.BLOOM_FILTER_FILE_PREFIX + uuid).toFile();
        Files.createFile(dataFile.toPath());
        Files.createFile(indexFile.toPath());
        Files.createFile(bloomFilterFile.toPath());
        Manifest.append(dataFile.getName(), this.manifestFile);
        return new Segment<>(dataFile, indexFile, bloomFilterFile,
                new ArrayList<>(), new BloomFilter(1, 0.01), 0, -1);
    }
}
