package service;

import bloomFilter.BloomFilter;
import constants.FileConstants;
import core.Segment;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CleanUpServiceTest {
    Path dir;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        this.dir = dir;
    }

    @Test
    void cleanUpRefusesSegmentsTheCallerDidNotMarkWithoutUnlinkingAnything() throws IOException {
        Segment<Integer> marked = segmentOnDisk();
        Segment<Integer> unmarked = segmentOnDisk();
        marked.markDeleted();

        assertTrue(marked.isDeleted(), "precondition: the first segment is marked");
        assertFalse(unmarked.isDeleted(), "precondition: the second segment is not");

        CleanUpService<Integer> cleanUpService =
                new CleanUpService<>(() -> 1L);

        assertThrows(IllegalStateException.class,
                () -> cleanUpService.cleanUp(List.of(marked, unmarked)),
                "cleanUp must refuse a segment the caller has not marked deleted");

        assertTrue(marked.getDataFile().exists(),
                "the refusal must happen before anything is unlinked: the properly marked segment "
                        + "in the same batch must be untouched");
        assertTrue(unmarked.getDataFile().exists(), "the unmarked segment must be untouched");
    }

    @Test
    void failedUnlinkStillAttemptsEveryFile() throws IOException {
        Segment<Integer> segment = segmentOnDisk();
        segment.markDeleted();

        Path index = segment.getIndexFile().toPath();
        Files.delete(index);
        Files.createDirectories(index.resolve("blocker"));

        CleanUpService<Integer> cleanUpService =
                new CleanUpService<>(() -> 1L);
        cleanUpService.cleanUp(List.of(segment));

        assertFalse(segment.getDataFile().exists(), "the data file must be deleted");
        assertFalse(segment.getBloomFilterFile().exists(),
                "every file must be attempted, not only those before the one that failed");
        assertTrue(Files.exists(index), "sanity: the blocked file is still there");
    }

    @Test
    void failedUnlinkIsRetriedOnTheNextCallOnceTheCauseClears() throws IOException {
        Segment<Integer> segment = segmentOnDisk();
        segment.markDeleted();

        Path index = segment.getIndexFile().toPath();
        Files.delete(index);
        Path blocker = Files.createDirectories(index.resolve("blocker"));

        CleanUpService<Integer> cleanUpService =
                new CleanUpService<>(() -> 1L);
        cleanUpService.cleanUp(List.of(segment));
        assertTrue(Files.exists(index), "precondition: the first pass cannot delete the blocked file");

        Files.delete(blocker);
        cleanUpService.cleanUp(List.of());

        assertFalse(Files.exists(index),
                "an empty call drains the queue: a segment whose unlink failed must be attempted again, "
                        + "not stranded until the next open");
    }

    @Test
    void aSegmentWhoseUnlinkFailedCanNeverBePinnedAgain() throws IOException {
        Segment<Integer> segment = segmentOnDisk();
        segment.markDeleted();

        Path index = segment.getIndexFile().toPath();
        Files.delete(index);
        Files.createDirectories(index.resolve("blocker"));

        CleanUpService<Integer> cleanUpService =
                new CleanUpService<>(() -> 1L);
        cleanUpService.cleanUp(List.of(segment));
        assertTrue(Files.exists(index), "precondition: the segment is half-deleted");

        assertFalse(segment.pin(),
                "a half-deleted segment must stay unpinnable: a scan that pinned it now would read "
                        + "files that are already gone");

        cleanUpService.cleanUp(List.of());
        assertFalse(segment.pin(), "a retry must not make it pinnable either");
    }

    private Segment<Integer> segmentOnDisk() throws IOException {
        String uuid = UUID.randomUUID().toString();
        File dataFile = this.dir.resolve(FileConstants.DATA_FILE_PREFIX + uuid).toFile();
        File indexFile = this.dir.resolve(FileConstants.INDEX_FILE_PREFIX + uuid).toFile();
        File bloomFilterFile = this.dir.resolve(FileConstants.BLOOM_FILTER_FILE_PREFIX + uuid).toFile();
        Files.createFile(dataFile.toPath());
        Files.createFile(indexFile.toPath());
        Files.createFile(bloomFilterFile.toPath());
        return new Segment<>(dataFile, indexFile, bloomFilterFile,
                new ArrayList<>(), new BloomFilter(1, 0.01), 0, -1);
    }
}
