package LSMTree;

import compaction.CompactStrategyImpl.FullCompactStrategy;
import compaction.MergeStrategyImpl.FullLoadMergeStrategy;
import core.Value;
import core.key.KeyCodecImpl.IntegerKeyCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/**
 * T7.4 AC: point-read latency. Re-measures docs/benchmark.md section 2 against the CURRENT
 * codebase -- that section was measured with ZERO bloom filter (T7.1 didn't exist yet), and its
 * entire finding (misses ~20x slower than hits) was literally the motivation for building one.
 * Re-running it now, with a bloom filter actually in place, is the honest current-state number
 * the AC is asking for.
 *
 *   javac -d out -cp "lib/*" $(find src -name "*.java")
 *   javac -d out -cp out $(find bench -name "*.java")
 *   java -cp out LSMTree.PointReadLatencyBenchmark
 */
public class PointReadLatencyBenchmark {
    static final int ENTRIES = 10_000;
    static final int VALUE_SIZE = 20;
    static final int READS_PER_KIND = 5_000;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("point-read-latency-bench");
        IntegerKeyCodec codec = new IntegerKeyCodec();
        // memTableThreshold set above total data size, then one explicit flush() -- matches the
        // old section 2's "10,000 entries flushed into a single segment" methodology exactly.
        LSMTree<Integer> tree = new LSMTree<>(codec, dir, 32, Integer.MAX_VALUE,
                new FullCompactStrategy<>(10), new FullLoadMergeStrategy<>(codec));

        Random loadRng = new Random(42);
        for (int i = 0; i < ENTRIES; i++) {
            byte[] value = new byte[VALUE_SIZE];
            loadRng.nextBytes(value);
            tree.put(i, Value.of(value));
        }
        tree.flush();
        System.out.println("segmentCount after flush: " + tree.segmentCount());

        // Hit keys: present, in [0, ENTRIES). Miss keys: absent, in [ENTRIES, 2*ENTRIES) -- never
        // written, so guaranteed misses regardless of bloom filter false positives.
        Random opRng = new Random(7);
        long[] hitLatencies = new long[READS_PER_KIND];
        for (int i = 0; i < READS_PER_KIND; i++) {
            int key = opRng.nextInt(ENTRIES);
            long t0 = System.nanoTime();
            tree.get(key);
            hitLatencies[i] = System.nanoTime() - t0;
        }

        long[] missLatencies = new long[READS_PER_KIND];
        for (int i = 0; i < READS_PER_KIND; i++) {
            int key = ENTRIES + opRng.nextInt(ENTRIES);
            long t0 = System.nanoTime();
            tree.get(key);
            missLatencies[i] = System.nanoTime() - t0;
        }

        report("hit ", hitLatencies);
        report("miss", missLatencies);

        double hitAvg = average(hitLatencies);
        double missAvg = average(missLatencies);
        System.out.printf("miss/hit avg ratio: %.2fx%n", missAvg / hitAvg);

        tree.close();
    }

    static void report(String label, long[] latenciesNanos) {
        long[] sorted = latenciesNanos.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        double avgUs = average(sorted) / 1000.0;
        double p50Us = sorted[n / 2] / 1000.0;
        double p99Us = sorted[(int) (n * 0.99)] / 1000.0;
        double maxUs = sorted[n - 1] / 1000.0;
        System.out.printf("%s: avg=%.1fus  p50=%.1fus  p99=%.1fus  max=%.1fus  (n=%d)%n",
                label, avgUs, p50Us, p99Us, maxUs, n);
    }

    static double average(long[] values) {
        long sum = 0;
        for (long v : values) sum += v;
        return (double) sum / values.length;
    }
}
