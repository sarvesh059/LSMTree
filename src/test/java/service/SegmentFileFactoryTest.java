package service;

import constants.FileConstants;
import core.SegmentFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SegmentFileFactoryTest {
    @TempDir
    Path dir;

    private static final String UUID_TEXT = "3c69c80d-d884-4f23-b4eb-5073d57df6f9";
    private static final String UUID_TEXT_UPPER = "3C69C80D-D884-4F23-B4EB-5073D57DF6F9";

    @Test
    void createNamesFilesWithoutWritingAnything() throws IOException {
        SegmentFileFactory factory = new SegmentFileFactory(dir);

        SegmentFiles files = factory.create();

        assertFalse(Files.exists(dir.resolve(FileConstants.MANIFEST_FILE_NAME)),
                "create() must not record the in-flight segment; the scan at open finds leftovers instead");
        assertFalse(files.dataFile().exists(), "create() only names files; the write creates them");
        assertFalse(files.indexFile().exists(), "create() only names files; the write creates them");
        assertFalse(files.bloomFilterFile().exists(), "create() only names files; the write creates them");
    }

    @ParameterizedTest(name="{0}")
    @ValueSource(strings = {
            FileConstants.DATA_FILE_PREFIX+UUID_TEXT,
            FileConstants.INDEX_FILE_PREFIX+UUID_TEXT,
            FileConstants.BLOOM_FILTER_FILE_PREFIX+UUID_TEXT
    })
    void parserCorrectlyParseFileName(String fileName){
    assertEquals(Optional.of(FileConstants.DATA_FILE_PREFIX+UUID_TEXT), SegmentFileFactory.parse(fileName),
            "every file of a segment must map to the segment's identity, the name the manifest uses");
    }

    @ParameterizedTest(name="{0}")
    @ValueSource(strings={
            FileConstants.DATA_FILE_PREFIX+"not-a-uuid",
            FileConstants.DATA_FILE_PREFIX+"1-1-1-1-1",
            FileConstants.INDEX_FILE_PREFIX+UUID_TEXT_UPPER,
            FileConstants.DATA_FILE_PREFIX,
            FileConstants.DATA_FILE_PREFIX+UUID_TEXT+".tmp",
            "notes.txt",
            FileConstants.MANIFEST_FILE_NAME,
            "backup-"+FileConstants.DATA_FILE_PREFIX+UUID_TEXT
    })
    void malformedOrForeignNamesAreRejected(String fileName){
        assertEquals(Optional.empty(), SegmentFileFactory.parse(fileName),
                "a name that is not exactly on we build is foreign, and the scan must skip it");
    }

    @Test
    void everyNameCreateBuildsParsesToTheSameSegment() throws IOException{
        SegmentFiles files = new SegmentFileFactory(dir).create();
        Optional<String> segment = SegmentFileFactory.parse(files.dataFile().getName());

        assertTrue(segment.isPresent(),"the in-flight data name create() builds must parse");
        assertEquals(segment, SegmentFileFactory.parse(files.indexFile().getName()), "the index name must parse to the same segment as the data name");
        assertEquals(segment, SegmentFileFactory.parse(files.bloomFilterFile().getName()), "the bloom filter name must parse to the same segment as the data name");
    }
}
