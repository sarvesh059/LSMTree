package core;

import bloomFilter.BloomFilter;
import core.key.KeyCodec;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Segment<K> {
    private final File dataFile;
    private final File indexFile;
    private final File bloomFilterFile;
    private final List<IndexEntry> loadedIndex;
    private final BloomFilter bloomFilter;
    private final int entryCount;
    private final long maxEventId;
    private final AtomicInteger refCount;
    private int level;
    private byte[] minKey;
    private byte[] maxKey;
    private volatile boolean isDeleted;


    public Segment(File dataFile, File indexFile, File bloomFilterFile, List<IndexEntry> loadedIndex, BloomFilter bloomFilter, int entryCount, long maxEventId) {
        this.dataFile = dataFile;
        this.indexFile = indexFile;
        this.bloomFilterFile = bloomFilterFile;
        this.loadedIndex = loadedIndex;
        this.bloomFilter = bloomFilter;
        this.entryCount = entryCount;
        this.maxEventId = maxEventId;
        this.refCount = new AtomicInteger(0);
        this.isDeleted = false;
    }

    public File getDataFile() {
        return dataFile;
    }

    public File getIndexFile() {
        return indexFile;
    }

    public File getBloomFilterFile() {
        return bloomFilterFile;
    }

    public void deleteFiles() throws IOException {
        List<Path> files = List.of(this.getDataFile().toPath(), this.getIndexFile().toPath(), this.getBloomFilterFile().toPath());
        FileUtils.cleanUpFiles(files);
    }

    public List<IndexEntry> getLoadedIndex() {
        return loadedIndex;
    }

    public BloomFilter getBloomFilter() {
        return this.bloomFilter;
    }

    public int getEntryCount() {
        return this.entryCount;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public byte[] getMinKey() {
        return minKey;
    }

    public void setMinKey(byte[] minKey) {
        this.minKey = minKey;
    }

    public byte[] getMaxKey() {
        return maxKey;
    }

    public void setMaxKey(byte[] maxKey) {
        this.maxKey = maxKey;
    }

    public long getMaxEventId() {
        return this.maxEventId;
    }

    public long getSize() {
        return this.dataFile.length();
    }

    public boolean pin() {
        int result = refCount.updateAndGet(current -> current < 0 ? current : current + 1);
        return result >= 0;
    }

    public void unpin() {
        refCount.updateAndGet(current -> {
            if (current <= 0) throw new IllegalStateException("Segment needs to be pinned before unpinning.");
            return current - 1;
        });
    }

    public int getRefCount() {
        return this.refCount.get();
    }

    /**
     * Marks this segment retired. One-way and irreversible.
     *
     * <p>Call this only once the segment is known to have left the current {@code Version} —
     * never speculatively, and never before a compaction's install is confirmed. The position of
     * this call relative to the install is load-bearing: {@code MergeStrategy} implementations
     * read {@link #isDeleted()} to decide which other segments are still live, and that list
     * drives tombstone retention. Marking a segment that is still live removes it from that list,
     * which can drop a tombstone whose suppression that segment's older data still needs —
     * resurrecting a deleted key, silently and permanently.
     *
     * <p>{@code CleanUpService} validates that its arguments have been marked and throws
     * otherwise, so forgetting the call fails loudly; calling it <em>too early</em> does not.
     *
     * <p>Note this does <em>not</em> prevent {@link #pin()}: a {@code scan()} holds an older
     * {@code Version} and must keep reading segments that have since been retired. Only a
     * successful {@link #tryClaimForDeletion()} makes a segment unpinnable.
     */
    public void markDeleted() {
        this.isDeleted = true;
    }

    public boolean isDeleted() {
        return this.isDeleted;
    }

    /**
     * Makes this retired segment unpinnable so its files can be deleted, or confirms that it
     * already is.
     *
     * <p>Succeeds only when no {@code scan()} holds a pin. From then on {@link #pin()} refuses for
     * good, so no new reader can start on files that are being, or have been, unlinked. A segment
     * stays claimed after a failed delete, and a repeat call returns {@code true}, so the deleter
     * can retry later without making the segment pinnable in between. Concurrent callers may both
     * succeed; that is safe because deleting a file that is already gone is a no-op.
     *
     * @return {@code true} if the segment is unpinnable and its files may be deleted;
     *         {@code false} if a reader still holds it
     * @throws IllegalStateException if the segment has not been {@linkplain #markDeleted() marked}
     */
    public boolean tryClaimForDeletion() {
        if (!isDeleted) throw new IllegalStateException("Segment hasn't been marked for deletion");
        if(this.refCount.get() == -1) return true;
        return this.refCount.compareAndSet(0, -1);
    }

    public boolean keyDefinitelyNotInSegment(byte[] encodedKey, KeyCodec<K> codec) {
        if (encodedKey == null || this.getMinKey() == null || this.getMaxKey() == null) return true;
        return codec.compareEncoded(encodedKey, this.getMinKey()) < 0 ||
                codec.compareEncoded(encodedKey, this.getMaxKey()) > 0 ||
                !this.getBloomFilter().mightContain(encodedKey);
    }

}
