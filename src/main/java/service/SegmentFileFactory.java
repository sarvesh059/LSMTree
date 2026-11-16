package service;

import constants.FileConstants;
import core.Segment;
import core.SegmentFiles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

public final class SegmentFileFactory {
    private final Path dataDir;

    public SegmentFileFactory(Path dataDir) {
        this.dataDir = dataDir;
    }

    public SegmentFiles create() {
        String uuid = UUID.randomUUID().toString();
        String wipDataFileName = FileConstants.WIP_FILE_PREFIX + FileConstants.DATA_FILE_PREFIX + uuid;
        String indexFileName = FileConstants.INDEX_FILE_PREFIX + uuid;
        String bloomFilterFileName = FileConstants.BLOOM_FILTER_FILE_PREFIX + uuid;

        return new SegmentFiles(
                this.dataDir.resolve(wipDataFileName).toFile(),
                this.dataDir.resolve(indexFileName).toFile(),
                this.dataDir.resolve(bloomFilterFileName).toFile());
    }

    public <K extends Comparable<K>> Segment<K> promote(Segment<K> wipSegment) throws IOException {
        String wipDataFileName = wipSegment.getDataFile().getName();
        String dataFileName = wipDataFileName.substring(FileConstants.WIP_FILE_PREFIX.length());
        File dataFile = this.dataDir.resolve(dataFileName).toFile();

        Files.move(wipSegment.getDataFile().toPath(), dataFile.toPath(), StandardCopyOption.ATOMIC_MOVE);

        Segment<K> finalSegment = new Segment<>(dataFile, wipSegment.getIndexFile(), wipSegment.getBloomFilterFile(),
                wipSegment.getLoadedIndex(), wipSegment.getBloomFilter(), wipSegment.getEntryCount(),
                wipSegment.getMaxEventId());
        finalSegment.setLevel(wipSegment.getLevel());
        finalSegment.setMinKey(wipSegment.getMinKey());
        finalSegment.setMaxKey(wipSegment.getMaxKey());
        return finalSegment;
    }

    public static Optional<String> parse(String fileName){
        if(fileName.startsWith(FileConstants.WIP_FILE_PREFIX+FileConstants.DATA_FILE_PREFIX)){
            fileName = fileName.substring(FileConstants.WIP_FILE_PREFIX.length());
        }

        String[] prefixes = {
                FileConstants.DATA_FILE_PREFIX,
                FileConstants.INDEX_FILE_PREFIX,
                FileConstants.BLOOM_FILTER_FILE_PREFIX
        };

        for (String prefix : prefixes) {
            if (fileName.startsWith(prefix)) {
                try {
                    UUID uuid = UUID.fromString(fileName.substring(prefix.length()));
                    if(uuid.toString().equals(fileName.substring(prefix.length()))) return Optional.of(FileConstants.DATA_FILE_PREFIX+uuid);
                    return Optional.empty();
                } catch (IllegalArgumentException e) {
                    return Optional.empty();
                }
            }
        }

        return Optional.empty();
    }
}
