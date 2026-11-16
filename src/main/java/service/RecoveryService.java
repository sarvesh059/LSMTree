package service;

import lsmtree.Version;
import sstable.SSTable;
import wal.WAL;
import wal.ReplayResult;
import wal.WALReplayStopReason;
import bloomFilter.BloomFilter;
import constants.FileConstants;
import core.DataFileMetaData;
import core.IndexEntry;
import core.Segment;
import core.key.KeyCodec;
import manifest.Manifest;
import memTable.MemTable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

public class RecoveryService<K extends Comparable<K>> {
    private final Path dataDir;
    private final SSTable<K> ssTable;
    private final WAL<K> wal;
    private final int memTableThreshold;
    private final KeyCodec<K> keyCodec;
    private final AtomicReference<Version<K>> version;
    private final Object manifestLock;
    private static final System.Logger LOG = System.getLogger(RecoveryService.class.getName());

    public RecoveryService(Path dataDir, SSTable<K> ssTable, WAL<K> wal, int memTableThreshold,
                            AtomicReference<Version<K>> version, Object manifestLock, KeyCodec<K> keyCodec) {
        this.dataDir = dataDir;
        this.ssTable = ssTable;
        this.wal = wal;
        this.memTableThreshold = memTableThreshold;
        this.version = version;
        this.manifestLock = manifestLock;
        this.keyCodec = keyCodec;
    }

    public long recover() throws IOException {
        File manifestFile = manifestFile();

        Version<K> currentVersion = this.version.get();
        long maxEventId = 0;
        List<Segment<K>> segments = new ArrayList<>(currentVersion.segments());

        LinkedHashSet<String> liveDataFiles = new LinkedHashSet<>();
        LinkedHashSet<String> pendingDeletionDataFiles = new LinkedHashSet<>();
        Map<String, List<String>> filesGrouping = scanDirectory();
        boolean manifestFileExists = manifestFile.exists();
        if(!manifestFileExists && !filesGrouping.isEmpty()) throw new IOException("Manifest file ("+manifestFile.getAbsolutePath()+") does not exist while "+filesGrouping.size()+" segments are present.");

        Manifest.initialiseManifestFile(manifestFile);
        sortManifestEntries(manifestFile, liveDataFiles, pendingDeletionDataFiles);


        for (String dataFileName : liveDataFiles) {
            String indexFileName = FileConstants.INDEX_FILE_PREFIX + dataFileName.substring(FileConstants.DATA_FILE_PREFIX.length());
            String bloomFilterFileName = FileConstants.BLOOM_FILTER_FILE_PREFIX + dataFileName.substring(FileConstants.DATA_FILE_PREFIX.length());
            File dataFile = this.dataDir.resolve(dataFileName).toFile();
            File indexFile = this.dataDir.resolve(indexFileName).toFile();
            File bloomFilterFile = this.dataDir.resolve(bloomFilterFileName).toFile();
            List<String> files = filesGrouping.getOrDefault(dataFileName, List.of());
            boolean dataFileExists = files.contains(dataFileName);
            boolean indexFileExists = files.contains(indexFileName);
            boolean bloomFilterExists = files.contains(bloomFilterFileName);
            if (!dataFileExists || !indexFileExists || !bloomFilterExists) {
                throw new IOException(String.format(
                        "%s is listed live in %s but its files are incomplete "
                                + "(dataFile=%b, indexFile=%b, bloomFilter=%b). Refusing to open rather "
                                + "than serve incomplete reads; remove that entry from the manifest to "
                                + "open without this segment.",
                        dataFileName, manifestFile, dataFileExists, indexFileExists, bloomFilterExists));
            }

            try (RandomAccessFile bloomFilterFileReader = new RandomAccessFile(bloomFilterFile, "r")) {
                List<IndexEntry> index = this.ssTable.loadIndex(indexFile);
                byte[] minKey = this.ssTable.minKey(dataFile, index);
                byte[] maxKey = this.ssTable.maxKey(dataFile, index);
                DataFileMetaData dataFileMetaData = this.ssTable.getMetaData(dataFile);
                Segment<K> segment = new Segment<>(dataFile, indexFile, bloomFilterFile, index,
                        BloomFilter.readFrom(bloomFilterFileReader), dataFileMetaData.entryCount(), dataFileMetaData.maxEventId());
                segment.setMinKey(minKey);
                segment.setMaxKey(maxKey);
                segment.setLevel(dataFileMetaData.level());
                maxEventId = Math.max(maxEventId, segment.getMaxEventId());
                segments.add(segment);
            }
        }

        MemTable<K> newMemTable = new MemTable<>(this.memTableThreshold, this.keyCodec);
        ReplayResult replayResult = this.wal.replay(newMemTable);
        if (replayResult.stopReason() != WALReplayStopReason.CLEAN_EOF) {
            LOG.log(replayResult.stopReason().logLevel(),
                    () -> "Opened " + this.dataDir + " with a damaged WAL: "
                            + replayResult.stopReason() + " after " + replayResult.recordsRecovered()
                            + " records. See the preceding WAL entry for the failing offset.");
        }
        this.version.updateAndGet((_) -> new Version<K>(newMemTable, segments));

        List<String> undeletedEntries = new ArrayList<>();
        for (String dataFileName : pendingDeletionDataFiles) {
            try {
                deleteSegmentFiles(dataFileName);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "failed to delete segment " + dataFileName
                        + " recorded as deleted; keeping its entry for the next open: " + e.getMessage());
                undeletedEntries.add(FileConstants.DELETED_FILE_PREFIX + dataFileName);
            }
        }

