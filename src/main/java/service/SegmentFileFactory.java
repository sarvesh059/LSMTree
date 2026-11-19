package service;

import constants.FileConstants;
import core.SegmentFiles;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

public final class SegmentFileFactory {
    private final Path dataDir;

    public SegmentFileFactory(Path dataDir) {
        this.dataDir = dataDir;
    }

    public SegmentFiles create() {
        String uuid = UUID.randomUUID().toString();
        String dataFileName = FileConstants.DATA_FILE_PREFIX + uuid;
        String indexFileName = FileConstants.INDEX_FILE_PREFIX + uuid;
        String bloomFilterFileName = FileConstants.BLOOM_FILTER_FILE_PREFIX + uuid;

        return new SegmentFiles(
                this.dataDir.resolve(dataFileName).toFile(),
                this.dataDir.resolve(indexFileName).toFile(),
                this.dataDir.resolve(bloomFilterFileName).toFile());
    }

    public static Optional<String> parse(String fileName){
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
