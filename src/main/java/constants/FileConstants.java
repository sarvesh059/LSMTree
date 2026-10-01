package constants;

public final class FileConstants {
    public static final String WAL_FILE_NAME = "wal.log";
    public static final String MANIFEST_FILE_NAME = "manifest.log";
    public static final String DATA_FILE_PREFIX = "dataFile-";
    public static final String INDEX_FILE_PREFIX = "indexFile-";
    public static final String WIP_FILE_PREFIX = "WIP-";
    public static final String DELETED_FILE_PREFIX = "DELETED-";
    public static final String BLOOM_FILTER_FILE_PREFIX = "bloomFilter-";
    public static final long STALE_PIN_WARNING_THRESHOLD_IN_MILLIS = 30_000;

    private FileConstants() {
    }
}
