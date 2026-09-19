package LSMTree;

import compactation.CompactStrategyImpl.FullCompactStrategy;
import compactation.CompactStrategyImpl.SizeTieredCompactStrategy;
import compactation.CompactionStrategy;
import compactation.MergeStrategyImpl.FullLoadMergeStrategy;
import core.Segment;
import core.Value;
import core.key.KeyCodecImpl.IntegerKeyCodec;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

/**
 * Compares FullCompactStrategy against SizeTieredCompactStrategy on write/read/space
 * amplification, running the identical workload (same seed, same operation sequence) under
 * each so the comparison is apples-to-apples. Not JUnit -- compile/run the same way as the
 * other benchmarks:
 *   javac -d out -cp "lib/*" $(find src -name "*.java")
 *   javac -d out -cp out $(find bench -name "*.java")
 *   java -cp out LSMTree.CompactionAmplificationBenchmark
 */
public class CompactionAmplificationBenchmark {
    static final double THETA = 0.99;
    static final int VALUE_SIZE = 100;
    static final int KEY_SPACE = 50_000;
    static final int OPS = 20_000;
    static final int MEMTABLE_THRESHOLD = 8192;
    static final int SAMPLE_EVERY = 32;

    public static void main(String[] args) throws Exception {
        report("FullCompactStrategy", runWorkload(new FullCompactStrategy<>(10)));
        report("SizeTieredCompactStrategy", runWorkload(new SizeTieredCompactStrategy<>(4, 0.5, 1.5)));
    }

    static Result runWorkload(CompactionStrategy<Integer> strategy) throws Exception {
        Path dir = Files.createTempDirectory("amp-bench");
        IntegerKeyCodec codec = new IntegerKeyCodec();
        LSMTree<Integer> tree = new LSMTree<>(codec, dir, SAMPLE_EVERY, MEMTABLE_THRESHOLD,
                strategy, new FullLoadMergeStrategy<>(codec));

        Random rng = new Random(42);
        long totalPutBytes = 0;

        for (int i = 0; i < KEY_SPACE; i++) {
            Value v = randomValue(rng);
            totalPutBytes += v.getSizeInBytes();
            tree.put(i, v);
        }
        tree.flush();

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

        long writtenBytes = sumDataFileBytes(dir);
        double writeAmp = writtenBytes / (double) totalPutBytes;

        long liveBytes = tree.segments().stream().mapToLong(Segment::getSize).sum();
        long logicalBytes = fullyMergedSize(tree, codec, dir);
        double spaceAmp = logicalBytes == 0 ? 0.0 : liveBytes / (double) logicalBytes;

        double readAmp = tree.getCallCount() == 0 ? 0.0 : tree.segmentsConsultedCount() / (double) tree.getCallCount();

        Result result = new Result(writeAmp, spaceAmp, readAmp, writtenBytes, totalPutBytes,
                liveBytes, logicalBytes, tree.segmentsConsultedCount(), tree.getCallCount());
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

        Path tmpData = Files.createTempFile(dir, "full-merge-", ".data");
        Path tmpIndex = Files.createTempFile(dir, "full-merge-", ".index");
        Path tmpBloom = Files.createTempFile(dir, "full-merge-", ".bloom");
        Segment<Integer> merged = new FullLoadMergeStrategy<>(codec)
                .merge(live, tmpData.toFile(), tmpIndex.toFile(), tmpBloom.toFile(), SAMPLE_EVERY);
        long size = merged.getSize();
        Files.deleteIfExists(tmpData);
        Files.deleteIfExists(tmpIndex);
        Files.deleteIfExists(tmpBloom);
        return size;
    }

    record Result(double writeAmp, double spaceAmp, double readAmp,
                   long writtenBytes, long totalPutBytes,
                   long liveBytes, long logicalBytes,
                   long segmentsConsulted, long getCalls) {
    }

    static void report(String label, Result r) {
        System.out.println(label);
        System.out.printf("  writeAmp=%.2fx  (writtenBytes=%d, putBytes=%d)%n", r.writeAmp(), r.writtenBytes(), r.totalPutBytes());
        System.out.printf("  spaceAmp=%.2fx  (liveBytes=%d, logicalBytes=%d)%n", r.spaceAmp(), r.liveBytes(), r.logicalBytes());
        System.out.printf("  readAmp=%.3f segments/get  (segmentsConsulted=%d, getCalls=%d)%n%n", r.readAmp(), r.segmentsConsulted(), r.getCalls());
    }
}
