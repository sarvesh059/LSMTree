package service;

import LSMTree.Version;
import SSTable.SSTable;
import WAL.WAL;
import bloomFilter.BloomFilter;
import constants.FileConstants;
import core.DataFileMetaData;
import core.IndexEntry;
import core.Segment;
import manifest.Manifest;
import memTable.MemTable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class RecoveryService<K extends Comparable<K>> {
    private final Path dataDir;
    private final SSTable<K> ssTable;
    private final WAL<K> wal;
    private final int memTableThreshold;
    private final AtomicReference<Version<K>> version;
    private final Object manifestLock;

    public RecoveryService(Path dataDir, SSTable<K> ssTable, WAL<K> wal, int memTableThreshold,
                            AtomicReference<Version<K>> version, Object manifestLock) {
        this.dataDir = dataDir;
        this.ssTable = ssTable;
        this.wal = wal;
        this.memTableThreshold = memTableThreshold;
        this.version = version;
        this.manifestLock = manifestLock;
    }

    public long recover() throws IOException {
        File manifestFile = manifestFile();

        Version<K> currentVersion = this.version.get();
        long maxEventId = 0;
        List<Segment<K>> segments = new ArrayList<>(currentVersion.segments());

        LinkedHashSet<String> liveDataFiles = new LinkedHashSet<>();
        LinkedHashSet<String> pendingDeletionDataFiles = new LinkedHashSet<>();
        if(manifestFile.exists()){
            List<String> dataFilesNames = Manifest.readAll(manifestFile);
            for (String fileName : dataFilesNames) {
                if (fileName.startsWith(FileConstants.WIP_FILE_PREFIX)) continue;
                if (fileName.startsWith(FileConstants.DELETED_FILE_PREFIX)) {
                    String underlyingName = fileName.substring(FileConstants.DELETED_FILE_PREFIX.length());
                    liveDataFiles.remove(underlyingName);
                    pendingDeletionDataFiles.add(underlyingName);
                } else {
                    liveDataFiles.add(fileName);
                    pendingDeletionDataFiles.remove(fileName);
                }
            }

            for (String dataFileName : liveDataFiles) {
                String indexFileName = FileConstants.INDEX_FILE_PREFIX + dataFileName.substring(FileConstants.DATA_FILE_PREFIX.length());
                String bloomFilterFileName = FileConstants.BLOOM_FILTER_FILE_PREFIX + dataFileName.substring(FileConstants.DATA_FILE_PREFIX.length());
                File dataFile = this.dataDir.resolve(dataFileName).toFile();
                File indexFile = this.dataDir.resolve(indexFileName).toFile();
                File bloomFilterFile = this.dataDir.resolve(bloomFilterFileName).toFile();
                if (dataFile.exists() && indexFile.exists() && bloomFilterFile.exists()) {
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
            }
        }

        MemTable<K> newMemTable = new MemTable<>(this.memTableThreshold);
        this.wal.replay(newMemTable);
        this.version.updateAndGet((_) -> new Version<K>(newMemTable, segments));

        for (String dataFileName : pendingDeletionDataFiles) {
            try {
                deleteSegmentFilesByName(dataFileName);
            } catch (IOException e) {
                System.err.println("WARNING: failed to physically delete orphaned segment file "
                        + dataFileName + " left over from a prior crash: " + e.getMessage());
            }
        }
        if (!pendingDeletionDataFiles.isEmpty()) {
            synchronized (this.manifestLock) {
                Manifest.rewrite(new ArrayList<>(liveDataFiles), manifestFile);
            }
        }

        return Math.max(maxEventId, this.wal.getLatestEventId());
    }

    private void deleteSegmentFilesByName(String dataFileName) throws IOException {
        String suffix = dataFileName.substring(FileConstants.DATA_FILE_PREFIX.length());
        Files.deleteIfExists(this.dataDir.resolve(dataFileName));
        Files.deleteIfExists(this.dataDir.resolve(FileConstants.INDEX_FILE_PREFIX + suffix));
        Files.deleteIfExists(this.dataDir.resolve(FileConstants.BLOOM_FILTER_FILE_PREFIX + suffix));
    }

    private File manifestFile() {
        return this.dataDir.resolve(FileConstants.MANIFEST_FILE_NAME).toFile();
    }
}
