package LSMTree;

import compaction.CompactStrategyImpl.FullCompactStrategy;
import compaction.CompactStrategyImpl.LeveledCompactionStrategy;
import compaction.CompactStrategyImpl.SizeTieredCompactStrategy;
import compaction.CompactionStrategy;
import compaction.MergeStrategy;
import compaction.MergeStrategyImpl.FullLoadMergeStrategy;
import compaction.MergeStrategyImpl.LeveledMergeStrategy;
import core.Segment;
import core.SegmentFiles;
import core.Value;
import core.key.KeyCodecImpl.IntegerKeyCodec;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Compares FullCompactStrategy, SizeTieredCompactStrategy and LeveledCompactionStrategy on
 * write/read/space amplification, running the identical workload (same seed, same operation
 * sequence) under each so the comparison is apples-to-apples. Not JUnit -- compile/run the same
 * way as the other benchmarks:
 *   javac -d out -cp "lib/*" $(find src -name "*.java")
 *   javac -d out -cp out $(find bench -name "*.java")
 *   java -cp out LSMTree.CompactionAmplificationBenchmark
 *
 * "Production-grade params" for each strategy means matching real systems' actual conventions,
 * scaled down in absolute size to fit this benchmark's dataset (real-world absolute sizes --
 * RocksDB's 256MB level base, 64MB target file size -- would never trigger a single compaction
 * at this scale, making the comparison meaningless):
 *   - SizeTieredCompactStrategy(4, 0.5, 1.5): Cassandra's actual STCS defaults
 *     (min_threshold=4, bucket_low=0.5, bucket_high=1.5), unchanged from T7.3's benchmark.
 *   - LeveledCompactionStrategy: L0 trigger=4 (RocksDB's level0_file_num_compaction_trigger
 *     default), level multiplier=10 (RocksDB's max_bytes_for_level_multiplier default) -- both
 *     hardcoded constants in the class, matching RocksDB's real defaults exactly, just not
 *     exposed as tunable parameters.
 *   - LeveledMergeStrategy's targetSegmentSizeBytes=256KB: RocksDB's target_file_size_base is
 *     64MB against a 256MB L1 base (roughly 1:4) -- 256KB against this benchmark's 1MB L1 base
 *     preserves that same "one file is a fraction of a level" ratio at benchmark scale.
 */
public class CompactionAmplificationBenchmark {
    static final double THETA = 0.99;
    static final int VALUE_SIZE = 100;
    static final int KEY_SPACE = 50_000;
    static final int OPS = 20_000;
    static final int MEMTABLE_THRESHOLD = 8192;
    static final int SAMPLE_EVERY = 32;
    static final long LEVELED_TARGET_SEGMENT_SIZE_BYTES = 256 * 1024;

    public static void main(String[] args) throws Exception {
        IntegerKeyCodec codec = new IntegerKeyCodec();
        report("FullCompactStrategy",
                runWorkload(new FullCompactStrategy<>(10), new FullLoadMergeStrategy<>(codec)));
        report("SizeTieredCompactStrategy",
                runWorkload(new SizeTieredCompactStrategy<>(4, 0.5, 1.5), new FullLoadMergeStrategy<>(codec)));
        report("LeveledCompactionStrategy",
                runWorkload(new LeveledCompactionStrategy<>(), new LeveledMergeStrategy<>(codec, LEVELED_TARGET_SEGMENT_SIZE_BYTES)));
    }

    static Result runWorkload(CompactionStrategy<Integer> compactionStrategy, MergeStrategy<Integer> mergeStrategy) throws Exception {
        Path dir = Files.createTempDirectory("amp-bench");
        IntegerKeyCodec codec = new IntegerKeyCodec();
        LSMTree<Integer> tree = new LSMTree<>(codec, dir, SAMPLE_EVERY, MEMTABLE_THRESHOLD,
                compactionStrategy, mergeStrategy);

        Random rng = new Random(42);
        long totalPutBytes = 0;

        for (int i = 0; i < KEY_SPACE; i++) {
            Value v = randomValue(rng);
            totalPutBytes += v.getSizeInBytes();
            tree.put(i, v);
        }
        tree.flush();
        tree.awaitCompaction();

        YcsbStyleBenchmark.ZipfianGenerator zipf = new YcsbStyleBenchmark.ZipfianGenerator(KEY_SPACE, THETA, rng);
        for (int i = 0; i < OPS; i++) {
            int key = (int) zipf.next();
            if (rng.nextDouble() < 0.5) {
                tree.get(key);
            } else {
                Value v = randomValue(rng);
                totalPutBytes += v.getSizeInBytes();
                tree.put(key, v);
            }
        }
        tree.awaitCompaction();

        long writtenBytes = sumDataFileBytes(dir);
        double writeAmp = writtenBytes / (double) totalPutBytes;

        long liveBytes = tree.segments().stream().mapToLong(Segment::getSize).sum();
        long logicalBytes = fullyMergedSize(tree, codec, dir);
        double spaceAmp = logicalBytes == 0 ? 0.0 : liveBytes / (double) logicalBytes;

        double readAmp = tree.getCallCount() == 0 ? 0.0 : tree.segmentsConsultedCount() / (double) tree.getCallCount();

        int segmentCount = tree.segmentCount();
        Result result = new Result(writeAmp, spaceAmp, readAmp, writtenBytes, totalPutBytes,
                liveBytes, logicalBytes, tree.segmentsConsultedCount(), tree.getCallCount(), segmentCount);
        tree.close();
        return result;
    }

    static Value randomValue(Random rng) {
        byte[] b = new byte[VALUE_SIZE];
        rng.nextBytes(b);
        return Value.of(b);
    }

    static long sumDataFileBytes(Path dir) {
        File[] files = dir.toFile().listFiles((d, n) -> n.startsWith("dataFile-"));
        long sum = 0;
        if (files != null) for (File f : files) sum += f.length();
        return sum;
    }

    static long fullyMergedSize(LSMTree<Integer> tree, IntegerKeyCodec codec, Path dir) throws Exception {
        List<Segment<Integer>> live = tree.segments();
        if (live.isEmpty()) return 0;

        String uuid = UUID.randomUUID().toString();
        File tmpData = dir.resolve("full-merge-data-" + uuid).toFile();
        File tmpIndex = dir.resolve("full-merge-index-" + uuid).toFile();
        File tmpBloom = dir.resolve("full-merge-bloom-" + uuid).toFile();
        List<Segment<Integer>> merged = new FullLoadMergeStrategy<>(codec)
                .merge(live, () -> new SegmentFiles(tmpData, tmpIndex, tmpBloom), SAMPLE_EVERY);
        long size = merged.stream().mapToLong(Segment::getSize).sum();
        Files.deleteIfExists(tmpData.toPath());
        Files.deleteIfExists(tmpIndex.toPath());
        Files.deleteIfExists(tmpBloom.toPath());
        return size;
    }

    record Result(double writeAmp, double spaceAmp, double readAmp,
                   long writtenBytes, long totalPutBytes,
                   long liveBytes, long logicalBytes,
                   long segmentsConsulted, long getCalls, int finalSegmentCount) {
    }

    static void report(String label, Result r) {
        System.out.println(label);
        System.out.printf("  writeAmp=%.2fx  (writtenBytes=%d, putBytes=%d)%n", r.writeAmp(), r.writtenBytes(), r.totalPutBytes());
        System.out.printf("  spaceAmp=%.2fx  (liveBytes=%d, logicalBytes=%d)%n", r.spaceAmp(), r.liveBytes(), r.logicalBytes());
        System.out.printf("  readAmp=%.3f segments/get  (segmentsConsulted=%d, getCalls=%d)%n", r.readAmp(), r.segmentsConsulted(), r.getCalls());
        System.out.printf("  finalSegmentCount=%d%n%n", r.finalSegmentCount());
    }
}