        for (String dataFileName : filesGrouping.keySet()) {
            if (liveDataFiles.contains(dataFileName) || pendingDeletionDataFiles.contains(dataFileName)) continue;
            try {
                deleteSegmentFiles(dataFileName);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "failed to delete unaccounted segment "
                        + dataFileName + " left over from a prior crash: " + e.getMessage());
            }
        }

        try {
            synchronized (this.manifestLock) {
                Manifest.rewrite(Stream.concat(liveDataFiles.stream(), undeletedEntries.stream()).toList(), manifestFile);
            }
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "could not compact the manifest at " + manifestFile
                    + "; opening anyway with its uncompacted contents: " + e.getMessage());
        }

        return Math.max(maxEventId, this.wal.getLatestEventId());
    }

    private void sortManifestEntries(
            File manifestFile,
            LinkedHashSet<String> liveDataFiles,
            LinkedHashSet<String> pendingDeletionDataFiles) throws IOException {

        List<String> dataFilesNames = Manifest.readAll(manifestFile);
        for (String fileName : dataFilesNames) {
            if (fileName.startsWith(FileConstants.DELETED_FILE_PREFIX)) {
                String underlyingName = fileName.substring(FileConstants.DELETED_FILE_PREFIX.length());
                liveDataFiles.remove(underlyingName);
                pendingDeletionDataFiles.add(underlyingName);
            } else {
                liveDataFiles.add(fileName);
                pendingDeletionDataFiles.remove(fileName);
            }
        }
    }

    private void deleteSegmentFiles(String dataFileName) throws IOException {
        String suffix = dataFileName.substring(FileConstants.DATA_FILE_PREFIX.length());

        IOException exception = null;

        for (String fileName : List.of(
                FileConstants.WIP_FILE_PREFIX + dataFileName,
                dataFileName,
                FileConstants.INDEX_FILE_PREFIX + suffix,
                FileConstants.BLOOM_FILTER_FILE_PREFIX + suffix
        )) {
            try {
                Files.deleteIfExists(this.dataDir.resolve(fileName));
            } catch (IOException e) {
                if (exception == null) {
                    exception = e;
                } else {
                    exception.addSuppressed(e);
                }
            }
        }

        if (exception != null) {
            throw exception;
        }
    }

    private Map<String, List<String>> scanDirectory() throws IOException {
        Map<String, List<String>> segmentFiles = new HashMap<>();
        try (Stream<Path> entries = Files.list(this.dataDir)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                String fileName = entry.getFileName().toString();
                SegmentFileFactory.parse(fileName).ifPresent(segment ->
                        segmentFiles.computeIfAbsent(segment, _ -> new ArrayList<>()).add(fileName));
            }
        }
        return segmentFiles;
    }

    private File manifestFile() {
        return this.dataDir.resolve(FileConstants.MANIFEST_FILE_NAME).toFile();
    }
}
