package LSMTree;

import compactation.CompactStrategyImpl.FullCompactStrategy;
import compactation.CompactStrategyImpl.LeveledCompactionStrategy;
import compactation.CompactStrategyImpl.SizeTieredCompactStrategy;
import compactation.CompactionStrategy;
import compactation.MergeStrategy;
import compactation.MergeStrategyImpl.FullLoadMergeStrategy;
import compactation.MergeStrategyImpl.LeveledMergeStrategy;
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
 * Scaled-up variant of CompactionAmplificationBenchmark (10x the key space: ~58MB of data
 * instead of ~5-6MB) specifically to test whether leveled compaction's read-amp advantage over
 * size-tiered -- well-documented in real systems, absent at CompactionAmplificationBenchmark's
 * small scale -- actually emerges once there's enough data to populate more than L0/L1. It does;
 * see docs/benchmark.md section 13. Slow: FullCompactStrategy's "rewrite everything on every
 * trigger" cost compounds badly at this scale (several minutes), unlike the other two.
 *
 *   javac -d out -cp "lib/*" $(find src -name "*.java")
 *   javac -d out -cp out $(find bench -name "*.java")
 *   java -cp out LSMTree.ScaledAmplificationBenchmark
 */
public class ScaledAmplificationBenchmark {
    static final double THETA = 0.99;
    static final int VALUE_SIZE = 100;
    static final int KEY_SPACE = 500_000;
    static final int OPS = 200_000;
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
        Path dir = Files.createTempDirectory("scaled-amp-bench");
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
        int maxLevel = tree.segments().stream().mapToInt(Segment::getLevel).max().orElse(0);
        Result result = new Result(writeAmp, spaceAmp, readAmp, writtenBytes, totalPutBytes,
                liveBytes, logicalBytes, tree.segmentsConsultedCount(), tree.getCallCount(), segmentCount, maxLevel);
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
                   long segmentsConsulted, long getCalls, int finalSegmentCount, int maxLevel) {
    }

    static void report(String label, Result r) {
        System.out.println(label);
        System.out.printf("  writeAmp=%.2fx  (writtenBytes=%d, putBytes=%d)%n", r.writeAmp(), r.writtenBytes(), r.totalPutBytes());
        System.out.printf("  spaceAmp=%.2fx  (liveBytes=%d, logicalBytes=%d)%n", r.spaceAmp(), r.liveBytes(), r.logicalBytes());
        System.out.printf("  readAmp=%.3f segments/get  (segmentsConsulted=%d, getCalls=%d)%n", r.readAmp(), r.segmentsConsulted(), r.getCalls());
        System.out.printf("  finalSegmentCount=%d  maxLevelReached=%d%n%n", r.finalSegmentCount(), r.maxLevel());
    }
}
